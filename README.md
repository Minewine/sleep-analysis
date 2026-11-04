# SleepAnalysis

A privacy-first **personal sleep intelligence platform** for Android. It records your nights with the phone microphone, measures only what a phone can credibly measure, and tracks your sleep health longitudinally against an immutable clinical baseline (HUG sleep studies, January 2026).

> **Wellness and research tool — not a medical device.** It does not diagnose sleep apnea or any condition, and never presents phone-derived data as equivalent to polysomnography. See [docs/PRIVACY_AND_REGULATORY.md](docs/PRIVACY_AND_REGULATORY.md).

## What it does (and deliberately doesn't)

**Measures (Tier 1 — established):** snoring events, duration, relative intensity; acoustic events (loud noises); time in bed. Snoring duration/intensity, acoustic events, estimated sleep/wake (Phase 2+), user-logged factors.

**Explores (Tier 2 — experimental, clearly labeled):** acoustic breathing-irregularity indicators (Phase 2, behind a toggle).

**Never computes (Tier 3 — out of scope for claims):** phone-derived AHI/RDI, sleep stages, arousal index, SpO₂ desaturation. Binding classification: [docs/MEASUREMENT_RELIABILITY.md](docs/MEASUREMENT_RELIABILITY.md).

**Foundation layers:** immutable clinical baseline · overnight acoustic monitoring · wearable data (Garmin / Health Connect, later) · lifestyle factors · longitudinal analysis. Every stored measurement carries provenance (source, method, unit, confidence, model version).

## Phase 1 — implemented

A reliable overnight recorder with an honest morning report ([docs/MVP_SCOPE.md](docs/MVP_SCOPE.md)):

- **Overnight recording** — foreground service with persistent notification and elapsed-time chronometer, AudioRecord 16 kHz mono capture, manual start/stop, thermal throttling (analysis every 2nd hop at MODERATE+), pre-flight storage check. If the process dies overnight, the next launch shows an explicit *interrupted night* banner (with how much was saved) and finalizes only when you confirm — never silent auto-recovery.
- **Local storage (Room)** — `SleepSession`, `Measurement` (full provenance: timestamp, source, method, type, unit, value, confidence, model version, `isClinical`), `AcousticEvent` (+ clip refs), `ClinicalBaseline` (immutable: insert-only DAO + SQL triggers), per-night and delete-all.
- **Basic acoustic analysis** — swappable `SnoreDetector` interface; shipped v0 is a documented energy/band-ratio heuristic (relative dBFS + rolling noise floor + low-band ratio), event shaping (hysteresis, min duration, gap merging), per-event confidence and decision-input record (`features_json`, debug visibility), ≤ 8 s WAV clips for detected events. Clip persistence is budgeted (`ClipPolicy`): a relative level floor (mean must clear the rolling noise floor + 10 dB, never under a −60 dBFS hard floor — so silent-room rumble is downgraded to OTHER_NOISE and never clipped or labeled SNORE, and the gate self-calibrates to louder rooms), +6 dB stricter during the first 30 min of capture (settling window), max 8 clips/hour so bedtime noise can't starve the night, 50/night backstop (retention-controlled). Full-night audio is **never** persisted.
- **Morning report (Compose)** — time in bed, snore score, snoring time/share/intensity breakdown, interactive night timeline (tap → clip playback), hourly snoring bars, session-integrity notes, mandatory disclaimer card, read-only clinical baseline context. A **session health** section shows capture statistics (analysis windows processed/skipped, clips saved, thermal throttling) with plain-language health warnings when the capture looks degraded, links to the per-session text log, and lets you add short **morning notes** (user context, never a measurement).
- **Clinical baseline** — structured read-only entry of study nights (date, lab, TST, efficiency, arousal index, N3 %, AHI, notes, source-document reference); corrections are new entries, never edits.
- **Privacy basics** — first-launch disclaimer (must accept), mic-active indicator on Home + persistent notification, `allowBackup=false`, app-private storage only.
- **Garmin import (Phase 3 first slice)** — Settings: Personal API key (Android Keystore-encrypted) + dated import of sleep score + factor sub-scores, sleep stages, HRV, Body Battery, resting HR, overnight heart rate/respiration/SpO₂ levels (levels only — desaturation counts are never imported), sleep/daily stress, awakenings, nap time, sleep need; raw JSON stored verbatim; report shows values labeled "(Garmin estimate)". See [docs/GARMIN_INTEGRATION.md](docs/GARMIN_INTEGRATION.md).
- **Recording controls** — a master "Recording enabled" switch (off = the app never starts a recording, manual or scheduled; manual start stays the primary method) and an optional scheduled nightly recording (off by default): user-set start/end times fire exact system alarms (inexact fallback when the Android exact-alarm permission is missing — a ~10-minute window that Doze can stretch well beyond that on some devices), a high-priority bedtime notification with one-tap **Start recording** / **Skip tonight** actions (the tap is what grants while-in-use microphone access on Android 11+), auto-stop at the end time, and re-arming across reboots, timezone/clock changes, and settings changes.
- **Start delay** — optional capture delay (Settings → Recording: Off/15/30/45/60/90 min): after you tap start, the service runs immediately (mic access is held) but audio capture begins later, so falling-asleep noise stays out of events and clips. Time in bed still counts from the tap; the FGS notification shows a live countdown; a stop during the delay finalizes the session with zero capture.

## Documentation

| Doc | Contents |
|---|---|
| [docs/PRODUCT_VISION.md](docs/PRODUCT_VISION.md) | Positioning, target user, 3/12-month success definition, non-goals |
| [docs/MEASUREMENT_RELIABILITY.md](docs/MEASUREMENT_RELIABILITY.md) | **Binding** measurement tier system — what we claim, explore, or never compute |
| [docs/MVP_SCOPE.md](docs/MVP_SCOPE.md) | Phase 1 scope, acceptance criteria, quality gates, milestones |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Stack, layers, capture pipeline, provenance enforcement, extension points |
| [docs/DATA_MODEL.md](docs/DATA_MODEL.md) | Entities, provenance schema, clinical immutability, retention rules |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Five phases with effort and success criteria |
| [docs/STATUS.md](docs/STATUS.md) | Implemented vs documented vs unverified — the honest current state |
| [docs/NEXT_STEPS.md](docs/NEXT_STEPS.md) | Prioritized plan; P0 = first overnight test checklist |
| [docs/PRIVACY_AND_REGULATORY.md](docs/PRIVACY_AND_REGULATORY.md) | On-device rules, deletion controls, disclaimer copy, Swiss/EU MDR notes |
| [docs/GARMIN_INTEGRATION.md](docs/GARMIN_INTEGRATION.md) | Garmin Personal API: auth, endpoints, mapping, security, verification checklist |
| `docs/Sleep_App_Proposal_Michael_the app author.docx` | Original proposal — refined by the docs above |

## Tech stack

Kotlin 2.0 · Jetpack Compose (Material 3) · MVVM/Clean · Room + DataStore · Hilt · AudioRecord in a `microphone`-type foreground service · WorkManager (retention) · min SDK 30 / target 35. **Network: exactly one purpose** — user-triggered Garmin Personal API pulls (single host, read-only; [docs/GARMIN_INTEGRATION.md](docs/GARMIN_INTEGRATION.md)). Audio never leaves the device. Rationale: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §1.

## Building and running Phase 1

Prerequisites:

- Android Studio (current stable) with bundled JDK 17+, **or** JDK 17+ + Android SDK with platform 35 and build-tools 35
- A real device with a microphone on **Android 11+** — overnight capture cannot be tested on an emulator

```bash
git clone <repo-url> && cd sleep-analysis
./gradlew :app:assembleDebug        # debug APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # unit tests (detector, event shaping, scoring, claim policy, report, capture stats, session logger, WAV)
uv run pytest                       # Python tooling tests (tools/; uv-managed env, see pyproject.toml)
```

Or open the project in Android Studio and press Run.

On the device:

1. Install the APK (sideload; there is no Play release).
2. Read and accept the first-launch disclaimer; grant microphone (+ notification) permission.
3. Follow the placement hint: phone on the nightstand, 0.5–1 m from your head, plugged in.
4. **Recommended:** when the Home screen shows the battery-optimization hint, open battery settings and exclude SleepAnalysis — aggressive OEM power management is the main overnight-recording risk.
5. Tap *Start night recording*; a persistent notification with a live timer confirms the microphone is active. Stop in the morning — you land directly on the report.

## Project structure

```
sleep-analysis/
├── README.md                 ← you are here
├── docs/                     # specification set + original proposal + HUG PDFs
└── app/src/main/java/com/sleepanalysis/
    ├── monitoring/           # service (FGS), audio engine, detectors, event shaping, scheduled recording
    ├── domain/               # claim policy (tiers), scoring, report assembly — pure Kotlin
    ├── data/                 # Room entities/DAOs, repositories, MeasurementRecorder, clip store, session logs, prefs
    ├── ui/                   # Compose screens: onboarding, home, report (+ session log), baseline, history, factor log, settings
    └── work/                 # RetentionWorker
```

Key swap point for Phase 2: `di/AppModule.provideSnoreDetector()` — replace the energy heuristic with a TFLite-backed `SnoreDetector`; capture, storage, and reports are untouched.

## Known limitations (Phase 1, by design)

- **Detection quality is v0.** The energy/band heuristic has known false-positive modes (speech near the phone, broadband household noise) and may miss distant snoring. Clip playback + event labels exist so you can verify; labels feed Phase 2 calibration. Fixture-based precision/recall targets (≥ 0.80 / ≥ 0.70) are a Phase 2 gate, not yet met/measured.
- **Only snoring and loud-noise events.** Cough/speech classification needs the Phase 2 model; the schema already supports them.
- **No sleep/wake estimate.** Time in bed (session bounds) only — movement-based estimation arrives with the accelerometer in Phase 2.
- **No breathing indicators at all** — not even experimental ones (deliberately deferred to Phase 2 behind the experimental toggle).
- **Report is computed, not snapshotted.** Reports are derived live from events (they exist for the 90-day retention window); the persisted Report-snapshot table from docs/DATA_MODEL.md §3.7 is deferred.
- **Clip timestamps have ~0.5 s granularity** and clip retention covers the last 8 s of each event.
- **A partial wake lock is held all night** (reliability over battery; see MonitorService docs). Quality gate: ≤ 10 %/8 h — measure in dogfood and drop the lock if the audio path alone proves sufficient.
- **Retention settings are stored but not yet editable** — `DataStore` holds clips/sessions retention (defaults: clips 7 days, sessions 90 days) and the worker reads it; the settings UI is still pending; per-night and delete-all exist.
- **Device/reroute handling is basic.** A mid-night audio-device change (headset unplug) is not actively detected; capture errors finalize the session as interrupted. Process death surfaces an explicit interrupted-night banner on next launch — finalization is user-confirmed, and a recovered report covers only up to the last saved event. If the system restarts the killed service (START_STICKY), capture does **not** silently auto-resume: on Android 11+ a background restart has no while-in-use microphone access (it would record silence), so the service posts a high-priority *recording interrupted* notification whose **Resume** action (a user tap — the interaction that grants mic access) restarts capture into the same session with stats carried over; if you never tap it, the Home interrupted-night banner handles finalize.
- **Capture statistics only exist for sessions recorded after the hardening pass.** Sessions finalized as RECOVERED (engine died with the process) keep whatever progress the 15-minute heartbeat persisted; with no heartbeat saved, the report says so instead of showing warnings.
- **Schema history.** Room schema export is on; the DB has evolved 1→7 via real migrations (raw payloads, factor log, label timestamps, event features JSON, morning notes) and upgrades in place.
- **Garmin re-import replaces per-metric.** Re-importing a night deletes and rewrites only the metrics whose endpoints succeeded in that run (a partial import never wipes previously imported metrics); duplicate rows from double-tapping Import are prevented by design.
- **Scheduled recording is off by default and alarm-based.** Force-stopping the app cancels its alarms (they re-arm on the next app open or settings change); Android 14+ requires the exact-alarm permission to be granted, or the schedule falls back to an inexact window that Doze can stretch well beyond its ~10 minutes on some devices.

## License

TBD before any code is published (personal project; consider proprietary-by-default or a permissive license if open-sourcing the recorder core).
