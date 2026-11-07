# Night Report — how the morning numbers are produced

This document defines what the morning summary (report screen), the Garmin section and the
7-day trend show, where every number comes from, and which constants govern them. Written
against schema v9 and detector `energy-heuristic-v2-burst` (v1-spectral nights are historical and flagged by the trend's version markers).

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
bout with its time range), and the comparison to the previous night. The comparison reads the
previous night's STORED measurements and is version-gated: the time/share delta appears only
when both nights' detector versions are known and equal; the score delta only when the previous
score row used the current formula (`SnoreScore.FORMULA_VERSION`). A version change hides the
delta and surfaces a banner instead of comparing across it.

| Number | Source | Notes |
|---|---|---|
| Time in bed | session row (`startedAt`→`endedAt`) | Includes a configured start delay (documented decision — the session row starts at the tap). |
| Snore score | `SnoreScore.compute` (formula `snore-score-v2`) | 60 pts from snoring share (saturates at **40%** of the night) + 40 pts tier-weighted over **snoring TIME**. The report shows the breakdown ("share X + intensity Y") and the SNORE_SCORE row stores formula version + breakdown in `notes`. See §Score formula versions. |
| Snoring time / share | `SnoreMath.unionDurationMs` over SNORE events | Union, never sum. The share denominator is **mic-live captured analysis time** (`capture_stats.windows × 0.5 s`), falling back to recording time only when no capture stats exist. The card states which denominator is in use. |
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

**Quiet stretches.** Event-free spans ≥ `QUIET_GAP_MIN_MS` (10 min) inside the **capture
window** (`capture_started_at` when present, session start otherwise — never tap-to-stop, or a
start delay would appear as one giant gap). "Nothing was detected" uses all detected events,
including display-filtered ones (see §4). Leading/trailing quiet is included.

- **Stored** (schema v10, `quiet_gap` table): computed at finalize from the night's events +
  the capture's level buckets and written per session (replace-per-refinalize, FK CASCADE).
- **Classified** via the capture's per-window level summary (`ambient_level_buckets`, schema v3
  capture_stats: mean window dBFS per 5-minute epoch bucket, accumulated by the engine):
  - `QUIET` — level buckets cover ≥50% of the gap: real quiet sleep, shown with the
    overlap-weighted mean level ("quiet (−84.2 dBFS)");
  - `UNCAPTURED` — no bucket evidence in the gap: process death, shown as "no capture";
  - unclassified — partial evidence (a death inside a quiet stretch) or pre-v3 nights.
- Legacy nights without stored rows fall back to the same computation at report time
  (`classifyQuietGaps`), classified when their capture stats carry buckets.

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

## 3. Last-N-nights trend

Full spec: [TRENDS.md](TRENDS.md). In short: `TrendAssembler` builds one tappable row per night
(tap → that night's Morning Summary). Mixed sources, by design — stored measurements supply the
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

Rationale for v2: every dogfood night exceeded the 10% saturation point, locking the share
component at 60/60 and leaving only ~7 pts of tier variance; and count-weighting let many short
QUIET time outweighs one long LOUD stretch. v2 weights by where snoring TIME sat and keeps the
whole 15–40% share range monotonic. Score semantics are still relative (dBFS-derived, never
SPL) and the score remains a wellness indicator, not a measurement of anything clinical.

Reports recompute the score from events, so **the report shows the v2 score for old nights
immediately**; the stored v1 rows (and the trend, which reads stored rows) update only when a
night's measurements are rewritten (re-finalize). The trend therefore flags the v1→v2 boundary
as a score version change — correct: stored v1 scores are not comparable with v2.

## Constants

| Constant | Value | Where |
|---|---|---|
| `BOUT_GAP_MS` | 10 000 ms | `Bouts.kt` — bout chaining (above the ≥1.5 s minimum inter-event gap the detector leaves; same bound as episodes) |
| `QUIET_GAP_MIN_MS` | 10 × 60 000 ms | `QuietGaps.kt` — quiet-stretch threshold |
| `DEFAULT_MIN_DISPLAYED_NOISE_CONFIDENCE` | 0.5 (user-configurable, 0 = off) | `ReportAssembler.kt` + `UserPreferences` — display filter (OTHER_NOISE only) |
| `AmbientLevelBuckets.BUCKET_MS` | 300 000 ms | 5-minute mean-window-dBFS buckets in capture stats (schema v3) |
| `QUIET_GAP_MIN_MS` storage | ≥ 600 000 ms stored per session | `quiet_gap` table (schema v10), finalize-time |
| `SnoreScore.SHARE_SATURATION` | 0.40 | `SnoreScore.kt` — share component locks at 60 pts at 40% of the night |
| `SnoreScore.FORMULA_VERSION` | `snore-score-v2` | `SnoreScore.kt` — stored on SNORE_SCORE rows (`model_version`) |
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
