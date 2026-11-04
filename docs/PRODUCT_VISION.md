# Product Vision — SleepAnalysis

**Personal sleep intelligence platform — not a snoring app.**

Related docs: [Measurement reliability](MEASUREMENT_RELIABILITY.md) · [MVP scope](MVP_SCOPE.md) · [Architecture](ARCHITECTURE.md) · [Data model](DATA_MODEL.md) · [Roadmap](ROADMAP.md) · [Privacy & regulatory](PRIVACY_AND_REGULATORY.md)

---

## 1. Positioning

SleepAnalysis is a privacy-first Android platform for one person's long-term sleep research. It combines five data layers into a single longitudinal view:

1. **Immutable clinical baseline** — structured results from the HUG (Hôpitaux Universitaires de Genève) sleep studies, January 2026.
2. **Overnight acoustic monitoring** — phone microphone, on-device processing only.
3. **Wearable data** (later) — Garmin / Health Connect: heart rate, HRV, SpO₂, vendor sleep estimates.
4. **Lifestyle factor logging** — weight, medication, alcohol, exercise, stress, pain.
5. **Longitudinal analysis** — trends, personal baseline bands, factor associations.

One-sentence pitch:

> *A personal platform that records your nights, measures only what a phone can credibly measure, and tracks your sleep health against your clinical baseline over time.*

The defining constraint is **measurement discipline**. Every number the app shows is classified up front as Established, Experimental, or Out-of-scope-for-claims ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md)). The app never presents phone-derived data as equivalent to polysomnography (PSG).

## 2. Target user

| User | Role | How served |
|---|---|---|
| Michael (the developer) | Primary and only interactive user | Sideloaded personal build, single account-free device, nightly use |
| Treating clinicians (sleep medicine, psychiatry) | Consumers of exports only | Periodic PDF/CSV exports shared during follow-up; they never touch the app |

This is a **personal platform first**. There is no public launch target in this document. Designing honestly for one informed user is what makes the conservative measurement stance sustainable; generalizing to consumers later would be a new project with new validation obligations, not a default growth path.

The clinical baseline is the anchor: elevated arousal index (27–32/h), variable efficiency (70–91%), one night with AHI 17.6 (hypopnea-dominant), reduced N3 on some nights. The platform's job is to track **trends against that baseline** and against the user's own normal ranges — not to re-measure what PSG measures.

## 3. What success looks like

### 3 months

- Phase 1 and Phase 2 shipped; Phase 3 started ([ROADMAP.md](ROADMAP.md)).
- **Reliability:** 50+ nights captured; ≥90% of intended nights have complete sessions; zero unrecoverable data loss.
- **Habit:** the morning report is checked daily and is trusted enough to skip re-checking with other apps.
- **Signal:** night-to-night snoring differences are visible and believable (verified by spot-listening to clips).
- **Baseline:** all four HUG nights stored, immutable, visible in the report context.
- **Discipline:** every displayed measurement has correct provenance and tier labeling; no Tier-3 values anywhere in the UI or DB.

### 12 months

- **Platform:** phone acoustics + wearable data + lifestyle factors + clinical baseline in one timeline and one comparison view.
- **Personal baseline model:** the app knows the user's normal ranges (e.g., "typical snoring time 8–20 min") and flags deviations conservatively — as *observations*, never as findings.
- **Actionable insight:** at least 1–2 associations the user considers real enough to act on (e.g., late alcohol → longer/louder snoring; weight trend → snoring trend), each traceable to the underlying data.
- **Clinical usefulness:** at least one export prepared for and discussed with a clinician; app trends reviewed alongside a follow-up sleep study as *correlation*, explicitly not equivalence.
- **Integrity held:** measurement tiers unchanged in substance after 12 months of pressure to overclaim. Experimental indicators remain labeled experimental or have been *promoted through the validation plan* ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §7), not by drift.

## 4. Explicit non-goals

| Non-goal | Why |
|---|---|
| Diagnosing sleep apnea or any condition | Regulatory boundary (wellness, not medical device) and scientific honesty |
| Phone-derived AHI, RDI, respiratory event index | Not credibly measurable without clinical-grade validation; hard Tier-3 line |
| Sleep staging (N1/N2/N3/REM) from phone | Not supported by microphone + movement at claim-grade confidence |
| Clinical arousal index from phone | Arousal is an EEG-scored phenomenon; acoustics can never score it |
| SpO₂ / desaturation estimation | No sensor on the phone; wearable SpO₂ stays vendor-reported context |
| Replacing PSG or discouraging clinical follow-up | The platform's value collapses if it displaces real studies |
| Cloud sync of audio, accounts, multi-user | Privacy posture and single-user scope |
| Social features, gamification, sleep-coaching content marketing | Different product; CBT-I-style hygiene tips stay minimal and Phase 3+ |
| Public Play Store launch (for now) | Personal platform first; consumer release would demand validation, review processes, and support this project deliberately excludes |
| iOS / cross-platform | Deferred; Kotlin/native chosen for service + audio reliability |

## 5. Guiding principles (summary)

1. **On-device or nothing** — audio never leaves the phone; the network posture is defined in [PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md).
2. **Provenance on every measurement** — source, method, unit, confidence, model version ([DATA_MODEL.md](DATA_MODEL.md)).
3. **Claims follow the tier system** — the UI can only render what the claim policy allows.
4. **The morning report is the product** — everything else exists to make that one screen trustworthy.
5. **Degrade, don't fabricate** — a partial night reported honestly beats a complete-looking number with hidden gaps.
