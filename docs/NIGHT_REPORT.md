# Night Report — how the morning numbers are produced

This document defines what the morning summary (report screen), the Garmin section and the
7-day trend show, where every number comes from, and which constants govern them. Written
against schema v9 and detector `energy-heuristic-v3-peakgate` (pre-v3 nights are historical and flagged by the trend's version markers).

## 1. Morning summary (report screen)

The report is assembled by `ReportAssembler` from stored rows only — nothing is recomputed
from audio. Screen order is comprehension-first: **TL;DR card → data-quality banners →
disclaimer → headline grid → top clips** (play buttons) → the rest.

Everything in this section is surfaced in-app (nothing report-relevant lives only in external
analysis): TL;DR carries the quiet-stretch total; the session-health card shows the detector
version; the Garmin card shows the windows comparison (phone / Garmin), the linkage line, the
vendor sleep score, stage totals, snoring-minutes-per-stage (when the timeline exists) and the
stage of the longest bout; the top-clips card leads with the worst bout's loudest clip.

**TL;DR card.** One glance: snored time + % of the captured window, the worst stretch (longest
bout with its time range), the comparison to the previous night, and — on Garmin-linked nights
with a real window overlap — the snoring share of the captured part of Garmin's sleep window
(table below). The comparison reads the
previous night's STORED measurements and is version-gated: the time/share delta appears only
when both nights' detector versions are known and equal; the score delta only when the previous
score row used the current formula (`SnoreScore.FORMULA_VERSION`). A version change hides the
delta and surfaces a banner instead of comparing across it.

| Number | Source | Notes |
|---|---|---|
| Time in bed | session row (`startedAt`→`endedAt`) | Includes a configured start delay (documented decision — the session row starts at the tap). |
| Previous-night comparison | `PreviousNight.pick` | Anchors on the previous **full night** (≥ 4 h in bed) when one exists, falling back to the latest eligible session — a short evening test session never becomes the comparison anchor. |
| Snore score | `SnoreScore.compute` (formula `snore-score-v3`) | 60 pts from snoring share (saturates at **40%** of the night) + 40 pts tier-weighted over **snoring TIME**, over the label-aware snore event set (a non-SNORE user label excludes the event). The report shows the breakdown ("share X + intensity Y") and the SNORE_SCORE row stores formula version + breakdown in `notes`. See §Score formula versions. |
| Snoring time / share | `SnoreMath.unionDurationMs` over SNORE events | Union, never sum. The share denominator is **mic-live captured analysis time** (`capture_stats.windows × 0.5 s`), falling back to recording time only when no capture stats exist. The card states which denominator is in use. |
| Garmin-normalized share | `ReportAssembler.garminNormalizedSharePercent(snoreSeconds, overlapWithCaptureMs)` | TL;DR-only line ("Snored X% of the captured part of Garmin's sleep window"), rendered only when the night is Garmin **LINKED** and the overlap is > 0 — the honest ratio when capture started late (dogfood 2026-11-18→19: capture from 01:02, Garmin's window from 22:14). A result above 100% (sub-second overlap inflation can push it there) **omits** the line instead of clamping — clamping would assert everything Garmin saw was snoring, a stronger claim than silence. Display-only: the finalize-time `snoreSharePercent` stays contract-frozen; this line is never stored or compared. |
| Longest burst | max-duration SNORE event (`LongSnoreEvent`) | The worst continuous stretch the detector emitted — a real burst since v2-burst, never a merged train. |
| Longest bout | `boutStats.longestBoutMs` (`BOUT_GAP_MS`) | See the bout definition below. |
| Intensity breakdown | per-event `intensityTier` counts | Tiers: QUIET < −30 dBFS, LIGHT −30…−20, LOUD −20…−10, EPIC ≥ −10 (`DetectorConfig`). |
| Hourly bars | per-event hour buckets (offset from session start) | Union-unaware (buckets, not union) — reading. |
| Top clips | clip-backed events ranked by peak ↓, clip duration ↓ (max 8) | The "hear the worst moments" entry points; play via MediaPlayer. |

**Snoring structure (bursts vs bouts).** Since detector `energy-heuristic-v2-burst` a stored
event IS a burst: cross-draft merging is off (`MERGE_GAP_MS = 0`), the 2 s exit hysteresis is
the silence threshold, and any internal silence > 2 s splits the chain — the v1 300+ s
"events" (breath trains glued back by the fold) no longer exist. Nights recorded before the
change keep their merged rows; their `modelVersion` (v1-spectral) is what the trend's detector
marker keys on. Bouts chain bursts report-time (`Bouts.kt`) at **≤5 s and ≤10 s** — the ≤10 s
stats are the headline "worst stretch", the ≤5 s view splits short-recovery chains — so a
train-level number never depends on the detector's merge bound. The structure card shows burst
count, median/longest burst, both bout views, and the longest bout's span/times; the pre-v2
merged-chains view remains recoverable as bouts with a 2 s gap.

**Event gate (v3).** A finalized SNORE event stays SNORE only if it clears the level gate: its
mean must beat its own mean noise floor + 10 dB, never under a −60 dBFS hard floor — and, only
when that mean floor sits at or below −80 dBFS (mic-self-noise rooms, where every relative
threshold loses meaning), its peak must stay above −38 dBFS. Failing events are relabeled
OTHER_NOISE and (via `ClipPolicy`) never persist a clip; they stay in the database for
labeling. Driver: dogfood night 2026-11-11 (session 13, ambient median −83 dBFS) — 21
breathing trains peaking −39.6…−54.3 dBFS cleared the mean gate and shipped as 47% of the
night's snore minutes; real snores that night peaked −16.3…−34.9 dBFS and are untouched, and
in normal rooms (floor above −80 dBFS) the tier is off, so no absolute cutoff eats audible
snoring there.

**Quiet stretches.** Event-free spans ≥ `QUIET_GAP_MIN_MS` (10 min) inside the **capture
window** (`capture_started_at` when present, session start otherwise — never tap-to-stop, or a
start delay would appear as one giant gap). "Nothing was detected" uses all detected events,
including display-filtered ones (see §4). Leading/trailing quiet is included.

- **Stored** (schema v10, `quiet_gap` table): computed at finalize from the night's events +
  the capture's level buckets and written per session (replace-per-refinalize, FK CASCADE).
- **Classified** via the capture's per-window level summary (`ambient_level_buckets`, stats
  v6: per 5-minute epoch bucket the mean window dBFS, the bucket's real covered time and its
  dBFS variance) plus the OS-silenced spans (`client_silenced_spans`, stats v6):
  - `QUIET` — usable (non-silent) bucket evidence covers ≥50% of the gap: real quiet sleep,
    shown with the overlap-weighted mean level ("quiet (−84.2 dBFS)"); a bucket counts toward
    coverage only in proportion to the windows it actually holds (P3-16);
  - `UNCAPTURED` — no usable evidence: process death, or the mic delivered **digital
    silence** (bucket mean ≤ −95 dBFS or ~zero variance — an Android-silenced or dead client
    delivers exact zeros, P2-4), or a `client_silenced_span` covers the gap (mic privacy
    toggle, a call, another app holding the mic — the OS flag outranks derived levels);
  - unclassified — partial evidence (a death inside a quiet stretch), a partly silenced gap,
    or pre-v3 nights.
- Legacy nights without stored rows fall back to the same computation at report time
  (`classifyQuietGaps`), classified when their capture stats carry buckets.
- **TL;DR and the shared text count only `kind == QUIET` as quiet stretches** (P2-5); hours
  the mic never heard get their own "not captured" line — never measured quiet.

**Session health card.** Records: recording time; capture window + start delay (when
`capture_started_at` exists); which share denominator was used; ambient level (median/p95);
analysis windows (with thermally skipped count); clips saved; and the derived health warnings
(gap suspicion, thermal throttling) from `CaptureStats`.

**Data-quality banners (non-blocking).** Shown under the TL;DR; evidence about the recording,
never a gate on the numbers:
- **Clipped clips** — `ClipIntegrity` (data/files) scans every retained clip's PCM for
  full-scale samples (|v| ≥ 32767); "N of M clips digitally clipped" means the loudest moments
  sound distorted on playback (fixed capture gain saturates; tracked for a write-time fix).
  Missing/unreadable clip files are flagged likewise. The scan runs asynchronously after
  first render (decodes ~10 MB per night worst-case) and never blocks playback.
- **Audio coverage** — union snoring time with a clip over total union snoring time, plus the
  burst counts (`ClipCoverage`); explains why some worst-moments have no audio. Coverage is
  partial by design: clips follow a budget (50/night + a 25-clip loud reserve, 8/hour) and,
  since night 4, a per-type bout cooldown (`ClipPolicy`) — after a clip is written, same-type
  bursts starting within 45 s of its end are skipped unless they peak ≥3 dB louder (an
  upgrade re-anchors the cooldown; loud-reserve peaks bypass it and re-anchor too). A bout
  therefore costs ~1 clip per cooldown window plus its loudest steps, so the night's worst
  moments get playable audio instead of one train eating the budget (night 4: 1,048 bursts →
  60 clips, 4% of snore time covered).
- **Garmin linkage** — `GarminLinkStatus`: LINKED (payload present, window+overlap shown),
  METRICS_ONLY (payload aged out / older import), MISSING (nothing imported).
- **Version change vs previous night** — detector or score-formula difference (see TL;DR gates).

**Share action.** The top-bar share emits `NightSummaryText.build` — plain text, session-timezone
times, same version gates as the TL;DR.

## 2. Garmin linking

Garmin vendor rows are stored **without a session id** (`measurement.session_id` is NULL by
design — imported outside any session). The join is report-time, by wake date:

- Join key: `NightDates.garminWakeDate` — the LOCAL WAKE date (Garmin keys a night by the
  morning it ends; a 21:52→07:10 night is Garmin's "day D+1"). Since schema v9 the imported
  rows also carry `sessionId`: the import links them to the acoustic session whose window
  best overlaps the payload's own sleep window (day window as fallback) — `null` when no
  session overlaps, which keeps the date-window join as the fallback. Rows written before v9
  stay unlinked until their date is re-imported.
- Metrics come from `MeasurementDao.garminBetween(day window)`; the card labels every value
  "Garmin estimate" and never compares them numerically with acoustic or clinical data.
- **Sleep score**: the verified live payload shape (2026-11-06) puts the headline 0–100 at
  `sleepScores.overall.value` — `GarminJsonParsers` reads that (plus the legacy
  `sleepScore.value` singular shape first), and maps the verified sub-scores
  (`totalDuration`→factor-duration, `stress`→factor-stress; unknown keys skipped, raw payload
  preserved). Nights imported before this parser fix show no score until re-imported.
- **Sleep window & overlap**: `GarminJsonParsers.parseSleepWindow` reads the payload's own
  window (`sleepStart/EndTimestampGMT`, Local fallback) from the verbatim `raw_payload` row.
  The card shows Garmin's window and how much of the captured night it overlaps
  (`ReportAssembler.garminOverlapMs`, clamped at 0). **Zero overlap is flagged in red** — the
  payload likely belongs to a different night. The two devices can measure substantially
  different windows (real example: phone 00:37–06:38, Garmin 02:48–09:15 → 3h50m overlap);
  the report states the overlap instead of silently aligning them.
- Stage seconds (deep/light/REM/awake) are totals; the stage **timeline** comes from the
  dedicated sleep-service detail endpoint (`/sleep-service/sleep/{date}`, fetched since the
  v9-era import with the dailySleep payload's own `sleepLevels` as fallback) and is stored in
  `sleep_stage` (schema v9) when present. With a timeline the report answers **"which stage
  did the longest snore bout fall in"** and shows **snoring minutes per stage** — without one
  (the verified Personal-API surface does not serve it on every key), both are omitted and
  totals remain. Never a guess.
- `raw_payload` rows follow session retention; when the payload has aged out the card still
  shows the day-window metrics, without the window/overlap header.
- **Morning backfill:** opening a night with MISSING/METRICS_ONLY Garmin data triggers one
  silent import for the wake date (rate-limited: once per date, one more try after a 6 h
  cooldown; no-op without a stored key), then the report re-renders with the fresh data.
- **Manual refresh:** a "Refresh with Garmin" button at the top of the Morning Summary re-runs
  the wake-date import on demand (same budget-marking as the card's button, never a duplicate
  import) and rebuilds the report in place ("Syncing Garmin…" → "Updating report…"); an
  already-LINKED night rebuilds only, and a MISSING outcome after an attempt shows
  "Garmin data not available yet" under the button — unless the attempt budget closed the
  gate (a recent failed import for this wake date is cooling down), in which case the line
  names the unblock path ("an earlier import attempt failed — 'Import from Garmin' below
  retries immediately"): the card's Import button is deliberately ungated, so a failed
  stop-time backfill must never read as missing data (2026-11-23 morning incident).

## 3. Last-N-nights trend

Full spec: [TRENDS.md](TRENDS.md). In short: `TrendAssembler` builds one tappable row per night
(tap → that night's Morning Summary; synthetic missed-night rows — [TRENDS.md](TRENDS.md) —
carry no metrics, so no report opens from them). Mixed sources, by design — stored measurements supply the
report-reproducible numbers (score, snore time, share), the structure numbers that are not
persisted (longest bout, mean peak, clip coverage) are computed from the night's events/clips
with the report's exact math, and the schema-v10 stored quiet gaps add **quiet minutes vs
uncaptured minutes** so a quieter night is distinguishable from a worse recording. Eligibility:
finalized COMPLETED/RECOVERED with actual capture; oldest first; last 7. Two independent
version markers ("detector changed", "score formula changed") fire only when both sides are
known — unknown provenance never renders as a change.

## 4. Low-confidence noise display filter

OTHER_NOISE events below a configurable confidence threshold are hidden from the report
(counts, timeline, episodes) with an integrity note stating how many were hidden. They stay in
the database (labeling dataset, exports); SNORE events are never filtered, so score inputs and
stored measurements are unaffected — the filter is display-only. Night Nov 5→6: 26 of 81 noise
events hidden at the default.

- **Threshold**: user-configurable in Settings ("Low-confidence noise in reports") —
  Off / ≥0.50 / ≥0.70 / ≥0.90 (DataStore key `noise_display_min_confidence`, default 0.5,
  clamped 0–0.9; 0 disables the filter). Passed to `ReportAssembler.assemble(noiseMinConfidence)`
  per report build; the integrity note quotes the active threshold.

## Score formula versions

The SNORE_SCORE measurement row stores the **score formula version** in `model_version` — never
the detector's model string. The two change on independent schedules, and the 7-day trend's
"score version changed" flag keys on this field. The row's `notes` hold the reproducible
breakdown, e.g. `snore-score-v2: share 43.8/60 + intensity 9.7/40 (tier weight 0.243)`.

| Version | Share component | Intensity component | Recalculated dogfood anchors |
|---|---|---|---|
| `snore-score-v1` (retroactive name; all rows before 2026-11-06) | 60 pts, saturating at **10%** share | 40 pts, tier weights over **event counts** | 13%→66, 16%→66, 27%→66, 29%→67, 30%→67 — saturated, indistinguishable |
| `snore-score-v2` | 60 pts, saturating at **40%** share | 40 pts, tier weights over per-tier **snoring seconds** (union) | 13%→**25**, 16%→**29**, 27%→**47**, 29%→**53**, 30%→**53** |
| `snore-score-v3` (2026-11-08) | identical arithmetic to v2 | identical arithmetic to v2 | unchanged — the **input set** changed: the snore event set is label-aware (`SnoreMath.eventsCountingAsSnore`) |

Rationale for v2: every dogfood night exceeded the 10% saturation point, locking the share
component at 60/60 and leaving only ~7 pts of tier variance; and count-weighting let many short
QUIET time outweighs one long LOUD stretch. v2 weights by where snoring TIME sat and keeps the
whole 15–40% share range monotonic. Score semantics are still relative (dBFS-derived, never
SPL) and the score remains a wellness indicator, not a measurement of anything clinical.

Rationale for v3: a user label says the detector misclassified a real sound (dogfood night of
2026-11-07→08: the night's loudest "snore" was a coughing fit the user labeled COUGH, still
counted toward share and score). A non-SNORE label now excludes the event from every snore
aggregate, `SNORE` includes it, no label keeps the detector's class; labeling REWRITES the
session's snore-aggregate PHONE rows in the labeling transaction, flagged
`label_corrected=true` (docs/DATA_MODEL.md §3.5). v2 rows are not comparable with v3 rows
(they may include events the user rejected) — the version bump hides the boundary in
cross-version comparisons, same mechanism as v1→v2.

Reports recompute the score from events, so **the report shows the current-version score for
old nights immediately**; the stored rows (and the trend, which reads stored rows) update when
a night's measurements are rewritten — re-finalize, or a label save on that night (labeling
any event of a session rewrites its snore aggregates). The trend therefore flags the v1→v2
and v2→v3 boundaries as score version changes — correct: those scores are not comparable.

## Night validity

One data-quality grade per night (A–D): how well the night was **captured** — never a
physiological judgment (the measurement type is `NIGHT_VALIDITY`, ESTABLISHED,
unit `GRADE`, valueText = the letter, valueNum null — docs/MEASUREMENT_RELIABILITY.md §8).
Pure, deterministic, versioned: `NightValidity.VERSION = "night-validity-v1"` (stored on the
row's `model_version`); the full rule set with threshold provenance lives in
`domain/validity/NightValidity.kt`'s KDoc.

**Grade matrix** (INFO reasons never lower the grade):

| Grade | Meaning |
|---|---|
| D | any CRITICAL reason — or no capture stats at all (`stats_missing`) |
| A | 0–1 WARNING reasons |
| B | 2–3 WARNING reasons |
| C | 4+ WARNING reasons |

**Reasons** (ordered, machine-readable ids; the UI maps ids to localized strings):
`stats_missing`, `capture_failed` (0 windows processed in a > 30 s capture window),
`capture_gaps` (> 10% window shortfall) / `capture_gaps_severe` (> 40%), `thermal_throttle`
(throttling with actually skipped hops), `unexplained_gap` (UNCAPTURED quiet-gap minutes
> 15, CRITICAL > 60), `clipped_clips` (> 10% of clips, CRITICAL > 30%), `ambient_noise_high`
(tonight's ambient P95 z > 2.5 vs the rolling prior-night distribution — needs ≥ 5 prior
nights, else omitted; **relative only**: phone dBFS is uncalibrated, absolute-dB comparisons
are banned, copy-only references like the WHO night-noise guidance must never become a
threshold), `session_interrupted` (not COMPLETED or resumed after interruption),
`garmin_*` (validator findings, below), `low_snore_signal` (snore share < 1% of captured
audio — INFO).

**App conventions vs cited thresholds**: the 30 s capture-failure window, 10%/40% shortfalls,
15/60 min unexplained gaps, 10%/30% clipped rates, 24 h Garmin freshness and the ±10%
stage-sum tolerance are app conventions (the capture heuristics mirror the report's existing
capture-health warnings). Cited: low-snore 1% floor (phone-mic snoring PPV collapses for
light snorers — Klaus 2021, IJERPH); wrist PPG plausibility bounds — heart rate 30–220 bpm,
rMSSD 2–300 ms, SpO₂ 70–100% with < 90% a distrust flag (wrist PPG error ±2–3 pp —
Jung 2022, Rajakariar 2024; SpO₂ is never desaturation counting, M12/M17); reported-vs-window
sleep-time tolerance ±45 min (vendor validation bias ~+40 min — Schyvens 2025, Chinoy 2021).
Garmin stage agreement with sleep-lab stages is fair-to-moderate in the literature
(κ 0.21–0.54) — surfaced in the UI as an "estimates" footnote, cited here, not in the app.

**The NIGHT_VALIDITY row format**: one PHONE row per session, `method = ALGORITHM_ESTIMATED`,
`valueText` = grade letter, `valueNum` = null, `unit = GRADE`, `model_version` =
`night-validity-v1`, `notes` = JSON
`{"version":"night-validity-v1","grade":"A","reasons":[{"id":…,"severity":…,"detail":…}]}` —
hand-built and stable (key order is part of the format; `NightValidity.parseNotes` reads it
back for the report and the trend).

**Write/replace paths** (the row is replaced, never duplicated):
1. **finalize** — SessionRepository.finalizeSession computes the grade from the capture
   evidence (windows, gaps, clip scan, interruptions, ambient z) inside the finalize
   transaction; Garmin is not an input there (the import usually lands later).
2. **label change** — SessionRepository.labelEvent recomputes + replaces in the same
   transaction (a label re-weighs the snore share), revalidating Garmin from the stored raw
   payloads (`ValidityCalculator.garminValidityFor`).
3. **Garmin re-import of a linked session** — GarminImportManager runs the validators on the
   parsed metrics/stages BEFORE the write (implausible values and corrupted spans are dropped
   from the rows; the raw payloads keep every original byte) and recomputes + replaces the
   linked night's row inside the import transaction with the fresh validation attached to
   `GarminImportState.Success.warnings`.

## Constants

| Constant | Value | Where |
|---|---|---|
| `BOUT_GAP_MS` | 10 000 ms | `Bouts.kt` — bout chaining (above the ≥1.5 s minimum inter-event gap the detector leaves; same bound as episodes) |
| `QUIET_GAP_MIN_MS` | 10 × 60 000 ms | `QuietGaps.kt` — quiet-stretch threshold |
| `DEFAULT_MIN_DISPLAYED_NOISE_CONFIDENCE` | 0.5 (user-configurable, 0 = off) | `ReportAssembler.kt` + `UserPreferences` — display filter (OTHER_NOISE only) |
| `AmbientLevelBuckets.BUCKET_MS` | 300 000 ms | 5-minute mean-window-dBFS buckets in capture stats (schema v3) |
| `QUIET_GAP_MIN_MS` storage | ≥ 600 000 ms stored per session | `quiet_gap` table (schema v10), finalize-time |
| `SnoreScore.SHARE_SATURATION` | 0.40 | `SnoreScore.kt` — share component locks at 60 pts at 40% of the night |
| `SnoreScore.FORMULA_VERSION` | `snore-score-v3` | `SnoreScore.kt` — stored on SNORE_SCORE rows (`model_version`) |
| `MAX_TIMELINE_EVENTS` | 2 000 (existing) | `ReportAssembler.kt` |
| `EPISODE_GAP_MS` | 10 000 ms (existing) | `Episodes.kt` — loudest-episode grouping |

The report features compute from the current v9 schema. The detector changed in this change
set (v1-spectral → v2-burst: burst-level events, see §1) — `MODEL_VERSION` was bumped in lockstep
so new nights are trend-flagged against v1 rows, and the fixture eval carries the same string.
The SNORE_SCORE row's provenance field remains the score formula version (not the detector).

## Known limitations / follow-ups

1. **Quiet gaps vs real capture gaps** — indistinguishable without a persisted heartbeat
   timeline (needs new capture-side state).
2. **Garmin session linkage is date-based** — a session-level link would need a schema change;
   the overlap header makes the join quality visible instead.
3. **Sleep score on old nights** requires a Garmin re-import (parser learned the plural shape).
4. **In-bed time includes the start delay** (documented decision); the capture-window row on
   the health card shows the difference explicitly.
5. **Event≠snore**: sustained snoring still merges into single long events (by design, ≤2 s
   internal gaps); the burst/bout view is the report-time lens over that.
6. Clip budget observability and the digital-clipping fix are tracked separately (not part of
   this change set).
