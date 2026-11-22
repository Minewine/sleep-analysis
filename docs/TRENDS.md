# Trends — the last-N-nights view

Reached from History → Trends. One row per night (real nights open that night's Morning
Summary — missed rows, below, have nothing to open); full field definitions and
cross-surface consistency rules live in
[NIGHT_REPORT.md](NIGHT_REPORT.md) §3 — this page is the quick reference.

## Row fields and their sources

| Field | Source | Notes |
|---|---|---|
| Snore minutes (union) | stored `SNORE_TIME` measurement | Union, never sum — identical to the night's report. |
| % of capture | stored `SNORE_SHARE_OF_NIGHT` | Denominator changed between early builds (time-in-bed → captured seconds); see the caveat below. |
| Longest bout | computed from the night's events (`Bouts.kt`, `BOUT_GAP_MS` = 10 s) | Same math as the Morning Summary's structure card. |
| Mean peak dBFS | mean of the night's per-event peaks (events) | Analysis-path values, relative dBFS — never SPL. |
| SnoreScore + formula version | stored `SNORE_SCORE` row (value + `model_version`) | Formula version shown per row ("score v2"); the score is comparable only within a formula version. |
| Detector version | first snore event's `model_version` | Shown per row ("v2-burst"). |
| Clip coverage % | union clip-backed snore time ÷ total union snore time (events + audio_clip) | Same math as the report's coverage banner. |
| Quiet-gap totals | `quiet_gap` table (schema v10) | Gap count (all kinds), total **QUIET** minutes, and total **UNCAPTURED** minutes kept separate; unclassified gaps (no level evidence) count but carry no minutes. |

## Eligibility

Finalized sessions only (COMPLETED, RECOVERED or — since the P2-6 fix — INTERRUPTED nights
that actually captured windows) **with actual capture**: a night needs ≥ 1 processed analysis
window (`windowsProcessed > 0`, P2-7). The stats-less stub (`{"schema_version":1}`, what a
stop during the start delay writes) and zero-window sessions are excluded — an hour of
nothing must not become a "0% snore, score 0" row — and stats-less legacy nights no longer
count either (unverifiable capture is not a verifiable night). Oldest first, last 7. Rows use
the label-aware snore set for the structure numbers (P2-9: a relabeled cough never counts
toward longest bout, mean peak, clip coverage or the detector version).

From schema v12 the analysis surfaces apply a second, stricter gate for anything that
compares nights: **validity grade ≥ B + same detector/score versions (registry-gated,
`model_registry`)** — that is the eligibility of the personal baseline and insights
([INSIGHTS.md](INSIGHTS.md)). Trend rows themselves still show every captured night (with the
grade letter per row and the version-change markers below); only the insight/baseline
analysis restricts itself to the registry-gated eligible set.

## Missed nights — synthetic rows for nights with nothing recorded

Since the missed-night pass the trend extends the last-7 wake dates (`today`..`today−6`) with
a synthetic **missed** row for any night that has neither a real row nor an in-progress session
and was not skipped on purpose (`TrendAssembler.injectMissedNights`; `NightTrend.missed`,
sentinel session id −1) — absence made visible, the same rule as the Home banner
(`MissedNightPolicy`).

- **Suppression rules.** Real rows win — a short or INTERRUPTED night keeps its real row (the
  Home banner explains it); an open session suppresses its in-progress night (provisional wake
  date = start + 12 h, the `NightDates` open-session fallback); a **skipped** night renders no
  row at all — the skip set holds the night's INTENDED-WAKE-DATE epoch days (P2-6: the wake
  date a post-midnight start shares with its occurrence day, the day after an evening start's),
  so a night waking on day D is suppressed when D is in the set (a deliberate absence, not a
  capture failure); with no finalized night in the window nothing is injected and the empty
  state speaks.
- **A missed row carries no metrics and is never compared.** No minutes, no share, no score,
  no grade — and therefore no version markers; the comparison-validity rules on this page
  simply do not apply to it. It renders the "missed" label, and its sentinel id is guarded at
  the click handler (there is no report behind it).
- **Anchoring.** Synthetic `startedAt`/`endedAt` = the wake date's local midnight in the
  assembler's zone (the last real row's zone, system-default fallback).

Known edge (tracked follow-up, matching the code comment): the span itself keys on
`LocalDate.now()` in the system zone while synthetic anchors use the row zone above — a
timezone traveller can get a mis-anchored synthetic row; the minimal fix is
`LocalDate.now(zone)`.

## Version-change markers

Each night carries two independent provenances, because they change on different schedules:

- **"detector changed"** — the night's detector version differs from the previous night's
  (first v1-spectral → v2-burst boundary: event semantics changed, so snore minutes across it
  are not comparable).
- **"score formula changed"** — the SNORE_SCORE row's formula version differs (v1 →
  snore-score-v2: scores across it are not comparable).

Both markers appear only when **both** nights' versions are known — unknown provenance never
renders as a change. A footnote appears when any boundary exists in the window. Historical
caveat no marker can express: the share denominator changed between early builds; treat
cross-era share comparisons with care.

## Quiet totals — "quieter nights" vs just lower snore time

Lower snore minutes alone cannot distinguish a genuinely quieter night from one where capture
was broken. The trend therefore shows the stored **QUIET** minutes next to snore minutes
("quiet 34m (2 gaps)") and keeps **UNCAPTURED** minutes ("· 12m no capture") visibly separate —
a night with less snoring AND more classified quiet time is a truly quieter night; one with
more uncaptured time is a worse recording. Gaps without level evidence are counted but carry no
minutes. Nights with **no stored `quiet_gap` rows — legacy (pre-v10) or genuinely gap-free —
show no quiet segment**; their gaps are not backfilled.
