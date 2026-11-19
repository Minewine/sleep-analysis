# Measurement Reliability — What SleepAnalysis Claims, and What It Never Will

This document is **binding for all phases**. No feature, model, or UI element may present a measurement outside its tier classification below. Adding or changing a measurement's tier requires updating this document first (§7 change control).

Related: [Product vision](PRODUCT_VISION.md) · [MVP scope](MVP_SCOPE.md) · [Privacy & regulatory](PRIVACY_AND_REGULATORY.md)

---

## 1. Measurement tiers

| Tier | Name | Meaning | UI rules |
|---|---|---|---|
| **Tier 1** | Established | Phone/sensor can credibly support it; precedent in research or commercial products; quality can be verified (clips, spot checks). | Shown as a primary measurement with normal wording. |
| **Tier 2** | Experimental | Plausibly supported but under-validated for this device class; may be noisy or biased. | Shown **only** behind an explicit "experimental" toggle in Settings, always with an experimental badge and its disclaimer text. Never in headline scores, never in exports as a claimed result. |
| **Tier 3** | Out of scope for claims | Phone cannot support it at claim-grade confidence, or it crosses the medical-device boundary. | **Never computed, never stored, never displayed.** No code path exists. |

Clinical baseline values (PSG-derived) are not on this scale — they are clinically measured facts, stored read-only with their own provenance ([DATA_MODEL.md](DATA_MODEL.md) §ClinicalBaseline).

## 2. Classification table

| # | Measurement | Tier | What the phone/sensor can actually support | Confidence | Required disclaimer / wording | Validation needs |
|---|---|---|---|---|---|---|
| M1 | Snoring detection (events, duration, count) | **1** | Mic + audio classifier; strong commercial (SnoreLab) and research precedent | **High** (relative) | "Snoring detected by microphone analysis" | Fixture eval set (precision/recall); user clip spot-checks; user labels |
| M2 | Snoring intensity (loud/quiet tiers, relative levels) | **1** | Relative loudness in dBFS; **not** calibrated sound pressure | High (relative) / **Low (absolute)** | "Relative loudness — not calibrated dB SPL" | Cross-night consistency check on same device/place |
| M3 | Snoring share of night (snoring time / time in bed, index) | **1** | Derived arithmetic from M1/M2 | High (as derived) | Inherit M1/M2 wording | Inherits M1 |
| M4 | Acoustic events: cough, speech, loud breathing | **1** | Audio classification; cough/speech well represented in public datasets | Moderate–High | "Acoustic events — detection may miss or misclassify" | Per-class fixture eval; user labels |
| M5 | Estimated sleep/wake periods (movement + sound stillness) | **1** | Actigraphy-like proxy: stillness + quiet ⇒ probable sleep | **Moderate** | "Estimated from movement and sound. Still wakefulness can read as sleep" | Compare vs. user's self-reported wake episodes; literature actigraphy agreement bounds |
| M6 | Movement events / restlessness index (accelerometer) | **1** (Phase 2+) | SensorManager accelerometer; movement counts are direct | Moderate–High | "Movement proxy — not body position scoring" | Sanity-check against known restless nights |
| M7 | Acoustic breathing-irregularity indicators (audible pauses, gasp-like onsets, snore-pattern breaks) | **2** | Sound features correlate with respiratory disturbance in research, but per-night phone estimates are unvalidated here | **Low–Moderate** | Badge + "Experimental indicator from sound patterns. Not a breathing measurement. Audible pauses are not apneas." | §7 promotion plan: prospective comparison against a follow-up PSG study |
| M8 | "Possible respiratory disturbance patterns" (aggregated breathing-disturbance indicator) | **2** | Aggregation of M7 over a night | **Low** | Badge + "Experimental, derived from sound patterns. Not an AHI estimate." | Same as M7 |
| M9 | Contactless breathing rate via ultrasonic sonar | **2** (Phase 5, optional) | Feasible (Sleep as Android precedent) but highly device-dependent | Low–Moderate | Badge + pet/hearing warning + "experimental" | Device-specific bench test before any display |
| M10 | Wearable heart rate (nocturnal) | **1** (as device-reported) | Garmin/Health Connect direct value | Moderate–High | "Reported by your wearable" — never re-derived by us | None beyond pass-through integrity |
| M11 | Wearable HRV (nocturnal) | **1** (as device-reported) | Vendor algorithm; opaque and device-specific | Moderate | "Vendor-reported HRV — algorithms differ between devices" | Consistency monitoring across firmware updates |
| M12 | Wearable SpO₂ — levels only (nightly average, nightly lowest) | **1** (as device-reported, levels only) | Consumer SpO₂ accuracy is limited — values are context, never re-derived by us; **never** derive desaturation events/index (M17) | Moderate (level) / n.a. (events) | "Vendor-reported SpO₂ level — not a desaturation measurement" | Trend-only display of the level values; the DTO's `numberOfEventsBelowThreshold` is deliberately not imported (M12/M17) |
| M13 | Vendor sleep stages (Garmin) | **1** (as device-reported, context only) | Vendor estimate, not measurement | Low–Moderate | "Vendor estimate — differs from PSG scoring by design" | Never numerically compared to clinical stages |
| M18 | Vendor sleep duration/score + score-factor sub-scores, Body Battery, resting HR, sleep stages (M13), nocturnal HR (M10), respiration, sleep/daily stress composites, awakenings, nap time, sleep-need baseline, SpO₂ **levels only** (M12) — all via the Garmin Personal API | **1** (as device-reported) | Direct vendor pass-through; Body Battery and stress are proprietary vendor composites; factor sub-score key names are provisional until the first real night (raw payloads preserved) | Low–Moderate | Every row labeled **"(Garmin estimate)"**; vendor algorithms opaque; context only, never merged into phone-derived aggregates; SpO₂ shown as trend levels — **never** a desaturation measurement and `numberOfEventsBelowThreshold` is not imported (M12/M17) | Pass-through integrity; document endpoint/field assumptions in GARMIN_INTEGRATION.md §4 |
| M14 | Phone-derived sleep stages (N1/N2/N3/REM) | **3** | Not credibly supported | — | — | Never built |
| M15 | Phone-derived AHI / RDI / respiratory event index | **3** | Research shows screening-grade correlation (c-stat ~0.87–0.91 in cited studies), which is **not** a per-night clinical claim; crossing this line is also a medical-device claim | — | — | Never built |
| M16 | Phone-derived arousal index | **3** | Arousal is EEG-scored; acoustics cannot score it | — | — | Never built |
| M17 | Phone-derived desaturation / SpO₂ events | **3** | No sensor | — | — | Never built |

**Resolution note (2026-11-04):** M12 previously read "**2**" while M18 listed "SpO₂ **levels only** (M12)" inside its Tier **1** device-reported row — an internal contradiction from the Garmin slice landing. The binding classification is **Tier 1 as device-reported, levels only** (what M18 always said and what the code implements): SpO₂ numbers are vendor pass-through context badged "(Garmin estimate)", never a desaturation measurement; deriving desaturation events/index from them remains Tier 3 (M17). Amending M12 (option (b) of the change control in §7) was chosen over demoting the code values to Tier 2, which would have hidden already-imported levels behind a toggle that does not exist until Phase 2.
| C1 | Clinical baseline metrics (TST, efficiency, arousal index, N3%, AHI, hypnogram summary) | **Clinical** | Entered read-only from HUG reports | **High** (as measured) | "Clinically measured (PSG, HUG, date)" | None — source of truth |

## 3. The two hard equivalences (never allowed)

1. **Acoustic events ≠ arousals.** An arousal is a cortical (EEG) phenomenon scored by a technologist per AASM rules. A snore, cough, or gasp is a sound. They sometimes coincide; the app must never present counts of sounds as arousal counts, arousal index, or "sleep fragmentation" with clinical meaning. Wording allowed: *"N acoustic events detected"* — never *"N arousals"*.

2. **Audible pauses ≠ apneas/hypopneas.** An apnea/hypopnea requires airflow reduction plus physiological correlates (desaturation and/or arousal), scored per AASM criteria with airflow and effort measured. Silence in a bedroom recording can be a posture change, a duvet over the phone, distance from the mic, or a quiet breathing pattern. Wording allowed: *"N breathing-pattern breaks (experimental indicator)"* — never *"N apneas"*, *"N hypopneas"*, *"N respiratory events"*, or any AHI-like index.

## 4. Cross-source comparison rules

- Phone audio intensity is **dBFS** (digital full scale) — device- and distance-dependent. It is never compared numerically to clinical dB SPL or displayed with clinical units.
- Clinical baseline and phone measurements may be displayed **side by side** (Phase 3+ clinical comparison view) under the framing: *"Your clinical study measured X on DATE. Phone monitoring tracks night-to-night trends, which are not the same kind of measurement."*
- Vendor wearable estimates (stages, SpO₂) are context, not evidence; they render with their vendor disclaimer and never merge numerically into phone-derived aggregates.
- Any cross-source view carries the standing caption: **"Correlation and context — not equivalence."**

## 5. Confidence definitions

| Label | Meaning |
|---|---|
| High | Behavior stable across nights/devices/placement; quality verifiable by the user (clips); precedent in validated products. |
| Moderate | Plausible and useful, known systematic biases documented (e.g., still wakefulness reads as sleep; vendor algorithm opacity). |
| Low | Indicative at best; noisy; could mislead if read literally; Tier 2 at most. |

## 6. What this means per phase (summary)

- **Phase 1:** M1–M4 only, plus time-in-bed (session bounds). M5 arrives properly with movement in Phase 2. No Tier-2 indicators yet — experimental breathing indicators start in Phase 2 behind the toggle, once event classification is good enough to make them worth labeling.
- **Phase 2:** M5–M6 (movement lands, sleep/wake estimate becomes Tier 1), M7–M8 launch as Tier 2 behind the experimental toggle.
- **Phase 3:** M10–M13 and M18 via the Garmin Personal API (first slice; see [GARMIN_INTEGRATION.md](GARMIN_INTEGRATION.md)) and later Health Connect, all under device-reported rules; clinical comparison view with §4 framing.
- **Phase 5:** only place where M7/M8 promotion (or M9 sonar) may be evaluated — through the validation plan below.
- **All phases:** M14–M17 are never built. Requests to compute them are rejected by design review, not deferred.

## 7. Change control — promoting or demoting a measurement

1. **Promotion (Tier 2 → Tier 1)** requires: (a) a written validation plan executed against ground truth — for breathing indicators this means a prospective comparison window against a follow-up HUG study, with agreement statistics reviewed honestly; (b) update to this document; (c) copy review of all UI strings; (d) version note in the app changelog.
2. **Demotion** (new evidence of unreliability) requires the same path in reverse plus removal or re-badging of affected UI within one release.
3. **New measurement proposals** start as Tier 2 by default, behind the experimental toggle, regardless of how confident the team feels.
4. This document is the single source of truth; `ClaimPolicy` in code (see [ARCHITECTURE.md](ARCHITECTURE.md) §6) must reference tiers consistent with §2, and a unit test asserts the two stay in sync — including parsing the machine-readable tier table (§8) so documentation drift fails the build.

### 7.1 Detector changelog (per §7(1)(d) — version note for every detector change)

- **2026-11-04 — `energy-heuristic-v0` → `energy-heuristic-v1-spectral`** (constant: `SessionRepository.MODEL_VERSION`). Adds two spectral gates on top of the v0 energy/band heuristic — spectral flatness ≤ max (tonal/peaked snore energy vs broadband noise) and harmonic ratio ≥ min (60–250 Hz autocorrelation), computed by the `monitoring/features/` FFT + autocorrelation — plus a breath-cycle rhythm gate (once a window passes all gates, adjacent windows of the same periodic train are believed; failing windows never seed it). Null-feature inputs fall back to exact v0 behavior. Thresholds tuned with the offline `FixtureEvaluator` (`monitoring/eval/`) over labeled dogfood clips via `ReplayEngine` (`monitoring/replay/`, feature-for-feature identical to the live loop). Driver: v0's broadband-household-noise false positives (night 1 produced 1,047 near-silent "SNORE" events). Documented limits: speech near the phone remains tonal+rhythmic (false positive); distant snoring may be missed.
- **2026-11-06 — `energy-heuristic-v1-spectral` → `energy-heuristic-v2-burst`** (constant: `SessionRepository.MODEL_VERSION`, mirrored in `FixtureEvaluator`). Event-SHAPING change only — per-window classification is untouched: cross-draft merging is off (`MERGE_GAP_MS = 0`), so every machine-open span is one burst-level event and any internal silence of 2 s of quiet stamps (4 hops, the exit hysteresis) splits a chain. Driver: v1's fold re-glued breath trains into single 300+ s "events" (session 11: up to 313 s), inflating duration stats and longest-event reporting. Bout-level aggregation (≤5/≤10 s gaps, report-time) replaces train-level merging; score inputs are tier-TIME weighted (v2 formula) and therefore robust to the finer event granularity.
- **2026-11-08 — score formula `snore-score-v2` → `snore-score-v3`** (constant: `SnoreScore.FORMULA_VERSION`; detector untouched). Input-definition change only — the arithmetic is byte-identical to v2, but the snore event set is now **label-aware** (`SnoreMath.eventsCountingAsSnore`): a non-SNORE user label (`COUGH`/`SPEECH`/`MOVEMENT`/`OTHER_NOT_SNORE`) excludes the event from every snore aggregate, `SNORE` includes it, no label keeps the detector's class. Labeling a session's event recomputes and REWRITES that session's snore-aggregate PHONE rows in the same transaction (`replacePhoneForSession`), flagged `label_corrected=true` in notes; the score row alone carries the formula version, exactly as finalize stamps it. Driver: dogfood night 2026-11-07→08 — the night's loudest "snore" (−12 dBFS, conf 0.97) was a coughing fit the user labeled COUGH, and v2 still counted it toward the 42/100 score. v2 rows are not comparable with v3 rows; the version bump hides the boundary per §7(1)(d). Full formula history: NIGHT_REPORT.md §Score formula versions.
- **2026-11-12 — `energy-heuristic-v2-burst` → `energy-heuristic-v3-peakgate`** (constant: `SessionRepository.MODEL_VERSION`, mirrored in `FixtureEvaluator`). Event-gate change only — per-window classification and event shaping are untouched: when a finalized SNORE event's mean noise floor sits at or below −80 dBFS (mic-self-noise rooms, where every relative threshold loses meaning), the event's PEAK must additionally stay above −38 dBFS or it is relabeled OTHER_NOISE (and `ClipPolicy` skips its clip by the same shared gate, so labels and clips stay consistent). Driver: dogfood night 2026-11-11 (session 13, ambient median −83 dBFS) — 21 breathing trains (29–99 s, peaks −39.6…−54.3 dBFS) cleared the −60 mean gate and shipped as 30.5 of 65.5 snore minutes (47%, from 2.6% of events); real snores that night peaked −16.3…−34.9 dBFS. The tier never arms in normal rooms (floor above −80 dBFS): an absolute cutoff there would eat clearly-audible snoring — the same reason the v0-era −55 dBFS mean cutoff was rejected — so no pre-v3 night's snoring in a normal room is affected. Documented limits: in such rooms a real snore peaking at or below −38 dBFS is relabeled (not deleted) and is recoverable only by manual labeling.

## 8. Machine-readable tier table (the binding code mirror)

One row per `MeasurementType` constant, its tier, and the M-number that governs it. **Do not
edit this table without the corresponding `MeasurementType` change in the same commit** —
`ClaimPolicyTest` parses this table and fails the build on any drift in either direction
(tier changed in code without this table, or vice versa, or a type missing from either side).
Tiers: `ESTABLISHED` / `EXPERIMENTAL` / `OUT_OF_SCOPE_FOR_CLAIMS` (§1).

| MeasurementType | Tier | M |
|---|---|---|
| `TIME_IN_BED` | ESTABLISHED | M5 (session-bounds time until movement lands) |
| `SNORE_EVENT_COUNT` | ESTABLISHED | M1 |
| `SNORE_TIME` | ESTABLISHED | M1 |
| `SNORE_SHARE_OF_NIGHT` | ESTABLISHED | M3 |
| `SNORE_INTENSITY_PEAK` | ESTABLISHED | M2 |
| `SNORE_INTENSITY_MEAN` | ESTABLISHED | M2 |
| `SNORE_SCORE` | ESTABLISHED | M3 (derived index) |
| `NIGHT_VALIDITY` | ESTABLISHED | — (data-quality grade: grades capture integrity, makes no physiological claim; docs/NIGHT_REPORT.md) |
| `NOISE_MEDIAN` | ESTABLISHED | M2 |
| `NOISE_P95` | ESTABLISHED | M2 |
| `ACOUSTIC_EVENT_COUNT` | ESTABLISHED | M4 |
| `BREATHING_IRREGULARITY_INDICATOR` | EXPERIMENTAL | M7 |
| `RESPIRATORY_PATTERN_INDICATOR` | EXPERIMENTAL | M8 |
| `VENDOR_SLEEP_DURATION` | ESTABLISHED | M18 |
| `VENDOR_SLEEP_SCORE` | ESTABLISHED | M18 |
| `VENDOR_HRV_RMSSD` | ESTABLISHED | M11/M18 |
| `VENDOR_BODY_BATTERY` | ESTABLISHED | M18 |
| `VENDOR_RESTING_HEART_RATE` | ESTABLISHED | M18 |
| `VENDOR_DEEP_SLEEP` | ESTABLISHED | M13/M18 |
| `VENDOR_LIGHT_SLEEP` | ESTABLISHED | M13/M18 |
| `VENDOR_REM_SLEEP` | ESTABLISHED | M13/M18 |
| `VENDOR_AWAKE_SLEEP` | ESTABLISHED | M13/M18 |
| `VENDOR_AVG_HEART_RATE_SLEEP` | ESTABLISHED | M10/M18 |
| `VENDOR_RESPIRATION_AVG_SLEEP` | ESTABLISHED | M18 |
| `VENDOR_SLEEP_STRESS_AVG` | ESTABLISHED | M18 |
| `VENDOR_STRESS_DAILY_AVG` | ESTABLISHED | M18 |
| `VENDOR_SPO2_AVG_SLEEP` | ESTABLISHED | M12/M18 |
| `VENDOR_SPO2_LOWEST_SLEEP` | ESTABLISHED | M12/M18 |
| `VENDOR_SLEEP_AWAKENINGS` | ESTABLISHED | M18 |
| `VENDOR_NAP_TIME` | ESTABLISHED | M18 |
| `VENDOR_SLEEP_NEED` | ESTABLISHED | M18 |
| `VENDOR_SLEEP_FACTOR_DURATION` | ESTABLISHED | M18 |
| `VENDOR_SLEEP_FACTOR_QUALITY` | ESTABLISHED | M18 |
| `VENDOR_SLEEP_FACTOR_STRESS` | ESTABLISHED | M18 |
| `VENDOR_SLEEP_FACTOR_TIMING` | ESTABLISHED | M18 |
| `AHI_ESTIMATE` | OUT_OF_SCOPE_FOR_CLAIMS | M15 |
| `RDI_ESTIMATE` | OUT_OF_SCOPE_FOR_CLAIMS | M15 |
| `SLEEP_STAGE_ESTIMATE` | OUT_OF_SCOPE_FOR_CLAIMS | M14 |
| `AROUSAL_INDEX_ESTIMATE` | OUT_OF_SCOPE_FOR_CLAIMS | M16 |
| `SPO2_DESATURATION_ESTIMATE` | OUT_OF_SCOPE_FOR_CLAIMS | M17 |
