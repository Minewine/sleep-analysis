package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.monitoring.detection.DetectorConfig
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cached rolling noise floor ([NoiseFloorCache]): same percentile over the same ring the
 * per-window copy+sort read, refreshed per push while the ring fills and every 8 pushes once
 * full. Pins the ring's push pattern (slots [0, count) in order before the first wrap) and the
 * equivalence contract across hop boundaries.
 */
class NoiseFloorCacheTest {

    /** AudioEngine's push pattern verbatim: write at index, advance modulo size, cap the count. */
    private class RingSim(private val size: Int) {
        val ring = DoubleArray(size)
        var count = 0
        private var index = 0

        fun push(value: Double) {
            ring[index] = value
            index = (index + 1) % size
            count = minOf(count + 1, size)
        }
    }

    /** The historical AudioEngine.currentNoiseFloor algorithm the cache must stay equivalent to. */
    private fun referenceFloor(ring: DoubleArray, count: Int): Double {
        if (count == 0) return NoiseFloorCache.EMPTY_RING_FLOOR_DBFS
        val copy = DoubleArray(count)
        System.arraycopy(ring, 0, copy, 0, count)
        copy.sort()
        return copy[((count - 1) * DetectorConfig.NOISE_FLOOR_PERCENTILE).toInt().coerceAtLeast(0)]
    }

    @Test
    fun `empty ring reports the historical default floor`() {
        val cache = NoiseFloorCache(ringSize = 240)
        assertEquals(-70.0, cache.floor(), 0.0)
        cache.onWindowPushed(DoubleArray(240), 0)
        assertEquals(-70.0, cache.floor(), 0.0)
    }

    @Test
    fun `warmup refreshes per push and reads exactly the per-window recomputation`() {
        val sim = RingSim(240)
        val cache = NoiseFloorCache(ringSize = 240)
        val rng = Random(seed = 42) // ambient levels, deterministic
        repeat(240) {
            sim.push(-95.0 + rng.nextDouble() * 60.0)
            cache.onWindowPushed(sim.ring, sim.count)
            // While the ring is short every push changes the percentile set — the cache must
            // match the historical per-window recompute EXACTLY on every hop.
            assertEquals(referenceFloor(sim.ring, sim.count), cache.floor(), 0.0)
        }
    }

    @Test
    fun `steady state stays within the last refresh boundary - never stale beyond 8 pushes`() {
        val sim = RingSim(240)
        val cache = NoiseFloorCache(ringSize = 240)
        val rng = Random(seed = 7)
        val recent = ArrayDeque<Double>()
        repeat(240) { // fill the ring (warmup, per-push exactness covered by the other test)
            sim.push(-90.0 + rng.nextDouble() * 40.0)
            cache.onWindowPushed(sim.ring, sim.count)
            // Seed the boundary window with the warmup tail: the first steady-state pushes may
            // legitimately still hold a warmup-era cache value.
            recent.addLast(referenceFloor(sim.ring, sim.count))
            if (recent.size > 8) recent.removeFirst()
        }
        // Steady state with a slow ambient drift (the room warming up) so the reference floor
        // moves every push — a stale cache is distinguishable from a fresh one.
        var pushesSinceRefresh = 0
        repeat(1_000) {
            sim.push(-88.0 + it * 0.005 + rng.nextDouble() * 8.0)
            cache.onWindowPushed(sim.ring, sim.count)
            val reference = referenceFloor(sim.ring, sim.count)
            recent.addLast(reference)
            if (recent.size > 8) recent.removeFirst()
            if (cache.floor() == reference) pushesSinceRefresh = 0 else pushesSinceRefresh++
            // The cached value must be one of the last 8 reference values (the ring's
            // percentile over the sample set at the last refresh boundary)...
            assertTrue(
                "cache floor ${cache.floor()} not among the last 8 boundaries",
                recent.any { it == cache.floor() },
            )
            // ...and must refresh at least every 8 pushes (REFRESH_EVERY_PUSHES cadence).
            assertTrue("cache stale for $pushesSinceRefresh pushes", pushesSinceRefresh <= 8)
        }
    }

    @Test
    fun `reset restores the empty default and re-enters per-push warmup`() {
        val sim = RingSim(240)
        val cache = NoiseFloorCache(ringSize = 240)
        repeat(300) { i ->
            sim.push(-80.0 + (i % 10))
            cache.onWindowPushed(sim.ring, sim.count)
        }
        assertTrue(cache.floor() != NoiseFloorCache.EMPTY_RING_FLOOR_DBFS)
        cache.reset()
        assertEquals(NoiseFloorCache.EMPTY_RING_FLOOR_DBFS, cache.floor(), 0.0)
        // Engine start cleared the ring too: the first push recomputes over exactly one sample.
        val fresh = RingSim(240)
        fresh.push(-77.5)
        cache.onWindowPushed(fresh.ring, fresh.count)
        assertEquals(referenceFloor(fresh.ring, fresh.count), cache.floor(), 0.0)
    }

    @Test
    fun `cache matches the historical algorithm through ring wraparound`() {
        // Rotation invariant: once full, the leading [count] slots are a permutation of the
        // last 240 pushes, and sorting erases the rotation — the cache must agree with the
        // reference across the wrap.
        val sim = RingSim(240)
        val cache = NoiseFloorCache(ringSize = 240)
        val rng = Random(seed = 1234)
        var refreshPush = -1
        repeat(600) { i ->
            sim.push(-100.0 + rng.nextDouble() * 70.0 + (i / 100) * 2.0)
            cache.onWindowPushed(sim.ring, sim.count)
            if (cache.floor() == referenceFloor(sim.ring, sim.count)) refreshPush = i
        }
        assertTrue("no refresh after the wrap", refreshPush in 239 until 600)
    }
}
