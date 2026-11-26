# 5. Report domain

**Scope:** `domain/report/ReportAssembler.kt`, `domain/report/ReportSnapshot.kt`, `domain/report/Bouts.kt`, `domain/report/Episodes.kt`, `domain/report/QuietGaps.kt`, `domain/report/NightDates.kt`, `domain/report/NightSummaryText.kt`, `domain/validity/NightValidity.kt`, `domain/validity/GarminValidators.kt`, `data/repo/ValidityCalculator.kt`, `domain/factors/DailyFactor.kt`, `domain/labels/EventUserLabel.kt`, `domain/policy/ClaimPolicy.kt`, plus their tests. NOT `domain/report/TrendAssembler.kt` (section 7), NOT `domain/report/CaptureStats.kt` (section 2). Callers live in sections 4 (session writes), 6 (report UI / refresh), 7 (trends), insights (8).

**Requirement prefix:** RPT — rows in N.3 are numbered RPT-01, RPT-02, …
**Status values:** `tested` = a unit/instrumented test asserts it (cite it) · `device-verified` = evidence from a real night or device run exists (cite where) · `unverified` = implemented, no test and no device evidence · `not implemented` = designed or documented only.

## N.1 What the user gets

1. Opening a finished night assembles the morning report live from stored rows: headline numbers, timeline, clips, structure, quiet, Garmin, previous-night comparison, validity badge. An in-progress session yields no report. [RPT-01]
2. The report headline shows time in bed, snoring time, snore share (stating its denominator), event count, score with its share+intensity breakdown, intensity-tier counts, peak and mean dBFS. [RPT-02..RPT-08]
3. Low-confidence OTHER_NOISE events are hidden from display (configurable threshold, default 0.5, 0 disables; SNORE events are never hidden) with an integrity note saying how many were hidden. [RPT-07]
4. The structure card shows the night as bursts and bouts at the ≤10 s gap, a tighter ≤5 s view, the longest single snore spell, the ten loudest episodes, and a one-tap clip of the worst bout. [RPT-12, RPT-13]
5. Quiet stretches ≥10 min inside the capture window are listed, each classified as verifiably QUIET or UNCAPTURED; the TL;DR counts only verifiable quiet. [RPT-14, RPT-15]
6. A Garmin card shows device-reported metrics, Garmin's own sleep window with its overlap against the capture window, a coverage-normalized share, and a link-status banner (LINKED / METRICS_ONLY / MISSING). [RPT-18, RPT-19]
7. The TL;DR compares against the previous comparable night — but only when detector and score-formula versions match; otherwise the summary says "not compared". [RPT-16, RPT-17]
8. The share action emits a plain-text night summary rendered in the session's own time zone. [RPT-24]
9. Every finished night stores a compact report snapshot row (schema v12): headline JSON + detector/score versions, rewritten when a label re-weighs the night. [RPT-22, RPT-23]
10. Every finished night gets a data-quality grade A–D stored as a NIGHT_VALIDITY measurement row, with machine-readable reasons; insights eligibility (grade ≥ B) is gated on it. [RPT-25..RPT-30]
11. The user can log ten daily factors (incl. morning rating 1–5 and free-text notes); they are context only and never feed scores, and all except NOTES and MORNING_RATING can confound-exclude an N-of-1 experiment night. [RPT-31, RPT-32]
12. The user can label any clip-backed event with one of six labels; a label re-weighs the night's snore aggregates, validity grade and snapshot in one transaction, without changing the stored event type. [RPT-33]
13. Measurement claim tiers (Established / Experimental / Out-of-scope) exist as code in `domain/policy/ClaimPolicy.kt` with a doc-sync test; the recorder refuses tier-3 writes. No production display path consults the policy — see N.8 gap. [RPT-34]

## N.2 How it works today

### N.2.1 Components

| Component | File | Responsibility |
|---|---|---|
| `ReportAssembler.assemble` | `domain/report/ReportAssembler.kt` | Pure assembly of `MorningReport` from stored rows; every report number is derived here |
| `MorningReport` | `domain/report/ReportAssembler.kt` | Immutable report model (defaults for pre-v8/pre-validity nights) |
| `PreviousNight.pick` | `domain/report/ReportAssembler.kt` | Pure pick of the previous comparable session (COMPLETED/RECOVERED, strictly earlier, prefers ≥4 h in bed) |
| `ReportSnapshot.payload` / `parse` | `domain/report/ReportSnapshot.kt` | Hand-built headline JSON (schema_version 1) and tolerant read-back |
| `NightSummaryText.build` | `domain/report/NightSummaryText.kt` | Shareable plain-text summary, session-zone times, version-gated comparison |
| `groupBouts` / `boutStats` | `domain/report/Bouts.kt` | Burst→bout chaining (SNORE events, gap ≤10 s) and headline stats |
| `groupEpisodes` / `loudestEpisodes` | `domain/report/Episodes.kt` | All-event episode chaining (gap ≤10 s), loudest-first top-10 |
| `quietGaps` / `classifyQuietGaps` / `QuietStretchTotals` | `domain/report/QuietGaps.kt` | ≥10 min event-free spans in the capture window; bucket-evidence classification QUIET / UNCAPTURED / null |
| `NightDates.garminWakeDate` / `factorDay` | `domain/report/NightDates.kt` | Wake-date (Garmin join key) vs start-date (factor key) anchoring |
| `NightValidity.compute` / `notes` / `parseNotes` | `domain/validity/NightValidity.kt` | Pure A–D grading + versioned reasons JSON |
| `GarminValidators.validate` | `domain/validity/GarminValidators.kt` | Pure Garmin freshness/plausibility/consistency validation; drops implausible values, returns stable-id warnings |
| `ValidityCalculator` (+ free functions `ambientNoiseSummaryFor`, `garminValidityFromPayloads`) | `data/repo/ValidityCalculator.kt` | Gathers stored evidence into `NightValidity.Input`, records the NIGHT_VALIDITY row |
| `DailyFactor` | `domain/factors/DailyFactor.kt` | The ten self-logged factor kinds |
| `EventUserLabel` | `domain/labels/EventUserLabel.kt` | The six user labels (DB stores `UserLabel` string constants, `data/db/entity/Entities.kt` · `UserLabel`) |
| `ClaimPolicy` / `MeasurementType` tiers | `domain/policy/ClaimPolicy.kt` | Tier table mirror of docs/MEASUREMENT_RELIABILITY.md §2/§8 |

### N.2.2 Flows

#### Flow: report assembly (read path)
1. `data/repo/SessionRepository.kt` · `buildReport` loads session, events, clips, baseline, Garmin metrics for the wake date, factors for the factor day, parsed capture stats (`domain/report/CaptureStats.kt` · `parse`), the Garmin sleep summary, the previous-night summary, stage spans, stored quiet gaps and the stored validity (`domain/validity/NightValidity.kt` · `parseNotes` over the NIGHT_VALIDITY row's notes) — see section 6 for the refresh pipeline.
2. `domain/report/ReportAssembler.kt` · `assemble` returns null when `endedAt` is null [RPT-01].
3. Display filter first: OTHER_NOISE events with confidence below `noiseMinConfidence` are removed from every display surface; `lowConfidenceNoiseExcluded` counts them [RPT-07].
4. Snore set from ALL events (never the display-filtered set) via `domain/scoring/SnoreMath.kt` · `eventsCountingAsSnore`; snoring time is `domain/scoring/SnoreMath.kt` · `unionDurationMs` over that set [RPT-03, RPT-04].
5. Denominator: `domain/report/CaptureStats.kt` · `capturedAnalysisMs` /1000 (=(processed+skipped hops)×500 ms) when >0, else time in bed; `shareUsesCapturedTime` records which was used [RPT-05].
6. Score recomputed with `domain/scoring/SnoreScore.kt` · `compute` over per-tier unions and the captured denominator — identical math to the finalize writer, so the report reproduces the stored score [RPT-06].
7. Clips joined per visible event via `event.clipId` → `audio_clip` (the `audio_clip.eventId` backfill is NOT used for the join); top clips ranked peak↓, duration↓, eventId↑, capped at 8 [RPT-10].
8. Bouts chained at 10 s and 5 s; episodes grouped from the FULL visible list (not the truncated timeline); quiet gaps from the UNFILTERED event list inside the capture window unless stored rows exist (`quietGapsOverride`) [RPT-12..RPT-14].
9. Integrity notes: resumed-after-interruption first, then non-COMPLETED status, hidden-noise count, timeline truncation; health warnings derived from capture stats [RPT-21].

#### Flow: finalize writes (session side — details in section 4)
1. `data/repo/SessionRepository.kt` · `finalizeSession` classifies quiet gaps (`domain/report/QuietGaps.kt` · `classifyQuietGaps` over `domain/report/QuietGaps.kt` · `quietGaps`, the ambient buckets and client-silenced spans) and replaces the session's quiet_gap rows (schema v10) [RPT-14, RPT-15].
2. It computes validity via `data/repo/ValidityCalculator.kt` (clip scan, ambient priors) and records the NIGHT_VALIDITY row through `data/recorder/MeasurementRecorder.kt` · `replaceNightValidity` (delete-then-insert, never duplicate) [RPT-27].
3. It upserts the report snapshot (`data/repo/SessionRepository.kt` · `upsertReportSnapshot` → `data/repo/SessionRepository.kt` · `reportSnapshotHeadline` → `domain/report/ReportSnapshot.kt` · `payload`) with the resolved detector version and `domain/scoring/SnoreScore.kt` · FORMULA_VERSION [RPT-22].

#### Flow: label recompute (`data/repo/SessionRepository.kt` · `labelEvent`, one transaction)
1. `eventDao.setUserLabel`; the snore-aggregate PHONE rows are recomputed and REWRITTEN, flagged `label_corrected` (section 4).
2. `data/repo/ValidityCalculator.kt` · `computeAndRecord` replaces the NIGHT_VALIDITY row — the Garmin side is revalidated from the stored raw payloads (`data/repo/ValidityCalculator.kt` · `garminValidityFromPayloads` re-runs `data/garmin/GarminImportManager.kt` · `parseNightData` + `domain/validity/GarminValidators.kt` · `validate`).
3. `upsertReportSnapshot` rewrites the snapshot so it keeps mirroring the rewritten rows [RPT-22].
4. A Garmin re-import of a linked session also recomputes validity (`data/garmin/GarminImportManager.kt`, `computeAndRecord` inside the import transaction).

#### Flow: share text
`ui/report/ReportViewModel.kt` · `buildShareText` → `domain/report/NightSummaryText.kt` · `build`: night-of line, snored X — Y% of the Z captured, worst stretch, longest burst, score with breakdown and formula version, loudest clip, quiet/not-captured totals, gated previous-night line [RPT-24].

### N.2.3 State and stored data

- report_snapshot table (schema v12; `data/db/entity/Entities.kt` · ReportSnapshotEntity): `sessionId`, `generatedAt`, `appVersion`, `detectorVersion` (first snore event's model version; on a zero-snore night the detector current at finalize), `scoreVersion` (`domain/scoring/SnoreScore.kt` · FORMULA_VERSION), `payload` (JSON). One row per session, upsert on regeneration, FK CASCADE.
- Snapshot payload JSON (hand-built, `domain/report/ReportSnapshot.kt` · PAYLOAD_VERSION = 1): `schema_version`, `validity_grade`, `snore_score`, `snore_share_percent` (2-dp, US locale), `snore_seconds`, `longest_bout_ms`, `garmin_sleep_start_ms`, `garmin_sleep_end_ms`, `garmin_sleep_score`, `garmin_overlap_ms`. Null fields are omitted, never rendered as 0; `parse` refuses a foreign `schema_version` and treats non-numeric values as missing. Row conditions mirror the measurement rows: a RECOVERED night with no events and a zero-processed-windows night store no share/score (pinned by `ReportSnapshotRefreshTest`).
- quiet_gap table (schema v10; `data/db/entity/Entities.kt` · QuietGapEntity): `sessionId`, `startMs`, `endMs`, `durationMs`, `kind` (QUIET / UNCAPTURED / null), `meanDbfs`, `createdAt`. Rows are ≥10 min spans, replace-per-session at finalize; the report prefers stored rows over recomputation.
- NIGHT_VALIDITY measurement row: `measurement_type = "NIGHT_VALIDITY"`, grade letter read back from `valueText` (readers: `domain/report/TrendAssembler.kt`, `data/repo/SessionRepository.kt` · `validityGradeFor`), `notes` = verbatim JSON `{"version":"night-validity-v1","grade":"A","reasons":[{"id","severity","detail"}]}` (key order is part of the format). Recompute paths replace, never duplicate.
- user_factor rows keyed by `domain/factors/DailyFactor.kt` · `key` on the session's START local date; only affirmative/set factors are stored.
- Event `userLabel` string column + `userLabelAt` timestamp; stored type unchanged.
- In-memory only (never stored): bouts, episodes, hourly buckets, topClips, clip coverage, integrity notes, health warnings, normalized share — recomputed report-time.

## N.3 Requirements

| ID | Requirement | Where | Pinned by | Status |
|---|---|---|---|---|
| RPT-01 | `assemble` returns null for a session whose `endedAt` is null; no report exists for an in-progress night | `domain/report/ReportAssembler.kt` · `assemble` | `ReportAssemblerTest.in-progress session cannot produce a report` | `tested` |
| RPT-02 | timeInBedSeconds = floor((endedAt − startedAt)/1000), floored at 0 | `domain/report/ReportAssembler.kt` · `assemble` | `ReportAssemblerTest.assembles headline numbers from events` | `tested` |
| RPT-03 | The snore set = events where userLabel == SNORE, or (no label AND type == SNORE) — computed from ALL events, immune to the display filter | `domain/scoring/SnoreMath.kt` · `eventsCountingAsSnore` | `ReportAssemblerTest.low-confidence SNORE events are never filtered` | `tested` |
| RPT-04 | snoreSeconds = union of snore-event intervals (overlaps counted once), never a sum | `domain/report/ReportAssembler.kt` · `assemble` | `ReportAssemblerTest.assembles headline numbers from events` | `tested` |
| RPT-05 | Share denominator = captured analysis seconds ((processed+skipped)×0.5 s) when >0, else time in bed; `shareUsesCapturedTime` states which | `domain/report/ReportAssembler.kt` · `assemble` | `ReportAssemblerTest.share denominator flags the captured-time source` | `tested` |
| RPT-06 | Report score recomputed identically to the finalize measurement (per-tier unions, captured denominator) so the report always reproduces the stored score | `domain/report/ReportAssembler.kt` · `assemble` | `ReportSnapshotRefreshTest.a label-corrected recompute lowers the snapshot aggregates` | `tested` |
| RPT-07 | OTHER_NOISE events with confidence < threshold (default 0.5, user-configurable, 0 disables) are excluded from counts/timeline/episodes/clips and counted in `lowConfidenceNoiseExcluded` with an integrity note; SNORE never filtered | `domain/report/ReportAssembler.kt` · `DEFAULT_MIN_DISPLAYED_NOISE_CONFIDENCE` | `ReportAssemblerTest.low-confidence noise is display-filtered with an integrity note` | `tested` |
| RPT-08 | Timeline and clip map carry the first 2000 visible events; a truncation integrity note is added at the cap | `domain/report/ReportAssembler.kt` · `MAX_TIMELINE_EVENTS` | `ReportAssemblerTest.resumed session leads the integrity notes` (note machinery) | `tested` |
| RPT-09 | intensityBreakdown = per-tier event counts of the snore set; hourly buckets accumulate event durations (not union) by start-offset hour with max tier (rank EPIC>LOUD>LIGHT>other) | `domain/report/ReportAssembler.kt` · `hourlyBuckets` | `ReportAssemblerTest.hourly buckets land in the right hour` | `tested` |
| RPT-10 | Clips join via `event.clipId` only; top clips = highest peak, then longest, then lowest eventId, capped at 8, drawn from visible events | `domain/report/ReportAssembler.kt` · `MAX_TOP_CLIPS` | `ReportAssemblerTest.top clips rank by peak then duration and are capped` | `tested` |
| RPT-11 | longestSnore = the snore event with max duration; clipCoverage = clip-backed snore events and union seconds over the snore set (null without snore events) | `domain/report/ReportAssembler.kt` · `assemble` | `ReportAssemblerTest.clip coverage reports spells and union snoring time` | `tested` |
| RPT-12 | A bout chains SNORE events whose gap (next.start − running-max end) ≤10 s (exactly 10 s chains); stats null without snore events; a second view uses the 5 s gap | `domain/report/Bouts.kt` · `groupBouts` | `BoutsTest.the threshold itself still chains (gap exactly 10s)`; `ReportAssemblerTest.bouts and quiet gaps ride along on the report` | `tested` |
| RPT-13 | Episodes chain ALL visible events at gap ≤10 s; members ordered loudest-first; `loudestEpisodes` returns the 10 highest-peak episodes; non-snore members count in the episode but not in snoreCount | `domain/report/Episodes.kt` · `groupEpisodes` | `EpisodesTest.loudestEpisodes ranks by peak and caps the count` | `tested` |
| RPT-14 | Quiet gaps = event-free spans ≥10 min inside [captureStartMs (fallback startedAt), endedAt], leading/trailing included, computed on the unfiltered event list; stored quiet_gap rows (schema v10) override the report-time computation when present | `domain/report/QuietGaps.kt` · `quietGaps` | `QuietGapsTest.a start delay must not appear as a quiet gap`; `ReportAssemblerTest.stored quiet gaps override the report-time computation` | `tested` |
| RPT-15 | Classification: QUIET when usable (non-digital-silence) bucket evidence covers ≥50% of the gap and no silenced span touches it; UNCAPTURED when a client-silenced span covers it, or only digital-silence/no bucket evidence exists; otherwise left unclassified (null); digital silence = mean ≤ −95 dBFS or variance ≤ 0.01 dB² | `domain/report/QuietGaps.kt` · `classifyQuietGaps` | `QuietGapsTest.digital-silence buckets classify the gap UNCAPTURED not QUIET` | `tested` |
| RPT-16 | Previous night = latest COMPLETED/RECOVERED session strictly before current, preferring ≥4 h in bed, id-descending tie-break; open/interrupted nights never anchor | `domain/report/ReportAssembler.kt` · `PreviousNight.pick` | `PreviousNightTest.prefers an older full night over a later short evening session` | `tested` |
| RPT-17 | TL;DR/share comparison appears only when sameDetectorVersion AND the previous night has a stored snore time; the score part additionally requires sameScoreFormula; otherwise "Previous night not compared" | `domain/report/NightSummaryText.kt` · `build` | `NightSummaryTextTest.comparison appears only when the detector version matches` | `tested` |
| RPT-18 | garminStatus = LINKED when a parsed sleep summary exists, else METRICS_ONLY when day-window metrics exist, else MISSING | `domain/report/ReportAssembler.kt` · `assemble` | `ReportAssemblerTest.garmin status reflects payload presence` | `tested` |
| RPT-19 | Normalized share = snoreSeconds×100 / overlapSeconds; omitted (null) when overlap ≤0 or the ratio exceeds 100% — never clamped; display-only, never stored | `domain/report/ReportAssembler.kt` · `garminNormalizedSharePercent` | `ReportAssemblerTest.share above 100 percent is omitted, not clamped` | `tested` |
| RPT-20 | One stage source per report: WEARABLE rows replace GARMIN/legacy rows entirely; `domain/report/ReportAssembler.kt` · `stageSource` labels provenance; longestBoutStage = majority-overlap stage of the longest ≤10 s bout (null without overlap); snoreMinutesPerStage = per-stage union snoring minutes, stages that round to no full minute dropped, sorted desc | `domain/report/ReportAssembler.kt` · `singleSourceStages` | `ReportAssemblerTest.overlapping garmin and wearable timelines report one source only` | `tested` |
| RPT-21 | Health warnings from capture stats: no stats → "unavailable" note; 0 processed+skipped windows in a >30 s window → capture likely failed; accounted < 90% of expected windows → gaps suspected; thermal throttling reported with skipped-hop count | `domain/report/ReportAssembler.kt` · `healthWarnings` | `ReportAssemblerTest.health warnings derive from capture stats` | `tested` |
| RPT-22 | Snapshot row upserted at finalize AND on every label recompute (same transaction as the measurement rewrite); payload carries detectorVersion + scoreVersion so later analysis can gate comparisons | `data/repo/SessionRepository.kt` · `upsertReportSnapshot` | `ReportSnapshotRefreshTest.a label-corrected recompute lowers the snapshot aggregates` | `tested` |
| RPT-23 | Snapshot row conditions mirror measurement rows: RECOVERED-with-no-events and zero-processed-windows nights store null share/score (never fabricated 0% / score 0) | `data/repo/SessionRepository.kt` · `reportSnapshotHeadline` | `ReportSnapshotRefreshTest.a RECOVERED night with no events stores no share or score in the snapshot` | `tested` |
| RPT-24 | Share text renders times in the SESSION zone; its "captured" span is the wall-clock capture window (endedAt − captureWindowStartMs, fallback startedAt) — NOT the captured-analysis denominator behind the printed share percent (see N.8 defect D1) | `domain/report/NightSummaryText.kt` · `build` | `NightSummaryTextTest.summary contains the headline numbers in the session timezone` | `tested` |
| RPT-25 | Grade: any CRITICAL reason → D; else 0–1 WARNINGs → A, 2–3 → B, ≥4 → C; INFO reasons never lower the grade; no capture stats at all → D with `stats_missing` | `domain/validity/NightValidity.kt` · `gradeOf` | `NightValidityTest.grade matrix - any critical is D regardless of warnings` | `tested` |
| RPT-26 | Criteria: 0 processed windows in a >30 s window → CRITICAL capture_failed; accounted-window shortfall >10% WARNING / >40% CRITICAL (thermal skips count as accounted); UNCAPTURED gap minutes >15 WARNING / >60 CRITICAL; clipped-clip rate >10% WARNING / >30% CRITICAL (needs clipCount>0); ambient P95 z>2.5 WARNING (needs ≥5 priors); non-COMPLETED or resumed → WARNING; Garmin validator warnings pass through verbatim; snore share of capture <1% → INFO only | `domain/validity/NightValidity.kt` · `compute` | `NightValidityTest.shortfall just over 10 percent warns`; `NightValidityTest.uncaptured gaps escalate from silent to warning to critical` | `tested` |
| RPT-27 | NIGHT_VALIDITY row stored via `data/recorder/MeasurementRecorder.kt` · `replaceNightValidity` (replace, never duplicate); notes JSON versioned night-validity-v1; `parseNotes` refuses wrong versions/unknown grades; recompute paths = finalize, labelEvent, Garmin re-import | `data/repo/ValidityCalculator.kt` · `computeAndRecord` | `ClaimPolicyTest.recorder replaces the night validity row instead of duplicating`; `NightValidityTest.parseNotes rejects wrong versions and unknown grades` | `tested` |
| RPT-28 | Ambient prior selection: exclude the graded night by id, strictly earlier starts only, finalized only, most recent 14 priors by start, P95 parsed before the window cut; z-math needs ≥5 priors and sd>0 else omitted | `data/repo/ValidityCalculator.kt` · `ambientNoiseSummaryFor` | `ValidityCalculatorTest.later nights never leak into a recomputed older night's priors` | `tested` |
| RPT-29 | Garmin validation: HR 30–220 bpm, rMSSD 2–300 ms, SpO₂ 70–100% (kept but flagged distrust <90%); out-of-range values dropped (raw payloads keep originals); negative/overlapping stage spans rejected (earliest kept); stage-sum vs reported duration ±10%; reported TST vs window ±45 min; import >24 h after night end stale; zero window overlap warns | `domain/validity/GarminValidators.kt` · `validate` | `GarminValidatorsTest.spo2 within 70-100 is kept and below 90 carries the distrust flag`; `GarminValidatorsTest.reported tst beyond 45 minutes of the window warns` | `tested` |
| RPT-30 | Downstream gating reads the STORED grade: insights eligibility requires grade A or B (`domain/insights/PersonalBaseline.kt` · `eligible`), report/trends/home display the stored grade and never re-grade at report time | `domain/validity/NightValidity.kt` · `compute` | `ValidityCalculatorTest.ambientNoise - z math, zero-spread omission and the 5-prior gate` (input side); insights tests own the gate (section 8) | `tested` |
| RPT-31 | Exactly ten daily factors (ALCOHOL, LATE_MEAL, CAFFEINE_AFTER_14, EXERCISE, MEDICATION_OR_DEVICE, SICK, SLEPT_ON_BACK, STRESS 1–5, MORNING_RATING 1–5, NOTES); factors ride the report as context and never touch scores; keyed on the session's start date | `domain/factors/DailyFactor.kt` · `DailyFactor` | `DailyFactorTest.exactly the ten documented factors exist`; `ReportAssemblerTest.self-logged factors ride along as user context and never touch scores` | `tested` |
| RPT-32 | N-of-1 confound exclusion keys = every DailyFactor except NOTES (free text is context) and MORNING_RATING (an outcome, not a manipulation) — a morning rating never confound-excludes a night | `domain/experiments/ExperimentTracker.kt` · `CONFOUNDABLE_FACTOR_KEYS` | `DailyFactorTest.morning rating is a 1-5 scale and the night tags are booleans` (values); verdict tests own the exclusion (section 8) | `tested` |
| RPT-33 | A user label overrides the detector class in aggregates only (SNORE includes; COUGH/SPEECH/MOVEMENT/OTHER_NOT_SNORE excludes; no label keeps detector type); stored type and label rows untouched; SILENCE is a label enum value but the persisted `UserLabel` constant set has five strings (see N.8) | `domain/labels/EventUserLabel.kt` · `EventUserLabel` | `EventUserLabelTest.exactly the six documented labels exist` | `tested` |
| RPT-34 | `ClaimPolicy.tierFor` mirrors the MEASUREMENT_RELIABILITY §8 tier table (doc-sync test fails the build on drift); tier-3 types are never displayable per the policy and the recorder refuses tier-3 writes; BUT no production code calls `displayAllowed` — report/export/UI do not enforce the policy (N.8 gap D2) | `domain/policy/ClaimPolicy.kt` · `displayAllowed` | `ClaimPolicyTest.tier table in MEASUREMENT_RELIABILITY matches the code enum` | `tested` (sync) / `not implemented` (enforcement) |

## N.4 Constants and thresholds

| Constant | Value | Defined in | Effect |
|---|---|---|---|
| `MAX_TIMELINE_EVENTS` | 2000 | `domain/report/ReportAssembler.kt` · `ReportAssembler` | Timeline/clip-map cap; truncation integrity note at the cap |
| `MAX_TOP_CLIPS` | 8 | `domain/report/ReportAssembler.kt` · `ReportAssembler` | "Hear the worst moments" list size |
| `DEFAULT_MIN_DISPLAYED_NOISE_CONFIDENCE` | 0.5 | `domain/report/ReportAssembler.kt` · `ReportAssembler` | Default OTHER_NOISE display-filter threshold (user preference reuses it; 0 disables) |
| `STAGE_SOURCE_WEARABLE` / `STAGE_SOURCE_GARMIN` | "WEARABLE" / "GARMIN" | `domain/report/ReportAssembler.kt` · `ReportAssembler` | Stage-row provenance literals; WEARABLE wins in `singleSourceStages` |
| `NIGHT_LIKE_MIN_IN_BED_MS` | 4 h | `domain/report/ReportAssembler.kt` · `PreviousNight` | "Full night" threshold for previous-night preference (duplicated in `domain/policy/MissedNightPolicy.kt`) |
| `BOUT_GAP_MS` | 10 000 ms | `domain/report/Bouts.kt` | Bout chaining; same bound as `EPISODE_GAP_MS` by design |
| `EPISODE_GAP_MS` / `MAX_LOUDEST_EPISODES` | 10 000 ms / 10 | `domain/report/Episodes.kt` | Episode chaining / loudest-first cap |
| `QUIET_GAP_MIN_MS` | 600 000 ms (10 min) | `domain/report/QuietGaps.kt` | Minimum quiet-stretch length (also the stored quiet_gap row threshold) |
| `DIGITAL_SILENCE_DBFS` / `DIGITAL_SILENCE_MAX_VARIANCE_DB2` | −95.0 dBFS / 0.01 dB² | `domain/report/QuietGaps.kt` | Digital-silence bucket test → UNCAPTURED, not QUIET |
| covered-fraction threshold | 0.5 (default parameter) | `domain/report/QuietGaps.kt` · `classifyQuietGaps` | Usable bucket coverage needed to call a gap QUIET |
| `PAYLOAD_VERSION` | 1 | `domain/report/ReportSnapshot.kt` · `ReportSnapshot` | `schema_version` inside the snapshot JSON; parse refuses others |
| `OPEN_SESSION_WAKE_OFFSET_MS` | 12 h | `domain/report/NightDates.kt` · `NightDates` | Wake-date fallback for open sessions |
| `VERSION` | "night-validity-v1" | `domain/validity/NightValidity.kt` · `NightValidity` | Stored on NIGHT_VALIDITY notes; bump on any recalibration |
| `CAPTURE_FAILED_MIN_WINDOW_S` | 30 s | `domain/validity/NightValidity.kt` · `NightValidity` | Below this a zero-window night is a test session, not CAPTURE_FAILED |
| `SHORTFALL_WARN` / `SHORTFALL_CRITICAL` | 0.10 / 0.40 | `domain/validity/NightValidity.kt` · `NightValidity` | Accounted-vs-expected window shortfall WARNING / CRITICAL |
| `UNEXPLAINED_GAP_WARN_MIN` / `UNEXPLAINED_GAP_CRITICAL_MIN` | 15 / 60 min | `domain/validity/NightValidity.kt` · `NightValidity` | UNCAPTURED quiet-gap minutes escalation |
| `CLIPPED_WARN` / `CLIPPED_CRITICAL` | 0.10 / 0.30 | `domain/validity/NightValidity.kt` · `NightValidity` | Clipped-clip rate escalation (needs clipCount > 0) |
| `AMBIENT_Z_TRIGGER` / `AMBIENT_MIN_PRIOR_NIGHTS` | 2.5 / 5 | `domain/validity/NightValidity.kt` · `NightValidity` | Relative-only ambient check trigger / omission gate |
| `LOW_SNORE_SHARE` | 0.01 | `domain/validity/NightValidity.kt` · `NightValidity` | Snore share of capture below this = INFO, never lowers the grade |
| `HOP_MS` | 500 ms | `domain/validity/NightValidity.kt` · `NightValidity` | Expected-window math (duplicated from the detector config) |
| `AMBIENT_PRIOR_NIGHTS_MAX` | 14 | `data/repo/ValidityCalculator.kt` · `ValidityCalculator` | Rolling prior-night window |
| `HR_MIN_BPM`/`HR_MAX_BPM` | 30 / 220 bpm (cited) | `domain/validity/GarminValidators.kt` · `GarminValidators` | Wrist-PPG HR plausibility; out-of-range dropped |
| `RMSSD_MIN_MS`/`RMSSD_MAX_MS` | 2 / 300 ms (cited) | `domain/validity/GarminValidators.kt` · `GarminValidators` | rMSSD plausibility |
| `SPO2_MIN_PERCENT`/`SPO2_MAX_PERCENT`/`SPO2_DISTRUST_BELOW` | 70 / 100 / 90 % (cited) | `domain/validity/GarminValidators.kt` · `GarminValidators` | SpO₂ plausibility / distrust flag |
| `STAGE_SUM_TOLERANCE` / `TST_TOLERANCE_MIN` | 0.10 / 45 min (cited) | `domain/validity/GarminValidators.kt` · `GarminValidators` | Stage-sum and reported-TST consistency |
| `FRESHNESS_LIMIT_MS` | 24 h | `domain/validity/GarminValidators.kt` · `GarminValidators` | Import-later-than-this → `garmin_stale` |

## N.5 Failure handling and edge cases

| Situation | Behaviour | Where |
|---|---|---|
| Session still recording (endedAt null) | No report, no snapshot, no validity row | `domain/report/ReportAssembler.kt` · `assemble`; `data/repo/SessionRepository.kt` · `upsertReportSnapshot` |
| No capture stats (pre-observability night) | Health warning "Capture statistics unavailable"; share falls back to time in bed with flag false | `domain/report/ReportAssembler.kt` · `healthWarnings` |
| windowsProcessed unknown (null) | No capture-failure/gap reasons — "unknown ≠ failed" | `domain/validity/NightValidity.kt` · `compute` |
| Zero processed windows but skipped hops > 0 (fully throttled) | Not CAPTURE_FAILED — throttled hops count as accounted coverage | `domain/validity/NightValidity.kt` · `compute` |
| No level buckets and no silenced spans (pre-v3 night) | Quiet gaps left unclassified (kind null) — never guessed | `domain/report/QuietGaps.kt` · `classifyQuietGaps` |
| Degenerate window (end ≤ start) | `quietGaps` returns empty | `domain/report/QuietGaps.kt` · `quietGaps` |
| Corrupt snapshot/notes JSON | `parse`/`parseNotes` return null — tolerant, never throw | `domain/report/ReportSnapshot.kt` · `parse`; `domain/validity/NightValidity.kt` · `parseNotes` |
| Future `schema_version` in snapshot | Refused (null), never degraded | `domain/report/ReportSnapshot.kt` · `parse` |
| No Garmin payloads for the wake date | No Garmin validity input, no garmin_* reasons; report shows MISSING | `data/repo/ValidityCalculator.kt` · `garminValidityFromPayloads` |
| Negative snore durations in input rows | Flow through unguarded — caller contract keeps them non-negative (test-documented) | `domain/report/ReportAssembler.kt` · `assemble` |
| Garmin overlap smaller than snoring span | Normalized share omitted (>100%), not clamped | `domain/report/ReportAssembler.kt` · `garminNormalizedSharePercent` |
| Both WEARABLE and GARMIN timelines coexist | Exactly one source used (WEARABLE) to avoid double-counted stage minutes | `domain/report/ReportAssembler.kt` · `singleSourceStages` |
| Open session wake-date anchor | startedAt + 12 h lands on the wake date for any realistic sleep window | `domain/report/NightDates.kt` · `garminWakeDate` |

## N.6 Contract boundaries — what a refactor must not break

**Persisted formats**
- report_snapshot columns (sessionId, generatedAt, appVersion, detectorVersion, scoreVersion, payload) and the payload JSON field names/semantics incl. `schema_version` (see N.2.3) — the snapshot is the versions-of-record for insights eligibility.
- quiet_gap columns and the QUIET/UNCAPTURED/null `kind` vocabulary (schema v10, ≥10 min rows).
- NIGHT_VALIDITY measurement row: type name, grade letter in `valueText`, notes JSON shape with key order version, grade, reasons — and reason objects id, severity, detail.
- user_factor keys = the `domain/factors/DailyFactor.kt` · `key` values, dated by session start day; only affirmative/set factors stored.
- Event `userLabel` string values (`data/db/entity/Entities.kt` · UserLabel).

**Version strings stored with data**
- night-validity-v1 (validity notes; parse refuses others), snapshot `schema_version: 1`, `domain/scoring/SnoreScore.kt` · FORMULA_VERSION in snapshot `scoreVersion`, detector model version in snapshot `detectorVersion` (insights eligibility gates on it — section 8).

**Platform contracts**
- None in this area (no manifest/notification surface); the NIGHT_VALIDITY row is a data contract only.

**Cross-area interfaces**
- Section 4 calls: `data/repo/ValidityCalculator.kt` · `computeAndRecord`, `clipQualityForSession`, `domain/report/QuietGaps.kt` · `quietGaps`/`classifyQuietGaps`, `domain/report/ReportSnapshot.kt` · `payload`, `data/repo/SessionRepository.kt` · `reportSnapshotHeadline`, `data/recorder/MeasurementRecorder.kt` · `replaceNightValidity`.
- Section 6 calls: `data/repo/SessionRepository.kt` · `buildReport`, `ui/report/ReportViewModel.kt` · `buildShareText` → `domain/report/NightSummaryText.kt` · `build`; the report screen reads `boutStats`, `boutStats5s`, `quietGaps` + `domain/report/QuietGaps.kt` · QuietStretchTotals, and `domain/report/ReportAssembler.kt` · `garminNormalizedSharePercent`.
- Section 7 calls: `domain/report/TrendAssembler.kt` reuses `domain/scoring/SnoreMath.kt` · `eventsCountingAsSnore` + `domain/report/Bouts.kt` · `groupBouts`/`boutStats` and reads the NIGHT_VALIDITY `valueText`.
- Section 8 calls: `domain/insights/PersonalBaseline.kt` / `domain/insights/InsightRules.kt` eligibility on grade A/B; `domain/experiments/ExperimentTracker.kt` · CONFOUNDABLE_FACTOR_KEYS reads `domain/factors/DailyFactor.kt`.
- Garmin area calls: `domain/validity/GarminValidators.kt` · `validate`, `domain/report/ReportAssembler.kt` · `garminOverlapMs`, `domain/report/NightDates.kt` · `garminWakeDate`.

**Safety-net tests**
Run first after touching this area: `ReportAssemblerTest`, `ReportSnapshotTest`, `ReportSnapshotRefreshTest`, `QuietGapsTest`, `BoutsTest`, `EpisodesTest`, `NightDatesTest`, `NightSummaryTextTest`, `PreviousNightTest`, `NightValidityTest`, `GarminValidatorsTest`, `ValidityCalculatorTest`, `ClaimPolicyTest`, `DailyFactorTest`, `EventUserLabelTest`.

## N.7 Consolidation candidates

| Candidate | Kind | Evidence | Risk to remove | Note |
|---|---|---|---|---|
| `domain/report/ReportAssembler.kt` · `StageSnoreMinutes.stageMinutes` | `test-only` | grep over app/src: only the definition + two `ReportAssemblerTest` asserts reference `stageMinutes`; no UI reader | `low` — field of a display data class; removal edits two test asserts | Carried "for context" but nothing displays it |
| `domain/report/ReportSnapshot.kt` · `parse` | `test-only` | grep: production refs of the snapshot object are only `payload`/`Headline` in SessionRepository; `parse` is called only by `ReportSnapshotTest` | `low` — keep if exports will read snapshots; else drop with its tests | KDoc says "exports, future gates" — decide |
| `domain/policy/MissedNightPolicy.kt` · `NIGHT_LIKE_MIN_IN_BED_MS` | `duplicate` | Same 4 h constant as `PreviousNight.NIGHT_LIKE_MIN_IN_BED_MS`; its KDoc says "mirrors" | `low` — must move together; a single constant would do | Drift would silently desync missed-night vs comparison semantics |
| `domain/report/NightSummaryText.kt` · `build` captured-span math | `duplicate` | Third derivation of a "captured" denominator: the assembler uses `domain/report/CaptureStats.kt` · `capturedAnalysisMs`, the summary uses wall-clock (endedAt − captureStart) | `medium` — user-visible inconsistency (N.8 D1), not just duplication | Consolidate on one denominator |
| TL;DR bout/quiet line logic in `ui/report/ReportScreen.kt` vs `domain/report/NightSummaryText.kt` | `duplicate` | Both format worst-stretch and QUIET-only quiet totals (report screen boutStats/QuietStretchTotals blocks; summary lines) | `low` — UI owns section 6; a shared formatter would keep share text and screen in sync | Already diverged once (D1) |
| Share/score aggregation math in `SessionRepository.finalizeSession` + `reportSnapshotHeadline` + `labelCorrectedSnoreEstimates` + `ReportAssembler.assemble` | `duplicate` | Four sites recompute snoreSeconds/share/score from the same primitives; equality is pinned by `ReportSnapshotRefreshTest` | `medium` — deliberate today (report must reproduce stored rows), but one shared function would remove the drift risk | `SnoreMath` already centralizes the event set and union math |
| `app/src/main/res/values/strings.xml` — `validity_title`, `report_garmin_window`, `home_view_report`, `report_interrupted_note` | `dead` | Lint unused-resource list + grep for each `R.string.` name over app/src: zero hits | `low` — plain deletions | Report/home-screen strings left behind |
| `docs/ARCHITECTURE.md` ("the persisted snapshot table is deferred to Phase 4"; "ReportAssembler includes a measurement only if ClaimPolicy allows") | `doc-drift` | The snapshot table exists (schema v12); ReportAssembler never references ClaimPolicy (grep) | `low` — doc fix | Code wins; enforcement gap recorded as D2 |

Free-text notes: the bout/episode chaining loops (`groupBouts` / `groupEpisodes`) are near-identical algorithms with different filters — a shared chaining helper is possible but low-value. `domain/validity/NightValidity.kt` · HOP_MS and `domain/report/CaptureStats.kt` · HOP_MS deliberately duplicate the detector's hop grid to keep domain pure (documented in both KDocs) — leave. Hourly buckets sum event durations rather than unioning overlapping intervals (unlike every other snoring-time figure); with the v2-burst detector events cannot overlap, so the numbers agree today, but the asymmetry is worth a comment or a union call during consolidation. docs/NIGHT_REPORT.md states the pre-v2 "merged-chains view remains recoverable as bouts with a 2 s gap"; `groupBouts(gapMs = 2_000)` makes that possible but nothing calls it — historical-data compatibility is a parameter away, not code that must stay.

## N.8 Open questions for the owner

- **D1 (defect).** The shared summary prints "Snored Xh Ym — Z% of the N captured" where Z is the stored share (denominator: captured analysis time = processed+skipped hops × 0.5 s) but N is the wall-clock capture window (endedAt − captureStart, fallback startedAt). Whenever hops were thermally skipped or capture gaps exist, N overstates the analysed time and the printed pair is internally inconsistent (and differs from the on-screen share line). Requirement RPT-24 records the actual behaviour; fix by using the captured-analysis span in `domain/report/NightSummaryText.kt`.
- **D2 (gap, not dead code).** `domain/policy/ClaimPolicy.kt` · displayAllowed (both overloads) has zero production callers — grep confirms only `ClaimPolicyTest`. docs/ARCHITECTURE.md §6.5 promises "claims are gated, not self-policed" and that ReportAssembler/exports filter by tier; no such enforcement point exists. The only real enforcement is upstream: the recorder refuses tier-3 types and tier-3 types have no producers (pinned by `ClaimPolicyTest.recorder refuses tier-3 measurement types outright`). Owner decision: accept write-side-only enforcement and fix the doc, or add a display gate.
- `domain/labels/EventUserLabel.kt` · SILENCE exists in the domain enum ("a negative for every class") but the persisted `data/db/entity/Entities.kt` · UserLabel constant object has only five strings (no SILENCE) — confirm whether SILENCE is ever stored or is Phase-2-only.
- Whether old-data compatibility beyond what the code already handles (legacy null-source stage rows, pre-v3 nights without buckets, pre-v8 nights without capture window, pre-v12 nights without snapshot rows) can be dropped — all four fallbacks are load-bearing for stored dogfood data today.
- **UNVERIFIED**: the exact column the recorder writes the validity grade letter into (`valueText` vs `valueNum`) — readers (`domain/report/TrendAssembler.kt`, `data/repo/SessionRepository.kt` · `validityGradeFor`) use `valueText`, but the body of `data/recorder/MeasurementRecorder.kt` · `replaceNightValidity` was not read.
- **UNVERIFIED**: whether the morning-rating (1–5) input is clamped in the UI (`ui/report/ReportViewModel.kt` / `ui/factorlog/FactorLogScreen.kt` carry an "in-range 1..5" comment; the clamp code was not read).
- **UNVERIFIED**: a full line-by-line comparison of docs/NIGHT_REPORT.md against the code was not performed; only the definitions quoted above (share denominator, bout/quiet thresholds, validity JSON, snapshot §5.2/§3.7) were checked and they match the code.
