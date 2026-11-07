# Architecture — SleepAnalysis

Android platform for personal sleep tracking. All audio processing is on-device; every stored measurement carries provenance; the UI can only claim what the measurement tiers allow.

Related: [Measurement reliability](MEASUREMENT_RELIABILITY.md) · [MVP scope](MVP_SCOPE.md) · [Data model](DATA_MODEL.md) · [Roadmap](ROADMAP.md) · [Privacy & regulatory](PRIVACY_AND_REGULATORY.md)

---

## 1. Recommended stack

| Layer | Choice | Rationale |
|---|---|---|
| Language | **Kotlin 2.x** | Coroutines/Flow for the capture pipeline; native FGS/audio APIs |
| UI | **Jetpack Compose** + Material 3 | Single-activity, state-driven report/timeline screens |
| Architecture | **MVVM + Clean Architecture** (ui → domain → data/platform) | Keeps claim policy and scoring logic testable, independent of Android |
| DI | **Hilt** | Standard, compile-time checked |
| Persistence | **Room** (SQLite, WAL) + **DataStore** (preferences) | Relational data with migrations; typed prefs |
| Audio capture | **AudioRecord** (16 kHz mono, 16-bit PCM) | Sufficient for acoustic event models; low-latency not required; Oboe (C++) rejected as overkill |
| ML runtime | **TensorFlow Lite / LiteRT** | YAMNet-class models available off the shelf; int8 quantization; ONNX Runtime Mobile is the fallback if a future model trains better in PyTorch — the `ClassifierEngine` interface isolates this choice |
| Background | **Foreground Service** (`microphone` type) + **WorkManager** | Overnight capture; retention/maintenance jobs |
| Charts | Custom Compose (Canvas) | Timeline, hourly bars and trend charts drawn directly — no chart library dependency |
| Min SDK | **30 (Android 11)** | Mic foreground-service type exists since API 30 → single code path; covers the vast majority of devices. Target SDK: current Play requirement (35 at time of writing) |
| Networking | **Garmin Personal API pull only** (Phase 3 first slice) | One host (`personal.connectapi.garmin.com`), user-triggered, read-only, stdlib `HttpURLConnection` — no third-party network dependency; rules in [GARMIN_INTEGRATION.md](GARMIN_INTEGRATION.md) §8 |

Pin exact versions in `gradle/libs.versions.toml` at scaffold time.

## 2. Design principles

1. **On-device or nothing.** Audio and health data never leave the device; the **only** network code path is the user-triggered Garmin Personal API pull to a single allowlisted host (`personal.connectapi.garmin.com`, read-only — [GARMIN_INTEGRATION.md](GARMIN_INTEGRATION.md) §8). Any new networked host or background transfer requires a privacy review and a deliberate amendment first; the planned CI permission check fails if the merged manifest gains anything unexpected.
2. **Provenance by construction.** There is exactly one write path for measurements ([§6](#6-provenance-enforcement-on-every-measurement)); provenance fields are constructor-required, not optional.
3. **Claims are gated, not self-policed.** UI renders only what `ClaimPolicy` (domain layer, mirrors [MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §2) permits. Tier-3 measurements have no code path at all.
4. **Degrade, don't fail.** A partial night reported honestly beats a crashed session; every failure mode has a defined partial-data outcome.
5. **Swappable intelligence.** Model changes must not require changes to detection, storage, or report logic (interfaces below).
6. **Simple first.** Single Gradle module for Phase 1; split only when test boundaries demand it.

## 3. Layers

```
┌────────────────────────────────────────────────────────────┐
│ UI (Compose)      Onboarding · Home · Report (+ Timeline,  │
│                   Session log) · History · Baseline ·      │
│                   Daily log · Settings                     │
│                   ── ViewModels (state, one per screen) ── │
├────────────────────────────────────────────────────────────┤
│ DOMAIN            ClaimPolicy (tier gating) · Scoring      │
│                   (snore score, indices) · InsightRules ·  │
│                   ReportAssembler · UseCases               │
├────────────────────────────────────────────────────────────┤
│ DATA              Repositories · Room DAOs/entities ·      │
│                   MeasurementRecorder · ModelRegistry ·    │
│                   DataStore settings                       │
├────────────────────────────────────────────────────────────┤
│ PLATFORM          MonitorService (FGS) · AudioEngine ·     │
│                   FeatureExtractor · ClassifierEngine ·    │
│                   EventDetector · RetentionWorker          │
└────────────────────────────────────────────────────────────┘
```

Package layout (single module `:app`):

```
app/src/main/java/com/sleepanalysis/
├── monitoring/
│   ├── service/     # MonitorService, session state machine (MonitorState), notifications
│   ├── audio/       # AudioEngine (AudioRecord, ring buffer, clip slicing, NightStats)
│   ├── detection/   # EventDetector (hysteresis, merging, tiers) + EventFeatures (decision record)
│   ├── schedule/    # RecordingScheduler (AlarmManager) + bedtime notification + start/stop/boot receivers
│   ├── features/    # FFT + spectral features (flatness, harmonicity) feeding the v1 detector (see §4)
│   ├── replay/      # offline clip replay, feature-for-feature identical to the live capture loop
│   ├── eval/        # FixtureEvaluator: labeled clips → per-class precision/recall (detector tuning)
│   └── ml/          # ClassifierEngine interface, TFLite impl, ModelRegistry — planned, Phase 2 (see §4)
├── domain/
│   ├── policy/      # ClaimPolicy, MeasurementTier — single source of claim rules
│   ├── scoring/     # SnoreScore, derived indices (pure functions)
│   └── report/      # ReportAssembler, CaptureStats parsing (+ InsightRules later)
├── data/
│   ├── db/          # Room entities, DAOs, migrations (schema v8)
│   ├── files/       # ClipStore (app-private clip files)
│   ├── logs/        # SessionLogger (per-session text logs)
│   ├── garmin/      # GarminClient, parsers, import manager (Phase 3 slice)
│   ├── secure/      # GarminKeyStore (Keystore-encrypted API key)
│   ├── prefs/       # UserPreferences (DataStore: disclaimer, retention, debug toggle)
│   ├── recorder/    # MeasurementRecorder — the ONLY measurement write path
│   └── repo/        # SessionRepository, BaselineRepository, FactorLogRepository
├── ui/              # Screens, ViewModels, components (timeline, charts, player, session log)
└── work/            # RetentionWorker, (later) export/analysis workers
```

## 4. Runtime components

| Component | Responsibility | Threading |
|---|---|---|
| **MonitorService** | Foreground service, `foregroundServiceType="microphone"`. Owns the session state machine `Idle → Starting → Recording (or Delayed during the start delay) → Finished`, with `StartError` and `Interrupted` states (exposed via `MonitorStateHolder`); pre-flight checks; ongoing notification; heartbeat watchdog; binds to AudioEngine. Started only from user action | Main + service scope |
| **AudioEngine** | AudioRecord lifecycle, 16 kHz mono PCM into an 8 s ring buffer on a dedicated thread. Two consumers: window slicing for detection, clip flush (last ≤ 8 s) when the EventDetector marks an event. Raw audio outside events is discarded. Accumulates per-night `NightStats` (windows processed/skipped, ambient dBFS median/P95, clip count, thermal events) surfaced as session `capture_stats` | Capture thread + buffered channels |
| **FeatureExtractor** | Window → log-mel spectrogram (model-spec driven: window 1 s, hop 0.5 s, 64 mel bins for the v0 model) | Inference dispatcher |
| **ClassifierEngine** | Interface; TFLite implementation loads the active model from ModelRegistry. Outputs per-window class probabilities. Windows are queued and **coalesced** — if inference falls behind, drop oldest, never backlog | Single-threaded dispatcher |
| **EventDetector** | Per-window snore/loud decisions → discrete events: window-count hysteresis (2 consecutive active 0.5 s hops to open, 4 consecutive quiet hops to close; the debug toggle relaxes the open count) → min duration 2 s → **no cross-draft merging (v2-burst): any internal silence > 2 s splits the chain, so one event = one burst; train-level grouping lives in report-time bouts (≤5/≤10 s)** → intensity tiering (relative dBFS bands) | Same dispatcher |
| **MeasurementRecorder** | The only component allowed to write Measurement rows; stamps full provenance (see §6) | Caller scope |
| **ReportAssembler** | Session + events + measurements → `MorningReport` (computed live; the §3.7 snapshot table is deferred) via Scoring and ClaimPolicy. Pure domain logic | Any |
| **SessionLogger** | Append-only, no-throw text log per session (`filesDir/logs/session_<id>.log`) written from the service and engine; viewable in the report's Session log screen; purged by RetentionWorker with session retention | Caller scope (background dispatchers) |
| **RetentionWorker** | Daily WorkManager job: delete expired clips, then expired sessions/events per DataStore retention settings; purge session logs past session retention; orphan-file sweep | WorkManager |
| **RecordingScheduler** | Arms/cancels the two nightly alarms (bedtime start, end time) via AlarmManager — exact (`setExactAndAllowWhileIdle`) when the exact-alarm permission is granted, inexact `setWindow` (~10 min) otherwise; start and end computed independently so both stay armed after the start fires; honors the one-night skip marker; re-armed on settings changes, boot, and after every start/stop receiver run | Caller scope (coroutines) |
| **Scheduled start/stop receivers** | Start receiver posts the high-priority bedtime notification (Start/Skip actions) — it never starts the mic FGS from the background, because a background start gets no while-in-use mic access on Android 11+; the notification tap is the user-interaction exemption. Stop receiver ends an active recording via the running service (plain `startService`, catches `IllegalStateException`); both re-arm afterwards. Boot receiver only re-arms | goAsync + short coroutine |
| **Recovery flow** | On app start: open sessions (`ended_at = null`) surface an **interrupted-night banner** (with saved-event count); finalization runs only on explicit user confirmation and is guarded so two sessions can never be open at once | App start (HomeViewModel) |

## 5. Data flow (text description)

**Overnight capture flow (the core path):**

1. User taps Start → pre-flight checks (storage, permissions) → session row created (`ended_at = null`) → service promotes to foreground with notification.
2. AudioEngine streams PCM into the ring buffer. Nothing here touches disk for full-night audio — only clip slices ever persist.
3. Every 0.5 s hop, a 1 s window is sliced → features → per-window snore probability. With the shipped `energy-heuristic-v2-burst` the features (RMS, low-band ratio, rolling noise floor, plus spectral flatness and harmonic ratio from `monitoring/features/`) are computed inline (`EventFeatures`, persisted per event as `features_json`) and the "classifier" is the `SnoreDetector` interface; Phase 2 swaps in log-mel + TFLite (`ClassifierEngine`) without touching steps 2 or 4.
4. EventDetector turns probability streams into discrete AcousticEvents with intensity and confidence; qualifying events trigger a clip flush from the ring buffer to `filesDir/clips/<session>/`. Clip persistence is gated by `ClipPolicy`: events below the relative level gate (mean under the event's own rolling noise floor + `DetectorConfig.GATE_MARGIN_DB`, 10 dB — never below the hard floor `MIN_GATE_DBFS`, −60 dBFS, so near-silence stays gated; unverifiable by playback) never persist a clip; for the first 30 min of capture the margin is +6 dB stricter (settling window — bedtime noise is the night's loudest period); a per-hour budget (`MAX_CLIPS_PER_HOUR`) stops bedtime noise from spending the whole night's clips (night 1 wrote all 50 in the first 42 min); and `MAX_CLIPS_PER_NIGHT` remains the backstop. Sub-gate SNORE detections are downgraded to OTHER_NOISE at finalization, so the SNORE label only ever applies to clearly audible events.
5. Events are written immediately per finalized event (one transaction covering the event row and its clip row, so process death can never leave one without the other).
6. On stop: session finalized — Measurement rows written through MeasurementRecorder, the engine's `NightStats` persisted as session `capture_stats`, per-session log closed. The morning report is assembled live on open (no snapshot table yet). Notification clears.

**Recovery flow:** process death or reboot mid-night → next launch, the Home screen finds the open session and shows an explicit **interrupted-night banner** (with the saved-event count) → the user confirms "Finalize & view report" → `finalizeOpenAsRecovered()` finalizes every open session as RECOVERED (guard: a new recording can never start while one is open) → report generated from whatever events were durably written, with `ended_at` bounded by the later of the last saved event or the last heartbeat (+1 min grace) so the dead gap doesn't inflate time in bed. If the system restarts the killed service (START_STICKY), capture does **not** auto-resume — on Android 11+ a background restart has no while-in-use microphone access, so `StartIntentPolicy` ignores the null-intent restart and a high-priority interrupted notification offers a **Resume** action; when the user taps it (the interaction that grants mic access), capture restarts into the same session, the row is marked `resumed_after_interruption` (schema v7), and the morning report leads its integrity notes with the discontinuity — the night was never continuously captured.

**Failure paths:** storage pressure → stop clip capture first, keep event stream, degrade report; thermal MODERATE+ → every 2nd analysis hop is skipped (windows still captured; the skipped spans are recorded in `capture_stats` so coverage can be corrected); permission revoked mid-run → stop gracefully with a notification.

**Why this shape:** the capture path is a strict pipeline with one writable sink (MeasurementRecorder + ClipStore). Nothing downstream (report, trends, future analysis) ever reads raw audio — it reads events and measurements. That is what makes models swappable and claims auditable.

## 6. Provenance enforcement on every measurement

Every Measurement row carries (see [DATA_MODEL.md](DATA_MODEL.md) for the full schema):

`source_type` (CLINICAL / PHONE / WEARABLE / USER) · `method` (CLINICAL_MEASURED / ALGORITHM_ESTIMATED / DEVICE_REPORTED / USER_REPORTED) · `measurement_type` + `unit` · `value` · `confidence` (nullable) · `tier` (ESTABLISHED / EXPERIMENTAL) · `model_version` (for algorithmic values) · timestamps.

Enforcement mechanisms:

1. **Single write path.** `MeasurementRecorder.record(spec)` takes a constructor that requires every provenance field; the Room DAO for measurements is `internal` to the data layer. No screen, worker, or service touches it directly.
2. **Tier comes from ClaimPolicy.** `ClaimPolicy.tierFor(measurementType)` is the code mirror of [MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §2; a unit test asserts the two tables are identical. The recorder refuses to write a measurement whose type is Tier 3 (which is also why Tier 3 has no code path).
3. **Clinical immutability.** Baseline entries are insert-only (no update/delete DAO methods for clinical rows) plus a SQL trigger that aborts `UPDATE`/`DELETE` on `method = 'CLINICAL_MEASURED'` rows outside the explicit delete-all maintenance path ([DATA_MODEL.md](DATA_MODEL.md) §4).
4. **Model binding.** Every algorithmic event/measurements stores `model_id`/`model_version` resolved from ModelRegistry at write time, so reprocessing or recalibration can be traced and re-run later.
5. **Report gating.** ReportAssembler includes a measurement in the report payload only if `ClaimPolicy` allows that tier in the current UI mode; Tier-2 values additionally require the experimental toggle. Exports inherit the same filter.
6. **CI checks — shipped** (GitHub Actions, `.github/workflows/ci.yml`; the same four gates run locally as Gradle tasks in `:app:check`): (a) **`checkPermissionAllowlist`** — the merged debug manifest must match `ci/permission-allowlist.txt` exactly; (b) **`checkForbiddenTerms`** — forbidden clinical terms ("AHI", "apnea", "arousal index", "sleep stage", "desaturation") fail the build outside the approved disclaimer and clinical-baseline label strings; (c) **`checkRepoHygiene`** — no tracked file matches the personal-data patterns (mirrors `.githooks/pre-commit`); (d) **`checkNetworkHosts`** — URL hosts in main sources are limited to the single Garmin host.

## 7. Separation of raw audio, features, and derived summaries

| Layer | What | Persisted? | Why |
|---|---|---|---|
| **Raw audio** | 16 kHz PCM stream | **Never in full.** Ring buffer in memory; only event-triggered clips (WAV, ≤ 8 s, top ~50/night) written to app-private storage | Continuous 16 kHz PCM is ~115 MB/h — persisting nights is neither private-proportionate nor useful |
| **Features** | Log-mel spectrograms | **Not persisted in Phase 1** (recomputable from clips/model spec; optional cache in Phase 5 for retraining experiments) | Storage vs. recomputability tradeoff favors recompute |
| **Events** | AcousticEvent rows (type, timing, intensity, confidence, model id, clip ref) | Yes — session-scoped, cascade-deleted with session | The verifiable substrate; user can replay every event |
| **Measurements** | Normalized, provenance-stamped values (per-session or per-period metrics) | Yes — **survive session deletion** (session_id nulled) so longitudinal history outlives heavy raw data | The analytic substrate for trends/comparisons |
| **Reports** | Morning report (`MorningReport`, assembled under ClaimPolicy) | Computed live on open; the persisted snapshot table (DATA_MODEL §3.7) is deferred to Phase 4 | Stable, exportable, auditable rendering of a night |

This layering is deliberate: raw data is ephemeral and heavy; events are the audit trail; measurements are the durable analysis substrate; reports are presentation snapshots. Later ML models can re-run over persisted clips + measurements without ever needing full-night audio.

## 8. Extension points (designed now, built later)

| Extension | Interface | Notes |
|---|---|---|
| Swap/upgrade ML model | `ClassifierEngine` + `ModelRegistry` | New model = new registry entry (input spec, classes, thresholds); detection/storage untouched. Old sessions keep their original `model_id` |
| Accelerometer movement (Phase 2) | `SensorEngine` sibling of `AudioEngine` inside MonitorService | Same event/measurement write paths; enables Tier 1 sleep/wake estimation |
| Wearable import (Phase 3) | `WearableSource` → implementations: `GarminPersonalApiSource` (**shipped**: `GarminImportManager` via Personal API key — see [GARMIN_INTEGRATION.md](GARMIN_INTEGRATION.md)), `HealthConnectSource`, `GarminFileImportSource` | All imports land as Measurement rows with `source_type = GARMIN/WEARABLE`, `method = DEVICE_REPORTED`; raw JSON kept in `raw_payload` |
| Lifestyle factors (Phase 3) | `FactorLogRepository` | **Shipped** (Phase 3 slice): schema, repository, Daily log screen, report card. Mirroring into `measurement` rows stays deferred to Phase 4 |
| Longitudinal analysis (Phase 4) | `AnalysisEngine` consuming Measurement rows only | Never reads events/audio; keeps analysis honest about provenance |
| Export (Phase 3) | `ExportSink` (local share sheet; CSV/JSON/PDF) | Same ClaimPolicy filter as the report |
| Experimental indicators (Phase 2) | `ExperimentalIndicator` behind Settings toggle | Tier 2 from day one, per [MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §7 |

## 9. Testing strategy

- **Unit (pure Kotlin):** EventDetector on synthetic probability streams; Scoring; ReportAssembler + ClaimPolicy gating; retention logic. No Robolectric needed.
- **Claim-policy sync test:** asserts `ClaimPolicy` == measurement table in [MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §2 (fails the build on drift).
- **Fixture ML eval:** labeled WAV fixtures (real nights + augmented noise beds) → per-class precision/recall; thresholds asserted as unit tests on release candidates.
- **DB:** schema exports verified in `app/schemas/`; migration SQL hand-reviewed, and the instrumented `MigrationTest` (androidTest, `MigrationTestHelper`) replays 1→latest against exported schemas, checks seeded rows survive, and asserts the clinical-immutability triggers exist and enforce.
- **Robolectric:** service state machine, permission-denied paths, recovery flow.
- **Instrumented (real devices):** overnight soak runs with battery/thermal logging before each phase release.

## 10. Open questions

- **Scheduled auto-start (Phase 3+):** resolved and implemented (recording controls pass) — an optional schedule, off by default, posts a bedtime notification whose tap or *Start recording* action starts the service: a notification interaction is the documented user-interaction exemption granting while-in-use mic access on Android 11+ (starting the FGS from the alarm receiver would get none). Includes auto-stop at the end time, skip-tonight, and reboot re-arming; manual start remains the primary method.
- **Clip encryption beyond device FBE** (SQLCipher / app-level AES for clip files): evaluate in Phase 2 against the threat model in [PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md).
- **ONNX vs TFLite for v1 models:** decided by training toolchain at the time; the `ClassifierEngine` interface absorbs it.
