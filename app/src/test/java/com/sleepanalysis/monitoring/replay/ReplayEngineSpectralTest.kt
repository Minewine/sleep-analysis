package com.sleepanalysis.monitoring.replay

import com.sleepanalysis.monitoring.detection.AudioFeatures
import com.sleepanalysis.monitoring.detection.DetectionResult
import com.sleepanalysis.monitoring.detection.EnergySnoreDetector
import com.sleepanalysis.monitoring.detection.SnoreDetector
import com.sleepanalysis.monitoring.features.SpectralFeatures
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that replay computes the stage 2 spectral features (flatness, harmonic ratio) with
 * the same values the live pipeline would see — the fixture eval is only meaningful if replay
 * matches [com.sleepanalysis.monitoring.audio.AudioEngine] feature-for-feature — and that the
 * v0 features (dBFS, low-band ratio) are unchanged by the addition.
 */
class ReplayEngineSpectralTest {

    private val sampleRate = 16_000

    /** Per-partial relative amplitudes of the 120 Hz harmonic stack. */
    private val partials = listOf(120.0 to 1.0, 240.0 to 0.6, 360.0 to 0.4, 480.0 to 0.25, 600.0 to 0.15)

    /** 120 Hz harmonic stack (snore-like), one full 1 s window plus one hop. */
    private fun harmonicClip(): ShortArray {
        val samples = ShortArray(sampleRate + sampleRate / 2)
        for (i in samples.indices) {
            val t = i.toDouble() / sampleRate
            var v = 0.0
            for ((f, a) in partials) v += a * sin(2 * PI * f * t)
            samples[i] = (6000.0 * v / partials.sumOf { it.second }).toInt().toShort()
        }
        return samples
    }

    @Test
    fun `replay produces the same spectral descriptors as SpectralFeatures directly`() {
        val clip = harmonicClip()
        val result = ReplayEngine(RecordingDetector()).replay(clip)

        assertEquals(2, result.windows.size) // 1.5 s clip: window ends at 1000 and 1500 ms
        // Each replay window must match a direct SpectralFeatures.compute over the SAME samples.
        val window0Samples = clip.copyOf(16_000)
        val window1Samples = clip.copyOfRange(8_000, 24_000)
        val direct0 = SpectralFeatures.compute(window0Samples, sampleRate)
        val direct1 = SpectralFeatures.compute(window1Samples, sampleRate)
        assertEquals(direct0.flatness, result.windows[0].spectralFlatness, 1e-9)
        assertEquals(direct0.harmonicRatio, result.windows[0].harmonicRatio, 1e-9)
        assertEquals(direct1.flatness, result.windows[1].spectralFlatness, 1e-9)
        assertEquals(direct1.harmonicRatio, result.windows[1].harmonicRatio, 1e-9)
        // Same tolerances as SpectralFeaturesTest: tonal content is peaked and periodic.
        assertTrue("flatness ${result.windows.first().spectralFlatness}", result.windows.first().spectralFlatness < 0.3)
        assertTrue("harmonicRatio ${result.windows.first().harmonicRatio}", result.windows.first().harmonicRatio > 0.6)
    }

    @Test
    fun `v0 features are unchanged by the spectral addition`() {
        val result = ReplayEngine(RecordingDetector()).replay(harmonicClip())
        val window = result.windows.first()
        // rms of the summed partials: per-unit-peak scale 6000/sum(a), rms = scale*sqrt(sum(a^2)/2).
        val scale = 6000.0 / partials.sumOf { it.second }
        val rms = scale * sqrt(partials.sumOf { it.second * it.second } / 2.0)
        val expectedDbfs = 20 * log10(rms / 32768.0)
        assertEquals(expectedDbfs, window.windowDbfs, 0.5)
        // A 120 Hz tone passes the ~1 kHz one-pole low-pass almost fully -> ratio near 1.
        assertTrue("lowBandRatio ${window.lowBandRatio}", window.lowBandRatio > 0.9)
        // Spectral fields flow into the detector input, not just the debug result.
        assertTrue(window.spectralFlatness.isFinite())
        assertTrue(window.harmonicRatio.isFinite())
    }

    @Test
    fun `replay through the real detector matches manually feeding the same features`() {
        // End-to-end equivalence: the DetectionResult the replay produced must be exactly the
        // one an EnergySnoreDetector returns when handed the reported window features — i.e.
        // replay adds no hidden feature math beyond SpectralFeatures + the v0 window formulas.
        val detector = EnergySnoreDetector()
        val result = ReplayEngine(detector).replay(harmonicClip())
        assertTrue(result.windows.isNotEmpty())

        val manual = EnergySnoreDetector()
        for (w in result.windows) {
            val expected = manual.classify(
                AudioFeatures(
                    windowDbfs = w.windowDbfs,
                    noiseFloorDbfs = w.noiseFloorDbfs,
                    lowBandRatio = w.lowBandRatio,
                    timestampMs = w.timestampMs,
                    spectralFlatness = w.spectralFlatness,
                    harmonicRatio = w.harmonicRatio,
                ),
            )
            assertEquals("window ${w.timestampMs} snore", expected.snore, w.snore)
            assertEquals("window ${w.timestampMs} confidence", expected.confidence, w.confidence, 1e-12)
            assertEquals("window ${w.timestampMs} loud", expected.loudAcousticEvent, w.loudAcousticEvent)
        }
    }

    @Test
    fun `replay passes the configured window and hop into event start backdating`() {
        // EventDetector backdates event starts by windowMs from the first pending stamp. The
        // replay engine must hand the CONFIGURED window/hop to the detector — exactly what
        // AudioEngine does — instead of letting it fall back to the DetectorConfig defaults.
        // A non-default config makes the difference observable: startMs = firstPending - 500.
        val config = ReplayEngine.Config(windowMs = 500, hopMs = 250)
        // Snore-flagged windows from stamp 1000 ms on (window ends 500, 750, 1000, ...).
        val detector = SnoreFromMs(1_000L)
        val result = ReplayEngine(detector, config).replay(ShortArray(48_000))

        assertEquals(
            listOf(500L, 750L, 1000L, 1250L, 1500L, 1750L, 2000L, 2250L, 2500L, 2750L, 3000L),
            result.windows.map { it.timestampMs },
        )
        assertEquals(1, result.events.size)
        val event = result.events.single()
        assertEquals(500L, event.startMs) // 1000 - windowMs(500); the 1000 ms default would give 0
        assertEquals(3000L, event.endMs)  // last flagged window end, closed by flush()
    }

    /** Passive detector: asserts the spectral wiring, denies everything. */
    private class RecordingDetector : SnoreDetector {
        override fun classify(features: AudioFeatures): DetectionResult {
            checkNotNull(features.spectralFlatness) { "replay must populate spectralFlatness" }
            checkNotNull(features.harmonicRatio) { "replay must populate harmonicRatio" }
            return DetectionResult(false, false, 0.0)
        }

        override fun reset() = Unit
    }

    /** Flags every window snore/loud from [thresholdMs] on — deterministic event shaping input. */
    private class SnoreFromMs(private val thresholdMs: Long) : SnoreDetector {
        override fun classify(features: AudioFeatures): DetectionResult {
            val on = features.timestampMs >= thresholdMs
            return DetectionResult(snore = on, loudAcousticEvent = on, confidence = if (on) 0.8 else 0.0)
        }

        override fun reset() = Unit
    }
}
