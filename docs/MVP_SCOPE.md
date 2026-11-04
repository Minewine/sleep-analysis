# MVP Scope — Phase 1: Sleep Recorder + Basic Report

Phase 1 ships one thing well: **a trustworthy overnight recorder with an honest morning report**. Sizing assumes part-time development; rough effort 4–8 weeks ([ROADMAP.md](ROADMAP.md) for the full plan).

Related: [Measurement reliability](MEASUREMENT_RELIABILITY.md) (binding tiers) · [Architecture](ARCHITECTURE.md) · [Data model](DATA_MODEL.md) · [Privacy & regulatory](PRIVACY_AND_REGULATORY.md)

> **Status amendment (post-implementation, docs/STATUS.md is authoritative).** Phase 1 is implemented and deviates from this spec in five known ways: (1) F4 ships the `energy-heuristic-v1-spectral` **heuristic** detector (energy/band + spectral flatness/harmonicity + breath-rhythm gates, tuned via the offline fixture evaluator), not a TFLite/YAMNet model — the `SnoreDetector` interface is the swap point; (2) F10/A8's "no INTERNET" posture was amended in Phase 3 for the Garmin pull ([PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md) §1); (3) the CI/lint checks named in F10/M0 were never built — verification is manual + unit tests so far; (4) Phase 3 slices (Garmin import, daily factor log, event labeling) landed **before** Phase 2 items; (5) a post-implementation hardening pass went beyond the F1–F10 list (interrupted-night UX with explicit finalize, session health/capture stats, per-session logs, morning notes, schema v6) — this spec remains the historical Phase 1 contract. Quality gates A3/A6/A8 and the battery/30-night dogfood gates are **untested until real-device runs** (see STATUS.md).

---

## 1. Phase 1 goal

> Start monitoring before sleep, stop it in the morning, and read a clear, honest report: how long the session ran, how much snoring was detected, which acoustic events occurred — with clips to verify, and your clinical baseline stored safely alongside.

Nothing in Phase 1 makes breathing or sleep-stage claims. Phase 1 measures what a phone microphone measures, reliably.

## 2. In scope

| # | Feature | Detail |
|---|---|---|
| F1 | **Onboarding & consent** | Medical disclaimer (full text, must accept), mic permission rationale, placement guidance (phone on nightstand, 0.5–1 m, charging), battery-optimization guidance for the specific OEM |
| F2 | **Overnight audio capture** | Foreground service (`microphone` type), AudioRecord 16 kHz mono 16-bit, ring buffer, **no full-night audio persisted** — only short event clips (default ≤ 8 s, capped top ~50/night). Pre-flight checks: ≥ 500 MB free storage, battery level, OEM optimization warning. Thermal-aware throttling |
| F3 | **Local session storage** | Room/SQLite, app-private. Session row, acoustic events, clips, derived measurements. Crash-safe: batched writes bound loss to ~30 s; interrupted sessions recoverable on next launch |
| F4 | **Basic snoring detection (swappable model)** | On-device TFLite classifier v0 (YAMNet-class model mapped to app taxonomy: snore / cough / speech / other-noise / silence). Events smoothed and de-duplicated (hysteresis, min duration, gap merge). Model loaded via ModelRegistry — swapping the model later must not require touching detection or storage code |
| F5 | **Night timeline + clip playback** | Horizontal night timeline colored by event type; tap an event to play its clip; label events (correct / not-a-snore) — labels are stored for future calibration, not used to alter scores in Phase 1 |
| F6 | **Morning report** | Session duration (time in bed), snoring summary (total time, % of night, count, intensity-tier breakdown, 0–100 snore score), acoustic event counts, top clips, per-hour chart, session-integrity notes (interruptions, dropouts). **No breathing estimates, no sleep/wake estimate beyond time-in-bed** |
| F7 | **Clinical baseline entry (read-only)** | Structured form for each HUG study night: date, lab, total sleep time, sleep efficiency, arousal index, N3 %, AHI, notes. Stored **immutable** ([DATA_MODEL.md](DATA_MODEL.md) §4). Displayed in a Baseline screen and cited in the report header as context. No comparison logic in Phase 1 |
| F8 | **Disclaimers everywhere** | First-run full text, report footer, baseline screen note, copy rules per [MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) and [PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md) |
| F9 | **Data controls** | Per-night delete (cascades events + clips), delete-all, retention defaults applied by a daily worker (clips 7 days, sessions/events 90 days; measurements persist — see data model) |
| F10 | **Privacy posture** | No `INTERNET` permission, `allowBackup=false`, no analytics, no crash-reporting SDK. Enforced by lint + CI |

## 3. Explicitly NOT in Phase 1

- **Apnea / breathing estimation of any kind** — not even the experimental (Tier 2) acoustic breathing indicators; those start in Phase 2 behind the experimental toggle once event classification is mature.
- **Sleep staging** (N1/N2/N3/REM) — never ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) M14).
- **Sleep/wake estimation** — arrives in Phase 2 with accelerometer; Phase 1 reports time-in-bed only.
- **Sonar / ultrasonic breathing** — Phase 5 at the earliest.
- **Under-mattress / accelerometer movement** — Phase 2.
- **Wearable sync, Health Connect, Garmin import** — Phase 3.
- **Lifestyle factor logging** — Phase 3.
- **Trends, correlations, clinical comparison views** — Phase 3+.
- **Export (CSV/PDF)** — Phase 3.
- **Scheduled auto-start, smart alarm, anti-snoring cues** — Phase 3+. *(Update: scheduled auto-start is now shipped as the one-tap bedtime-notification flow in the recording-controls pass — optional and off by default; smart alarm and anti-snoring cues remain out of scope.)*
- **Any networking, accounts, cloud** — no phase has a need; see privacy doc.

## 4. Functional acceptance criteria

- **A1** Onboarding requires disclaimer acceptance before mic permission is requested; acceptance recorded with a timestamp.
- **A2** Start/stop from the home screen; persistent notification shows elapsed time and a stop action; force-stop/reboot mid-night leaves a recoverable session.
- **A3** An 8-hour session completes without crash on reference devices; report renders from DB in < 1 s cold.
- **A4** Snoring events carry type, start/end, duration, peak/mean intensity (dBFS), confidence, model id/version, and (for top events) a playable clip.
- **A5** Timeline playback works with screen off-then-on, after process death, and in Doze.
- **A6** Clinical baseline entries cannot be edited or deleted from the UI after save (except delete-all), and their provenance (source = clinical, method = clinically measured, entered date) is visible.
- **A7** Delete-night and delete-all remove DB rows **and** clip files, verified by a storage probe.
- **A8** Installed APK does not request `INTERNET` (CI check); clip files live only in app-private storage.

## 5. Quality gates

| Gate | Target | Notes |
|---|---|---|
| Battery | ≤ 10 % per 8 h night on reference device (Pixel-class) | Logged nightly during dogfooding; investigate > 15 % |
| Thermal | No sustained > MODERATE thermal status | Throttle path must engage before SEVERE |
| Stability | Zero data-loss incidents across a 30-night dogfood | Recovery flow exercised intentionally at least once |
| Snoring detection (v0) | Precision ≥ 0.80, recall ≥ 0.70 on the internal labeled fixture set | Tunable defaults; fixture suite runs in CI ([Architecture](ARCHITECTURE.md) §9) |
| Clip storage | ≤ ~15 MB per typical night | Quota enforced by capture code |
| Report honesty | Every displayed number traceable to a Measurement row with provenance | Code review checklist + report snapshot schema |

## 6. Milestones (indicative)

| M | Week | Deliverable |
|---|---|---|
| M0 | 1 | Gradle scaffold, Hilt, Room schema v1, version catalog, no-INTERNET lint rule, CI (build + unit tests) |
| M1 | 1–2 | MonitorService + AudioEngine + notification; overnight soak test on real device with battery logging |
| M2 | 2–3 | TFLite v0 via ModelRegistry + FeatureExtractor + EventDetector + fixture eval harness |
| M3 | 3–4 | Clips, batched DB writes, crash recovery, pre-flight checks, thermal throttling |
| M4 | 4–5 | Timeline + clip player + user labels; morning report v1 |
| M5 | 5–6 | Clinical baseline entry (immutable), history list, settings, retention worker, delete-all |
| M6 | 6–8 | Disclaimer copy pass, 30-night dogfood, quality-gate review, release build (sideload) |

## 7. Definition of done (Phase 1)

- [ ] All acceptance criteria A1–A8 pass on the reference device.
- [ ] Quality gates met or deviations documented with cause.
- [ ] Measurement tiers M1–M4 (Tier 1) implemented exactly as specified; zero Tier-2/3 code paths present.
- [ ] Privacy checklist in [PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md) signed off.
- [ ] 30 consecutive nights of real use completed without data loss.
