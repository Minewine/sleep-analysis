package com.sleepanalysis.monitoring.features

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Per-window spectral descriptors for the stage 2 detector (docs/ARCHITECTURE.md §4
 * FeatureExtractor plan): spectral flatness and harmonic ratio discriminate tonal/peaked
 * snore energy from broadband household noise, and a log-mel filterbank is computed now (stored
 * on the result, NOT consumed by the detector yet) so the future YAMNet-style classifier can be
 * fed without touching the capture pipeline again.
 *
 * All math is pure JVM doubles and deterministic (no randomness). Cost per 1 s window at the
 * live defaults (16 kHz, frame 1024 / hop 512): ~31 frames x 1024-pt FFTs plus one
 * O(N x lagRange) autocorrelation over the 60-250 Hz lag range — the autocorrelation alone is
 * ~16 000 x 203 ≈ 3.2M multiply-adds, comfortably within the 500 ms hop budget but NOT
 * "microseconds"; it must stay per-window, never per-hop or per-sample. On the audio capture
 * thread use [Workspace.compute], which allocates nothing in steady state — the allocating
 * [compute] shortcut below creates ~0.75 MB of garbage per window and is meant for tests and
 * offline tools only.
 */
object SpectralFeatures {

    /** Guards log/normalization against silent or near-silent frames. */
    private const val EPS = 1e-12

    /** Flatness/harmonicity are computed over speech-and-snore-relevant content only: DC and
     *  near-Nyquist bins carry rumble and hiss that flatten the metric without telling snore
     *  from broadband noise apart. */
    private const val FLATNESS_MIN_HZ = 80.0
    private const val FLATNESS_MAX_HZ = 8000.0

    /** Human breathing fundamental range for the autocorrelation lag search (60-250 Hz covers
     *  adult snore periodicity). */
    private const val HARMONIC_MIN_HZ = 60.0
    private const val HARMONIC_MAX_HZ = 250.0

    /** Mel filterbank span — matches the flatness band: snore energy lives in 80 Hz..8 kHz. */
    private const val MEL_MIN_HZ = 80.0
    private const val MEL_MAX_HZ = 8000.0

    data class Result(
        /** 0..1, geometric/arithmetic mean of the 80 Hz..8 kHz power spectrum (1 = white noise). */
        val flatness: Double,
        /**
         * 0..1, peak of the normalized autocorrelation over the 60-250 Hz lag range
         * (0 = inharmonic). Computed on the mean-removed FULL window at once, not per frame:
         * a 1 s window at 16 kHz captures complete cycles down to 1 Hz, so even the 60 Hz
         * lower bound spans ~60 periods — per-frame (64 ms) autocorrelation could not resolve
         * the low end.
         */
        val harmonicRatio: Double,
        /** Frequency of the maximum averaged power bin (diagnostics/eval only). */
        val dominantFreqHz: Double,
        /**
         * Log-spaced mel band energies in dB (10*log10 of the filtered power), length =
         * [bandCount]. Stored for the future YAMNet-style classifier — NOT used by the
         * current detector.
         *
         * When the result comes from [Workspace.compute] this array ALIASES the workspace's
         * internal band buffer and is overwritten by the next [Workspace.compute] call on the
         * same workspace — callers must not retain a workspace-produced [Result] across calls
         * (copy `melBands` first if they must). The scalar fields are values and always safe
         * to retain.
         */
        val melBands: DoubleArray,
    )

    /**
     * Reusable per-stream scratch space: preallocates the Hann window, frame buffer, FFT
     * re/im/power scratch, averaged power spectrum, mean-removal buffer, and mel band edges +
     * output, so [compute] allocates nothing on the steady-state path. The audio capture thread
     * analyzes a window every 500 ms; a fresh [compute] call would drop ~0.75 MB of garbage per
     * window (Hann + frame + per-frame FFT arrays x 31 + the mean-removed copy) into the
     * allocator, which is exactly what this class avoids.
     *
     * Not thread-safe by design: one workspace per capture stream (AudioEngine / ReplayEngine
     * each own one), used only from the stream's own thread.
     *
     * ALIASING (see [Result.melBands]): the returned result's `melBands` array aliases this
     * workspace's internal buffer and is overwritten by the next [compute] call — copy it if a
     * result must outlive the next window.
     */
    class Workspace(
        private val sampleRate: Int,
        private val frameSize: Int = 1024,
        private val hopSize: Int = 512,
        bandCount: Int = 16,
    ) {
        init {
            require(frameSize >= 2 && frameSize and (frameSize - 1) == 0) {
                "frameSize must be a power of two (>= 2), was $frameSize"
            }
            require(frameSize <= sampleRate) {
                "frameSize ($frameSize) must not exceed sampleRate ($sampleRate)"
            }
            require(hopSize in 1..frameSize) { "hopSize must be in 1..frameSize, was $hopSize" }
            require(bandCount in 1..64) { "bandCount must be in 1..64, was $bandCount" }
        }

        private val hann = DoubleArray(frameSize) { i ->
            0.5 - 0.5 * cos(2.0 * Math.PI * i / frameSize)
        }
        private val frame = DoubleArray(frameSize)
        private val fftRe = DoubleArray(frameSize)
        private val fftIm = DoubleArray(frameSize)
        private val fftPower = DoubleArray(frameSize / 2 + 1)
        private val avgPower = DoubleArray(frameSize / 2 + 1)

        /** Mean-removed window for the autocorrelation; grown lazily to the window length. */
        private var meanRemoved = DoubleArray(0)

        private val melBands = DoubleArray(bandCount)
        private val melEdgesBin = melEdgeBins(sampleRate, frameSize, bandCount)

        /**
         * Computes the spectral descriptors of one analysis window (typically 16 000 samples
         * = 1 s at 16 kHz). Power spectra of all frames are Hann-windowed and averaged in the
         * POWER domain before flatness/dominant-frequency extraction (power averaging is
         * magnitude-insensitive, which is what flatness needs); harmonicity uses the full
         * window. Allocates only when the window is longer than any previous one seen by this
         * workspace (growing the mean-removal buffer).
         *
         * @param samples PCM window (any length >= [frameSize]; shorter input yields neutral
         *        zeros — the live pipeline always passes a full 1 s window).
         */
        fun compute(samples: ShortArray): Result {
            avgPower.fill(0.0)

            var frames = 0
            var frameStart = 0
            while (frameStart + frameSize <= samples.size) {
                for (i in 0 until frameSize) {
                    frame[i] = samples[frameStart + i] * hann[i]
                }
                Fft.powerSpectrum(frame, fftRe, fftIm, fftPower)
                for (k in avgPower.indices) {
                    avgPower[k] += fftPower[k]
                }
                frames++
                frameStart += hopSize
            }

            val flatness: Double
            val dominantFreqHz: Double
            if (frames == 0) {
                // Shorter than one frame: no spectral view exists. Neutral zeros keep the caller
                // contract total; the detector's null-feature fallback is upstream of this.
                flatness = 0.0
                dominantFreqHz = 0.0
                melBands.fill(0.0)
            } else {
                for (k in avgPower.indices) {
                    avgPower[k] /= frames.toDouble()
                }
                flatness = flatness(avgPower, sampleRate, frameSize)
                dominantFreqHz = dominantFreq(avgPower, sampleRate, frameSize)
                melFilterbank(avgPower, melEdgesBin, melBands)
            }

            if (meanRemoved.size < samples.size) meanRemoved = DoubleArray(samples.size)
            return Result(
                flatness = flatness,
                harmonicRatio = harmonicRatio(samples, sampleRate, meanRemoved),
                dominantFreqHz = dominantFreqHz,
                melBands = melBands,
            )
        }
    }

    /**
     * Convenience wrapper creating a throwaway [Workspace] per call — deterministic and
     * correct, but allocates every scratch buffer fresh; tests and offline tools only (the
     * capture path holds one [Workspace] per stream and calls [Workspace.compute]).
     *
     * @param samples PCM window (any length >= [frameSize]; shorter input yields neutral
     *        zeros — the live pipeline always passes a full 1 s window).
     * @param sampleRate analysis sample rate.
     * @param frameSize FFT size, must be a power of two and <= sampleRate.
     * @param hopSize frame advance in samples (1..frameSize).
     * @param bandCount mel filters (1..64).
     * @throws IllegalArgumentException on out-of-range configuration.
     */
    fun compute(
        samples: ShortArray,
        sampleRate: Int,
        frameSize: Int = 1024,
        hopSize: Int = 512,
        bandCount: Int = 16,
    ): Result = Workspace(sampleRate, frameSize, hopSize, bandCount).compute(samples)

    /** Geometric/arithmetic mean over the bins covering [FLATNESS_MIN_HZ, FLATNESS_MAX_HZ]. */
    private fun flatness(power: DoubleArray, sampleRate: Int, frameSize: Int): Double {
        val lo = binOf(FLATNESS_MIN_HZ, sampleRate, frameSize)
        var hi = binOf(FLATNESS_MAX_HZ, sampleRate, frameSize)
        if (hi >= power.size) hi = power.size - 1
        if (lo > hi) return 0.0
        var logSum = 0.0
        var sum = 0.0
        var count = 0
        for (k in lo..hi) {
            val p = power[k] + EPS
            logSum += ln(p)
            sum += p
            count++
        }
        // Digital silence would formally be "perfectly flat" (all bins equal at EPS); report 0
        // instead — silence carries no evidence of any kind and must never gate a snore open.
        if (sum <= EPS * count) return 0.0
        val geometric = exp(logSum / count)
        val arithmetic = sum / count
        return (geometric / arithmetic).coerceIn(0.0, 1.0)
    }

    /** Frequency (Hz) of the maximum averaged power bin. */
    private fun dominantFreq(power: DoubleArray, sampleRate: Int, frameSize: Int): Double {
        var best = 0
        var bestPower = power[0]
        for (k in 1 until power.size) {
            if (power[k] > bestPower) {
                bestPower = power[k]
                best = k
            }
        }
        return best * sampleRate.toDouble() / frameSize
    }

    /**
     * Peak normalized autocorrelation over lags for [HARMONIC_MIN_HZ]..[HARMONIC_MAX_HZ]
     * (lag = sampleRate / f; 64..266 samples at 16 kHz). r(0) is the mean-removed energy, so
     * the ratio is 1.0 for a perfectly periodic window and near 0 for noise. ~N x lagRange
     * multiply-adds (≈3.2M for a 16 000-sample window at 16 kHz — see the object KDoc).
     */
    private fun harmonicRatio(samples: ShortArray, sampleRate: Int, x: DoubleArray): Double {
        val n = samples.size
        if (n < 2) return 0.0
        var mean = 0.0
        for (v in samples) mean += v
        mean /= n
        for (i in 0 until n) x[i] = samples[i] - mean
        var r0 = 0.0
        for (i in 0 until n) r0 += x[i] * x[i]
        if (r0 <= EPS) return 0.0 // digital silence carries no harmonic evidence

        val minLag = (sampleRate / HARMONIC_MAX_HZ).toInt().coerceAtLeast(1)
        val maxLag = (sampleRate / HARMONIC_MIN_HZ).toInt().coerceAtMost(n - 1)
        var best = 0.0
        for (lag in minLag..maxLag) {
            var r = 0.0
            for (i in 0 until n - lag) {
                r += x[i] * x[i + lag]
            }
            val ratio = r / r0
            if (ratio > best) best = ratio
        }
        return best.coerceIn(0.0, 1.0)
    }

    /** Mel-scale band edge positions in bins for a [bandCount]-filter bank. */
    private fun melEdgeBins(
        sampleRate: Int,
        frameSize: Int,
        bandCount: Int,
    ): DoubleArray {
        val edgesHz = DoubleArray(bandCount + 2) { m ->
            melToHz(hzToMel(MEL_MIN_HZ) + (hzToMel(MEL_MAX_HZ) - hzToMel(MEL_MIN_HZ)) * m / (bandCount + 1))
        }
        return DoubleArray(bandCount + 2) { m -> edgesHz[m] * frameSize / sampleRate }
    }

    /**
     * Standard mel filterbank: bands.size triangular filters spanning [MEL_MIN_HZ, MEL_MAX_HZ]
     * with band edges evenly spaced on the mel scale (mel = 2595*log10(1+f/700)); each band
     * reports 10*log10(filtered power + EPS) dB into [bands] (the workspace's reusable output).
     */
    private fun melFilterbank(
        power: DoubleArray,
        edgesBin: DoubleArray,
        bands: DoubleArray,
    ) {
        for (b in bands.indices) {
            val start = edgesBin[b]
            val center = edgesBin[b + 1]
            val end = edgesBin[b + 2]
            var energy = 0.0
            // Triangular weights over whole bins; bin centers (k) are compared against the
            // fractional edge positions. Guard degenerate zero-width triangles (bandCount that
            // crowds the mel scale) by skipping instead of dividing by zero.
            val lo = start.toInt().coerceAtLeast(0)
            val hi = (end.toInt() + 1).coerceAtMost(power.size - 1)
            for (k in lo..hi) {
                val w = when {
                    k < start || k > end -> 0.0
                    k <= center && center > start -> (k - start) / (center - start)
                    k > center && end > center -> (end - k) / (end - center)
                    else -> 0.0
                }
                if (w > 0.0) energy += power[k] * w
            }
            bands[b] = 10.0 * log10(energy + EPS)
        }
    }

    private fun binOf(freqHz: Double, sampleRate: Int, frameSize: Int): Int =
        (freqHz * frameSize / sampleRate).toInt().coerceIn(0, frameSize / 2)

    private fun hzToMel(f: Double): Double = 2595.0 * log10(1.0 + f / 700.0)

    private fun melToHz(m: Double): Double = 700.0 * (10.0.pow(m / 2595.0) - 1.0)
}
