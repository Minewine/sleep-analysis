package com.sleepanalysis.monitoring.replay

import com.sleepanalysis.monitoring.detection.AudioFeatures
import com.sleepanalysis.monitoring.detection.DetectorConfig
import com.sleepanalysis.monitoring.detection.EventDetector
import com.sleepanalysis.monitoring.detection.SnoreDetector
import com.sleepanalysis.monitoring.features.SpectralFeatures
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Offline replay of recorded clip audio through the detection pipeline, feature-for-feature
 * identical to the live loop in [com.sleepanalysis.monitoring.audio.AudioEngine] — the fixture
 * eval harness (docs/NEXT_STEPS.md P2) exists so detector changes can be evaluated against
 * labeled clips (user labels from clip playback) without a device, and that only means
 * something if replay computes the same window dBFS, low-band ratio, noise floor and window
 * timing the detector saw live. docs/ARCHITECTURE.md §9: "Fixture ML eval: labeled WAV
 * fixtures → per-class precision/recall".
 *
 * Alignment semantics (mirroring the live loop):
 *  - timestamps are window END times in ms relative to clip start: the first window covers
 *    samples 0..16000 and is stamped 1000 ms; every hop slides the window forward 0.5 s and
 *    stamps the new end (1500, 2000, ...). EventDetector backdates event starts from these,
 *    exactly as it does live — replay passes timestamps and window count through unchanged.
 *  - the one-pole low-pass state (`lp`) is reset to 0.0 at each window start, as AudioEngine's
 *    analyzeWindow keeps it local per call — replay reproduces that (including its per-window
 *    transient) rather than a "better" continuous filter.
 *  - the noise-floor ring can be pre-filled via [Config.initialFloorDbfs] — the session's
 *    recorded noise floor (export_fixtures writes the median event floor into the manifest).
 *    Without it the ring starts empty and every window of the clip sees a floor built from
 *    the clip's own audio: SNR collapses to ~0 and the detector goes silent on exactly the
 *    clips a real night labeled as snoring (measured 2026-11-05: recall 0 across ALL classes
 *    with a cold ring, despite a live floor −84 dBFS vs snore peaks −11..−30 dBFS). The live
 *    engine's floor is warm from minutes of prior room tone; replay must be too.
 *
 * Pure JVM, no Android imports — runs in plain unit tests.
 */
class ReplayEngine(
    private val detector: SnoreDetector,
    private val config: Config = Config(),
) {

    /** Preallocated spectral-feature scratch, shared by every replay of this engine. */
    private val spectralWorkspace = SpectralFeatures.Workspace(config.sampleRate)

    data class Config(
        val sampleRate: Int = DetectorConfig.SAMPLE_RATE,
        val windowMs: Long = DetectorConfig.WINDOW_MS,
        val hopMs: Long = DetectorConfig.HOP_MS,
        val lowPassAlpha: Double = DetectorConfig.LOW_PASS_ALPHA,
        val noiseFloorWindowCount: Int = DetectorConfig.NOISE_FLOOR_WINDOW_COUNT,
        val noiseFloorPercentile: Double = DetectorConfig.NOISE_FLOOR_PERCENTILE,
        /**
         * Room-tone floor (dBFS) pre-filled into the noise-floor ring — the live steady state
         * after minutes of quiet audio. Null (default) keeps the historical cold-start ring,
         * which makes event-only clips unreplayable (see class KDoc).
         */
        val initialFloorDbfs: Double? = null,
        /** Consecutive active windows required to open an event — mirrors AudioEngine's Config. */
        val enterWindows: Int = DetectorConfig.ENTER_WINDOWS,
        /** Relative clip-gate margin — mirrors AudioEngine's Config (evaluated at finalization). */
        val gateMarginDb: Double = DetectorConfig.GATE_MARGIN_DB,
        /** Hard absolute floor of the gate threshold — mirrors AudioEngine's Config. */
        val gateHardFloorDbfs: Double = DetectorConfig.MIN_GATE_DBFS,
    )

    /** Per-window features as fed to the detector, plus its decision. */
    data class WindowResult(
        val timestampMs: Long,
        val windowDbfs: Double,
        val noiseFloorDbfs: Double,
        val lowBandRatio: Double,
        /** Stage 2 spectral descriptors — kept on the result for eval debugging/threshold tuning. */
        val spectralFlatness: Double,
        val harmonicRatio: Double,
        val snore: Boolean,
        val loudAcousticEvent: Boolean,
        val confidence: Double,
    )

    data class ReplayResult(
        val windows: List<WindowResult>,
        val events: List<EventDetector.EventDraft>,
    )

    fun replay(samples: ShortArray): ReplayResult {
        val windowSamples = sampleCount(config.windowMs)
        val hopSamples = sampleCount(config.hopMs)
        if (samples.size < windowSamples) return ReplayResult(emptyList(), emptyList())
        val eventDetector = EventDetector(
            enterWindows = config.enterWindows,
            windowMs = config.windowMs,
            hopMs = config.hopMs,
            gateMarginDb = config.gateMarginDb,
            gateHardFloorDbfs = config.gateHardFloorDbfs,
        )
        val windows = mutableListOf<WindowResult>()
        // The live loop delivers every draft update() finalizes (mid-clip exit-timeout closes
        // included) — replay must accumulate them too, not only the flush() tail, or every
        // event closed before the clip's last window would vanish from the harness output.
        val events = mutableListOf<EventDetector.EventDraft>()
        val windowBuf = ShortArray(windowSamples)
        System.arraycopy(samples, 0, windowBuf, 0, windowSamples) // prime the first full window
        val noiseFloorRing = DoubleArray(config.noiseFloorWindowCount)
        var noiseFloorIndex = 0
        var noiseFloorCount = 0
        config.initialFloorDbfs?.let { warm ->
            // Steady-state floor: the live engine's ring is dominated by minutes of room tone,
            // so a constant pre-fill reproduces the percentile exactly.
            noiseFloorRing.fill(warm)
            noiseFloorCount = noiseFloorRing.size
        }

        var windowStart = 0
        while (true) {
            // Feature math mirrors AudioEngine.analyzeWindow exactly, including the per-window
            // low-pass reset (`lp` is local to each live analyzeWindow call).
            var sum = 0.0
            var lp = 0.0
            var lpSum = 0.0
            for (j in 0 until windowSamples) {
                val x = windowBuf[j].toDouble()
                sum += x * x
                lp += config.lowPassAlpha * (x - lp)
                lpSum += lp * lp
            }
            val rms = sqrt(sum / windowSamples)
            val dbfs = if (rms <= 0.0) -100.0 else 20 * log10(rms / 32768.0)
            val lpRms = sqrt(lpSum / windowSamples)
            val lowRatio = if (rms <= 0.0) 0.0 else (lpRms / rms).coerceIn(0.0, 1.0)

            // Stage 2 spectral features with the live defaults (frame 1024 / hop 512 / 16 bands)
            // — the whole point of replay is that offline eval sees the exact features the
            // detector saw live, and the stage 2 gates read flatness/harmonicRatio.
            val spectral = spectralWorkspace.compute(windowBuf)

            noiseFloorRing[noiseFloorIndex] = dbfs
            noiseFloorIndex = (noiseFloorIndex + 1) % noiseFloorRing.size
            noiseFloorCount = minOf(noiseFloorCount + 1, noiseFloorRing.size)

            // Window END in engine-relative ms: samples written so far (live: clipRing
            // holds the first full window plus every hop written before this analysis).
            val timestampMs = (windowStart + windowSamples) * 1000L / config.sampleRate

            val features = AudioFeatures(
                windowDbfs = dbfs,
                noiseFloorDbfs = currentNoiseFloor(noiseFloorRing, noiseFloorCount),
                lowBandRatio = lowRatio,
                timestampMs = timestampMs,
                spectralFlatness = spectral.flatness,
                harmonicRatio = spectral.harmonicRatio,
            )
            val result = detector.classify(features)
            events += eventDetector.update(timestampMs, features, result)
            windows.add(
                WindowResult(
                    timestampMs = timestampMs,
                    windowDbfs = dbfs,
                    noiseFloorDbfs = features.noiseFloorDbfs,
                    lowBandRatio = lowRatio,
                    spectralFlatness = spectral.flatness,
                    harmonicRatio = spectral.harmonicRatio,
                    snore = result.snore,
                    loudAcousticEvent = result.loudAcousticEvent,
                    confidence = result.confidence,
                )
            )

            // Slide forward by one hop, exactly like the live loop's System.arraycopy step.
            // A partial tail (fewer than hop+window samples left) never completes a window,
            // so replay ends there — the live engine likewise never analyzes it.
            if (windowStart + hopSamples + windowSamples > samples.size) break
            System.arraycopy(windowBuf, hopSamples, windowBuf, 0, windowSamples - hopSamples)
            for (j in 0 until hopSamples) {
                windowBuf[windowSamples - hopSamples + j] = samples[windowStart + windowSamples + j]
            }
            windowStart += hopSamples
        }

        return ReplayResult(windows, events + eventDetector.flush())
    }

    private fun currentNoiseFloor(ring: DoubleArray, count: Int): Double {
        if (count == 0) return -70.0 // mirrors AudioEngine.currentNoiseFloor; unreachable mid-replay
        val copy = DoubleArray(count)
        System.arraycopy(ring, 0, copy, 0, count)
        copy.sort()
        return copy[((count - 1) * config.noiseFloorPercentile).toInt().coerceAtLeast(0)]
    }

    private fun sampleCount(ms: Long): Int = (ms * config.sampleRate / 1000L).toInt()
}
