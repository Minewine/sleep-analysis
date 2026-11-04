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
| M12 | Wearable SpO₂ | **2** (as device-reported) | Consumer SpO₂ accuracy is limited; **never** derive desaturation events/index | Moderate (level) / n.a. (events) | "Vendor-reported SpO₂ level — not a desaturation measurement" | Trend-only display |
| M13 | Vendor sleep stages (Garmin) | **1** (as device-reported, context only) | Vendor estimate, not measurement | Low–Moderate | "Vendor estimate — differs from PSG scoring by design" | Never numerically compared to clinical stages |
| M18 | Vendor sleep duration/score + score-factor sub-scores, Body Battery, resting HR, sleep stages (M13), nocturnal HR (M10), respiration, sleep/daily stress composites, awakenings, nap time, sleep-need baseline, SpO₂ **levels only** (M12) — all via the Garmin Personal API | **1** (as device-reported) | Direct vendor pass-through; Body Battery and stress are proprietary vendor composites; factor sub-score key names are provisional until the first real night (raw payloads preserved) | Low–Moderate | Every row labeled **"(Garmin estimate)"**; vendor algorithms opaque; context only, never merged into phone-derived aggregates; SpO₂ shown as trend levels — **never** a desaturation measurement and `numberOfEventsBelowThreshold` is not imported (M12/M17) | Pass-through integrity; document endpoint/field assumptions in GARMIN_INTEGRATION.md §4 |
| M14 | Phone-derived sleep stages (N1/N2/N3/REM) | **3** | Not credibly supported | — | — | Never built |
| M15 | Phone-derived AHI / RDI / respiratory event index | **3** | Research shows screening-grade correlation (c-stat ~0.87–0.91 in cited studies), which is **not** a per-night clinical claim; crossing this line is also a medical-device claim | — | — | Never built |
| M16 | Phone-derived arousal index | **3** | Arousal is EEG-scored; acoustics cannot score it | — | — | Never built |
| M17 | Phone-derived desaturation / SpO₂ events | **3** | No sensor | — | — | Never built |
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
4. This document is the single source of truth; `ClaimPolicy` in code (see [ARCHITECTURE.md](ARCHITECTURE.md) §6) must reference tiers consistent with §2, and a unit test asserts the two stay in sync.
