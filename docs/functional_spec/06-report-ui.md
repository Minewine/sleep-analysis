# 6. Report UI

**Scope:** `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt`, `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt`, `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt`, `app/src/main/java/com/sleepanalysis/ui/report/SessionLogScreen.kt`, `app/src/main/java/com/sleepanalysis/ui/report/Timeline.kt`, plus `app/src/main/java/com/sleepanalysis/ui/navigation/AppNavHost.kt` and `app/src/main/java/com/sleepanalysis/MainActivity.kt`; the tests under `app/src/test/java/com/sleepanalysis/ui/report/`. The report numbers themselves (section 5), session writes (section 4) and the importers (section 8) are owned there; this section covers how the report screen renders and refreshes them.
**Requirement prefix:** RUI — rows in 6.3 are numbered `RUI-01`, `RUI-02`, …
**Status values:** `tested` = a unit/instrumented test asserts it (cite it) · `device-verified` = evidence from a real night or device run exists (cite where) · `unverified` = implemented, no test and no device evidence · `not implemented` = designed or documented only.

## 6.1 What the user gets

1. A per-night Morning Summary screen opened by session id, showing headline numbers, a TL;DR with the previous-night comparison, a validity grade, data-quality banners, session health, bursts/bouts/quiet stretches, timeline, hourly bars, loudest episodes, all events, Garmin and stage data, and a mandatory disclaimer. [RUI-01, RUI-03, RUI-17, RUI-24, RUI-25]
2. A silent Garmin backfill on first open (rate-limited per wake date, with a looser budget for "Garmin not ready yet") and a silent Health Connect backfill, followed by an automatic re-render. [RUI-04, RUI-05, RUI-06, RUI-07]
3. A "Generate morning report" button that re-pulls Garmin and Health Connect data under the same gates, rebuilds the report in place, and reports an honest status line (syncing / updating / not available / retry blocked / stages pending / included with time). [RUI-08 – RUI-13]
4. Tap-to-play audio clips with a single shared player, from top clips, loudest episodes and selected events. [RUI-16, RUI-17]
5. Per-event labeling (six ground-truth labels, tap again to clear) both inline on the report and in a bulk "Label clips" pass with progress and unlabeled-first ordering. [RUI-18 – RUI-21]
6. Morning context editing: a 1–5 night rating, five quick night tags, and a free-text note stored with the night. [RUI-22 – RUI-24]
7. A share action emitting a plain-text night summary, and a read-only per-session log viewer with its own share sheet. [RUI-25, RUI-31]
8. Low-confidence noise events hidden from display (not from data) with an integrity note counting them. [RUI-26]

## 6.2 How it works today

### 6.2.1 Components

| Component | File | Responsibility |
|---|---|---|
| `Routes` | `app/src/main/java/com/sleepanalysis/ui/navigation/AppNavHost.kt` | Route strings: `home`, `report/{sessionId}`, `report/{sessionId}/log`, `report/{sessionId}/label`, history, trends, insights, baseline, settings, dailylog |
| `AppNavHost` | `app/src/main/java/com/sleepanalysis/ui/navigation/AppNavHost.kt` | NavHost wiring; report routes declare a LongType `sessionId` nav argument |
| `MainActivity` | `app/src/main/java/com/sleepanalysis/MainActivity.kt` | Edge-to-edge `ComponentActivity`; sets `AppRoot` (onboarding gate, then `AppNavHost`) inside the theme |
| `ReportScreen` | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` | The whole Morning Summary: top bar (Label clips, share), card list, per-card composables |
| `ReportViewModel` | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` | Report loading, silent backfills, manual refresh, labeling, notes/factors, clip playback, share text; ~10 extracted pure helpers for the gates and status mapping |
| `LabelingScreen` / LabelingViewModel | `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt` | Bulk per-session clip labeling pass with its own player |
| `SessionLogScreen` / `SessionLogViewModel` | `app/src/main/java/com/sleepanalysis/ui/report/SessionLogScreen.kt` | Read-only session log text plus share-sheet export |
| `NightTimeline`, `HourlyBars` | `app/src/main/java/com/sleepanalysis/ui/report/Timeline.kt` | Canvas drawings: interactive event strip, hourly snore bars |
| `ClipIntegrity`, `NightValidity.clipQualitySummary` | data / domain (section 5) | Shared per-clip scan and aggregation behind the quality banners |

### 6.2.2 Flows

#### Flow: a report comes to life

1. HomeScreen (or History/Trends/Insights) calls Routes.report(sessionId); the report/{sessionId} route parses sessionId as LongType into the back-stack entry. [RUI-01]
2. ReportScreen gets a Hilt ReportViewModel; the view model reads the savedStateHandle key sessionId (fallback -1) and init calls reload → reloadSuspend. [RUI-02]
3. `reloadSuspend` sets `report` from SessionRepository.buildReport, then sequentially: `scanClipQuality` (never rebuilds the report), `maybeBackfillGarmin`, `maybeBackfillHealthConnect` (each backfill rebuilds the report after its import attempt regardless of outcome). [RUI-02, RUI-04, RUI-07]
4. While `report` is null the screen shows a "no sessions" placeholder; every card appears only after the first build. [RUI-03]

#### Flow: Garmin backfill and its attempt budget

1. Gates in `maybeBackfillGarmin`: skip when report null, when garminStatus is already LINKED, when GarminImportManager.canImport is false (no stored key), when an import is already in flight, or when userPreferences.garminBackfillAllowed says the wake-date budget is exhausted. [RUI-04]
2. The wake date is NightDates.garminWakeDate of the session's own start/end/timeZone; import runs in that zone, never the device default. [RUI-04, RUI-15]
3. After the attempt, `markGarminAttemptBudget` marks the counter the outcome drew from: `drawsNotReadyBudget` is true only for the NotReady outcome, which marks the separate not-ready counter; Success (including a 0-metric success) and Error mark the general counter. [RUI-05]
4. Bounds: general = 2 attempts per wake date with a 6 h cooldown; not-ready = 4 attempts with a 1 h cooldown; the gate consults the counter of whichever kind was attempted LAST on that date, so a general failure's 6 h cooldown also delays a following not-ready attempt. Manual imports from Settings consume no budget, but the report's own manual import marks it. [RUI-06, RUI-15]

#### Flow: manual "Generate morning report" refresh

1. Guard: no-op when a refresh phase is already active or a Garmin import is in flight. [RUI-14]
2. Garmin step: skipped entirely when `refreshSkipsImport` (LINKED — rebuild only); a METRICS_ONLY night still imports because only the metrics survived. Otherwise the three backfill gates (`refreshGarminGateOpen`: canImport, nothing in flight, budget allowed) decide; a closed gate is skipped silently and the rebuild still runs. [RUI-08, RUI-09]
3. `garminStepBudgetBlocked` (importable, idle, budget exhausted) is remembered and shown as the distinct "an earlier import attempt failed — Import from Garmin below retries immediately" line. [RUI-12]
4. Health Connect step: `refreshHealthConnectGateOpen` (provider available, nothing in flight, needsImport) evaluated against the freshly rebuilt report. [RUI-10]
5. Phase order SYNCING_GARMIN → SYNCING_HEALTHCONNECT → UPDATING_REPORT → IDLE with completedAtMs; a `finally` block forces IDLE if a phase was left set (button never freezes). Button is disabled while not IDLE or while any Garmin import runs. [RUI-13, RUI-14]
6. Status line comes from the pure `refreshStatusLine` mapping; see rows RUI-11 – RUI-13. [RUI-11]

#### Flow: clip playback

1. `playClip` first stops any current playback, returns silently when the clip file does not exist, else creates a MediaPlayer (setDataSource/prepare/start) and publishes the playing event id; completion or failure clears the id and releases the player. `stopPlayback` stops, releases, clears. `onCleared` releases. [RUI-16]
2. LabelingViewModel.play / stopPlayback are byte-for-byte the same logic against LabelingItem instead of ClipRef. [RUI-17]

#### Flow: labeling

1. Inline (report): label chips under top clips, in the selected-event card and in episode expansion call `labelEvent(id, label)`; tapping the chip of the already-stored label passes null (toggle). The repository write re-weighs the night's aggregates (section 4), then `reload` re-renders. [RUI-18]
2. Bulk: "Label clips" in the top bar opens `report/{sessionId}/label`; LabelingViewModel loads clipBackedEventsForLabeling once, sorts unlabeled first, offers a hide-labeled switch and progress (labeledCount/totalCount + bar). [RUI-19]
3. Bulk writes are optimistic: the chip sticks immediately, the DB write trails; a failed write reverts the target row's label to null — wiping any pre-existing label instead of restoring it. [RUI-20]
4. The label set is the six EventUserLabel entries (Snore, Cough, Speech, Movement, Other / Not snore, Silence) rendered identically in both screens. [RUI-21]

#### Flow: morning notes, rating, quick tags

1. MorningNotesCard seeds MorningFactorsUiState from the report's factor rows via morningFactorsUiState: the rating chip is selected only for an in-range 1..5 MORNING_RATING value; a tag chip is selected when its factor row exists at any value. [RUI-23]
2. Save calls `saveMorningFactors` → `morningFactorWrites` on the session's factor day (NightDates.factorDay): one row or removal per key, MORNING_TAGS order, STRESS written as 5.0 (`morningTagValue`); never a whole-day replace, other factors untouched; then `reload`. [RUI-24]
3. The note editor starts open when no note is saved; Save persists via `saveMorningNotes` (session row, section 4); Cancel reverts to the saved note, and with none saved it keeps the editor open (`notesCancelReturnsToEdit`) — rendering a null note was the 2026-11-23 morning crash. [RUI-22]

#### Flow: share and session log

1. The share icon (top bar, only when a report exists) sends `buildShareText` — NightSummaryText.build of the current report — as an ACTION_SEND text/plain intent. Content pinned by NightSummaryTextTest: headline numbers, worst stretch, longest burst, score with formula version, loudest clip, quiet/uncaptured gaps, version-gated previous-night comparison. [RUI-25]
2. Session log: `report/{sessionId}/log`; SessionLogViewModel reads SessionLogger.readSessionLog (file `<filesDir>/logs/session_<id>.log`, rootDir wired by AppModule.provideSessionLogger) on Dispatchers.IO into a WhileSubscribed(5_000) state; null/blank renders log_empty, else monospace text; the share action sends the full text with subject "SleepAnalysis session log #id". [RUI-31]

### 6.2.3 State and stored data

View-model state (all Kotlin StateFlows; ReportScreen and SessionLogScreen collect with collectAsStateWithLifecycle, LabelingScreen with collectAsState):

| State | Null means | Read by |
|---|---|---|
| `ReportViewModel.report` (MorningReport?) | report not yet built, or session missing | every card |
| `ReportViewModel.clipQuality` (ClipQualityReport: checked, clippedFilePaths, missingFiles, unreadableFiles) | scan not finished (deliberately off the render path; skipped when the clip-path set is unchanged — see `lastScannedClipPaths`) | QualityBannersCard |
| `ReportViewModel.refreshState` (RefreshState: phase, completedAtMs, garminBudgetBlocked) | — (defaults IDLE/no time/no block) | RefreshReportCard |
| `ReportViewModel.garminImportState` | passthrough of the singleton GarminImportManager.state shared with Settings | GarminCard, refresh button enablement |
| `ReportViewModel.playingEventId` (Long?) | nothing playing | play/stop buttons |
| `LabelingUiState` (items, hideLabeled; derived labeledCount, totalCount; `visible()` sort/filter; `withLabel`) | — | LabelingScreen |
| `SessionLogViewModel.log` (String?) | file read not finished | SessionLogScreen |

MorningReport (domain, section 5) fields the screen never reads: `status`, `eventCountsByType`, `meanSnoreDbfs`, `lowConfidenceNoiseExcluded` (its effect reaches the screen only via the integrityNotes string), and `sessionId` (the screen uses the nav argument instead). Nullability semantics used by the UI: `validity` null = night predates grade rows (badge omitted); `capture` null = pre-observability session (rows show "—"); `captureWindowStartMs` null = pre-v8 session (TldrCard and GarminCard fall back to startedAt; SessionHealthCard instead omits its capture-window and start-delay rows entirely); `boutStats` null = no snore events; `garminSleep` null = raw payload not retained; `stageSource` null = no stage timeline rows; `peakDbfs` / `longestSnore` / `worstBoutClip` / `longestBoutStage` / `garminSleepScore` null = no such data; `morningNotes` null = none saved; `previousNight` null = no comparison.

Persisted or written by this area (columns and keys owned by sections 4 / 7 / 8, listed here because the UI writes them):

- Event user label: AcousticEventEntity.userLabel via SessionRepository.labelEvent (name of an EventUserLabel entry, or null).
- Morning note: session row column via SessionRepository.setMorningNotes.
- Rating and tags: factor rows UserFactorEntity(date, factorKey, valueNum) on the factor day via FactorLogRepository.saveFactorForDate / removeFactorForDate; STRESS quick tag writes 5.0, booleans 1.0.
- DataStore keys touched by the refresh pipeline: `garmin_auto_retry_date` / `garmin_auto_retry_ms` / `garmin_auto_retry_count` (general budget), `garmin_not_ready_retry_date` / `garmin_not_ready_retry_ms` / `garmin_not_ready_retry_count` (not-ready budget), `noise_display_min_confidence` (display filter).
- Session log file: `<filesDir>/logs/session_<id>.log`, lines "<ISO-8601 UTC> [EVENT] detail" (written by section 4's logger; only read here).

## 6.3 Requirements

| ID | Requirement | Where | Pinned by | Status |
|---|---|---|---|---|
| RUI-01 | The report, its log and its labeling are routes `report/{sessionId}`, `report/{sessionId}/log`, `report/{sessionId}/label`, all with a LongType sessionId argument | `app/src/main/java/com/sleepanalysis/ui/navigation/AppNavHost.kt` · `Routes.REPORT` | — | unverified |
| RUI-02 | Opening the screen builds the report from the nav-arg session id via sessionRepository.buildReport and re-runs the silent backfills after every import | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `reloadSuspend` | — | unverified |
| RUI-03 | While no report is built the screen renders only the "no sessions" placeholder and no cards | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `ReportScreen` | — | unverified |
| RUI-04 | On open, Garmin is imported silently for the wake date only when: status is not LINKED, a key is stored, no import is in flight, and the wake-date budget allows; afterwards the report is rebuilt | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `maybeBackfillGarmin` | `ReportViewModelGarminBackfillTest.morning report's garmin step runs under the backfill's exact gates` | tested |
| RUI-05 | A finished attempt marks the counter its outcome drew from: only a NotReady outcome draws the not-ready budget; Success (even 0 metrics) and Error draw the general budget | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `markGarminAttemptBudget` | `ReportViewModelGarminBackfillTest.only a not-ready outcome draws the not-ready budget` | tested |
| RUI-06 | Budgets: general 2 attempts per wake date with 6 h cooldown; not-ready 4 attempts with 1 h cooldown; the kind of the date's last attempt gates the next try | `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt` · `GarminNotReadyBounds.backfillAllowed` | `ReportViewModelGarminBackfillTest.a not-ready morning marks its own counter and retries after its 1 h cooldown`, `ReportViewModelGarminBackfillTest.a real error keeps the general budget and its 6 h cooldown` | tested |
| RUI-07 | On open, Health Connect is imported for the wake date only when the provider is available, no import is in flight, and stage rows are still missing (needsImport); no attempt budget (local IPC) | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `maybeBackfillHealthConnect` | `ReportViewModelGarminBackfillTest.morning report's health connect step runs under the backfill's exact gates` | tested |
| RUI-08 | The manual refresh skips the Garmin network import for a LINKED night (rebuild only) but still imports for METRICS_ONLY (payload aged out) | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `refreshSkipsImport` | `ReportRefreshStateTest.linked night skips the import - rebuild only`, `ReportRefreshStateTest.metrics-only night still imports - the payload aged out` | tested |
| RUI-09 | The refresh's Garmin step runs under exactly the silent backfill's gates; a closed gate skips the step silently and the rebuild still runs | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `refreshGarminGateOpen` | `ReportViewModelGarminBackfillTest.morning report's garmin step runs under the backfill's exact gates` | tested |
| RUI-10 | The refresh's Health Connect step gates on available / not importing / needsImport evaluated against the freshly rebuilt report | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `refreshHealthConnectGateOpen` | `ReportViewModelGarminBackfillTest.morning report's health connect step runs under the backfill's exact gates` | tested |
| RUI-11 | The refresh status line shows the running phase while busy and no line at all before the first completed refresh (MISSING data alone never implies a failed attempt) | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `refreshStatusLine` | `ReportRefreshStateTest.running phases win over any outcome line`, `ReportRefreshStateTest.no status line before any completed refresh - even when data is missing` | tested |
| RUI-12 | After a completed refresh with MISSING Garmin data the line is "not available", or "an earlier import attempt failed — retry via Import below" when the budget blocked the step | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `garminStepBudgetBlocked` | `ReportRefreshStateTest.missing outcome after an attempt shows not available`, `ReportRefreshStateTest.budget-blocked missing outcome names the retry path instead of plain not available` | tested |
| RUI-13 | "Stage timeline not available yet" shows only when neither a stage timeline nor Garmin stage totals exist; stage totals alone count as included | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `stagesStillMissing` | `ReportRefreshStateTest.completed refresh with neither timeline nor stage totals shows stages pending`, `ReportRefreshStateTest.stage totals alone still count as included` | tested |
| RUI-14 | The refresh button is disabled while a refresh phase is active or a Garmin import is in flight; a failed attempt resets to IDLE in a finally block (button never freezes) | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `RefreshReportCard` | — | unverified |
| RUI-15 | The Garmin card's Import button is ungated (enabled whenever no import runs), imports the wake date in the session's zone, and consumes/marks the wake-date budget like the backfill | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `importGarminForThisNight` | — | unverified |
| RUI-16 | Clip playback: starting a clip stops the current one; a missing file silently does nothing; completion or failure clears the playing id and releases the player; view-model teardown releases it | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `playClip` | — | unverified |
| RUI-17 | The labeling screen's player is the identical stop-play-release lifecycle (same code as the report view model) | `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt` · `stopPlayback` | — | unverified |
| RUI-18 | Tapping a label chip stores that label; tapping the chip of the stored label clears it; the write re-weighs aggregates and reload re-renders | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `labelEvent` | — | unverified |
| RUI-19 | The bulk labeling list sorts unlabeled rows first (oldest first within each group), can hide labeled rows, and shows labeled/total progress | `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt` · `LabelingUiState.visible` | `LabelingUiStateTest.unlabeled rows sort first`, `LabelingUiStateTest.hide-labeled filter drops labeled rows`, `LabelingUiStateTest.progress counts labeled items` | tested |
| RUI-20 | A bulk label tap updates the chip immediately and only the target row; a failed DB write reverts the target row's label to null — wiping any pre-existing label, not restoring it | `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt` · `LabelingUiState.withLabel` | `LabelingUiStateTest.withLabel updates only the target and can clear` | tested |
| RUI-21 | The label vocabulary is exactly the six EventUserLabel entries, identical across report chips and labeling rows | `app/src/main/java/com/sleepanalysis/domain/labels/EventUserLabel.kt` · `EventUserLabel` | `EventUserLabelTest` | tested |
| RUI-22 | The note editor starts open when no note is saved; Save persists the note; Cancel reverts to the saved note and, when none is saved, keeps the editor open | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `MorningNotesCard` | `MorningFactorsUiStateTest.notes cancel keeps the editor open only when a note exists to revert to` | tested |
| RUI-23 | Rating and tag chips derive from the report's factor rows: only an in-range 1..5 MORNING_RATING selects a rating chip; any stored STRESS level selects the stress chip | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `morningFactorsUiState` | `MorningFactorsUiStateTest.initial chip state comes from the factor rows`, `MorningFactorsUiStateTest.out-of-range rating row never selects a chip`, `MorningFactorsUiStateTest.stress chip is selected for any stored level` | tested |
| RUI-24 | Saving writes/removes exactly the rating and tag keys on the factor day (merge, never whole-day replace); unselected tags are removed (removal wins over a Daily-log refinement); notes and other factors are untouched | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `morningFactorWrites` | `MorningFactorsUiStateTest.save writes one row per selected tag and removes unselected ones`, `MorningFactorsUiStateTest.save never touches the notes or other logged factors` | tested |
| RUI-25 | Share sends NightSummaryText.build's plain text (headline, worst stretch, score with formula version, loudest clip, quiet/uncaptured gaps, detector-version-gated previous-night comparison) as ACTION_SEND text/plain | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `buildShareText` | `NightSummaryTextTest.comparison appears only when the detector version matches` | tested |
| RUI-26 | OTHER_NOISE events below the configured confidence threshold are hidden from counts/timeline/episodes with an integrity note counting them; rows stay in the DB; SNORE events are never filtered; 0 disables | `app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt` · `lowConfidenceNoiseExcluded` | `ReportAssemblerTest` | tested |
| RUI-27 | Quality banners list clipped / missing / unreadable clip counts from the shared integrity scan, clip-coverage evidence, Garmin MISSING or METRICS_ONLY status, and detector/score version changes vs the previous night | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `QualityBannersCard` | — | unverified |
| RUI-28 | The timeline draws events across the full time in bed; tap selects the nearest event; SNORE blocks are tier-colored, other noise grey; the selected block is outlined | `app/src/main/java/com/sleepanalysis/ui/report/Timeline.kt` · `NightTimeline` | — | unverified |
| RUI-29 | Hourly bars show snore seconds per hour with the y axis auto-scaled to the busiest hour | `app/src/main/java/com/sleepanalysis/ui/report/Timeline.kt` · `HourlyBars` | — | unverified |
| RUI-30 | The event list shows the newest 200 events first, although the timeline draws up to MAX_TIMELINE_EVENTS (2000) | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `ReportScreen` | — | unverified |
| RUI-31 | The session log screen reads one session's log file (null/blank renders the empty note), renders it monospace, and shares its full text with subject "SleepAnalysis session log #id" | `app/src/main/java/com/sleepanalysis/ui/report/SessionLogScreen.kt` · `SessionLogViewModel` | `SessionLoggerTest.missing log reads as null` | tested |
| RUI-32 | The validity badge shows the stored A–D grade (D in error color) with an expandable reason list; unknown reason ids render as the raw id | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `ValidityBadge` | — | unverified |
| RUI-33 | The TL;DR previous-night comparison shows the snoring delta only when the detector version matches and a stored previous snoreSeconds exists; the score comparison additionally requires the same score formula; otherwise a "hidden" line is shown | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `TldrCard` | `NightSummaryTextTest.comparison appears only when the detector version matches` | tested |
| RUI-34 | The Garmin card renders vendor metrics labeled "(Garmin estimate)", phone-vs-Garmin windows with overlap (zero overlap in error color), live validator warnings from the last import, and the import-state line | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `GarminCard` | — | unverified |
| RUI-35 | The disclaimer card renders unconditionally on every report | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `DisclaimerCard` | — | unverified |

## 6.4 Constants and thresholds

| Constant | Value | Defined in | Effect |
|---|---|---|---|
| `GarminRetryBounds.AUTO_RETRY_COOLDOWN_MS` | 21,600,000 ms (6 h) | `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt` · `GarminRetryBounds` | Cooldown between general-outcome backfill/refresh attempts on one wake date |
| `GarminRetryBounds.MAX_ATTEMPTS_PER_DATE` | 2 | `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt` · `GarminRetryBounds` | General attempts allowed per wake date (initial try + one cooldown retry) |
| `GarminNotReadyBounds.NOT_READY_COOLDOWN_MS` | 3,600,000 ms (1 h) | `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt` · `GarminNotReadyBounds` | Cooldown for "Garmin not ready" outcomes (payload usually lands within hours) |
| `GarminNotReadyBounds.MAX_ATTEMPTS_PER_DATE` | 4 | `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt` · `GarminNotReadyBounds` | Not-ready attempts allowed per wake date |
| `QUIET_GAP_MIN_MS` | 600,000 ms (10 min) | `app/src/main/java/com/sleepanalysis/domain/report/QuietGaps.kt` · `QUIET_GAP_MIN_MS` | Minimum stretch reported as a quiet / not-captured line in TL;DR and NightStructureCard |
| `MAX_TIMELINE_EVENTS` | 2,000 | `app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt` · `MAX_TIMELINE_EVENTS` | Bound on events carried in MorningReport (timeline source) |
| `MAX_TOP_CLIPS` | 8 | `app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt` · `MAX_TOP_CLIPS` | Rows in the "hear the worst moments" card |
| `DEFAULT_NOISE_MIN_CONFIDENCE` | 0.5 | `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt` · `DEFAULT_NOISE_MIN_CONFIDENCE` | Default for the low-confidence OTHER_NOISE display filter (DataStore key noise_display_min_confidence; 0 disables) |
| (hard-coded literal) 200 | 200 events | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `ReportScreen` | takeLast(200).reversed() cap of the scrollable event list |
| (hard-coded literal) 5,000 ms | 5 s | `app/src/main/java/com/sleepanalysis/ui/report/SessionLogScreen.kt` · `SessionLogViewModel` | WhileSubscribed timeout keeping the log state while the screen is visible |

## 6.5 Failure handling and edge cases

| Situation | Behaviour | Where |
|---|---|---|
| buildReport returns null (session deleted / bad id) | "No sessions" placeholder, no cards, share icon hidden | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `ReportScreen` |
| No Garmin key stored | Silent backfill skip, debug log only | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `maybeBackfillGarmin` |
| Wake-date budget exhausted on open | Backfill skipped; the blocked diagnosis surfaces only on the status line after a completed refresh | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `refreshStatusLine` |
| Exception during a refresh phase | finally block resets to IDLE, keeping completedAtMs and the budget-blocked flag; button re-enabled | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `refreshMorningReport` |
| Clip file missing at play time | No playback, no state change (button still reads Play) | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `playClip` |
| MediaPlayer setup throws | Playing id cleared, player released | `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `playClip` |
| Bulk-label DB write fails | Chip reverts to label null — a pre-existing label on that event is wiped, not restored | `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt` · `LabelingViewModel.label` |
| No clips to label / all labeled | "empty" / "all done" text in place of the list | `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt` · `LabelingScreen` |
| Log file missing or blank | "log empty" note; share action hidden | `app/src/main/java/com/sleepanalysis/ui/report/SessionLogScreen.kt` · `SessionLogScreen` |
| Previous night recorded with a different detector or score version | Comparison replaced by a "hidden" line plus a version-change quality banner | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `TldrCard` |
| Returning from the bulk labeling screen to an already-open report | Labels edited there are NOT shown — ReportScreen has no on-resume reload; the stale report persists until the next labeling/notes save or refresh. DEFECT: the screen shows outdated labels with no indication | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `ReportScreen` |
| RefreshReportCard "has stage timeline" vs GarminCard | Two different definitions: the refresh card uses stageSource != null, the Garmin card uses longestBoutStage != null or snoreMinutesByStage non-empty | `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `RefreshReportCard` |

## 6.6 Contract boundaries — what a refactor must not break

- **Persisted formats**: AcousticEventEntity.userLabel (EventUserLabel entry name or null); session morning-notes column; factor rows UserFactorEntity(date, factorKey, valueNum) written per key (STRESS quick tag = 5.0, booleans = 1.0); DataStore keys `garmin_auto_retry_date`/`_ms`/`_count`, `garmin_not_ready_retry_date`/`_ms`/`_count`, `noise_display_min_confidence`; log file name `session_<id>.log` under filesDir/logs with lines "<ISO-8601 UTC> [EVENT] detail".
- **Version strings stored with data**: SnoreScore.FORMULA_VERSION appears in the shared summary text and gates its score comparison; validity reason ids (stats_missing, capture_failed, garmin_stale, …) are mapped by id in `validityReasonText` — unknown ids must keep rendering raw.
- **Platform contracts**: ACTION_SEND text/plain intents (report share: EXTRA_TEXT; log share: EXTRA_SUBJECT "SleepAnalysis session log #id"); nav route strings in Routes; MediaPlayer lifecycle must stay stop-then-play with release on completion/failure/teardown.
- **Cross-area interfaces**: ReportViewModel calls SessionRepository.buildReport / labelEvent / setMorningNotes and FactorLogRepository.saveFactorForDate / removeFactorForDate (section 4); it consumes MorningReport, GarminLinkStatus, QuietStretchTotals, NightSummaryText, NightDates.garminWakeDate / factorDay, ReportAssembler.garminNormalizedSharePercent and STAGE_SOURCE_WEARABLE (section 5); it drives GarminImportManager (canImport, importForDate, state) and HealthConnectImportManager (isAvailable, needsImport, importForDate) whose singleton state flows are shared with Settings (section 8); the clip scan uses ClipIntegrity + NightValidity.clipQualitySummary (section 5).
- **Safety-net tests**: `ReportRefreshStateTest`, `ReportViewModelGarminBackfillTest`, `MorningFactorsUiStateTest`, `LabelingUiStateTest`, `SessionLoggerTest`, `NightSummaryTextTest`, plus `SessionLoggerTest.appends timestamped lines and reads them back` for the log file format.

## 6.7 Consolidation candidates

| Candidate | Kind | Evidence | Risk to remove | Note |
|---|---|---|---|---|
| `app/src/main/java/com/sleepanalysis/ui/report/ReportViewModel.kt` · `playClip` + `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt` · `play` | `duplicate` | Identical MediaPlayer blocks (stop-first, exists check, completion/failure release); reference scan lists stopPlayback twice with identical bodies | `low` — extract one small player holder; keep stop-then-play and release-on-clear semantics | Classic shared-player extraction |
| `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `TldrCard` previous-night block vs `app/src/main/java/com/sleepanalysis/domain/report/NightSummaryText.kt` · `build` | `duplicate` | Same detector/score version gating, delta and sign logic implemented twice (UI and share text) | `low` — divergence makes the card and the share text disagree | One shared formatter could serve both |
| Label-chip FlowRow: `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `ClipLabelChips`, the chip row inside `SelectedEventCard`, and `app/src/main/java/com/sleepanalysis/ui/report/LabelingScreen.kt` · `LabelingRow` | `duplicate` | The same six FilterChips rendered in three places | `low` — pure UI | Keep one composable |
| strings `report_garmin_window`, `report_interrupted_note`, `validity_title` in `app/src/main/res/values/strings.xml` | `dead` | Lint UnusedResources; grep over app/src finds no reference outside strings.xml (only `report_garmin_windows`, a different key, is used) | `none` | `report_interrupted_note` is superseded by the validity reason "session_interrupted" rendering in `validityReasonText` |
| `app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt` · `eventCountsByType` and `meanSnoreDbfs` | `test-only` | grep over app/src: only ReportAssemblerTest assertions and the NightSummaryTextTest fixture reference them; no composable reads them | `low` — removing them deletes those fixture fields and assertions | Report state carried but never rendered |
| `app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt` · `status` (MorningReport field) | `dead` | grep over app/src/main finds no reader; HistoryScreen/HomeScreen read the session entity's status instead | `low` — DTO-only field | Settled: tools/morning_report.sh and tools/export_night_csv.py read the DB status column directly, not this DTO; no external consumer |
| `app/src/main/java/com/sleepanalysis/ui/report/ReportScreen.kt` · `HeadlineGrid` | `debug/experiment` (minor) | Intensity-tier AssistChips have onClick = {} — clickable affordance with no action | `none` | Render as Text, not chips |

Duplication notes: the report view model already extracted its gates and mappings into testable top-level pure functions (`refreshStatusLine`, `refreshGarminGateOpen`, `morningFactorWrites`, …) — a good pattern the screen's own TL;DR and comparison logic has not followed. The "has stage timeline" divergence (6.5 last row) is a consistency, not dead-code, issue. ReportScreen.kt is 1,709 lines with 20+ private composables; a split along the card boundaries would not change behaviour. Several sites format with the default locale (HeadlineGrid's snore share, SelectedEventCard's confidence %.2f and the detector-feature lines) while the rest of the file pins Locale.US — a minor formatting inconsistency to unify during consolidation.

## 6.8 Open questions for the owner

- DEFECT (see 6.5): labels made in the bulk labeling pass do not appear on an already-open report — there is no reload on returning to the screen. Fix by reloading on resume, or accept and document?
- Unify or keep the two "has stage timeline" definitions (refresh card vs Garmin card)? The honest line semantics depend on this choice.
- Keep the ungated manual "Import from Garmin" button (it bypasses the budget gate but then marks the budget) — is the immediate-retry escape hatch intended policy?
- DEFECT (see RUI-20): a failed bulk-label write reverts the target row's label to null instead of the previous label — a failure after retapping an already-labeled chip silently wipes that earlier label. Fix the revert target or accept as-is.
- UNVERIFIED: device-verification status of the refresh pipeline in docs/STATUS.md and docs/DOGFOOD_CHECKLIST.md was not consulted under this session's reading budget; all device-verified claims were therefore avoided.
- Settled by review: tools/morning_report.sh gates on the DB status column (status IN ('COMPLETED','RECOVERED')) and tools/export_night_csv.py reads the column directly; neither consumes the MorningReport DTO, so the DTO status field has no external consumer.
