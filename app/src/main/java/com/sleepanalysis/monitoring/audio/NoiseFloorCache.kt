package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.monitoring.detection.DetectorConfig

/**
 * Cached rolling noise floor percentile for the capture loop. Pure policy over the ring's push
 * pattern — unit tested (NoiseFloorCacheTest).
 *
 * Replaces the per-window copy+sort of the 240-sample ring (AudioEngine fed one fresh
 * DoubleArray + sort into the allocator on EVERY analyzed window — ~2 KB × 2/s ≈ 100 MB of
 * transient garbage per 8 h night) with a cached percentile recomputed on a push cadence:
 *
 *  - **Warmup (count < ring size):** every push changes the sample set the percentile reads
 *    (the ring's push pattern writes slots [0, count) in order before the first wrap — index
 *    equals count until the ring is full, verified in AudioEngine), so the cache refreshes on
 *    EVERY push and reads exactly the values the per-window recomputation read.
 *  - **Steady state (ring full):** the sample set only rotates, so the cache refreshes every
 *    [DEFAULT_REFRESH_EVERY_PUSHES] pushes (8 = 4 s at the 2 windows/s hop grid — 1/30th of the
 *    ~2-minute ring, 8× fewer sorts, at most 7 pushes of staleness on the 20th-percentile
 *    index).
 *
 * An empty ring reports [EMPTY_RING_FLOOR_DBFS] — AudioEngine's historical empty-ring fallback
 * (unreachable in practice: the floor is read only after the current window was pushed, but
 * kept for exactness). [reset] restores the empty state on engine start.
 */
class NoiseFloorCache(
    private val ringSize: Int = DetectorConfig.NOISE_FLOOR_WINDOW_COUNT,
    private val percentile: Double = DetectorConfig.NOISE_FLOOR_PERCENTILE,
    private val refreshEveryPushes: Int = DEFAULT_REFRESH_EVERY_PUSHES,
) {
    /** Copy+sort scratch — one array for the engine's lifetime instead of one per window. */
    private val scratch = DoubleArray(ringSize)

    private var cachedFloor = EMPTY_RING_FLOOR_DBFS

    /** Pushes since the last recompute; pre-armed to [refreshEveryPushes] so push #1 computes. */
    private var pushesSinceRefresh = refreshEveryPushes

    /** Drops the cached value — the ring was reset (engine start). */
    fun reset() {
        cachedFloor = EMPTY_RING_FLOOR_DBFS
        pushesSinceRefresh = refreshEveryPushes
    }

    /**
     * Refresh policy for one window push. [ring] is the engine's noise-floor ring and [count]
     * the number of valid leading slots AFTER the current window's value was pushed (the floor
     * is always read with the current window included). The recomputation is exactly the
     * historical copy+sort-of-leading-[count]-slots percentile.
     */
    fun onWindowPushed(ring: DoubleArray, count: Int) {
        if (count == 0) {
            cachedFloor = EMPTY_RING_FLOOR_DBFS
            pushesSinceRefresh = refreshEveryPushes
            return
        }
        // Warmup: each push adds a sample to a short ring — the percentile set changes per
        // push, so the cache must follow per push to stay equivalent to a per-window recompute.
        // Steady state: recompute on every REFRESH_EVERY_PUSHES-th push (counter first, so the
        // cadence is exactly 8 pushes and the cache never goes stale beyond 7).
        pushesSinceRefresh++
        if (count < ringSize || pushesSinceRefresh >= refreshEveryPushes) {
            cachedFloor = percentileOf(ring, count)
            pushesSinceRefresh = 0
        }
    }

    /** The cached floor as of the most recent qualifying push. */
    fun floor(): Double = cachedFloor

    /** The historical AudioEngine.currentNoiseFloor algorithm, verbatim over [0, count). */
    private fun percentileOf(ring: DoubleArray, count: Int): Double {
        System.arraycopy(ring, 0, scratch, 0, count)
        scratch.sort(0, count)
        return scratch[((count - 1) * percentile).toInt().coerceAtLeast(0)]
    }

    companion object {
        /** The engine's historical empty-ring floor (mic-self-noise ballpark, not calibrated). */
        const val EMPTY_RING_FLOOR_DBFS = -70.0

        /**
         * Steady-state refresh cadence in ring pushes: 8 hops = 4 s at the 2 windows/s grid —
         * the 240-sample ring (~2 minutes) turns over 30 times per refresh, so the 20th-
         * percentile rank drifts at most 8 positions between recomputes.
         */
        const val DEFAULT_REFRESH_EVERY_PUSHES = 8
    }
}
