package com.sleepanalysis.monitoring.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.Process
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.domain.report.SkippedHopSpan
import com.sleepanalysis.monitoring.detection.AudioFeatures
import com.sleepanalysis.monitoring.detection.DetectorConfig
import com.sleepanalysis.monitoring.detection.EventDetector
import com.sleepanalysis.monitoring.detection.SnoreDetector
import com.sleepanalysis.monitoring.features.SpectralFeatures
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Overnight capture pipeline (docs/ARCHITECTURE.md §5). Runs entirely on one capture thread:
 *
 *   AudioRecord (16 kHz mono PCM16) → 1 s windows @ 0.5 s hop → features (dBFS, low-band ratio,
 *   rolling noise floor) → [SnoreDetector] → [EventDetector] → finalized events →
 *   clip flush from the in-memory ring buffer (RAW, pre-gain samples) → [ClipPolicy] gates →
 *   [ClipStore] (float mic gain + −1 dBFS block peak-limit at write time).
 *
 * Clip persistence is bounded: a relative level gate (noise floor + margin, capped by a hard
 * absolute floor) keeps mic-self-noise artifacts from being saved (night 1: 1,047 near-silent
 * "SNORE" events in a −92 dBFS room), and a per-hour budget keeps bedtime noise from spending
 * the whole night's clips in the first minutes.
 *
 * Privacy: full-night audio is never persisted — only the in-memory ring buffer and short
 * event clips exist (docs/PRIVACY_AND_REGULATORY.md §2).
 */
class AudioEngine(
    private val sessionId: Long,
    private val detector: SnoreDetector,
    private val clipStore: ClipStore,
    private val listener: Listener,
    private val config: Config = Config(),
) {

    data class Config(
        val sampleRate: Int = DetectorConfig.SAMPLE_RATE,
        val windowMs: Long = DetectorConfig.WINDOW_MS,
        val hopMs: Long = DetectorConfig.HOP_MS,
        val clipMs: Long = DetectorConfig.CLIP_MS,
        val maxClipsPerNight: Int = DetectorConfig.MAX_CLIPS_PER_NIGHT,
        /** Max clips in any wall-clock hour — a bedtime burst cannot starve the night. */
        val maxClipsPerHour: Int = DetectorConfig.MAX_CLIPS_PER_HOUR,
        /** Gate margin over the rolling noise floor: quieter events are never SNORE-labeled nor clipped. */
        val gateMarginDb: Double = DetectorConfig.GATE_MARGIN_DB,
        /** Hard absolute floor of the gate threshold — near-silence never gates open. */
        val gateHardFloorDbfs: Double = DetectorConfig.MIN_GATE_DBFS,
        /**
         * Software mic gain applied to every raw PCM16 sample right after it is read from
         * AudioRecord. Raises effective mic sensitivity without touching the detector's
         * relative thresholds. Adjust here (or via MonitorService's Config construction) —
         * 2.5 ≈ +8 dB; values above ~4 risk clipping the ANALYSIS path on loud snores (the
         * clip path is protected: the ring holds raw samples and ClipStore re-applies this
         * gain in float with a −1 dBFS block peak-limit at write time).
         */
        val micGain: Float = 2.5f,
        /** Consecutive active windows required to open an event (debug toggle may set 1). */
        val enterWindows: Int = DetectorConfig.ENTER_WINDOWS,
        /**
         * Clips already persisted for this session before this engine started. When a killed
         * process resumes the same session (START_STICKY restart), the per-night clip cap must
         * count the whole session, not just the current engine run.
         */
        val initialClipCount: Int = 0,
    )

    interface Listener {
        /**
         * Called on the capture thread whenever an event is finalized (clip already written,
         * if any). [appliedGain] and [filePeakDbfs] are the persisted audio_clip values — the
         * gain actually applied to the stored samples and the file's true peak in dBFS — and
         * are null whenever the event has no clip (level/budget-gated, horizon-skipped, or
         * clip write failure).
         */
        fun onEventFinalized(
            draft: EventDetector.EventDraft,
            clipRelativePath: String?,
            clipDurationMs: Long,
            clipSizeBytes: Long,
            appliedGain: Float?,
            filePeakDbfs: Double?,
        )

        fun onCaptureError(throwable: Throwable)

        /** Non-fatal operational events (clip write failures, etc.) for the session log. */
        fun onLog(event: String, detail: String)
    }

    /** Per-night capture statistics (docs/DATA_MODEL.md §5.1 captureStats). */
    data class NightStats(
        val windowsProcessed: Long,
        val windowsSkipped: Long,
        val ambientDbfsMedian: Double?,
        val ambientDbfsP95: Double?,
        val clipCount: Int,
        val clipBytesTotal: Long,
        val thermalThrottleEvents: Int,
        /** Events dropped by the relative level gate (DetectorConfig.gateThresholdDbfs). */
        val levelGateSkipped: Int = 0,
        /** Thermally skipped analysis hops as merged epoch spans — analysis-correctable coverage. */
        val skippedHopSpans: List<SkippedHopSpan> = emptyList(),
        /** Mean window dBFS per 5-minute epoch bucket — quiet-sleep vs no-capture evidence. */
        val ambientBuckets: List<com.sleepanalysis.domain.report.AmbientLevelBucket> = emptyList(),
    )

    private val windowSamples = (config.sampleRate * config.windowMs / 1000).toInt()
    private val hopSamples = (config.sampleRate * config.hopMs / 1000).toInt()
    private val clipSamples = (config.sampleRate * config.clipMs / 1000).toInt()

    /** See [DetectorConfig.LOW_PASS_ALPHA] for the value's provenance. */
    private val lowPassAlpha = DetectorConfig.LOW_PASS_ALPHA

    /** Preallocated spectral-feature scratch — the capture thread must not allocate per window. */
    private val spectralWorkspace = SpectralFeatures.Workspace(config.sampleRate)

    private val clipRing = RingBuffer(clipSamples)

    /**
     * Preallocated RAW (pre-gain) scratch for the capture reads — the clip ring must hold the
     * UNGAINED hop so the clip write path can float-gain + peak-limit instead of inheriting
     * [applyGain]'s int16-clamped peaks (session 4: loud clips written flat-topped at 0 dBFS).
     * [applyGain] mutates the read buffer in place for the analysis path, so the raw hop is
     * copied here first. Two buffers: [fillBlocking] reads a whole window, the main loop one
     * hop. Mirrors the [SpectralFeatures.Workspace] no-allocation-in-the-hot-loop rule.
     */
    private val rawWindowBuf = ShortArray(windowSamples)
    private val rawHopBuf = ShortArray(hopSamples)
    private val ambientBuckets = AmbientLevelBuckets()
    private val eventDetector = EventDetector(
        enterWindows = config.enterWindows,
        windowMs = config.windowMs,
        hopMs = config.hopMs,
        gateMarginDb = config.gateMarginDb,
        gateHardFloorDbfs = config.gateHardFloorDbfs,
    )
    private val clipPolicy = ClipPolicy(
        gateMarginDb = config.gateMarginDb,
        gateHardFloorDbfs = config.gateHardFloorDbfs,
        maxPerHour = config.maxClipsPerHour,
        maxTotal = config.maxClipsPerNight,
        initialTotal = config.initialClipCount,
    )
    private val skippedHops = HopSkipLedger()
    private val noiseFloorRing = DoubleArray(DetectorConfig.NOISE_FLOOR_WINDOW_COUNT)
    private var noiseFloorCount = 0
    private var noiseFloorIndex = 0

    /**
     * Ambient levels for session statistics. Mutated by the capture thread, iterated by
     * [stats] from the service heartbeat — every access to the list itself holds [statsLock].
     * (Night 1 dogfood: an unsynchronized read here crashed the process with a
     * ConcurrentModificationException after ~7.75 h, killing the recording.)
     */
    private val statsLock = Any()
    private val ambientDbfs = ArrayList<Double>(64_000)

    /** Epoch ms of the first captured sample — the base for event absolute timestamps. */
    @Volatile private var captureStartEpochMs = 0L

    @Volatile private var running = false
    @Volatile private var throttled = false
    private var thread: Thread? = null

    /**
     * Platform AGC effect, disabled so the software gain below is the only level authority.
     * Held for the engine's lifetime instead of released right after disabling: on some OEMs
     * releasing re-enables AGC, which then fights the fixed software gain. Released in the
     * capture loop's finally (capture-thread-confined).
     */
    private var agc: AutomaticGainControl? = null

    // Capture-thread-only counters. @Volatile because stats() reads them from other threads.
    @Volatile private var windowsProcessed = 0L
    @Volatile private var windowsSkipped = 0L
    @Volatile private var thermalThrottleEvents = 0
    @Volatile private var levelGateSkipped = 0
    @Volatile private var clipBytes = 0L
    /** Hour bucket of the last clip_budget_skipped log — rate-limits the session log. Capture-thread-only. */
    private var lastBudgetLogBucket = Long.MIN_VALUE
    private var lastCooldownLogBucket = Long.MIN_VALUE

    fun start() {
        check(!running) { "AudioEngine already running" }
        running = true
        detector.reset()
        eventDetector.reset()
        noiseFloorCount = 0
        noiseFloorIndex = 0
        synchronized(statsLock) { ambientDbfs.clear() }
        windowsProcessed = 0
        windowsSkipped = 0
        thermalThrottleEvents = 0
        levelGateSkipped = 0
        lastBudgetLogBucket = Long.MIN_VALUE
        clipBytes = 0
        skippedHops.reset()
        captureStartEpochMs = 0L
        thread = Thread({ captureLoop() }, "SleepAudioCapture").apply { start() }
    }

    /** Signals the loop to finish; still-open events are flushed. Blocks until stopped. */
    fun stop() {
        running = false
        thread?.let { t ->
            try {
                t.join(5_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }

    /** Thermal response (docs/ARCHITECTURE.md §5): analyze every 2nd hop while throttled. */
    fun setThrottled(enabled: Boolean) {
        if (enabled && !throttled) thermalThrottleEvents++
        throttled = enabled
    }

    fun stats(): NightStats {
        // Snapshot under the lock, sort outside it — the capture thread keeps adding ~2/s.
        val sorted = synchronized(statsLock) { ambientDbfs.toDoubleArray() }.also { it.sort() }
        fun percentile(p: Double): Double? =
            if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * p).toInt().coerceAtLeast(0)]
        return NightStats(
            windowsProcessed = windowsProcessed,
            windowsSkipped = windowsSkipped,
            ambientDbfsMedian = percentile(0.5),
            ambientDbfsP95 = percentile(0.95),
            clipCount = clipPolicy.totalWritten,
            clipBytesTotal = clipBytes,
            thermalThrottleEvents = thermalThrottleEvents,
            levelGateSkipped = levelGateSkipped,
            skippedHopSpans = skippedHops.spans(),
            ambientBuckets = ambientBuckets.snapshot().map {
                com.sleepanalysis.domain.report.AmbientLevelBucket(it.startMs, it.endMs, it.meanDbfs)
            },
        )
    }

    /**
     * Epoch ms of the engine's first captured sample. Event absolute times are
     * `captureStartEpochMs() + draft.startMs`; using the session start instead backdates every
     * event of a resumed engine (night 1 dogfood bug). Returns 0 before capture began —
     * callers must fall back to the session start.
     */
    fun captureStartEpochMs(): Long = captureStartEpochMs

    @SuppressLint("MissingPermission") // MonitorService verifies RECORD_AUDIO before start().
    private fun captureLoop() {
        // The whole init sequence runs inside the try: AudioRecord construction, buffer-size
        // queries and the audiofx calls are OEM-flaky, and a throw from any of them used to
        // escape this loop and kill the process via the default uncaught handler (the exact
        // overnight-death mode this class documents elsewhere). Any init failure now reaches
        // Listener.onCaptureError, so the service finalizes the session INTERRUPTED instead.
        var record: AudioRecord? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val minBuffer = AudioRecord.getMinBufferSize(
                config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                listener.onCaptureError(IllegalStateException("AudioRecord.getMinBufferSize failed: $minBuffer"))
                return
            }
            // UNPROCESSED skips the platform's level/EQ processing for maximum headroom; fall back
            // to MIC on devices where it fails to initialize. (minSdk 30 guarantees the constant.)
            val bufferSize = maxOf(minBuffer * 2, hopSamples * 4)
            var source = MediaRecorder.AudioSource.UNPROCESSED
            val opened = tryInitRecord(source, bufferSize)
                ?: tryInitRecord(MediaRecorder.AudioSource.MIC, bufferSize).also {
                    if (it != null) source = MediaRecorder.AudioSource.MIC
                }
            if (opened == null) {
                listener.onCaptureError(IllegalStateException("AudioRecord initialization failed"))
                return
            }
            record = opened
            // Disable the platform's automatic gain control so the software gain below is the
            // only level authority — hardware AGC silently rescaling samples would fight the
            // fixed factor. The effect is held in [agc] for the engine's lifetime (releasing
            // it here lets some OEMs re-enable AGC) and released in the finally below.
            try {
                agc = AutomaticGainControl.create(opened.audioSessionId)?.also { it.enabled = false }
            } catch (_: UnsupportedOperationException) {
                // No AGC effect on this input — nothing to disable.
            }
            listener.onLog("capture_source", "source=$source gain=${config.micGain}")

            val windowBuf = ShortArray(windowSamples)
            val hopBuf = ShortArray(hopSamples)
            var hopCounter = 0L

            opened.startRecording()
            captureStartEpochMs = System.currentTimeMillis()
            // Prime the first full window. A failed priming read (device error or a stop
            // mid-fill) must not feed a stale/zero-filled window into the detector and the
            // noise floor — bail out exactly like a failed loop read below.
            if (!fillBlocking(opened, windowBuf, windowSamples)) return

            while (running) {
                analyzeWindow(windowBuf, hopCounter)
                hopCounter++

                if (readBlocking(opened, hopBuf, hopSamples, rawHopBuf) < hopSamples) break

                // Slide the window forward by one hop; ring buffer always advances so clips
                // stay available even while thermal throttling skips analysis. The window
                // keeps the GAINED hop (analysis path, unchanged); the ring gets the RAW one
                // (clip path — ClipStore gains in float + peak-limits at write time).
                System.arraycopy(windowBuf, hopSamples, windowBuf, 0, windowSamples - hopSamples)
                System.arraycopy(hopBuf, 0, windowBuf, windowSamples - hopSamples, hopSamples)
                clipRing.write(rawHopBuf)
            }
        } catch (t: Throwable) {
            listener.onCaptureError(t)
        } finally {
            record?.let { r ->
                try {
                    r.stop()
                } catch (_: IllegalStateException) {
                }
                runCatching { agc?.release() }
                agc = null
                runCatching { deliver(eventDetector.flush()) }
                runCatching { r.release() }
            }
        }
    }

    /** Features + detection for the window currently held in [windowBuf] (ends "now"). */
    private fun analyzeWindow(windowBuf: ShortArray, hopCounter: Long) {
        var sum = 0.0
        var lp = 0.0
        var lpSum = 0.0
        for (i in 0 until windowSamples) {
            val x = windowBuf[i].toDouble()
            sum += x * x
            lp += lowPassAlpha * (x - lp)
            lpSum += lp * lp
        }
        val rms = sqrt(sum / windowSamples)
        val dbfs = if (rms <= 0.0) -100.0 else 20 * log10(rms / 32768.0)
        val lpRms = sqrt(lpSum / windowSamples)
        val lowRatio = if (rms <= 0.0) 0.0 else (lpRms / rms).coerceIn(0.0, 1.0)

        noiseFloorRing[noiseFloorIndex] = dbfs
        noiseFloorIndex = (noiseFloorIndex + 1) % noiseFloorRing.size
        noiseFloorCount = minOf(noiseFloorCount + 1, noiseFloorRing.size)
        synchronized(statsLock) {
            if (ambientDbfs.size < 100_000) ambientDbfs.add(dbfs)
        }
        // Level buckets anchor on the same epoch expression as the skipped-hop spans —
        // a window-END anchor (fillBlocking writes the primed window before the first
        // analysis); harmless at 5-minute granularity, consistent across both consumers.
        val bucketBase = captureStartEpochMs
        if (bucketBase > 0L) {
            ambientBuckets.add(bucketBase + clipRing.totalWritten * 1000 / config.sampleRate, dbfs)
        }
        windowsProcessed++

        // Thermal throttle: skip classification, keep the ring buffer advancing. Record the
        // skipped hop (epoch-anchored span) so analysis can correct for the reduced rate.
        // Sits BEFORE any spectral work: a skipped window must do no FFT (allocation-free path).
        if (throttled && hopCounter % 2 != 0L) {
            windowsSkipped++
            val base = captureStartEpochMs
            if (base > 0L) {
                skippedHops.skipped(base + clipRing.totalWritten * 1000 / config.sampleRate)
            }
            return
        }

        // Stage 2 spectral features, once per ANALYZED window (31 frames x 1024-pt FFT +
        // one full-window autocorrelation, ~3.2M multiply-adds — fine within the 500 ms hop
        // budget, never per hop; scratch comes from the preallocated workspace, allocation-free).
        val spectral = spectralWorkspace.compute(windowBuf)

        val features = AudioFeatures(
            windowDbfs = dbfs,
            noiseFloorDbfs = currentNoiseFloor(),
            lowBandRatio = lowRatio,
            timestampMs = clipRing.totalWritten * 1000 / config.sampleRate,
            spectralFlatness = spectral.flatness,
            harmonicRatio = spectral.harmonicRatio,
        )
        val result = detector.classify(features)
        val drafts = eventDetector.update(
            timestampMs = features.timestampMs,
            features = features,
            result = result,
        )
        deliver(drafts)
    }

    private fun currentNoiseFloor(): Double {
        if (noiseFloorCount == 0) return -70.0
        val copy = DoubleArray(noiseFloorCount)
        System.arraycopy(noiseFloorRing, 0, copy, 0, noiseFloorCount)
        copy.sort()
        return copy[((noiseFloorCount - 1) * DetectorConfig.NOISE_FLOOR_PERCENTILE).toInt().coerceAtLeast(0)]
    }

    /**
     * Persists finalized events. Clips are gated by [ClipPolicy.decide]: the relative level
     * gate (mean dBFS over the event's noise floor — noise-floor rumble never persists a clip
     * and is counted in [NightStats.levelGateSkipped]), the per-hour budget, and the per-night
     * backstop. Budget- and level-skipped events are still delivered to the database, only
     * their audio is dropped. A third gate ([RingBuffer.holdsEventAudio]) refuses the write —
     * and the budget booking — when the event's audio has already slid past the clip ring
     * horizon (thermally-delayed emission; the event is likewise delivered without a clip).
     */
    private fun deliver(finalized: List<EventDetector.EventDraft>) {
        // Absolute event start names the clip file. Engine-relative names would collide when a
        // resumed engine (START_STICKY restart) re-uses offsets starting at 0 and overwrite the
        // pre-death segment's clips. The finally-flush can run after a failed startRecording(),
        // before the time base is set — persist nothing then.
        val clipTimeBase = captureStartEpochMs
        for (draft in finalized) {
            var clipRelPath: String? = null
            var clipDuration = 0L
            var clipSize = 0L
            var clipAppliedGain: Float? = null
            var clipFilePeakDbfs: Double? = null
            val eventStartEpochMs = clipTimeBase + draft.startMs
            if (clipTimeBase > 0L) {
                when (
                    val decision = clipPolicy.decide(
                        meanDbfs = draft.meanDbfs,
                        noiseFloorDbfs = draft.features.noiseFloorDbfsMean,
                        peakDbfs = draft.peakDbfs,
                        eventStartEpochMs = eventStartEpochMs,
                        captureStartEpochMs = clipTimeBase,
                        type = draft.type,
                    )
                ) {
                    ClipPolicy.Decision.WRITE, ClipPolicy.Decision.WRITE_LOUD_RESERVE -> {
                        // Deferred emission delays delivery: at the shipped gap of 0 drafts
                        // arrive ≤ ~2.5 s after endMs (idle bound endMs + WINDOW_MS; hold cap
                        // endMs + 5 s + one hop when a machine is busy), so the 8 s snapshot
                        // covers ≥ 2.5 s of the event head plus its full tail — comfortably
                        // inside the ring (the v1 2 s gap capped at 7.5 s, 0.5 s inside).
                        // Horizon guard (defense in depth): if the event's tail has slid past
                        // the ring horizon anyway (thermally-delayed emission, or a config
                        // whose hold bounds exceed clipMs), the snapshot would hold zero
                        // event audio — persist nothing and do not book the budget; the event
                        // still reaches the database below, like budget-skipped ones.
                        if (
                            RingBuffer.holdsEventAudio(
                                ringEndMs = clipRing.totalWritten * 1000L / config.sampleRate,
                                eventEndMs = draft.endMs,
                                spanMs = config.clipMs,
                            )
                        ) {
                            val samples = clipRing.snapshotLast(clipSamples) // RAW, pre-gain audio
                            if (samples.isNotEmpty()) {
                                runCatching {
                                    val written = clipStore.writeClip(
                                        sessionId, draft.type, eventStartEpochMs,
                                        samples, config.sampleRate, config.micGain,
                                    )
                                    clipRelPath = clipStore.relativePath(written.file)
                                    clipDuration = samples.size * 1000L / config.sampleRate
                                    clipSize = written.file.length()
                                    clipBytes += clipSize
                                    clipAppliedGain = written.appliedGain
                                    clipFilePeakDbfs = written.filePeakDbfs
                                    clipPolicy.onWritten(
                                        draft.peakDbfs,
                                        eventStartEpochMs,
                                        decision == ClipPolicy.Decision.WRITE_LOUD_RESERVE,
                                        type = draft.type,
                                        eventEndEpochMs = clipTimeBase + draft.endMs,
                                    )
                                }.onFailure {
                                    // A failed clip write must never break the capture loop or drop the event.
                                    listener.onLog("clip_write_failed", "${it.javaClass.simpleName}: ${it.message}")
                                }
                            }
                        } else {
                            listener.onLog(
                                "clip_horizon_skipped",
                                "type=${draft.type} endMs=${draft.endMs} ringEndMs=${clipRing.totalWritten * 1000L / config.sampleRate}",
                            )
                        }
                    }
                    ClipPolicy.Decision.SKIP_LEVEL -> levelGateSkipped++
                    ClipPolicy.Decision.SKIP_BUDGET -> {
                        // Passed the level gate but out of budget — a burst is spending its
                        // hourly share. Log once per hour bucket; events still reach the DB.
                        val bucket = eventStartEpochMs / ClipPolicy.HOUR_MS
                        if (bucket != lastBudgetLogBucket) {
                            lastBudgetLogBucket = bucket
                            listener.onLog(
                                "clip_budget_skipped",
                                "type=${draft.type} meanDbfs=${draft.meanDbfs} total=${clipPolicy.totalWritten}",
                            )
                        }
                    }
                    ClipPolicy.Decision.SKIP_COOLDOWN -> {
                        // Inside a bout cooldown with no upgrade — the EXPECTED common case
                        // under burst-level detection (a 110-burst bout yields ~15 clips).
                        // Separate event name so cooldown suppression is never misread as
                        // budget exhaustion. Same once-per-hour-bucket rate limit.
                        val bucket = eventStartEpochMs / ClipPolicy.HOUR_MS
                        if (bucket != lastCooldownLogBucket) {
                            lastCooldownLogBucket = bucket
                            listener.onLog(
                                "clip_cooldown_skipped",
                                "type=${draft.type} meanDbfs=${draft.meanDbfs} total=${clipPolicy.totalWritten}",
                            )
                        }
                    }
                }
            }
            listener.onEventFinalized(draft, clipRelPath, clipDuration, clipSize, clipAppliedGain, clipFilePeakDbfs)
        }
    }

    /**
     * Digital gain step for the ANALYSIS path: scales each PCM16 sample by [Config.micGain]
     * in place. Pure multiply + clamp (coerced back to Short) — branch-light and allocation-
     * free, so it is real-time safe on the capture thread. Clamping hard-limits loud snore
     * peaks to the Short range instead of wrapping on overflow — which is exactly why the
     * CLIP path no longer runs through it: the ring holds the raw hop and ClipStore re-applies
     * the gain in float with a block peak-limit at write time.
     */
    private fun applyGain(samples: ShortArray, count: Int) {
        val gain = config.micGain
        for (i in 0 until count) {
            val scaled = (samples[i] * gain).toInt()
            samples[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Builds an AudioRecord for [source]; null if the source fails to initialize on this device.
     * The constructor needs RECORD_AUDIO — MonitorService verifies it (and bails with a
     * user-visible error) before the engine is ever started.
     */
    @SuppressLint("MissingPermission") // MonitorService verifies RECORD_AUDIO before start().
    private fun tryInitRecord(source: Int, bufferSize: Int): AudioRecord? {
        val record = AudioRecord(
            source,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        return if (record.state == AudioRecord.STATE_INITIALIZED) {
            record
        } else {
            record.release()
            null
        }
    }

    /**
     * Blocking read of exactly [n] samples into [buf], preserving the raw (pre-gain) samples
     * into [raw] at the same offsets; returns samples read (< n on error/stop). [buf] leaves
     * this function gained, exactly as before — only the analysis path consumes it. [raw]
     * must be at least [n] long (callers pass the matching rawWindowBuf/rawHopBuf scratch).
     */
    private fun readBlocking(record: AudioRecord, buf: ShortArray, n: Int, raw: ShortArray): Int {
        var filled = 0
        while (filled < n && running) {
            val got = record.read(buf, filled, n - filled)
            if (got < 0) {
                listener.onCaptureError(IllegalStateException("AudioRecord.read failed: $got"))
                return -1
            }
            if (got == 0) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                continue
            }
            // Keep the raw hop for the clip ring, then gain in place — the analysis path sees
            // the identical gained samples as before (bit-identical pipeline), the ring feeds
            // the float-gain + peak-limit clip writer.
            System.arraycopy(buf, filled, raw, filled, got)
            applyGain(buf, got)
            filled += got
        }
        return if (running) filled else -1
    }

    /**
     * Blocking read of the first full window into [buf]. Returns false when the read failed
     * or the engine was stopped mid-fill — the caller must then not analyze the stale or
     * zero-filled window (readBlocking already reported the error). The primed window enters
     * the clip ring RAW (like every hop after it).
     */
    private fun fillBlocking(record: AudioRecord, buf: ShortArray, n: Int): Boolean {
        val got = readBlocking(record, buf, n, rawWindowBuf)
        if (got > 0) clipRing.write(rawWindowBuf, got)
        return got == n
    }
}
