package com.sleepanalysis.monitoring.features

/**
 * Iterative radix-2 Cooley-Tukey FFT for real input (docs/ARCHITECTURE.md §4 FeatureExtractor
 * plan). Per-window spectral features — spectral flatness (tonal snore vs broadband noise),
 * mel band energies for the future YAMNet-style classifier — all start from the power spectrum
 * of each analysis frame; this is the only FFT in the project, kept pure-JVM and dependency-free
 * so it runs identically on-device and in the offline replay/eval harness.
 *
 * Complexity: O(N log N) per frame (a 1024-pt transform is ~10 * 1024 butterfly multiply-adds
 * per stage set — cheap relative to the 500 ms analysis hop budget, but not "microseconds" of
 * work, and at the live cadence of 31 frames per 1 s window it is one of the larger items on
 * the capture thread). Real-time safety comes from cadence + zero steady-state allocation (use
 * the scratch overload on the capture thread), not from per-call cheapness: the expensive path
 * must stay per-window, never per-sample (AudioEngine only computes features on analyzed
 * windows, throttled hops early-return first).
 *
 * No window function is applied here: windowing is a caller concern ([SpectralFeatures] applies
 * a Hann window before calling) so tests can feed unwindowed frames and compare against
 * analytic spectra directly.
 */
object Fft {

    /**
     * Power spectrum of a real input frame.
     *
     * Allocates a fresh working copy and output array per call — fine for tests/tools; the
     * capture path uses [powerSpectrum] with preallocated scratch (see
     * [SpectralFeatures.Workspace]) so it allocates nothing per frame.
     *
     * @param frame time-domain samples; length MUST be a power of two (>= 2).
     * @return magnitudes squared (power) for bins 0..N/2 inclusive (N/2+1 entries). Keeping the
     *         Nyquist bin costs one entry and makes the bin-to-frequency mapping total
     *         (freq(k) = k * sampleRate / N) — the mel filterbank and the 80 Hz..8 kHz flatness
     *         range both index it directly.
     * @throws IllegalArgumentException when the length is not a power of two.
     */
    fun powerSpectrum(frame: DoubleArray): DoubleArray {
        val n = frame.size
        require(n >= 2 && n and (n - 1) == 0) {
            "FFT frame length must be a power of two (>= 2), was $n"
        }
        val power = DoubleArray(n / 2 + 1)
        powerSpectrum(frame, DoubleArray(n), DoubleArray(n), power)
        return power
    }

    /**
     * Power spectrum of a real input frame into caller-provided scratch — the allocation-free
     * variant for real-time use. Numerically identical to [powerSpectrum].
     *
     * @param frame time-domain samples; length MUST be a power of two (>= 2); not modified.
     * @param re scratch of at least [frame].size entries; overwritten.
     * @param im scratch of at least [frame].size entries; overwritten (zeroed internally).
     * @param power output of at least [frame].size / 2 + 1 entries; fully written.
     * @throws IllegalArgumentException when the length is not a power of two or a scratch
     *         buffer is too small.
     */
    fun powerSpectrum(
        frame: DoubleArray,
        re: DoubleArray,
        im: DoubleArray,
        power: DoubleArray,
    ) {
        val n = frame.size
        require(n >= 2 && n and (n - 1) == 0) {
            "FFT frame length must be a power of two (>= 2), was $n"
        }
        require(re.size >= n && im.size >= n) {
            "re/im scratch must hold $n doubles (was ${re.size}/${im.size})"
        }
        require(power.size >= n / 2 + 1) {
            "power scratch must hold ${n / 2 + 1} doubles (was ${power.size})"
        }

        System.arraycopy(frame, 0, re, 0, n)
        im.fill(0.0, 0, n)

        // Bit-reversal permutation: the iterative Cooley-Tukey consumes the input in bit-reversed
        // index order, so reordering up front lets the butterfly stages run in contiguous blocks.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j and bit.inv()
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
            }
        }

        // Butterfly stages: len doubles each pass; twiddle factors are stepped multiplicatively
        // within a block (one cos/sin pair per stage) instead of per butterfly.
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = -2.0 * Math.PI / len
            val stepRe = kotlin.math.cos(angle)
            val stepIm = kotlin.math.sin(angle)
            var block = 0
            while (block < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val iEven = block + k
                    val iOdd = iEven + half
                    val oddRe = re[iOdd] * wRe - im[iOdd] * wIm
                    val oddIm = re[iOdd] * wIm + im[iOdd] * wRe
                    re[iOdd] = re[iEven] - oddRe
                    im[iOdd] = im[iEven] - oddIm
                    re[iEven] += oddRe
                    im[iEven] += oddIm
                    val nextRe = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = nextRe
                }
                block += len
            }
            len = len shl 1
        }

        val bins = n / 2 + 1
        for (k in 0 until bins) {
            power[k] = re[k] * re[k] + im[k] * im[k]
        }
    }
}
