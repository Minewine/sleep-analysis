package com.sleepanalysis.monitoring.features

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FftTest {

    private val sampleRate = 16_000

    @Test
    fun `on-bin sine peaks at its bin with analytic magnitude`() {
        val n = 1024
        val k = 7 // 7 full cycles in the frame -> the DFT is an exact single-bin delta
        val freq = k * sampleRate.toDouble() / n // 109.375 Hz
        val amplitude = 8000.0
        val frame = DoubleArray(n) { i -> amplitude * cos(2 * PI * freq * i / sampleRate) }

        val power = Fft.powerSpectrum(frame)

        var peakBin = 0
        for (bin in 1 until power.size) {
            if (power[bin] > power[peakBin]) peakBin = bin
        }
        assertEquals(k, peakBin)
        // Rectangular-windowed on-bin sine: |X[k]| = A * N / 2 (1% tolerance for rounding).
        val analyticMagnitude = amplitude * n / 2
        val magnitude = sqrt(power[k])
        assertTrue(
            "magnitude $magnitude vs analytic $analyticMagnitude",
            abs(magnitude / analyticMagnitude - 1.0) < 0.01,
        )
    }

    @Test
    fun `dc input has power only in bin 0`() {
        val n = 256
        val frame = DoubleArray(n) { 1000.0 }
        val power = Fft.powerSpectrum(frame)
        val dcSum = 1000.0 * n
        assertEquals(dcSum * dcSum, power[0], 1e-6)
        for (bin in 1 until power.size) {
            assertEquals(0.0, power[bin], 1e-6)
        }
    }

    @Test
    fun `matches naive dft on a fixed 16-point input`() {
        // Deterministic "random-ish" input (Random(42), rounded to ints) — fixed so failures
        // are reproducible; compares against the O(N^2) definition directly.
        val random = Random(42)
        val frame = DoubleArray(16) { (random.nextGaussian() * 100).toInt().toDouble() }

        val power = Fft.powerSpectrum(frame)

        for (k in power.indices) {
            var re = 0.0
            var im = 0.0
            for (t in frame.indices) {
                val angle = 2 * PI * k * t / frame.size
                re += frame[t] * cos(angle)
                im -= frame[t] * kotlin.math.sin(angle)
            }
            val expected = re * re + im * im
            assertEquals("bin $k", expected, power[k], abs(expected) * 1e-9 + 1e-6)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-power-of-two length throws`() {
        Fft.powerSpectrum(DoubleArray(1000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `single-sample length throws`() {
        Fft.powerSpectrum(DoubleArray(1))
    }
}
