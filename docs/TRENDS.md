# Trends — the last-N-nights view

Reached from History → Trends. One tappable row per night (tap opens that night's Morning
Summary); full field definitions and cross-surface consistency rules live in
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

Finalized sessions only (COMPLETED or RECOVERED) **with actual capture** — a session whose
capture stats show 0 processed windows is excluded (junk/failed starts); sessions predating
capture stats count as real nights. Oldest first, last 7.

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
