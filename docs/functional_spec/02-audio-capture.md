# 2. Audio capture engine

**Scope:** monitoring/audio/ — `monitoring/audio/AudioEngine.kt`, `monitoring/audio/CaptureStallWatchdog.kt`, `monitoring/audio/HopSkipLedger.kt`, `monitoring/audio/NightStatsMerge.kt`, `monitoring/audio/RingBuffer.kt`, `monitoring/audio/AmbientLevelBuckets.kt`, `monitoring/audio/NoiseFloorCache.kt`, `monitoring/audio/SpectralGatePolicy.kt`, `monitoring/audio/WavReader.kt`, `monitoring/audio/WavWriter.kt` — plus `data/files/ClipIntegrity.kt`, the clip-write path of `data/files/ClipStore.kt`, `domain/report/CaptureStats.kt`, `data/recorder/MeasurementRecorder.kt`, `data/logs/SessionLogger.kt`, `domain/policy/MeasurementType.kt`, `monitoring/service/ThermalPolicy.kt`, and the engine-adjacent parts of `monitoring/service/MonitorService.kt` (service lifecycle, wake lock, FGS type: see section 1).
**Requirement prefix:** AUD — rows in N.3 are numbered AUD-01, AUD-02, …
**Status values:** `tested` = a unit/instrumented test asserts it (cite it) · `device-verified` = evidence from a real night or device run exists (cite where) · `unverified` = implemented, no test and no device evidence · `not implemented` = designed or documented only.

## N.1 What the user gets

- Overnight recording from the phone microphone at 16 kHz mono PCM16; full-night audio is never persisted — only an in-memory ring buffer and short (≤ 8 s) event clips [AUD-01, AUD-13].
- Snore/acoustic events are detected in real time and stored, with a WAV clip for events that pass the clip gates [AUD-11, AUD-12].
- When the phone runs hot, analysis is throttled to every 2nd hop while capture itself continues, and the skipped spans are recorded so the report can correct coverage [AUD-04, AUD-05].
- A wedged mic (no samples for 60 s) or an init failure finalizes the night as INTERRUPTED instead of "recording" digital silence [AUD-06, AUD-07, AUD-08].
- Spans where Android silences this capture client (mic privacy toggle, a call, another app holding the mic) are recorded and reported as NOT captured, never as quiet [AUD-09, AUD-10].
- Capture statistics survive process death: heartbeat saves plus a counter carry across engine restarts [AUD-17, AUD-18].
- The morning report flags clips whose stored audio is digitally clipped [AUD-16].
- Every night gets a plain-text session log diagnosable on a machine, parsed by the Python tools [AUD-19, AUD-20].
- Every stored measurement carries provenance and tier, and Tier-3 (medical-claim) values cannot be written at all [AUD-21, AUD-22].

## N.2 How it works today

### N.2.1 Components

| Component | File | Responsibility |
|---|---|---|
| `AudioEngine` | `monitoring/audio/AudioEngine.kt` | Owns AudioRecord + capture thread; window/hop slicing, detection feed, clip flush, per-run NightStats |
| `AudioEngine.Config` | `monitoring/audio/AudioEngine.kt` | Capture format, gain, clip caps, stall timeout (defaults from DetectorConfig) |
| `AudioEngine.Listener` | `monitoring/audio/AudioEngine.kt` | Callbacks implemented by MonitorService: onEventFinalized, onCaptureError, onLog |
| `CaptureStallWatchdog` | `monitoring/audio/CaptureStallWatchdog.kt` | Pure stall seam (fake-clock testable): no ring progress for 60 s → stalled |
| `HopSkipLedger` | `monitoring/audio/HopSkipLedger.kt` | Merges thermally skipped hops into epoch spans (gap ≤ 3 s, cap 1000 spans) |
| `RingBuffer` | `monitoring/audio/RingBuffer.kt` | 8 s raw-PCM ring for clip flushes; horizon guard holdsEventAudio |
| `AmbientLevelBuckets` | `monitoring/audio/AmbientLevelBuckets.kt` | Per-window dBFS folded into 5-min epoch buckets (mean, variance, covered ms) |
| `NoiseFloorCache` | `monitoring/audio/NoiseFloorCache.kt` | Cached 20th-percentile noise floor over a 240-window ring; refresh every 8 pushes when full |
| `SpectralGatePolicy` | `monitoring/audio/SpectralGatePolicy.kt` | Pure decision: may this quiet, idle window skip the spectral (FFT) compute |
| `ClipPolicy` | `monitoring/audio/ClipPolicy.kt` | Per-event clip gates: relative level gate (settling +6 dB for the first 30 min; ultra-quiet-room peak tier), hourly budget, nightly cap, bout cooldown (detection-side details in the detection area) |
| `NightStatsMerge` | `monitoring/audio/NightStatsMerge.kt` | StatsCarry (cross-restart counter carry) + withCarry + captureStatsJson writer |
| `CaptureStats` | `domain/report/CaptureStats.kt` | Parsed capture_stats JSON model + tolerant parser (legacy versions) |
| `WavWriter` / `WavReader` | `monitoring/audio/WavWriter.kt`, `monitoring/audio/WavReader.kt` | Minimal PCM16 mono WAV writer (44-byte header) / chunk-walking reader |
| `ClipIntegrity` | `data/files/ClipIntegrity.kt` | Read-only decode of stored clips; counts full-scale samples (clipping) |
| `ClipStore` | `data/files/ClipStore.kt` | Clip file naming and the float gain + −1 dBFS block peak-limit write path |
| `MeasurementRecorder` | `data/recorder/MeasurementRecorder.kt` | The ONLY writer of Measurement rows; stamps provenance, refuses Tier-3 types |
| `SessionLogger` | `data/logs/SessionLogger.kt` | Append-only, no-throw per-session text log |
| `MeasurementType` | `domain/policy/MeasurementType.kt` | Measurement taxonomy with tier and unit (code mirror of the claim-policy table) |
| `ThermalPolicy` | `monitoring/service/ThermalPolicy.kt` | Pure: throttled iff thermal status ≥ MODERATE |

### N.2.2 Flows

#### Flow: capture start and the hop loop

1. `monitoring/service/MonitorService.kt` · `beginCaptureLocked` (see section 1 for state/mutex) constructs the engine with config enterWindows = 1 when the debug toggle is set else the DetectorConfig ENTER_WINDOWS default (`monitoring/detection/DetectorConfig.kt`), initialClipCount = clipCountForSession (resumed sessions count saved clips against the nightly cap), and the system AudioManager; assigns the engine field BEFORE start so the capture thread can deliver into it, then calls `monitoring/audio/AudioEngine.kt` · `start`.
2. `monitoring/audio/AudioEngine.kt` · `start` resets detectors, noise floor, all per-run counters, the skip ledger, silenced-span state and the ambient level list, then starts thread "SleepAudioCapture" and daemon thread "SleepAudioWatchdog".
3. `monitoring/audio/AudioEngine.kt` · `captureLoop` sets THREAD_PRIORITY_AUDIO, queries AudioRecord.getMinBufferSize (≤ 0 → onCaptureError), builds AudioRecord with source UNPROCESSED (falls back to MIC if init fails), buffer = max(minBuffer × 2, hopSamples × 4); disables platform AutomaticGainControl and holds the effect for the engine lifetime; logs `capture_source` with the chosen source and gain.
4. startRecording; `monitoring/audio/AudioEngine.kt` · `captureStartEpochMs` = now (the absolute time base for events and buckets — never the session start, which would backdate resumed engines); the watchdog is armed only now; the AudioRecordingCallback is registered on the main-looper handler; the first full window is primed RAW into the ring (`fillBlocking` — a failed prime bails like a failed loop read).
5. Loop: `monitoring/audio/AudioEngine.kt` · `analyzeWindow` on the 1 s window (1000 ms window, 500 ms hop; DetectorConfig WINDOW_MS / HOP_MS — see `monitoring/detection/DetectorConfig.kt`) → read one hop blocking (`readBlocking`: the raw hop is copied to the raw scratch, then micGain applied in place for the analysis path) → slide window → write the RAW hop to the clip ring → `monitoring/audio/CaptureStallWatchdog.kt` · `onProgress`.
6. In analyzeWindow: RMS → dBFS (−100 floor) and low-band ratio (LOW_PASS_ALPHA 0.32); the window dBFS pushes the noise-floor ring and NoiseFloorCache; the level is appended (cap 100 000) under statsLock and folded into AmbientLevelBuckets anchored at captureStartEpochMs + ring position.
7. Thermal skip (AUD-04): if throttled and hopCounter is odd → windowsSkipped++, `monitoring/audio/HopSkipLedger.kt` · `skipped`(epoch), return BEFORE any spectral work; the ring still advanced in step 5, so clips stay available. Otherwise windowsProcessed++.
8. `monitoring/audio/SpectralGatePolicy.kt` · `shouldComputeSpectral` (skip only when below gate threshold AND below the loud line AND the event detector idle; else compute) — a skipped window increments spectralSkipped and classifies on the detector's null-feature path.
9. `monitoring/audio/AudioEngine.kt` · `deliver` runs for finalized drafts (see Flow: event finalize). On stop (`monitoring/audio/AudioEngine.kt` · `stop`: running=false, join 5 s) the capture thread's finally flushes still-open events via `monitoring/detection/EventDetector.kt` · `flush`, unregisters the callback, closes an open silenced span, releases AudioRecord and AGC.

#### Flow: event finalize → clip write

1. `monitoring/audio/AudioEngine.kt` · `deliver`: for each finalized draft, eventStartEpochMs = captureStartEpochMs + draft.startMs; if captureStartEpochMs is still 0 (flush after a failed startRecording) nothing is persisted.
2. `monitoring/audio/ClipPolicy.kt` · `decide` gates on mean dBFS vs the relative gate (noise floor + 10 dB margin, hard floor −60 dBFS): during settling — the first 30 min after capture start, re-armed on resume — the margin is tightened by a further 6 dB (SETTLING_MINUTES 30 / SETTLING_EXTRA_DB 6); in ultra-quiet rooms (floor ≤ QUIET_ROOM_FLOOR_DBFS −80) the event must additionally clear the peak tier (peak > QUIET_ROOM_PEAK_DBFS −38) or the clip is level-skipped; plus the per-hour budget (15) and the nightly cap (150, seeded with initialClipCount for resumed engines); WRITE_LOUD_RESERVE covers loud peaks (≥ −25 dBFS) even past budget.
3. WRITE decisions first pass `monitoring/audio/RingBuffer.kt` · `holdsEventAudio` (ringEndMs − eventEndMs < clipMs, i.e. 8000): past-horizon events (thermally delayed emission) log `clip_horizon_skipped` and reach the DB without a clip and without booking budget.
4. Accepted clips snapshot the last 8 s of RAW (pre-gain) ring audio; `data/files/ClipStore.kt` · `writeClip` applies micGain in FLOAT and block-peak-limits the whole clip to −1 dBFS (`gainAndLimit`), writes the WAV as session_<id>/evt_<type>_<startMs>_<n>.wav under the clips root filesDir/clips, and returns appliedGain + filePeakDbfs.
5. SKIP_LEVEL increments levelGateSkipped; SKIP_BUDGET / SKIP_COOLDOWN log `clip_budget_skipped` / `clip_cooldown_skipped` once per hour bucket; a failed file write logs `clip_write_failed` and never drops the event.
6. Every finalized draft reaches the listener's onEventFinalized (the `monitoring/audio/AudioEngine.kt` Listener interface) with the clip path/duration/size/appliedGain/filePeakDbfs (null when no clip); `monitoring/service/MonitorService.kt` · `engineListener` inserts it via SessionRepository.insertEvent using captureTimeBase = engine captureStartEpochMs (fallback session start), model version activeModelVersion (energy-heuristic-v3-peakgate, +dbg1 suffix under the debug toggle).

#### Flow: capture error → interrupted

1. Sources: init failure or getMinBufferSize failure (captureLoop entry), AudioRecord.read < 0, the bounded zero-read path (`monitoring/audio/AudioEngine.kt` · `readBlocking`: got == 0 retries with 10 ms sleep until the watchdog limit, then error audio_read_stalled), a throw anywhere in the loop, and the watchdog thread (`monitoring/audio/AudioEngine.kt` · `watchdogLoop`: ticks every 5 s, reports once when `monitoring/audio/CaptureStallWatchdog.kt` · `isStalled` — no ring progress for 60 s — with capture_stalled).
2. `monitoring/service/MonitorService.kt` · `onCaptureError` (in engineListener) logs `capture_error` and calls endRecording(INTERRUPTED): heartbeat cancelled, stats snapshot with carry, engine stop (flush on capture thread), then `data/repo/SessionRepository.kt` · `finalizeSession` — see section 1 for the interrupted-night UX.

#### Flow: heartbeat, stats carry and finalize

1. `monitoring/service/MonitorService.kt` · `startHeartbeat` every 15 min (MonitorService HEARTBEAT_INTERVAL_MS = 15 × 60 × 1000, `monitoring/service/MonitorService.kt`): stats() with withCarry(statsCarry), logs `heartbeat` (windows, skipped, clips, gateSkip, free storage, battery), and persists via SessionRepository.saveCaptureProgress — the saved_at_ms / capture_started_at fields of capture_stats come from here.
2. On resume after process death, `monitoring/service/MonitorService.kt` · `beginRecording` builds statsCarry from the NightStatsMerge `monitoring/audio/NightStatsMerge.kt` · `from` factory over `domain/report/CaptureStats.kt` · `parse` of the session row; `monitoring/audio/NightStatsMerge.kt` · `withCarry` sums the five counters (windowsProcessed, windowsSkipped, thermalThrottleEvents, levelGateSkipped, spectralSkipped) and concatenates + sorts skipped-hop spans, ambient buckets and client-silenced spans. Ambient median/p95 and clip count/bytes are NOT carried (raw levels are gone; the audio_clip table is authoritative — finalize uses clipCountOverride).
3. `monitoring/service/MonitorService.kt` · `endRecording` snapshots stats with carry, logs `recording_stop` (windows, skipped, clips, bytes, gateSkip, thermalEvents) and passes the merged stats to finalizeSession, which writes `monitoring/audio/NightStatsMerge.kt` · `captureStatsJson` into the session row (see N.2.3).

#### Flow: silenced-client detection (P2-4)

1. `monitoring/audio/AudioEngine.kt` · `recordingCallback` (its onRecordingConfigChanged override): only an explicit isClientSilenced on the config matching our activeAudioSessionId marks silence (our session merely absent from the list is NOT evidence).
2. `monitoring/audio/AudioEngine.kt` · `onClientSilenceChanged` records spans under silencedLock (start ms, close into a ClientSilencedSpan on recovery); `monitoring/audio/AudioEngine.kt` · `closeOpenSilencedSpan` closes a still-open span at capture end; stats() exposes them and they land in capture_stats client_silenced_spans; the report and quiet-gap classification (area 5) use them plus the bucket variance≈0 digital-silence signal — silenced spans are never classified as quiet.

#### Flow: thermal throttling

1. `monitoring/service/MonitorService.kt` · `registerThermalListener` registers a PowerManager listener that logs `thermal` and calls `monitoring/audio/AudioEngine.kt` · `setThrottled` with `monitoring/service/ThermalPolicy.kt` · `isThrottled`(status); because the system does not re-broadcast on registration, `monitoring/service/MonitorService.kt` · `applyCurrentThermalStatusToEngine` applies the current status to each freshly created engine.
2. Throttled means thermal status ≥ THERMAL_STATUS_MODERATE. `monitoring/audio/AudioEngine.kt` · `setThrottled` counts a rising edge as one thermalThrottleEvent.

### N.2.3 State and stored data

- **capture_stats JSON** (session row column, written at every heartbeat save and at finalize by `monitoring/audio/NightStatsMerge.kt` · `captureStatsJson`; schema_version 6; a null-stats session gets the stub {"schema_version":1}): windows_processed, windows_skipped (disjoint since v5: sum × 0.5 s = mic-live analysis time, see `domain/report/CaptureStats.kt` · `capturedAnalysisMs`), ambient_dbfs_median, ambient_dbfs_p95, clip_count (finalize overrides from the audio_clip table), thermal_throttle_events, level_gate_skipped, spectral_skipped (v4), optional saved_at_ms and capture_started_at (heartbeat/finalize progress), thermal_skipped_hops (v2: [startEpochMs,endEpochMs,hops] arrays), ambient_level_buckets (v3, extended v6: [startMs,endMs,meanDbfs,coveredMs,varianceDb2]), client_silenced_spans (v6: [startEpochMs,endEpochMs]). Version history: v1 baseline; v2 thermal_skipped_hops; v3 ambient_level_buckets; v4 spectral_skipped; v5 disjoint window counters (pre-v5 writers counted a skipped hop in both, inflating the denominator ~1.5×); v6 silenced spans + honest bucket coverage/variance. The parser (`domain/report/CaptureStats.kt` · `parse`) tolerates all absences and drops degenerate spans; pre-v6 3-element bucket rows get coveredMs = end−start (legacy claim kept).
- **Clip WAV files**: filesDir/clips/session_<id>/evt_<type>_<startMs>_<n>.wav; PCM16 mono, 44-byte RIFF header (`monitoring/audio/WavWriter.kt` · `writePcm16Mono`); audio_clip row columns appliedGain (REAL NOT NULL DEFAULT 2.5) and filePeakDbfs (REAL), added by MIGRATION_10_11 (`data/db/AppDatabase.kt`); appliedGain = micGain × block peak-limit scale, filePeakDbfs NULL for pre-v11 rows, which carried the fixed 2.5× int16 gain.
- **Session logs**: filesDir/logs/session_<id>.log (`data/logs/SessionLogger.kt` · `log`); line format `<ISO-8601 UTC Instant.toString()> [<event>] <detail>` + newline; append-only, never throws. Events written by THIS area via onLog: `capture_source`, `clip_write_failed`, `clip_horizon_skipped`, `clip_budget_skipped`, `clip_cooldown_skipped`. Events written by MonitorService around the engine: `recording_start`, `start_delay`, `start_delay_skipped`, `start_delay_cancelled`, `start_delay_skip_ignored`, `start_delay_skip_failed`, `capture_start_aborted`, `capture_start_skipped`, `capture_wakelock_released`, `detector_config`, `thermal`, `heartbeat`, `heartbeat_failed`, `recording_stop`, `capture_error`, `event_persist_failed`, `session_resumed`, `finalize_failed`, `finalize_skipped`, `start_failed`, `start_aborted`, `start_blocked_interrupted`, `interrupted_fg_promotion_failed`, `service_coroutine_crash`.
- **Python consumers (log format is a contract)**: tools/night_battery.py reads only the session log — session_<id>.log lines including `[heartbeat]` key=value details (its tests pin the format). The capture_stats consumers are exactly: tools/night_trends.py (capture_started_at), tools/export_night_csv.py (capture_started_at, windows_processed, ambient_dbfs_median, thermal_throttle_events, ambient_level_buckets) and the morning-report SQL gate (windows_processed, capture_started_at, thermal_throttle_events, thermal_skipped_hops); tools/export_fixtures.py does NOT read capture_stats — it exports acoustic_event + audio_clip files and a manifest, with a null-appliedGain integrity count.
- **Measurement rows** (`data/recorder/MeasurementRecorder.kt`): provenance stamped at write — sourceType/method PHONE / ALGORITHM_ESTIMATED (phone estimates), GARMIN / DEVICE_REPORTED (vendor rows); notes flag label_corrected=true on label-recompute rewrites; tier/unit strings are the MeasurementType enum names. Stored vocab strings: PHONE, GARMIN, ALGORITHM_ESTIMATED, DEVICE_REPORTED (+ the reserved CLINICAL / WEARABLE / USER and CLINICAL_MEASURED / USER_REPORTED constants, unwritten today).

## N.3 Requirements

| ID | Requirement | Where | Pinned by | Status |
|---|---|---|---|---|
| AUD-01 | Capture is AudioRecord 16 kHz mono PCM16; source UNPROCESSED with fallback to MIC when UNPROCESSED fails to initialize; buffer = max(minBuffer × 2, hopSamples × 4) | `monitoring/audio/AudioEngine.kt` · `captureLoop` | — | unverified |
| AUD-02 | Analysis grid: 1 s windows advanced by 0.5 s hops; the window keeps GAINED samples, the clip ring receives the RAW hop | `monitoring/audio/AudioEngine.kt` · `analyzeWindow` | `AudioEngineThrottleAccountingTest.n unthrottled hops capture exactly n hops of analysis time` | tested |
| AUD-03 | Engine defaults equal the DetectorConfig defaults (sample rate, window/hop/clip, gate, enter-windows) | `monitoring/audio/AudioEngine.kt` · `Config` | `DetectorConfigCoherenceTest.engine config defaults equal the detector defaults` | tested |
| AUD-04 | While thermally throttled, every 2nd hop (odd hopCounter) is skipped for classification but the ring still advances and the skip is ledgered; each captured hop increments exactly one of windowsProcessed / windowsSkipped | `monitoring/audio/AudioEngine.kt` · `analyzeWindow` | `AudioEngineThrottleAccountingTest.n hops with thermal throttling capture exactly n hops of analysis time` | tested |
| AUD-05 | A quiet window with a fully idle detector skips the spectral compute (counts spectralSkipped) and classifies byte-identically to the compute-everything pipeline | `monitoring/audio/SpectralGatePolicy.kt` · `shouldComputeSpectral` | `SpectralGatePolicyTest.gated pipeline emits byte-identical events to the compute-everything pipeline` | tested |
| AUD-06 | No clip-ring progress for 60 s (DEFAULT_STALL_LIMIT_MS) is a stall; the watchdog thread reports onCaptureError at most once; never stalled before arm | `monitoring/audio/CaptureStallWatchdog.kt` · `isStalled` | `CaptureStallWatchdogTest.a stalled reader trips the watchdog exactly at the limit on a fake clock` | tested |
| AUD-07 | In-loop zero reads are retried with 10 ms sleeps bounded by the same stall limit, then reported as an error — capture never spins all night reading nothing | `monitoring/audio/AudioEngine.kt` · `readBlocking` | — | unverified |
| AUD-08 | Any captureLoop init failure (minBufferSize ≤ 0, AudioRecord init, AGC) is reported via onCaptureError instead of killing the process | `monitoring/audio/AudioEngine.kt` · `captureLoop` | `AudioEngineInitErrorTest.init failure is reported via onCaptureError instead of killing the capture thread` | tested |
| AUD-09 | A capture error finalizes the session INTERRUPTED (never silent auto-recovery) and logs capture_error | `monitoring/service/MonitorService.kt` · `onCaptureError` | — | unverified |
| AUD-10 | Platform AGC is disabled and held for the engine lifetime (released in the finally); the fixed software micGain 2.5 is the only analysis-path gain, clamped in int16 | `monitoring/audio/AudioEngine.kt` · `applyGain` | — | unverified |
| AUD-11 | Spans where the OS reports this client silenced are recorded as ClientSilencedSpan and closed on recovery; at capture end an open span is closed | `monitoring/audio/AudioEngine.kt` · `onClientSilenceChanged` | `AudioEngineSilenceSpanTest.a silenced-client span is recorded and closed` | tested |
| AUD-12 | Clip write decision gates: relative level gate (floor + 10 dB margin, hard floor −60 dBFS) tightened by +6 dB during the first 30 min of capture (settling, re-armed on resume), ultra-quiet-room peak tier (floor ≤ −80 requires peak > −38, else SKIP_LEVEL), per-hour budget 15, nightly cap 150 seeded with already-saved clips on resume, bout cooldown with loud-peak reserve | `monitoring/audio/ClipPolicy.kt` · `decide` | `ClipPolicyTest.settling window is stricter for the first 30 minutes of capture`, `ClipPolicyTest.quiet-room rumble never gets a clip even when its mean clears the gate` | tested |
| AUD-13 | A clip is written only while the ring still holds the event's tail (horizon guard); otherwise the event is delivered without a clip and without booking budget | `monitoring/audio/RingBuffer.kt` · `holdsEventAudio` | `RingBufferTest.horizon guard refuses a clip when the event ends exactly on the horizon` | tested |
| AUD-14 | Level- and budget-skipped events still reach the database; only their audio is dropped | `monitoring/audio/AudioEngine.kt` · `deliver` | — | unverified |
| AUD-15 | Stored clips: float mic gain re-applied to RAW samples, block peak-limited to −1 dBFS (one scale factor per clip); quiet clips bit-identical to the legacy int16 gain; writes via 44-byte RIFF PCM16 mono WAV | `data/files/ClipStore.kt` · `gainAndLimit` | `ClipAudioTest.a signal whose gained peak would exceed full scale is block-limited to the target` | tested |
| AUD-16 | A clip whose decoded samples reach abs(sample) ≥ 32767 counts as clipped; missing/corrupt clip files degrade to flagged non-blocking checks, never throw | `data/files/ClipIntegrity.kt` · `check` | `ClipIntegrityTest.missing and corrupt files degrade to flagged checks` | tested |
| AUD-17 | capture_stats JSON is written with schema_version 6 and the fields listed in N.2.3; null stats writes the stub {"schema_version":1} | `monitoring/audio/NightStatsMerge.kt` · `captureStatsJson` | `NightStatsMergeTest.capture stats writes schema v6 and round-trips spectral_skipped` | tested |
| AUD-18 | Parsing is tolerant: missing/malformed fields become nulls, degenerate spans are dropped, pre-v6 bucket rows keep the full-bucket coverage claim | `domain/report/CaptureStats.kt` · `parse` | `CaptureStatsTest.parses legacy payload without skipped field` | tested |
| AUD-19 | Mic-live capture time = (windowsProcessed + windowsSkipped) × 500 ms; zero windows means no snore evidence is fabricated | `domain/report/CaptureStats.kt` · `capturedAnalysisMs` | `CaptureStatsTest.captured analysis time is processed plus skipped hops on the half-second grid` | tested |
| AUD-20 | On engine restart within a session, counters are carried forward (summed) and spans/buckets concatenated + sorted; clip counts/bytes and ambient percentiles are not carried | `monitoring/audio/NightStatsMerge.kt` · `withCarry` | `NightStatsMergeTest.withCarry concatenates and sorts skipped-hop spans` | tested |
| AUD-21 | Heartbeat every 15 min persists cumulative stats + saved_at_ms/capture_started_at into the session row and logs heartbeat; a stats failure only logs heartbeat_failed | `monitoring/service/MonitorService.kt` · `startHeartbeat` | — | unverified |
| AUD-22 | Session logs: append-only filesDir/logs/session_<id>.log, line `<ISO-8601 UTC> [<event>] <detail>`, never throws on I/O failure | `data/logs/SessionLogger.kt` · `log` | `SessionLoggerTest.timestamps are ISO-8601 UTC and stamped at write time` | tested |
| AUD-23 | MeasurementRecorder is the only Measurement write path; OUT_OF_SCOPE_FOR_CLAIMS types are refused outright | `data/recorder/MeasurementRecorder.kt` · `requireWritable` | `ClaimPolicyTest.recorder refuses tier-3 measurement types outright` | tested |
| AUD-24 | Phone estimates carry source PHONE / method ALGORITHM_ESTIMATED with tier and unit from MeasurementType | `data/recorder/MeasurementRecorder.kt` · `recordPhoneEstimate` | `ClaimPolicyTest.recorder stamps full provenance on phone estimates` | tested |
| AUD-25 | Garmin rows carry source GARMIN / method DEVICE_REPORTED, no confidence, no model version; re-import replaces prior rows per day and never wipes on empty batch | `data/recorder/MeasurementRecorder.kt` · `replaceGarminEstimates` | `MeasurementRecorderTest.batch rows carry the same provenance as the single-metric writer` | tested |
| AUD-26 | A label recompute rewrites the SNORE_AGGREGATE_TYPES rows (deleting aggregates that stop being produced), flags them label_corrected=true, and never touches non-aggregate rows | `data/recorder/MeasurementRecorder.kt` · `replacePhoneEstimates` | `MeasurementRecorderTest.label recompute deletes a stale aggregate that stops being produced` | tested |
| AUD-27 | MeasurementType tiers/units match the documented claim table (Tier-3 = exactly the M14–M17 set) | `domain/policy/MeasurementType.kt` · `MeasurementType` | `ClaimPolicyTest.tier table in MEASUREMENT_RELIABILITY matches the code enum` | tested |

## N.4 Constants and thresholds

| Constant | Value | Defined in | Effect |
|---|---|---|---|
| `SAMPLE_RATE` | 16 000 Hz | `monitoring/detection/DetectorConfig.kt` · `SAMPLE_RATE` | Capture and analysis sample rate |
| `WINDOW_MS` / `HOP_MS` | 1000 / 500 ms | `monitoring/detection/DetectorConfig.kt` · `WINDOW_MS` | Analysis window and hop grid (0.5 s per captured hop) |
| `CLIP_MS` | 8 000 ms | `monitoring/detection/DetectorConfig.kt` · `CLIP_MS` | Ring capacity and clip length (128 000 samples) |
| `MAX_CLIPS_PER_NIGHT` / `MAX_CLIPS_PER_HOUR` | 150 / 15 | `monitoring/detection/DetectorConfig.kt` · `MAX_CLIPS_PER_NIGHT` | Clip budget; hourly keys on the absolute wall-clock hour |
| `GATE_MARGIN_DB` / `MIN_GATE_DBFS` | 10.0 / −60.0 dBFS | `monitoring/detection/DetectorConfig.kt` · `GATE_MARGIN_DB` | Relative clip/event gate and its hard absolute floor |
| `LOUD_DELTA_DB` | 12.0 dB | `monitoring/detection/DetectorConfig.kt` · `LOUD_DELTA_DB` | Spectral-gate clause 3 loud line over the floor |
| `NOISE_FLOOR_PERCENTILE` / `NOISE_FLOOR_WINDOW_COUNT` | 0.20 / 240 windows (~2 min) | `monitoring/detection/DetectorConfig.kt` · `NOISE_FLOOR_PERCENTILE` | Rolling noise-floor definition |
| `LOW_PASS_ALPHA` | 0.32 | `monitoring/detection/DetectorConfig.kt` · `LOW_PASS_ALPHA` | Low-band ratio filter coefficient |
| `micGain` | 2.5 × (~ +8 dB) | `monitoring/audio/AudioEngine.kt` · `Config` | Software gain on the analysis path; re-applied in float on the clip path |
| `DEFAULT_STALL_LIMIT_MS` / `TICK_MS` | 60 000 / 5 000 ms | `monitoring/audio/CaptureStallWatchdog.kt` · `DEFAULT_STALL_LIMIT_MS` | Stall detection window and watchdog latency bound |
| `MERGE_GAP_MS` / `MAX_SPANS` | 3 000 ms / 1 000 | `monitoring/audio/HopSkipLedger.kt` · `MERGE_GAP_MS` | Skipped-hop span merging and cap |
| `BUCKET_MS` / `DEFAULT_WINDOW_SPAN_MS` | 300 000 / 500 ms | `monitoring/audio/AmbientLevelBuckets.kt` · `BUCKET_MS` | 5-min ambient level buckets; per-window covered extent |
| `DEFAULT_REFRESH_EVERY_PUSHES` / `EMPTY_RING_FLOOR_DBFS` | 8 / −70.0 dBFS | `monitoring/audio/NoiseFloorCache.kt` · `DEFAULT_REFRESH_EVERY_PUSHES` | Floor refresh cadence when full; empty-ring fallback |
| `MAX_CLIP_TARGET_DBFS` | −1.0 dBFS | `data/files/ClipStore.kt` · `MAX_CLIP_TARGET_DBFS` | Block peak-limit target for stored clips |
| `PCM16_FULL_SCALE` | 32 767 | `data/files/ClipIntegrity.kt` · `PCM16_FULL_SCALE` | Clipping detection threshold (both signs) |
| `HOP_MS` | 500 ms | `domain/report/CaptureStats.kt` · `HOP_MS` | Domain-local copy of the hop grid for capturedAnalysisMs |
| `HEARTBEAT_INTERVAL_MS` / `STORAGE_WARN_MB` | 15 × 60 000 ms / 500 MB | `monitoring/service/MonitorService.kt` · `HEARTBEAT_INTERVAL_MS` | Heartbeat cadence and STORAGE_LOW warn threshold |
| Thermal threshold | THERMAL_STATUS_MODERATE | `monitoring/service/ThermalPolicy.kt` · `isThrottled` | First status level that throttles analysis |

## N.5 Failure handling and edge cases

| Situation | Behaviour | Where |
|---|---|---|
| AudioRecord.getMinBufferSize ≤ 0 or both sources fail init | onCaptureError → session finalizes INTERRUPTED | `monitoring/audio/AudioEngine.kt` · `captureLoop` |
| UNPROCESSED source fails to initialize | Silent fallback to MIC source; capture_source log records which | `monitoring/audio/AudioEngine.kt` · `tryInitRecord` |
| read returns < 0 | onCaptureError "AudioRecord.read failed"; loop exits | `monitoring/audio/AudioEngine.kt` · `readBlocking` |
| read returns 0 repeatedly | 10 ms sleep retries; after 60 s without progress → "audio_read_stalled" error | `monitoring/audio/AudioEngine.kt` · `readBlocking` |
| Wedged native read that never returns | Watchdog thread reports once after 60 s ("capture_stalled") | `monitoring/audio/AudioEngine.kt` · `watchdogLoop` |
| Stop requested | join(5_000) on the capture thread; finally flushes open events; engine stopped | `monitoring/audio/AudioEngine.kt` · `stop` |
| Clip file write throws | Logs clip_write_failed; event still delivered; capture continues | `monitoring/audio/AudioEngine.kt` · `deliver` |
| Event tail slid past ring horizon | clip_horizon_skipped; no clip, no budget booking | `monitoring/audio/RingBuffer.kt` · `holdsEventAudio` |
| Engine start before capture base set (flush after failed startRecording) | Persists nothing (clipTimeBase 0 guard) | `monitoring/audio/AudioEngine.kt` · `deliver` |
| ambientDbfs grows unbounded | Capped at 100 000 entries (~13.9 h of hops) | `monitoring/audio/AudioEngine.kt` · `analyzeWindow` |
| Ring totalWritten past 2^31 samples (~37 h) | availableSamples clamps before int conversion; no NegativeArraySizeException | `monitoring/audio/RingBuffer.kt` · `availableSamples` |
| > 1000 throttle episodes | Ledger drops further hops; counters stay exact | `monitoring/audio/HopSkipLedger.kt` · `skipped` |
| Phone already ≥ MODERATE when engine starts | applyCurrentThermalStatusToEngine applies current status (no re-broadcast) | `monitoring/service/MonitorService.kt` · `applyCurrentThermalStatusToEngine` |
| Stop during start delay | Session closes with zero capture; log start_delay_cancelled; empty stats stub, no fabricated zeros | `monitoring/service/MonitorService.kt` · `endRecording` |
| Log filesystem refuses writes | Silently skipped; app continues (all SessionLogger methods no-throw) | `data/logs/SessionLogger.kt` · `log` |
| Clip file missing / undecodable at report time | exists=false / readable=false flags; never throws; banner stays non-blocking | `data/files/ClipIntegrity.kt` · `check` |

## N.6 Contract boundaries — what a refactor must not break

- **Persisted formats**: capture_stats JSON keys and array shapes exactly as in N.2.3 (consumed by the report, Home, validity, and the Python tools); the {"schema_version":1} stub; session-log line format `<ISO-8601 UTC> [<event>] <detail>` and the session_<id>.log name; clip path pattern session_<id>/evt_<type>_<startMs>_<n>.wav; WAV 44-byte PCM16 mono layout; measurement provenance strings PHONE / GARMIN / ALGORITHM_ESTIMATED / DEVICE_REPORTED / label_corrected=true and the MeasurementType enum names stored in rows.
- **Version strings stored with data**: capture_stats schema_version (6; parser must keep reading 1–5); audio_clip appliedGain/filePeakDbfs column names and semantics of schema v11 (pre-v11 rows: fixed 2.5× gain, NULL peak); detector model version strings energy-heuristic-v3-peakgate and the +dbg1 debug suffix stored with events.
- **Platform contracts**: AudioRecord source UNPROCESSED with MIC fallback; AutomaticGainControl disabled; AudioManager.AudioRecordingCallback registration with isClientSilenced semantics (the silenced-span evidence depends on this API); PowerManager thermal listener + THERMAL_STATUS_MODERATE threshold; THREAD_PRIORITY_AUDIO. (FGS type, permissions, notifications: see section 1.)
- **Cross-area interfaces**: MonitorService calls the engine's start/stop/stats/setThrottled/captureStartEpochMs and implements Listener (`monitoring/service/MonitorService.kt` · `engineListener`); section 1 also reads the engine Config (`monitoring/audio/AudioEngine.kt`) and the carry type (`monitoring/audio/NightStatsMerge.kt`); section 5 (report/validity) reads `domain/report/CaptureStats.kt` · `parse`, `capturedAnalysisMs`, `anyProcessedWindows`, and `data/files/ClipIntegrity.kt` · `check` (ValidityCalculator, ReportViewModel, NightValidity.clipQualitySummary); Garmin import calls `data/recorder/MeasurementRecorder.kt` · `replaceGarminEstimates` (`data/garmin/GarminImportManager.kt`); label recompute calls replacePhoneEstimates (SessionRepository.labelEvent); validity calls replaceNightValidity (`data/repo/ValidityCalculator.kt`); Python tools parse the log format and capture_stats (`tools/night_battery.py`).
- **Safety-net tests**: `NightStatsMergeTest`, `CaptureStatsTest`, `AudioEngineThrottleAccountingTest`, `AudioEngineSilenceSpanTest`, `CaptureStallWatchdogTest`, `RingBufferTest`, `HopSkipLedgerTest`, `SpectralGatePolicyTest`, `NoiseFloorCacheTest`, `AmbientLevelBucketsTest`, `ClipPolicyTest`, `ClipAudioTest`, `ClipIntegrityTest`, `WavWriterTest`, `SessionLoggerTest`, `MeasurementRecorderTest`, `ClaimPolicyTest`, `LabelCorrectedSnoreEstimatesTest`, `DetectorConfigCoherenceTest`, `AudioEngineInitErrorTest`.

## N.7 Consolidation candidates

| Candidate | Kind | Evidence | Risk to remove | Note |
|---|---|---|---|---|
| `data/recorder/MeasurementRecorder.kt` · `recordGarminEstimate` | test-only | grep over app/src/main + tools + analysis: zero production callers; only `MeasurementRecorderTest` and `ClaimPolicyTest.recorder stamps garmin provenance as device-reported, not clinical` use it; production writes via replaceGarminEstimates (`data/garmin/GarminImportManager.kt`) | low — both tests can move to the batch API, which they already partly cover | Single-metric Garmin writer superseded by the day-batch replace path |
| `data/recorder/MeasurementRecorder.kt` · `SourceTypes` (CLINICAL, WEARABLE, USER) and `Methods` (CLINICAL_MEASURED, USER_REPORTED) | dead | grep: no reference to these constants anywhere in app/src (the "WEARABLE" strings elsewhere are ReportAssembler/HealthConnect constants); production writes only PHONE/GARMIN/ALGORITHM_ESTIMATED/DEVICE_REPORTED | low — names survive in docs as reserved vocabulary; no stored data depends on the constants | Keep or drop is a vocabulary-documentation decision, not a code one |
| `monitoring/audio/ClipPolicy.kt` · `shouldWrite` | test-only | grep: production gate is decide; shouldWrite referenced only from `ClipPolicyTest` (helper + 1 direct call) | low — ClipPolicyTest helper goes with it | decide presumably embeds the same predicate; verify equivalence when deleting |
| Debug enter-windows=1 path (`monitoring/service/MonitorService.kt` · `beginCaptureLocked` debugEnter1 → enterWindows=1, model suffix +dbg1) | debug/experiment | Config doc "debug toggle may set 1"; detector_config log records it | medium — the +dbg1 suffix is stored with events; dropping the toggle must keep reading old +dbg1 rows | Owner decision: keep the A/B arm or fix enterWindows |
| docs/ARCHITECTURE.md §6.1 "MeasurementRecorder.record(spec)" single-method API and the §6 source list without GARMIN | doc-drift | Actual API: recordPhoneEstimate / replacePhoneEstimates / replaceNightValidity / replaceGarminEstimates / recordGarminEstimate; GARMIN rows are written today | none — documentation fix | ARCHITECTURE lags the implemented recorder and Garmin provenance |
| `monitoring/service/MonitorService.kt` · `beginRecording` comment "a fresh 50-clip budget" | doc-drift | `monitoring/detection/DetectorConfig.kt` · `MAX_CLIPS_PER_NIGHT` = 150 | none — comment fix | Stale in-code comment only |
| AmbientLevelBucket mapping duplicated in `monitoring/audio/AudioEngine.kt` · `stats` and `monitoring/audio/NightStatsMerge.kt` · `withCarry` | duplicate | Both convert the monitoring.audio bucket fields to the identical domain AmbientLevelBucket | low — pure plumbing; unify if the engine depended on domain types | The split exists to keep monitoring/audio free of the domain import |

Non-normative notes: captureStatsJson hand-builds JSON strings while the CaptureStats parser uses org.json — an asymmetric writer/parser pair; any schema change must touch both plus the tests. `monitoring/audio/WavReader.kt` · `readPcm16Mono` has exactly two consumers (ClipIntegrity for report banners/validity, FixtureEvaluator for the offline eval harness) — it is NOT dead, but it is eval-harness-adjacent. `monitoring/audio/RingBuffer.kt` · `available` has no external callers (used only internally by snapshotLast). `monitoring/audio/AudioEngine.kt` · `onRecordingConfigChanged` flagged unreferenced by the mechanical scan is a framework callback (AudioRecordingCallback override) and must stay. The doc-drift kind applies to this area only in the two spots above; DATA_MODEL.md and NIGHT_REPORT.md match the implemented v6 stats shape.

## N.8 Open questions for the owner

- Keep or drop the debug enter-windows=1 toggle (AUD area) and the related capture-wake-lock experiment arms (section 1)? Both are marked in the session log (`detector_config`, wl=off) and the +dbg1 model suffix is stored with old events — dropping the toggle must not orphan those rows.
- How long must legacy-compat parsing be kept: pre-v5 capture_stats (double-counted window counters), pre-v6 bucket rows (full-bucket coverage claim), the {"schema_version":1} stub, and pre-v11 audio_clip rows (fixed 2.5× applied_gain, NULL file_peak_dbfs)? Data ages out with the 90-day session retention, which bounds the need.
- Is recordGarminEstimate kept as the documented single-metric API (docs/GARMIN_INTEGRATION.md references) or removed with its two tests?
- UNVERIFIED: AGC-disable and UNPROCESSED-source behaviour on real OEM hardware (JVM tests cannot exercise it; docs/DOGFOOD_CHECKLIST.md items 10–11 cover silenced-mic and stall but I found no stored device-run evidence in this session).
- Settled by review — capture_stats Python consumers are exactly as listed in N.2.3; night_battery.py reads only the session log.
- Settled by review — a stop during the start delay has engine == null → stats null → `data/repo/SessionRepository.kt` · `finalizeSession` writes the {"schema_version":1} stub only when no heartbeat-saved stats exist; after process death with prior heartbeats the saved stats are kept instead of the stub.
