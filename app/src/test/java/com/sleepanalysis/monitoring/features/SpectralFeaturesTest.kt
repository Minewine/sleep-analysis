package com.sleepanalysis.monitoring.features

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectralFeaturesTest {

    private val sampleRate = 16_000

    /** One full 1 s window (the live window size), so every helper gets whole frames. */
    private fun window() = ShortArray(sampleRate)

    /** 120 Hz fundamental with decaying harmonics — the tonal/peaked shape of real snore energy. */
    private fun harmonicStack(amplitude: Double = 6000.0): ShortArray {
        val samples = window()
        val partials = listOf(120.0 to 1.0, 240.0 to 0.6, 360.0 to 0.4, 480.0 to 0.25, 600.0 to 0.15)
        for (i in samples.indices) {
            val t = i.toDouble() / sampleRate
            var v = 0.0
            for ((f, a) in partials) v += a * sin(2 * PI * f * t)
            samples[i] = (amplitude * v / partials.sumOf { it.second }).toInt().toShort()
        }
        return samples
    }

    private fun whiteNoise(seed: Long, amplitude: Double = 3000.0): ShortArray {
        val random = Random(seed)
        val samples = window()
        for (i in samples.indices) {
            samples[i] = (random.nextGaussian() * amplitude).toInt().toShort()
        }
        return samples
    }

    @Test
    fun `harmonic tone stack is tonal - high harmonic ratio, low flatness`() {
        val result = SpectralFeatures.compute(harmonicStack(), sampleRate)
        assertTrue("harmonicRatio ${result.harmonicRatio}", result.harmonicRatio > 0.6)
        assertTrue("flatness ${result.flatness}", result.flatness < 0.3)
        // The 120 Hz fundamental is the strongest partial -> dominant bin near 120 Hz
        // (bin width 15.625 Hz at frameSize 1024).
        assertTrue("dominant ${result.dominantFreqHz}", result.dominantFreqHz in 100.0..140.0)
    }

    @Test
    fun `white noise is flat - high flatness, low harmonic ratio`() {
        val result = SpectralFeatures.compute(whiteNoise(seed = 1234), sampleRate)
        assertTrue("flatness ${result.flatness}", result.flatness > 0.8)
        assertTrue("harmonicRatio ${result.harmonicRatio}", result.harmonicRatio < 0.2)
    }

    @Test
    fun `mel bands are finite, correctly sized, and trend downward for the tone stack`() {
        val harmonic = SpectralFeatures.compute(harmonicStack(), sampleRate)
        assertEquals(16, harmonic.melBands.size)
        for (band in harmonic.melBands) {
            assertTrue("band $band", band.isFinite())
        }
        // Energy concentrated at the low fundamental/harmonics: lowest band well above the
        // highest band (both are dB, so the gap is large but finite).
        assertTrue(
            "first ${harmonic.melBands.first()} last ${harmonic.melBands.last()}",
            harmonic.melBands.first() > harmonic.melBands.last(),
        )

        val noise = SpectralFeatures.compute(whiteNoise(seed = 99), sampleRate)
        // Broadband noise fills the spectrum roughly evenly: band spread stays small.
        val spread = noise.melBands.max() - noise.melBands.min()
        assertTrue("spread $spread", spread < 12.0)
    }

    @Test
    fun `silence yields neutral descriptors without NaN`() {
        val result = SpectralFeatures.compute(ShortArray(sampleRate), sampleRate)
        assertEquals(0.0, result.flatness, 0.0)
        assertEquals(0.0, result.harmonicRatio, 0.0)
        for (band in result.melBands) assertTrue(band.isFinite())
    }

    @Test
    fun `results are deterministic`() {
        val a = SpectralFeatures.compute(harmonicStack(), sampleRate)
        val b = SpectralFeatures.compute(harmonicStack(), sampleRate)
        assertEquals(a.flatness, b.flatness, 0.0)
        assertEquals(a.harmonicRatio, b.harmonicRatio, 0.0)
        assertEquals(a.dominantFreqHz, b.dominantFreqHz, 0.0)
    }

    @Test
    fun `workspace reuse matches the allocating path and melBands alias is documented`() {
        val workspace = SpectralFeatures.Workspace(sampleRate)
        val first = workspace.compute(harmonicStack())
        val second = workspace.compute(whiteNoise(seed = 99))
        val direct = SpectralFeatures.compute(whiteNoise(seed = 99), sampleRate)
        // The reusable path must be numerically identical to the allocating one.
        assertEquals(direct.flatness, second.flatness, 0.0)
        assertEquals(direct.harmonicRatio, second.harmonicRatio, 0.0)
        assertEquals(direct.dominantFreqHz, second.dominantFreqHz, 0.0)
        // Documented aliasing: first.melBands IS the workspace buffer, overwritten by the
        // second call — callers retaining results must copy it.
        assertTrue(first.melBands.contentEquals(second.melBands))
        assertFalse(first.melBands.contentEquals(SpectralFeatures.compute(harmonicStack(), sampleRate).melBands))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-power-of-two frame size throws`() {
        SpectralFeatures.compute(window(), sampleRate, frameSize = 1000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frame size above sample rate throws`() {
        SpectralFeatures.compute(window(), sampleRate, frameSize = 32_768)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero hop size throws`() {
        SpectralFeatures.compute(window(), sampleRate, hopSize = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `band count out of range throws`() {
        SpectralFeatures.compute(window(), sampleRate, bandCount = 65)
    }
}
