# Consolidation plan — reduce app complexity and size

**Status:** agreed plan (2026-11-26) · executed as separate tasks T1–T10, one branch-less sequence on `consolidate/2026-11-22` · verified baseline: 821 unit tests / 116 pytest / ruff clean.
**Execution status:** T1–T10 executed in commit a48790e (2026-11-26); final counts **767 unit / 121 pytest** (per [docs/STATUS.md](STATUS.md)); migrations 14→15→16 applied, on-device validation still pending. The §6 appendix task prompts are archival (already executed); the T10 APK-size measurement was not recorded in-repo.
**Authority:** every scope claim below traces to the verified functional specification
([docs/FUNCTIONAL_SPEC.md](FUNCTIONAL_SPEC.md), sections in [docs/functional_spec/](functional_spec/))
and its consolidation map (section 11: 109 candidates, 29 defects, do-not-touch list).
**During the refactor the spec is FROZEN** — it is the 2026-11-25/26 baseline contract. Tasks must
not edit it; task T9 refreshes it at the end. (Citations in it may point at symbols deleted by a
map row — that is expected mid-refactor.)

## 0. Goal and principles

Core loop to preserve: **Start → (scheduled) overnight recording → snore detection + clips →
morning report + Garmin / Health Connect import → trends & insights.**

1. **One copy of every behaviour** — duplicated logic already produced bugs (the 06:30 stop
   confirmation; the share-text denominator). Consolidate to one implementation per behaviour.
2. **Cut along the core loop** — features outside it are removed, not refactored.
3. **Contracts survive** — persisted formats, log-event names parsed by tools, provenance
   columns, SQL triggers, migration chain, Kotlin↔Python mirror pairs (see §3).
4. **Small verified steps** — every task ends with the full suites green and the tree
   uncommitted; the owner commits between tasks.
5. **No device access** — never adb, never install. Migrations are validated by code review +
   schema export; the owner validates on their phone at the next ordinary install (after a
   manual `tools/backup_db.sh` run).

## 1. Scope decision record (agreed 2026-11-26)

Decision D1 — **follow the recommendation**: cut the N-of-1 experiment machinery, playbook,
escalation card, the standalone Factor Log screen and the Clinical Baseline screen; keep
trends, insights (rules, personal baseline, SRI/social jetlag), the full report, Garmin and
Health Connect.

| Feature | Decision | Rationale / consequence |
|---|---|---|
| N-of-1 experiments + playbook | **CUT** (T6) | Not in the core loop; the biggest single block: `domain/experiments/ExperimentTracker.kt`, `domain/insights/Playbook.kt`, experiment card + playbook tab in `ui/insights`, experiment chip in `ui/home/HomeViewModel.kt`, `experimentPhaseNights` in `SessionRepository`, `sleep_experiment` table (drop in migration), `experiment_*` strings, tracker glue. Their defects die with them (map D-08 subset). |
| Escalation card (`EscalationRule`) | **CUT** (T6) | Per the agreed recommendation. One-line de-scope if the owner prefers the "consider a sleep study" card: it is small and independent of experiments. |
| Standalone Factor Log screen | **CUT** (T7) | `ui/factorlog/FactorLogScreen.kt` + its route. `user_factor` table and `FactorLogRepository` **STAY** — the morning report's notes / 1–5 rating / quick tags still read and write them. Verify what the Home morning card reads before deleting anything else. |
| Clinical Baseline screen | **CUT** (T7) | `ui/baseline/` + `BaselineRepository`. The table: `BaselineRepository.all` is already dead and nothing in-app reads entries except the screen itself — grep `tools/` first; if no tool reads `clinical_baseline`, drop table + triggers + DAO in the migration (this retires invariant INV-03 — T9 rewrites it); if a tool reads it, keep the table, drop only screen + repository. |
| Trends + insights (non-experiment) | **KEEP** | The "improve my sleep" feedback loop: `TrendAssembler`, `InsightRules`, `PersonalBaseline`, `SleepRegularity`, both screens minus experiment parts. |
| Report + Garmin/HC + capture/detection | **KEEP** | The core loop. Only dedup (T4) and defect fixes (T8) touch them. |

## 2. Baseline and runbook (owner steps, not agent tasks)

Run these yourself before T1 and between tasks:

0. ~~**Commit the current state first** (the approved Home change + the spec must not tangle
   with refactor diffs):
   `git add -A && git commit -m "feat: home schedule-row off-state (approved review)"` — the
   working tree also contains the new spec; either include it in the same commit or a second
   `docs:` commit.~~ — executed (2026-11-26): the baseline was committed before T1; T1–T10
   landed as commit a48790e.
1. **Capture the size baseline:** `./gradlew :app:assembleDebug` and note the size and path of
   `app/build/outputs/apk/debug/app-debug.apk` (T10 compares against this).
2. **Back up the phone data once before the first install of any refactored build:** run
   `tools/backup_db.sh` yourself (it uses adb; agent tasks must never run it).
3. **After every task:** run the checks in the task's "Verify" section yourself or read them
   from the task report, then commit: `refactor:` (T1–T7), `fix:` (T8), `docs:` (T9).
4. **After T7's first phone install:** expect migrations 14→15→16 to run on the real database.
   If anything fails, restore from the step-2 backup and report back.

## 3. Do not touch (contract list — spec section N.6s and map §11.4 phase 4)

- SQL triggers on `clinical_baseline` (until T7 decides the table's fate — then they go with it).
- Provenance columns and the write boundary: `MeasurementRecorder.requireWritable`, the
  `isClinical`/`method`/`periodEnd` columns, `checkForbiddenTerms`, `checkNetworkHosts`,
  `checkPermissionAllowlist` Gradle gates.
- Log-event and marker names other layers parse: the `recording_stop` line incl. source/status
  (`tools/night_battery.py`), `wl=off` / `wl=off-delay-covered` markers in old logs
  (readers stay tolerant even after T3 stops writing them), `capture_wakelock_released`,
  `detector_config`, the `label_corrected=true` note token.
- `raw_payload` storage (verbatim Garmin responses for revalidation), `sleep_stage` /
  `source` values (`GARMIN`/`WEARABLE` — the spelling must stay equal to the HC `STAGE_SOURCE`
  literal), `audio_clip.eventId` back-fill the export join relies on.
- Version strings and their consumers: `energy-heuristic-v3-peakgate`, `snore-score-v4`,
  the model registry rows, `captureStats` schema_version 6 + legacy parsing v1–v5,
  report snapshot payload v12, `+dbg1` / `"wl=…"` values already stored in old rows (stop
  writing, keep parsing).
- The migration chain 1→14 (never rewrite old steps; only append).
- Kotlin↔Python mirror pairs — change both sides in the same task or not at all: validity
  thresholds, `SnoreMath` counting/union, the capture-window rule, the night-validity SQL of
  `tools/morning_report.sh` (pinned by `test_morning_report_sql`).

## 4. Task graph

| # | Task | Depends on | Budget (tool calls) | Primary verification |
|---|---|---|---|---|
| T1 | Zero-risk deletions | — | 35 | suites green; grep proofs per deletion |
| T2 | Evaluation code out of the APK | T1 | 25 | `evalFixtures` still works; no main-source refs |
| T3 | Debug toggles removal | T1 | 30 | suites green; old-row parsing intact |
| T4 | App-code dedup, one copy each | T2, T3 | 45 | suites green; behaviour rows unchanged |
| T5 | Python tooling dedup + fixes | T1 | 30 | pytest green; mirror pairs intact |
| T6 | Cut experiments + playbook + escalation (incl. migration 14→15) | T4 | 45 | suites green; schema 15.json exported |
| T7 | Cut Baseline/FactorLog screens + column bundle (migration 15→16) | T6 | 40 | suites green; schema 16.json exported |
| T8 | Defect-fix pass (map §11.2 subset) | T4–T7 | 45 | suites green; each row fixed or won't-fixed with reason |
| T9 | Docs rewrite + spec refresh | T1–T8 | 30 | doc claims match code; spec citations resolve |
| T10 | Final verification + size report | T9 | 20 | all suites; APK size vs §2 baseline |

Do not parallelise T6/T7/T8; run the tasks in order — each one's diff should be reviewable alone.

## 5. Task specifications

**Archival (2026-11-26):** T1–T10 were executed in commit a48790e; the specifications below
are the record of what was run, kept unmodified — the per-task evidence lives in the task
reports and in [docs/STATUS.md](STATUS.md) §3/§5.

### T1 — Zero-risk deletions

Scope (map §11.1 rows, `dead` and `dependency` kinds only, plus comment-only `doc-drift` rows):
`PreFlightChecklist.allClear`, `ClipStore.totalBytes`, `SessionRepository.sessionsOlderThan` +
its DAO query + `allStartTimes`, `ModelRegistryDao.historyForKind` + `versionsAt`,
`ReportDao.all`, `BaselineRepository.all`, the `provideReportDao`/`provideModelRegistryDao`/
`provideSleepExperimentDao` trio, `UserPreferences.retentionNow`, `GarminImportManager.garminMetricsBetween`
(with its false KDoc), `MorningReport.status` (verified: no UI reader; Python reads the DB
column, not the DTO), the `turnScreenOn=false` attribute, the unused `source` parameter of
`MonitorService.start`, privatise `SessionRepository.currentModelVersions`; the 9 lint-dead
strings (`home_view_report`, `home_battery_hint_title/_body/_button`, `validity_title`,
`retention_worker_note`, `report_garmin_window`, `report_interrupted_note`,
`experiment_active_body` — hold the last two only if their T6/T9 fate is unclear; it is not:
both die in T6/T9, delete now); the three unused version-catalog entries
(`lifecycle-viewmodel-compose`, `ui-tooling`, `ui-tooling-preview`). Comment/KDoc-only fixes
with no behaviour change: `Daos.clearClipIds` comment, the "50-clip budget" comment in
`MonitorService`, `finalizeOpenAsRecovered` KDoc, `MissedNightPolicyTest` KDoc, `RecordingScheduler.arm`
KDoc, the `build.gradle.kts` "1.json…8.json" comment, `EventFeatures`/`ReplayEngine` KDoc drift,
`SettingsScreen.setScheduleStart` misplaced KDoc, `ReportAssembler.garminOverlapMs` orphaned KDoc
+ `snoreMinutesPerStage` KDoc, `EventUserLabel` "five labels" KDoc, `ReportViewModel.stagesStillMissing`
KDoc, `AppDatabase.purgeEverythingIncludingClinical` rollback comment (map D-06 comment part,
D-09, D-10, D-13, D-27, D-28 comment parts), the false `GarminClient.bearerToken` message text
(D-11 — message text only, keep behaviour). Also retire the test-only display half of
`ClaimPolicy.displayAllowed` (no production caller; the write boundary `requireWritable` stays):
delete `displayAllowed` + its ClaimPolicyTest cases; INV-08's doc half is rewritten in T9.
Out of scope: anything behavioural, any contract row, any string still referenced anywhere
(re-grep each including tests first). Verify: full suites green; for every deletion a grep
proof that zero references remain. Risks: `experiment_active_body`/`validity_title` re-added
by mistake — they are dead now; `Methods.CLINICAL_MEASURED`/`USER_REPORTED` constants are
dead-but-contract-adjacent (ARCHITECTURE names them) — delete the constants, T9 fixes the doc.

### T2 — Evaluation code out of the APK

Scope: move `monitoring/eval/FixtureEvaluator.kt`, `monitoring/replay/ReplayEngine.kt`,
`EvalReport` (wherever it lives — grep), and the test-only `SpectralFeatures.compute` overload
out of `app/src/main` into `app/src/test` (same packages; the `evalFixtures` Gradle task runs
`FixtureEvalRunTest`, which keeps working from the test source set). Update imports; keep
`FixtureEvalRunTest` and the Gradle task functional. Also move the `DETECTOR_VERSION` constant
usage note: NO constant changes here (that is T4) — only relocation. Out of scope: any
behaviour change, the detector itself. Verify: `./gradlew :app:evalFixtures` still skips
cleanly without `fixtures/manifest.json` (it must not fail); suites green;
`grep -rn "FixtureEvaluator\|ReplayEngine\|EvalReport" app/src/main` returns nothing
(except allowed comments — none should remain). Risks: KDoc references from main-source files
(`SpectralFeatures` KDoc references ReplayEngine) — update or drop those lines.

### T3 — Debug toggles removal

Scope: remove the two debug switches end to end — Settings UI (`setDebugEnterWindows1`,
`setDebugSkipWakelock`, their rows and DataStore keys `debug_enter_windows_1` /
`debug_skip_capture_wakelock`), the `+dbg1` model-version suffix branch
(`MonitorService.activeModelVersion` simplifies to `SessionRepository.MODEL_VERSION`),
`CaptureWakelockMode` FULL/DELAY_ONLY/SKIPPED selection and its branches in
`MonitorService.beginRecording` (hardcode the FULL arm — the default today), the `wl=off` /
`wl=off-delay-covered` marker writing, `DetectorConfigValues` A/B toggles
(`spectralGatingEnabled`, `rhythmEnabled` + `RHYTHM_ENABLED` constant) and the
`EnergySnoreDetector` branches they gate. Readers of OLD stored data stay tolerant: nothing
may assume the markers' absence (grep `wl=off` consumers — report what you find; do not delete
tolerant parsing). Delete the related tests (they test the toggles) but keep every test that
pins default-path behaviour; do not weaken any surviving test. Verify: suites green;
`grep -rn "debug_skip_capture_wakelock\|debug_enter_windows_1\|CaptureWakelockMode\|+dbg1" app/src`
shows only tolerant-read sites if any. Risks: the `+dbg1` suffix appears in `insertEvent`
modelVersion writes — ensure the simplification keeps writing the plain constant.

### T4 — App-code dedup, one copy each

Scope (map `duplicate` kind, app side; each item: keep ONE implementation, move callers to it,
delete the rest — behaviour must be unchanged per the spec's requirement rows):
(a) the captured-span/share/score builder: one shared implementation behind finalize,
    `reportSnapshotHeadline`, `labelCorrectedSnoreEstimates`, `ReportAssembler.compute`
    (sections 4+5 map rows; keep denominators EXACTLY as documented — captured-analysis vs
    time-in-bed); (b) TL;DR text: `NightSummaryText` wins, `TldrCard` calls it; (c) `skipTonight`
    + the five next-start refresh sites → one HomeViewModel path used by Settings too;
    (d) the three notification builders (delay/recording/interrupted content intents via one
    `openAppPendingIntent`-style helper); (e) MediaPlayer start/stop/release → one small holder
    used by ReportViewModel and LabelingViewModel; (f) the label-chip FlowRow triplicate and the
    SwitchRow/CheckRow/TimeRow trio → shared composables in `ui/components`; (g) the duplicate
    disclaimer accepted-mapper (AppRoot vs OnboardingViewModel) → one; (h) `DETECTOR_VERSION` →
    `SessionRepository.MODEL_VERSION` everywhere (FixtureEvaluator now test-side imports it);
    (i) `NIGHT_LIKE_MIN_IN_BED_MS` → one constant (Kotlin side; Python mirrors in T5);
    (j) `AmbientLevelBuckets` mapping duplication; (k) `garminMetricsForSessionDay` merge;
    (l) `home_schedule_off` string reuse vs `settings_schedule_off`; (m) `findOpenSession` /
    `openSession` query merge. Out of scope: experiment code (T6 removes it first), Python
    (T5), defect behaviour changes (T8 — EXCEPT where a dedup naturally absorbs the fix site,
    e.g. (c) is also where the D-15 `scheduleEndMinutes` flow fix lands — coordinate: implement
    the D-15 fix IN this item per map 11.2). Verify: suites green; for each item, the spec
    requirement rows of sections 1–8 still hold (spot-check the pinned tests still pass — they
    do, since the suite runs). Risks: denominator drift in (a) — copy values verbatim, let the
    tests prove equality; keep diffs per item small and separate.

### T5 — Python tooling dedup + fixes

Scope: (a) `detect_serial` ×3 + `latest_session_id` ×4 + clip/SQL join helpers → one shared
module under `analysis/` imported by the tools; (b) the snore-union + counts-as-snore mirror:
`night_trends.py` must use the exact `SnoreMath`-parity implementations — fix the
empty-string `userLabel` asymmetry (treat `""` as unlabeled, matching `night_aggregates.py`);
(c) fix `night_trends.collect_nights` to label the night date from the session's stored
`timeZone` column if the data source provides it, else document the local-zone approximation
in the module docstring (do not invent a column — grep the CSV/DB source first);
(d) `night_trends.capture_window_ms`: add the `capture_started_at < endedAt` guard
(morning_report.sh parity) so a corrupt value falls back to `startedAt` instead of a negative
window; (e) document `tools/backup_db.sh` in the README Python-tooling section (it is
referenced nowhere today) — do not run it; (f) declare matplotlib/numpy as a uv extra in
`pyproject.toml` (`analysis` extra) matching the `--with` usage in `morning_report.sh` +
`night_report.py`. Out of scope: any Kotlin file; the morning_report SQL text (mirror-pinned);
removing any tool. Verify: `uv run pytest -q` green (add/adjust tests for (b)/(d) — the
existing test files show the fixture style); `uv run ruff check .` clean. Risks: the mirror
pairs (§3) — if a fix would change a value the Kotlin side also computes, STOP and note it.

### T6 — Cut experiments + playbook + escalation (migration 14→15)

Scope: remove the whole vertical — `domain/experiments/ExperimentTracker.kt` +
`ExperimentMath`, `domain/insights/Playbook.kt`, `domain/insights/EscalationRule.kt`,
`SessionRepository.experimentPhaseNights` + experiment result/confirm persistence +
`SleepExperimentDao` + the `sleep_experiment` entity and all its SQL guards,
`di/AppModule` experiment providers, the experiment chip in `HomeViewModel` + Home UI,
the experiment/playbook cards and tabs in `InsightsScreen`/`InsightsViewModel`
(keep rules/baseline/regularity/SRI cards), `experiment_*` strings, `CONFOUNDABLE_FACTOR_KEYS`
(only used by experiments), and all experiment-only tests. **Migration 14→15** drops
`sleep_experiment` (extend `MigrationTest` code accordingly; on-device validation is the
owner's next install — say so in the report); export the new schema JSON
(`14→15` appears under `app/schemas/`). Data note: any stored experiment rows are lost —
accepted (owner decision D1). Keep: `user_factor` (report notes), `InsightsScreen`
non-experiment cards, `model_registry` (version gating for trends/insights stays).
Verify: suites green after removing experiment-only tests; `grep -rn "Experiment\|playbook\|escalation" app/src/main`
returns nothing outside allowed coincidences (report each hit); schema 15.json exists;
`./gradlew :app:testDebugUnitTest` count drops by the removed tests — report the new count.
Risks: HomeViewModel/InsightsViewModel surgery — keep the surviving flows compiling and
pinned by their tests; the `report` snapshot table must stay untouched.

### T7 — Cut Baseline + FactorLog screens + column bundle (migration 15→16)

Scope: (a) cut `ui/baseline/` (screen, view model, route in `AppNavHost`/`AppRoot`),
`BaselineRepository`; grep `tools/` for `clinical_baseline` readers — if none, drop the
`clinical_baseline` table + `ClinicalBaselineDao` + the immutability triggers +
`createImmutabilityTriggers` in migration 15→16 (this retires INV-03; T9 rewrites it) and
delete `ClaimPolicyTest` fixtures that use the baseline entity only as fixture data (adjust
them to another fixture); if a tool DOES read it, keep the table and drop only screen +
repository — report which happened. (b) cut `ui/factorlog/FactorLogScreen.kt` + route;
KEEP `user_factor`, `FactorLogRepository` and the report-side morning notes/rating/tags
editing; grep the Home morning card first and keep what it reads. (c) column bundle in the
same migration 15→16: drop write-only columns `enteredAt`, `recordedAt`, `audioBytesTotal`,
`deviceModel`, `osVersion`, `appVersion`, the reader-less `createdAt` trio (acoustic_event,
quiet_gap, sleep_experiment — the last is already gone via T6), and `labeledAt` — FIRST
update `tools/export_fixtures.py` and `tools/export_night_csv.py` to stop selecting dropped
columns (Kotlin↔Python mirror rule: same task, and pytest must stay green). Keep `isClinical`,
`method`, `periodEnd` (provenance contract) and the three exporter-read `createdAt`s
(session, measurement, sleep_stage). Verify: suites green; schema 16.json exported;
`grep -rn "enteredAt\|recordedAt\|audioBytesTotal\|labeledAt\|deviceModel" app/src/main tools/`
returns only intended hits; pytest covers the updated exporters. Risks: Room identity hash —
provide a real migration, never `fallbackToDestructiveMigration`; MigrationTest gets new
14→15→16 cases (code-level; on-device validation is the owner's next install).

### T8 — Defect-fix pass

Scope: work through map §11.2 rows — EXCLUDING comment-only items fixed in T1, experiment
items that died in T6, and Python items fixed in T5. The expected live set: D-01
(`skipStartDelay` notification race), D-02 (beginRecording orphan-row guard order),
D-03 (stale interrupted banner during Delayed after resume-with-delay), D-04
(`start()` must reset `lastCooldownLogBucket`), D-05 (empty clip-ring delivery must log a
diagnostic), D-07 (`OrphanSweepPolicy` negative-age guard), D-12 (AppRoot accepted-flow
spinner flash after 5 s away), D-15 (already done if T4(c) implemented it — verify), D-16
(`MicIndicator` content description must state the real state; fix colour-only CheckRow too
or note), D-17 (bulk-label failure must revert to the row's PREVIOUS label, not null),
D-29 (onboarding "no internet permission" reword), plus section 1.8's D3 (disabled-Start hint
fires for interrupted-night and low-storage too — narrow the text or its condition),
D4 (`onCaptureError` stop should log an honest source, not `manual`), D5 (exact-alarm grant
must re-arm — smallest correct fix: re-arm on ON_RESUME when the grant state changed),
D7 (service-recreation holder staleness: reset the holder state in `onDestroy` when the
process keeps living — verify the real lifecycle first), and D6 (Home "Resume" for an
interrupted night — smallest fix: add the Resume action button to the interrupted card,
wired to the existing notification action's intent). For each row: verify it still exists,
fix minimally in the existing style, add or extend a unit test (JVM-runnable only — no
instrumented tests), or declare won't-fix with a reason. Verify: suites green; new tests for
every fixed row; report per-row status. Risks: D-03/D6 touch the interrupted-night state
machine — the INV-04 rows in spec section 0.4 and section 1's decision tables are the
contract; do not weaken `StartIntentPolicyTest`/`InterruptionStopSequenceTest` expectations.

### T9 — Docs rewrite + spec refresh

Scope: (a) fix every `doc-drift` row the map inherited (docs/ARCHITECTURE.md §6 items,
DATA_MODEL snake_case + phantom columns + migration-count + retention-table gaps,
NIGHT_REPORT snore-score-v3 and schema v9, MVP_SCOPE schema v6, PRIVACY §4 clips-only purge +
retention-UI-pending, NEXT_STEPS/ROADMAP/PRODUCT_VISION/STATUS "not implemented" claims now
that experiments/baseline/factor-log screens are gone — rewrite STATUS §3 to the new truth);
(b) consolidate the five-place "done/verified" overlap per overview 0.7 notes and mark
superseded docs; (c) README: remove the retention-settings claim, the onboarding-internet
wording if present, document `backup_db.sh`, refresh the Documentation table; (d) refresh the
functional spec: update the sections' rows whose `Where` symbols moved or died (mark removed
rows as `removed in consolidation <task>` rather than deleting history), regenerate the
invariants (INV-03 gone or narrowed per T7's outcome), re-run `ktscan.py` +
`lint-summary.py` to regenerate `evidence/`, and update section 11 statuses; (e) record the
final APK size and test counts. Verify: spot-check every rewritten claim with grep (docs
rules: code wins); suites green (docs-only + spec edits, but run them anyway). Risks: none
behavioural; do not touch code in this task.

### T10 — Final verification + size report

Scope: run everything and report: `./gradlew :app:testDebugUnitTest --console=plain` (full
count + failures), `uv run pytest`, `uv run ruff check .`, `check_refs.py` over the refreshed
spec, `assembleDebug` APK size vs the §2 baseline, `grep`-based proof that the §3 contract
list is intact, and the final symbol count (re-run `ktscan.py`). Report what remains
unverified (migrations awaiting the owner's next install; anything tasks marked won't-fix).
No code changes. Risks: none.

## 6. Appendix A — task prompts (paste into a fresh session, one per task)

Shared preamble is already inline in each prompt; do not merge tasks into one session.

---

**PROMPT T1**

```
Task T1 of docs/CONSOLIDATION_PLAN.md — zero-risk deletions. Repository: /home/mick/Projects/sleep-analysis (Kotlin Android app; Python tooling under tools/ and analysis/ — you will touch Kotlin + resources + gradle only).

First read, in as few calls as possible: docs/CONSOLIDATION_PLAN.md (§0–§5, task T1), docs/functional_spec/11-consolidation-map.md (the rows named in plan §T1), docs/functional_spec/01-recording-controls.md §1.8 and docs/functional_spec/04-data-layer.md §4.7 (context for the symbols). Batch your reading; never read test files in full (grep -n 'fun ' then sed -n the assertions you need).

HARD BUDGET: at most 35 tool calls. Rules: minimal diffs; match existing code style; do NOT fix behaviour (that is T8); do NOT touch the §3 do-not-touch contract list; NEVER run adb or install a build (a phone with irreplaceable recordings is attached); NEVER commit — leave the tree green and uncommitted; do not weaken or delete tests except the named ClaimPolicyTest displayAllowed cases.

Delete/fix exactly the items in plan §T1 (re-grep each symbol over app/src + tools/ + analysis/ before deleting; if a symbol turns out to be referenced, STOP for that item and report it). For the KDoc/comment fixes: change only the text.

Verify, in one or two calls: ./gradlew :app:testDebugUnitTest --console=plain -q  (must pass; report the test count from app/build/test-results), uv run pytest -q, uv run ruff check . — and show the real result lines. Report (≤200 words): items deleted/fixed with the grep proofs, the suite result lines, anything you did not do and why.
```

**PROMPT T2**

```
Task T2 of docs/CONSOLIDATION_PLAN.md — move evaluation-only code out of the production APK. Repository: /home/mick/Projects/sleep-analysis.

First read: docs/CONSOLIDATION_PLAN.md §T2, docs/functional_spec/03-detection-clips-scoring.md §3.3 rows DET-44…DET-47 and §3.6 (what the eval code must keep doing), and the files monitoring/eval/FixtureEvaluator.kt, monitoring/replay/ReplayEngine.kt (grep for EvalReport's file). Batch reads.

HARD BUDGET: at most 25 tool calls. Rules: relocation only — no behaviour edits, no constant changes (DETECTOR_VERSION unification is T4), no adb, no installs, NEVER commit. Move monitoring/eval/FixtureEvaluator.kt, monitoring/replay/ReplayEngine.kt, and the test-only SpectralFeatures.compute overload into app/src/test/java (same packages). Update the KDoc lines in main sources that reference the moved classes. The Gradle task evalFixtures and FixtureEvalRunTest must keep working from the test source set.

Verify: ./gradlew :app:testDebugUnitTest --console=plain -q (report count), ./gradlew :app:evalFixtures --console=plain (must skip cleanly without fixtures/manifest.json, not fail), uv run pytest -q, uv run ruff check ., and grep -rn "FixtureEvaluator\|ReplayEngine\|EvalReport" app/src/main (expect zero real hits). Report (≤200 words): files moved, verification lines, anything not done.
```

**PROMPT T3**

```
Task T3 of docs/CONSOLIDATION_PLAN.md — remove the two debug toggles end to end. Repository: /home/mick/Projects/sleep-analysis.

First read: docs/CONSOLIDATION_PLAN.md §T3 and §3 (do-not-touch), docs/functional_spec/01-recording-controls.md (state machine + beginRecording gates + notification/markers rows), docs/functional_spec/03-detection-clips-scoring.md (DetectorConfigValues A/B toggles row, DET-41 +dbg1 row), docs/functional_spec/09-app-shell-settings-privacy.md (Settings debug toggles). Batch reads; for tests use grep -n 'fun ' then sed -n only what you change.

HARD BUDGET: at most 30 tool calls. Rules: minimal diff; keep default-path behaviour EXACTLY (FULL wake-lock arm, no +dbg1 suffix — the plain SessionRepository.MODEL_VERSION); keep OLD-data readers tolerant (wl=off markers in stored logs must still parse wherever they are read today — grep first and report the consumers); never adb/install; NEVER commit; do not weaken surviving tests; deleting toggle-only tests is expected.

Remove: the Settings rows + setDebugEnterWindows1/setDebugSkipWakelock + DataStore keys debug_enter_windows_1/debug_skip_capture_wakelock (leave the old stored key values in place on devices — just stop reading them), the CaptureWakelockMode selection + branches in MonitorService.beginRecording (hardcode FULL), the wl=off/wl=off-delay-covered marker writing, the +dbg1 branch in activeModelVersion, DetectorConfigValues spectralGatingEnabled/rhythmEnabled + RHYTHM_ENABLED + the branches they gate, and the toggle-only tests.

Verify: ./gradlew :app:testDebugUnitTest --console=plain -q (report new count), uv run pytest -q, uv run ruff check ., grep -rn "debug_skip_capture_wakelock\|debug_enter_windows_1\|CaptureWakelockMode\|+dbg1" app/src (report every remaining hit and why it is allowed). Report (≤200 words): what was removed, consumers of old markers found, suite lines.
```

**PROMPT T4**

```
Task T4 of docs/CONSOLIDATION_PLAN.md — app-code dedup: one copy of every duplicated behaviour. Repository: /home/mick/Projects/sleep-analysis.

First read: docs/CONSOLIDATION_PLAN.md §T4 and §3; docs/functional_spec/11-consolidation-map.md `duplicate` rows for sections 1–8; then the spec sections for each area you touch — §N.6 contract boundaries and §N.3 rows pin the behaviour you must preserve; docs/functional_spec/05-report-domain.md (denominators!) and 06-report-ui.md for the TL;DR/player items, 04-data-layer.md for the math builder, 01-recording-controls.md for skipTonight and the notification builders. Batch reads.

HARD BUDGET: at most 45 tool calls. Work through plan §T4 items (a)…(m) ONE AT A TIME, smallest diff each, running the unit suite after each group of items (./gradlew :app:testDebugUnitTest --console=plain -q). Rules: behaviour must be UNCHANGED except item (c), which also implements map defect D-15 (HomeViewModel.shouldConfirmStopNow must see the real scheduleEndMinutes — derive both schedule inputs from one refreshed read per the spec 1.8 fix note); denominators and formulas copied verbatim with the pinned tests proving equality; never adb/install; NEVER commit; do not weaken tests; skip experiment-area items (T6 removes them) — if an item overlaps experiment code, note and skip.

Verify: full suites + ruff after the last item; report (≤250 words): per item — kept implementation, deleted copies, suite lines, skipped items.
```

**PROMPT T5**

```
Task T5 of docs/CONSOLIDATION_PLAN.md — Python tooling dedup + fixes. Repository: /home/mick/Projects/sleep-analysis (Python under tools/ and analysis/; use uv: uv run pytest, uv run ruff check; add deps only via pyproject).

First read: docs/CONSOLIDATION_PLAN.md §T5 and §3 (Kotlin↔Python mirror pairs — do not break them), docs/functional_spec/10-python-tooling-ci.md (the verified per-tool contracts and the duplication rows), then the actual scripts. Batch reads; never read test files in full.

HARD BUDGET: at most 30 tool calls. Rules: no Kotlin changes; never run adb (backup_db.sh, morning_report.sh, export scripts in device mode are forbidden — read them, don't run them); NEVER commit; keep the morning_report.sh SQL text untouched (mirror-pinned).

Do plan §T5 (a)–(f): shared module under analysis/ for detect_serial/latest_session_id/join helpers; night_trends.py switched to the SnoreMath-parity union + counts-as-snore with the empty-string-userLabel asymmetry fixed ("" = unlabeled, matching night_aggregates.py); the night-date timezone fix (verify what data source collect_nights actually reads; use the stored session timeZone if present, else document the approximation — do not invent columns); the capture_window_ms guard (capture_started_at < endedAt else startedAt); README documentation for backup_db.sh; a uv `analysis` extra declaring matplotlib/numpy. Add/adjust pytest cases for the behaviour fixes following the existing test style.

Verify: uv run pytest -q (green, report the count), uv run ruff check . (clean). If any fix would change a value the Kotlin side also computes (a mirror pair), STOP for that item and report. Report (≤200 words): changes per item, suite lines, anything stopped.
```

**PROMPT T6**

```
Task T6 of docs/CONSOLIDATION_PLAN.md — cut the experiments/playbook/escalation vertical, migration 14→15. Repository: /home/mick/Projects/sleep-analysis.

First read: docs/CONSOLIDATION_PLAN.md §T6, §1 (decision D1) and §3 (contracts — model_registry, report snapshot, user_factor STAY); docs/functional_spec/07-trends-insights-experiments.md (the verified lifecycle — to know what you are removing), 04-data-layer.md (sleep_experiment table, DAO, SQL guards, migration chain rules), 09-app-shell-settings-privacy.md (route table). Batch reads; grep before deleting.

HARD BUDGET: at most 45 tool calls. Rules: minimal diff; keep the surviving insights (InsightRules, PersonalBaseline, SleepRegularity, the non-experiment cards of InsightsScreen) compiling and pinned by their tests; keep model_registry and the report snapshot table untouched; never adb/install; NEVER commit; never rewrite old migrations — APPEND MIGRATION_14_15; no fallbackToDestructiveMigration.

Remove the whole experiment vertical per plan §T6 (tracker, math, playbook, escalation rule + card, experimentPhaseNights, SleepExperimentDao + entity + SQL guards, AppModule providers, Home experiment chip, InsightsScreen/InsightsViewModel experiment parts, experiment_* strings, CONFOUNDABLE_FACTOR_KEYS, experiment-only tests). Write MIGRATION_14_15 dropping sleep_experiment; extend the MigrationTest source with the 14→15 case (code-level only — instrumented tests cannot run here; say so in the report); ensure Room schema export produces app/schemas/**/15.json (build once to generate: ./gradlew :app:compileDebugKotlin or the schema-export task — building is allowed, installing is not).

Verify: ./gradlew :app:testDebugUnitTest --console=plain -q (report the NEW lower count), uv run pytest -q, uv run ruff check ., grep -rn "ExperimentTracker\|Playbook\|EscalationRule\|sleep_experiment\|experimentPhaseNights" app/src/main (expect zero; report any hit), ls app/schemas (15.json present). Report (≤250 words): removed surfaces, migration + schema status, new test count, anything not done.
```

**PROMPT T7**

```
Task T7 of docs/CONSOLIDATION_PLAN.md — cut Baseline + FactorLog screens and the write-only column bundle, migration 15→16. Repository: /home/mick/Projects/sleep-analysis.

First read: docs/CONSOLIDATION_PLAN.md §T7 and §3; docs/functional_spec/04-data-layer.md (clinical_baseline immutability + triggers, the write-only column rows and the exporter-read columns), 09-app-shell-settings-privacy.md (baseline + factor-log screens, routes), and tools/export_fixtures.py + tools/export_night_csv.py (which columns they select). Batch reads; grep before deleting.

HARD BUDGET: at most 40 tool calls. Rules: minimal diff; user_factor + FactorLogRepository + the report-side morning notes/rating/tags editing STAY; keep provenance columns isClinical/method/periodEnd and the exporter-read createdAt columns; never adb/install; NEVER commit; APPEND MIGRATION_15_16 (after T6's 14→15), no destructive fallback; Kotlin↔Python mirror rule — exporter updates happen in THIS task with pytest green.

Do plan §T7 (a)–(c): baseline screen/repository cut, with the grep decision on clinical_baseline (drop table + DAO + triggers only if NO tool reads it — report which branch you took); factor-log screen + route cut (keep the repository and whatever the Home morning card reads — grep first); the column bundle (drop enteredAt, recordedAt, audioBytesTotal, deviceModel, osVersion, appVersion, the reader-less createdAt trio, labeledAt) — update the two exporters FIRST to stop selecting dropped columns, then the entities, then the migration; extend MigrationTest with 15→16 (code-level; on-device validation is the owner's next install — say so).

Verify: ./gradlew :app:testDebugUnitTest --console=plain -q (report count), uv run pytest -q, uv run ruff check ., grep -rn "enteredAt\|recordedAt\|audioBytesTotal\|labeledAt\|deviceModel\|FactorLogScreen\|BaselineScreen" app/src/main tools/ (only intended hits), 16.json exported. Report (≤250 words): the clinical_baseline decision + evidence, columns dropped, exporter changes, migration status, suite lines.
```

**PROMPT T8**

```
Task T8 of docs/CONSOLIDATION_PLAN.md — defect-fix pass. Repository: /home/mick/Projects/sleep-analysis.

First read: docs/CONSOLIDATION_PLAN.md §T8 and §3; docs/functional_spec/11-consolidation-map.md §11.2 (the defect table with evidence); for the interrupted-night items also docs/functional_spec/01-recording-controls.md (state machine, decision tables, §1.8 defects D1–D7) — those rows are the contract (INV-04); do not weaken StartIntentPolicyTest/InterruptionStopSequenceTest. Batch reads.

HARD BUDGET: at most 45 tool calls. Rules: minimal fixes in existing style; JVM-runnable tests only (no instrumented tests, no adb, no installs); NEVER commit; one fix per concern with its own test where feasible; if a defect died with an earlier task (e.g. experiment defects), mark it done-by-removal with evidence.

Work the live set in plan §T8: D-01, D-02, D-03, D-04, D-05, D-07, D-12, D-15 (verify — may already be done by T4c), D-16, D-17, D-29, section-1.8 D3/D4/D5/D7, and D6 (add a Resume action to Home's interrupted card wired to the existing resume intent — the smallest correct fix). For each: verify present → fix → test → or won't-fix with a reason.

Verify: ./gradlew :app:testDebugUnitTest --console=plain -q (report count — it should GROW by the new tests), uv run pytest -q, uv run ruff check . Report (≤250 words): per row — fixed (with test name) / done-by-removal / won't-fix (reason), suite lines.
```

**PROMPT T9**

```
Task T9 of docs/CONSOLIDATION_PLAN.md — docs rewrite + spec refresh. Repository: /home/mick/Projects/sleep-analysis.

First read: docs/CONSOLIDATION_PLAN.md §T9; docs/functional_spec/00-overview.md §0.7 (doc-freshness rows) and the doc-drift rows of docs/functional_spec/11-consolidation-map.md; then skim each doc you rewrite (grep its claims first; the code wins — verify every rewritten claim with grep before writing it). Batch reads; never read long docs end to end.

HARD BUDGET: at most 30 tool calls. Rules: docs only — NO code changes; never adb/install; NEVER commit; keep each doc's voice; when the code contradicts a doc, the doc changes.

Do plan §T9 (a)–(e): correct every drift row (list them in the map), rewrite STATUS §3 to the post-refactor truth, consolidate the overlapping "done/verified" claims, README fixes (retention claim, backup_db.sh, table refresh, onboarding wording if present), then refresh the functional spec: mark rows whose symbols moved/died as `removed in consolidation <task>` (keep the history), regenerate the invariants (INV-03 per T7's outcome), update section 11 statuses to done, re-run the mechanical scans: .venv/bin/python -I ktscan.py /home/mick/Projects/sleep-analysis and the lint summary (see /home/mick/sleep-local/spec-draft/ for the script copies) and refresh evidence/ if the scripts are still present, else note their absence. Record final test counts and APK size placeholder for T10.

Verify: uv run pytest -q and the unit suite still green (docs-only change, but confirm), .venv/bin/python -I /home/mick/sleep-local/spec-draft/check_refs.py /home/mick/Projects/sleep-analysis docs/FUNCTIONAL_SPEC.md docs/functional_spec/*.md — 0 problems after your refresh (fix citations you broke by the refactor). Report (≤200 words): docs rewritten, drift rows closed, spec rows marked, check_refs line.
```

**PROMPT T10**

```
Task T10 of docs/CONSOLIDATION_PLAN.md — final verification + size report. Repository: /home/mick/Projects/sleep-analysis. Read docs/CONSOLIDATION_PLAN.md §2, §3, §T10 first. HARD BUDGET: at most 20 tool calls. Rules: NO code changes; no adb; no installs; NEVER commit.

Run and report real output: (1) ./gradlew :app:testDebugUnitTest --console=plain (full line + count from app/build/test-results), (2) uv run pytest, (3) uv run ruff check ., (4) .venv/bin/python -I /home/mick/sleep-local/spec-draft/check_refs.py /home/mick/Projects/sleep-analysis docs/FUNCTIONAL_SPEC.md docs/functional_spec/*.md, (5) ./gradlew :app:assembleDebug and the byte size of app/build/outputs/apk/debug/app-debug.apk vs the baseline recorded in plan §2, (6) .venv/bin/python -I /home/mick/sleep-local/spec-draft/ktscan.py /home/mick/Projects/sleep-analysis (regenerated scan — summarise table A count), (7) grep proofs that the §3 contract list is intact (one grep per contract group). Finish with the consolidated report the plan promises: final numbers, remaining unverified items (migrations awaiting the owner's next install, won't-fix defects), and any recommendation. Report ≤300 words.
```

## 7. Appendix B — defect assignment summary

| Where | Map rows |
|---|---|
| T1 (text-only) | D-06 comment, D-09, D-10, D-13, D-27, D-28, D-11 message text |
| T4 (absorbed) | D-15 via item (c) |
| T5 (Python) | counts_as_snore asymmetry, night-date timezone, capture_window guard |
| T6 (die with cut) | D-08 row group (evaluateConfirm/plannedNights, ACTIVE_EXISTS message, keepVerdict partition), experiment-related 7.8 defect |
| T8 (behaviour fixes) | D-01, D-02, D-03, D-04, D-05, D-07, D-12, D-16, D-17, D-29, §1.8 D3, D4, D5, D6, D7 |
| T9 (doc-side) | every doc-drift row, INV-03 rewrite, onboarding wording if not fixed in T8 |
