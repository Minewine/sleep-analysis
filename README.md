# SleepAnalysis

A privacy-first **personal sleep intelligence platform** for Android. It records your nights with the phone microphone, measures only what a phone can credibly measure, and tracks your sleep health longitudinally.

> **Wellness and research tool — not a medical device.** It does not diagnose sleep apnea or any condition, and never presents phone-derived data as equivalent to polysomnography. See [docs/PRIVACY_AND_REGULATORY.md](docs/PRIVACY_AND_REGULATORY.md).

## What it does (and deliberately doesn't)

**Measures (Tier 1 — established):** snoring events, duration, relative intensity; acoustic events (loud noises); time in bed. Snoring duration/intensity, acoustic events, estimated sleep/wake (Phase 2+), user-logged factors.

**Explores (Tier 2 — experimental, clearly labeled):** acoustic breathing-irregularity indicators (Phase 2, behind a toggle).

**Never computes (Tier 3 — out of scope for claims):** phone-derived AHI/RDI, sleep stages, arousal index, SpO₂ desaturation. Binding classification: [docs/MEASUREMENT_RELIABILITY.md](docs/MEASUREMENT_RELIABILITY.md).

**Foundation layers:** overnight acoustic monitoring · wearable data (Garmin / Health Connect, later) · lifestyle factors · longitudinal analysis. Every stored measurement carries provenance (source, method, unit, confidence, model version).

## Phase 1 — implemented

A reliable overnight recorder with an honest morning report ([docs/MVP_SCOPE.md](docs/MVP_SCOPE.md)):

- **Overnight recording** — foreground service with persistent notification and elapsed-time chronometer, AudioRecord 16 kHz mono capture, manual start/stop, thermal throttling (analysis every 2nd hop at MODERATE+), pre-flight storage check. If the process dies overnight, the next launch shows an explicit *interrupted night* banner (with how much was saved) and finalizes only when you confirm — never silent auto-recovery, and Start stays disabled (Home and the bedtime notification) until you Resume or Finalize, so a new night can never finalize the interrupted one behind your back. A capture whose clip ring stops advancing for 60 s (wedged reader) errors into the same interrupted path instead of recording silence, and spans where Android silenced the mic (privacy toggle, a call, another app) are recorded and shown as *not captured*, never as quiet. Once a night has ended, the last-night card also shows the actual **capture window** ("Captured X – Y") — capture-start-aware after a start delay with a tap-time fallback (the report's session-health rule; a corrupt/zero capture start counts as absent, and an open night shows nothing).
- **Local storage (Room, schema v16)** — `SleepSession`, `Measurement` (full provenance: timestamp, source, method, type, unit, value, confidence, model version, `isClinical`), `AcousticEvent` (+ clip refs), ~~`ClinicalBaseline` (immutable: insert-only DAO + SQL triggers)~~ (dropped by migration 15→16 in the 2026-11-22 consolidation), plus the Garmin `raw_payload` / `sleep_stage` tables (v14: per-row stage provenance `source` — `GARMIN` estimate vs `WEARABLE` Health Connect timeline), `quiet_gap` (v10), the daily factor log, per-event clip gain/peak columns (v11), the per-night `report` snapshot + temporal `model_registry` (v12), and ~~the N-of-1 `sleep_experiment` table (v13)~~ (dropped by migration 14→15 in the 2026-11-22 consolidation); per-night and delete-all.
- **Basic acoustic analysis** — swappable `SnoreDetector` interface; shipped `energy-heuristic-v3-peakgate` (v1-spectral classification + burst-level event shaping + the ultra-quiet-room peak gate): the v0 energy/band-ratio heuristic (relative dBFS + rolling noise floor + low-band ratio) plus two spectral gates (spectral flatness + harmonic ratio via the `monitoring/features/` FFT — tonal snore energy vs broadband noise) and a breath-cycle rhythm gate (adjacent windows of one periodic train are believed; isolated spikes are not), thresholds tuned offline with the fixture evaluator on dogfood clips; event shaping (hysteresis 2-in/4-out windows, min duration, **burst-level events — no cross-draft merging, so a 300+ s "event" train is impossible; silences > 2 s split it**), per-event confidence and decision-input record (`features_json`, debug visibility — including the spectral gate means, folded into one window-count-weighted mean per event on merge), ≤ 8 s WAV clips for detected events. Clip persistence is budgeted (`ClipPolicy`): a relative level floor (mean must clear the rolling noise floor + 10 dB, never under a −60 dBFS hard floor — so silent-room rumble is downgraded to OTHER_NOISE and never clipped or labeled SNORE, and the gate self-calibrates to louder rooms) plus, only in ultra-quiet rooms (mean noise floor at/below −80 dBFS, mic-self-noise territory), an absolute peak floor (−38 dBFS) — real snores burst above it, steady breathing trains do not (session 13: −39.6…−54.3 dBFS breathing trains vs −16.3…−34.9 real snores; the tier is off in normal rooms where an absolute cutoff would eat audible snoring), +6 dB stricter during the first 30 min of capture (settling window), max 15 clips/hour so bedtime noise can't starve the night, 150/night backstop with a 25-clip loudness reserve so the loudest events always get a clip, (retention-controlled). Full-night audio is **never** persisted.
- **Morning report (Compose)** — time in bed, snore score, snoring time/share/intensity breakdown, interactive night timeline (tap → clip playback), hourly snoring bars, session-integrity notes, mandatory disclaimer card. A **session health** section shows capture statistics (analysis windows processed/skipped, clips saved, thermal throttling, capture window + start delay, ambient levels) with plain-language health warnings when the capture looks degraded, links to the per-session text log, and lets you add short **morning notes**, a 1–5 **morning rating** and **night quick tags** (`ALCOHOL` / `LATE_MEAL` / `STRESS` / `SICK` / `SLEPT_ON_BACK` — user context, never a measurement). The report also breaks the night into **bursts and bouts** (bursts chained report-time at two gap views, ≤ 5 s and ≤ 10 s) and lists **quiet stretches ≥ 10 min** inside the capture window (stored since schema v10 and classified as real quiet vs. no capture); low-confidence noise (OTHER_NOISE below 0.5 confidence) is display-filtered with an explicit note. A **TL;DR card** compares the night with the previous one (version-gated) and, on Garmin-linked nights with a real window overlap, adds the snoring share of the **captured part of Garmin's sleep window** (omitted rather than clamped when the arithmetic would exceed 100%), a **data-quality** section flags clipped/missing clips, audio coverage and Garmin linkage, and the top-bar has a bulk **Label clips** action (per-event labeling from the report works inline; the labeling screen labels a whole session's clips in one pass — [docs/EVAL_RUNBOOK.md](docs/EVAL_RUNBOOK.md)) and a **share** action that emits a plain-text summary. Definition of every number: [docs/NIGHT_REPORT.md](docs/NIGHT_REPORT.md).
- **7-night trend (Compose)** — one row per night (real nights open that night's report): snoring minutes, % of capture, longest bout, mean peak, score, clip coverage and **quiet vs no-capture minutes**, plus a minutes-scaled bar; nights with nothing recorded surface as non-clickable **missed** rows (a deliberate skip or an in-progress night never renders as missed); "detector changed" / "score formula changed" flags mark nights across which comparisons are not valid. Reached from History → Trends. Spec: [docs/TRENDS.md](docs/TRENDS.md). From schema v12 the pipeline also produces **validity-gated personal insights** (baseline bands, sleep regularity, rule-based suggestions — only nights with grade ≥ B and the current detector/score versions feed them). ~~Since v13 the guide layer added ranked evidence-graded playbook actions with runnable 7-night N-of-1 experiments (one ACTIVE at a time, verdict against your own baseline band, optional A-B-A confirmation) and a clinician-escalation card over the same eligible nights~~ — removed in the 2026-11-22 consolidation: [docs/INSIGHTS.md](docs/INSIGHTS.md).
- ~~**Clinical baseline** — structured read-only entry of study nights (date, lab, TST, efficiency, arousal index, N3 %, AHI, notes, source-document reference); corrections are new entries, never edits.~~ — removed in the 2026-11-22 consolidation (table, repository and screen dropped in migration 15→16).
- **Privacy basics** — first-launch disclaimer (must accept), mic-active indicator on Home + persistent notification, `allowBackup=false`, app-private storage only.
- **Garmin import (Phase 3 first slice)** — Settings: Personal API key (Android Keystore-encrypted) + dated import of sleep score + factor sub-scores, sleep stages, HRV, Body Battery, resting HR, overnight heart rate/respiration/SpO₂ levels (levels only — desaturation counts are never imported), sleep/daily stress, awakenings, nap time, sleep need; raw JSON stored verbatim; report shows values labeled "(Garmin estimate)". Since schema v9, imported rows are **linked to the acoustic session** whose window best overlaps Garmin's sleep window, the payload's `sleepLevels` timeline (when present) is stored in `sleep_stage`, and the report adds the vendor **sleep score** prominence, the window **overlap**, and **which stage the longest snore bout fell in**. Opening a night without Garmin data triggers a rate-limited **morning backfill** import (first syncs often run before Garmin's payload is ready). See [docs/GARMIN_INTEGRATION.md](docs/GARMIN_INTEGRATION.md) and [docs/NIGHT_REPORT.md](docs/NIGHT_REPORT.md).
- **Health Connect stage import (Phase 3 second slice)** — Settings: dated import of the **stage timeline from Health Connect** (written there by the Garmin Connect app after each watch sync) when the Personal API has none: availability gate (Android 14+ or the Health Connect app), runtime `READ_SLEEP` grant flow, silent morning backfill on report open, and per-stage provenance — `sleep_stage.source` (`GARMIN` vs `WEARABLE`) keeps the two import paths from clobbering each other, and the report labels the stage timeline by source. Totals/score/HRV/Body Battery stay Personal-API-owned; Health Connect contributes per-stage spans only. See [docs/DATA_MODEL.md](docs/DATA_MODEL.md).
- **Recording controls** — a master "Recording enabled" switch (off = the app never starts a recording, manual or scheduled; manual start stays the primary method) and an optional scheduled nightly recording (off by default): user-set start/end times fire exact system alarms (inexact fallback when the Android exact-alarm permission is missing — a ~10-minute window that Doze can stretch well beyond that on some devices), a high-priority bedtime notification with one-tap **Start recording** / **Skip tonight** actions (the tap is what grants while-in-use microphone access on Android 11+), auto-stop at the end time, and re-arming across reboots, timezone/clock changes, and settings changes. While the schedule is off, Home keeps a visible **Scheduled start is off** row with a shortcut to Settings. Every **Skip tonight** is kept in a persistent skip history, and the night before surfaces on Home as a calm **missed-night banner** (nothing recorded, or a short night with how much was captured) — a deliberate skip or a properly recorded night renders nothing.
- **Start delay** — optional capture delay (Settings → Recording: Off/15/30/45/60/90 min): after you tap start, the service runs immediately (mic access is held) but audio capture begins later, so falling-asleep noise stays out of events and clips. Time in bed still counts from the tap; the FGS notification shows a live countdown; a stop during the delay finalizes the session with zero capture.

## Device data safety

**Installing any build over an existing install signed with a different key — `./gradlew installDebug`, `connectedDebugAndroidTest`, or an Android Studio deploy — forces an uninstall first, and uninstalling destroys the app's entire private data directory:** all recorded nights, clip audio, labels, and settings. Never deploy to a device that carries real recordings without explicitly confirming the signature matches (or that data loss is acceptable). The morning-report pipeline's `--keep-db` snapshots under `~/sleep-local/` are the recovery source of last resort — they contain the database (including labels) and only the most recently exported sessions' clips, never preferences.

## Documentation

| Doc | Contents |
|---|---|
| [docs/FUNCTIONAL_SPEC.md](docs/FUNCTIONAL_SPEC.md) | **Entry point** — what the product does and how: 11 verified sections (388 requirement rows, 9 invariants) usable for AI code review and as the basis for consolidation, with a 109-candidate consolidation map |
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
| [docs/NIGHT_REPORT.md](docs/NIGHT_REPORT.md) | How the morning summary, Garmin linking, trend and burst/bout numbers are produced — sources, constants, limitations |
| [docs/TRENDS.md](docs/TRENDS.md) | The last-N-nights trend: row fields and sources, eligibility, version-change markers, missed-night rows, quiet totals |
| [docs/INSIGHTS.md](docs/INSIGHTS.md) | Personal baseline (median+MAD), sleep regularity (SRI/SJL), the rule catalog, confidence tiers and never-claims |
| [docs/EVIDENCE_BASE.md](docs/EVIDENCE_BASE.md) | Every encodable parameter: value, citation, evidence strength, allowed-claim language — machine-checked against the code |
| [docs/EVAL_RUNBOOK.md](docs/EVAL_RUNBOOK.md) | Detector eval loop: labeling → fixture eval → tuning, with the recorded precision/recall history |
| [docs/DOGFOOD_CHECKLIST.md](docs/DOGFOOD_CHECKLIST.md) | Per-night dogfood checklist |
| [docs/IDEAS_BACKLOG.md](docs/IDEAS_BACKLOG.md) | Sequenced improvement ideas |
| [docs/HANDOFF.md](docs/HANDOFF.md) | Developing this repo with an AI coding agent: orientation, non-negotiables, ready-to-run task prompts |
| [docs/REVIEW_2026-11-04.md](docs/REVIEW_2026-11-04.md) | Full-system audit findings and fix record |
| [docs/CONSOLIDATION_PLAN.md](docs/CONSOLIDATION_PLAN.md) | The 2026-11-22 consolidation plan (task-level removals and keeps) |

## Tech stack

Kotlin 2.0 · Jetpack Compose (Material 3) · MVVM/Clean · Room + DataStore · Hilt · AudioRecord in a `microphone`-type foreground service · WorkManager (retention) · min SDK 30 / target 35. **Network: exactly one purpose** — user-triggered Garmin Personal API pulls (single host, read-only; [docs/GARMIN_INTEGRATION.md](docs/GARMIN_INTEGRATION.md)). Audio never leaves the device. Rationale: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §1.

## Building and running Phase 1

Prerequisites:

- Android Studio (current stable) with bundled JDK 17+, **or** JDK 17+ + Android SDK with platform 35 and build-tools 35
- A real device with a microphone on **Android 11+** — overnight capture cannot be tested on an emulator

CI (GitHub Actions) runs the same gates on every PR: assemble, unit tests, Android lint, permission/forbidden-terms/hygiene/network-host checks, the instrumented Room migration test on a KVM emulator, ruff, pytest.

```bash
git clone <repo-url> && cd sleep-analysis
./gradlew :app:assembleDebug        # debug APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # unit tests (detector, event shaping, scoring, claim policy, report, capture stats, session logger, WAV)
./gradlew :app:connectedDebugAndroidTest  # Room migration test (needs a device or emulator)
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
├── docs/                     # specification set
├── analysis/                 # offline per-night analysis script (night_report.py, env-configured)
├── tools/                    # Python helpers: fixture export, clip-loudness ranking (pytest-tested)
└── app/src/main/java/com/sleepanalysis/
    ├── monitoring/           # service (FGS), audio engine, detectors, event shaping, scheduled recording
    │   └── features/         # FFT + spectral features (flatness, harmonicity) feeding the detector gates
    ├── domain/               # claim policy (tiers), scoring, report assembly, night-date anchoring — pure Kotlin
    ├── data/                 # Room entities/DAOs, repositories, MeasurementRecorder, clip store, session logs, prefs
    ├── ui/                   # Compose screens: onboarding, home, report (+ session log), history, insights, trends, settings
    ├── di/                   # Hilt module (provides the swappable SnoreDetector)
    └── work/                 # RetentionWorker

app/src/test/java/com/sleepanalysis/    # eval-only sourceset (moved out of main in the 2026-11-22 consolidation)
└── monitoring/
    ├── replay/               # offline clip replay, feature-for-feature identical to the live loop
    └── eval/                 # FixtureEvaluator: labeled clips → per-class precision/recall
```

**Python tooling** (`uv`-managed; stdlib-first runtime, pytest/ruff as dev-only deps — `pyproject.toml`): `tools/` provides adb export/eval helpers; `analysis/night_report.py` is an offline per-night analysis script — configured entirely through environment variables, reads CSVs exported from the pulled session DB as untrusted input (`python -I`), computes union-aware snore time, bouts and the hourly distribution, compares against the previous night, and renders matplotlib PNG charts. `tools/backup_db.sh [serial]` snapshots the app's database (db + wal + shm) off the phone via adb run-as into `$SLEEP_BACKUP_ROOT` (default `~/sleep-local/backups`)/<timestamp>/, validates the copy (quick_check + session count) and prunes to the newest 30 snapshots — exits 0 with a note when no device is attached. Night data and outputs live outside the repo and are never committed.

Key swap point for Phase 2: `di/AppModule.provideSnoreDetector()` — replace the `energy-heuristic-v3-peakgate` heuristic with a TFLite-backed `SnoreDetector`; capture, storage, and reports are untouched.

## Known limitations (Phase 1, by design)

- **Detection quality is heuristic, not learned.** `energy-heuristic-v2-burst` added spectral flatness/harmonicity and breath-rhythm gates on top of the v0 energy/band heuristic, which removed the broadband-household-noise false positives seen in dogfooding; `v3-peakgate` added an absolute peak floor for ultra-quiet rooms, where every relative threshold loses meaning (mic self-noise floor) and steady breathing shipped as SNORE. Speech close to the phone is also tonal and rhythmic, and very distant snoring may be missed. Clip playback + event labels exist so you can verify; labels feed Phase 2 calibration. Fixture-based precision/recall targets (≥ 0.80 / ≥ 0.70) are a Phase 2 gate, measured so far only on the small dogfood label set, not met/formally validated.
- **Only snoring and loud-noise events.** Cough/speech classification needs the Phase 2 model; the schema already supports them.
- **No sleep/wake estimate.** Time in bed (session bounds) only — movement-based estimation arrives with the accelerometer in Phase 2.
- **No breathing indicators at all** — not even experimental ones (deliberately deferred to Phase 2 behind the experimental toggle).
- **Report view is computed live; a compact headline snapshot is stored.** The full report derives live from events (they exist for the 90-day retention window); since schema v12 finalize also writes a compact headline snapshot + model-version registry rows (docs/DATA_MODEL.md §3.7/§3.8) for later analysis, never as a second source of truth.
- **Clip timestamps have ~0.5 s granularity** and clip retention covers the last 8 s of each event.
- **A partial wake lock is held all night** (reliability over battery; see MonitorService docs). Quality gate: ≤ 10 %/8 h — measure in dogfood and drop the lock if the audio path alone proves sufficient.
- **Retention settings are stored but not yet editable** — `DataStore` holds clips/sessions retention (defaults: clips 7 days, sessions 90 days) and the worker reads it; the settings UI is still pending. Per-night and delete-all exist; there is **no user-triggered clips-only purge** — only the daily retention worker removes expired clips.
- **Device/reroute handling is basic.** A mid-night audio-device change (headset unplug) is not actively detected; capture errors finalize the session as interrupted. Process death surfaces an explicit interrupted-night banner on next launch — finalization is user-confirmed, a recovered report covers only up to the last saved event, and a new start while a night is interrupted is refused (Home button disabled; the bedtime notification is not posted) rather than silently finalizing the open night RECOVERED. A silenced or dead microphone never reads as a quiet night: OS-silenced spans and digital-silence level buckets classify as *not captured* (stats schema v6), and a stalled capture loop (no ring progress for 60 s) finalizes the night INTERRUPTED. If the system restarts the killed service (START_STICKY), capture does **not** silently auto-resume: on Android 11+ a background restart has no while-in-use microphone access (it would record silence), so the service posts a high-priority *recording interrupted* notification whose **Resume** action (a user tap — the interaction that grants mic access) restarts capture into the same session with stats carried over; if you never tap it, the Home interrupted-night banner handles finalize.
- **Capture statistics only exist for sessions recorded after the hardening pass.** Sessions finalized as RECOVERED (engine died with the process) keep whatever progress the 15-minute heartbeat persisted; with no heartbeat saved, the report says so instead of showing warnings.
- **Schema history.** Room schema export is on; the DB has evolved 1→16 (all migrations real except a declaration-only v8: raw payloads, factor log, event features JSON, morning notes, interruption flag, sleep-stage timeline, quiet gaps, clip gain/peak columns, report snapshots + model registry, Health Connect stage provenance — and, in the 2026-11-22 consolidation, removal of the sleep-experiment and clinical-baseline tables plus a bundle of write-only columns) and upgrades in place, covered by an instrumented `MigrationTest` that CI runs on a KVM emulator.
- **Garmin re-import replaces per-metric.** Re-importing a night deletes and rewrites only the metrics whose endpoints succeeded in that run (a partial import never wipes previously imported metrics); duplicate rows from double-tapping Import are prevented by design.
- **Scheduled recording is off by default and alarm-based.** Force-stopping the app cancels its alarms (they re-arm on the next app open or settings change); Android 14+ requires the exact-alarm permission to be granted, or the schedule falls back to an inexact window that Doze can stretch well beyond its ~10 minutes on some devices.
- **The missed-night verdict is evaluated when its inputs change, not on the clock.** A Home screen foregrounded across midnight keeps showing yesterday's verdict until the session list or the skip history changes — it self-heals when the flow re-subscribes after 5 s (`WhileSubscribed(5_000)`); the trend's missed-night span has a mirrored timezone edge (docs/TRENDS.md).

## License

TBD before any code is published (personal project; consider proprietary-by-default or a permissive license if open-sourcing the recorder core).
