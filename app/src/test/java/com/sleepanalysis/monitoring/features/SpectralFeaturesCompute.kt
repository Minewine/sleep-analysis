package com.sleepanalysis.monitoring.features

import com.sleepanalysis.monitoring.features.SpectralFeatures.Result
import com.sleepanalysis.monitoring.features.SpectralFeatures.Workspace

/**
 * Test-only allocating `compute` overload of [SpectralFeatures], split out of the production
 * object when the offline replay/eval code moved to the test source set. Convenience wrapper
 * creating a throwaway [Workspace] per call — deterministic and
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
fun SpectralFeatures.compute(
    samples: ShortArray,
    sampleRate: Int,
    frameSize: Int = 1024,
    hopSize: Int = 512,
    bandCount: Int = 16,
): Result = Workspace(sampleRate, frameSize, hopSize, bandCount).compute(samples)
