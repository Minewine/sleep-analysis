# Ideas Backlog — Sequenced

Ideas surfaced 2026-11-02 after the night-1/2 dogfood fixes and the Garmin expansion, ordered
into an intelligent sequence. Ordering principle: protect **data quality** first (every later
analysis eats what the recorder produces), then build **intelligence on top** of accumulated
nights, then polish. Everything stays inside the claim policy
([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md)) — patterns in the user's own data,
never clinical claims.

Related: [NEXT_STEPS.md](NEXT_STEPS.md) (P0 gate) · [GARMIN_INTEGRATION.md](GARMIN_INTEGRATION.md) ·
[ARCHITECTURE.md](ARCHITECTURE.md) (InsightRules box)

## Wave 1 — protect the data (before more nights accumulate)

| # | Idea | What / Why | Effort |
|---|---|---|---|
| 0 | **Start delay (DONE first)** | Configurable capture delay after the user taps start, so falling-asleep noise doesn't spend clips/events. Mic access persists: the FGS started by the tap keeps while-in-use access; only the *reading* waits. | done |
| 1 | **Pre-flight mic sanity check** | 5 s capture before the night starts: RMS above self-noise, signal present. Catches blocked mics / dead audio paths at bedtime instead of in the morning (night-1 style silence). | S |
| 2 | **Self-calibrating level gate (DONE)** | The absolute `MIN_EVENT_DBFS = −55` is replaced by a relative gate: the event's rolling noise floor + `GATE_MARGIN_DB` (10 dB), floored by `MIN_GATE_DBFS` (−60) so near-silence still never gates open. Uses the rolling per-event floor (adapts within the night) rather than a nightly `ambient_dbfs_median` seed. The clip gate additionally runs +6 dB stricter for the first 30 min of capture (settling window, complements the start delay). | done |

## Wave 2 — intelligence on real nights (needs ~1–2 weeks of data)

| # | Idea | What / Why | Depends on |
|---|---|---|---|
| 3 | **Acoustic events × vendor sleep stages** | Join event timestamps with the Garmin sleep window/stages: "7 of 11 events in REM windows". Stage totals are verified; exact stage boundaries when a real-night payload lands. The single best morning-report upgrade. | real nights |
| 4 | **InsightRules engine** | Join the three layers — self-logged factors (alcohol, late meal, caffeine), phone acoustics, vendor physiology — into honest pattern statements: "nights with a late meal ran 40 % above your median event count". Own-data patterns only, n= thresholds to avoid noise-reading. The `InsightRules` box in ARCHITECTURE §3 finally gets its resident. | ≥ 10–14 nights, factors logged |
| 5 | **Data-driven delay tuning** | After ~5 nights: first-event-after-start distribution suggests the delay ("you usually fall asleep ~35 min after tapping start — set 30 min?"). Feeds back into idea 0's setting. | ≥ 5 delayed nights |
| 6 | **Sleep-debt view** | 7-day actual vs `sleepNeed.baseline` (460 min) card from Garmin data. Two queries + one card. | Garmin nights |

## Wave 3 — product surface

| # | Idea | What / Why | Effort |
|---|---|---|---|
| 7 | **Clinic-visit export** | One-tap CSV/PDF night export (events, clip index, Garmin context) + the stored clinical-baseline rows side by side, §4 framing intact. Turns the app into an artifact for HUG appointments. | M |
| 8 | **Swipe-to-label clips** | Labeling is the P0 exit currency (≥ 10 events) and the Phase 2 seed; swipe gesture + "3/5 labeled tonight" progress. | S |
| 9 | **Full-screen bedtime prompt** | `setFullScreenIntent` on the "Time to record" notification — alarm-style takeover, unmissable, still exactly one tap (Android while-in-use requirement). Needs `USE_FULL_SCREEN_INTENT` + one settings grant on Android 14+. | S |

## Moonshot

**10. Second-phone "partner mode"** — two devices on opposite nightstands, clips time-synced;
level difference of the same event attributes snoring (me vs partner vs environment). The
analysis is a threshold; the work is pairing/sync/merge. Park until the single-device pipeline
is boring.

## Explicitly rejected / parked

- **Bypassing the start tap** (accessibility auto-click, Shizuku): circumvents the while-in-use
  mic consent — makes the privacy story false. Never.
- **Vendor desaturation counts** (`numberOfEventsBelowThreshold`): banned from import/display by
  M12/M17. Levels only.

## Execution order

0 ✅ → 1 → 2 ✅ → (nights accumulate) → 3 → 6 → 8 → 9 → 4 → 5 → 7 → (10 moonshot).
Waves 1 items go in before/with tonight's run; Wave 2 items land as soon as their data exists;
Wave 3 fills the gaps between data waves.
