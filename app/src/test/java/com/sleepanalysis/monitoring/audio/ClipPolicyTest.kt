package com.sleepanalysis.monitoring.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clip gating tests: relative level gate (noise floor + margin, hard absolute floor), the
 * settling window, the loudness reserve (night 3: the loudest snore missed its clip because
 * the hour's budget was spent), the per-wall-clock-hour budget, and the per-night backstop.
 */
class ClipPolicyTest {

    /** Real night-1 session start (2026-11-01 21:23 local). */
    private val nightStartMs = 1_793_564_621_686L
    private val hourMs = ClipPolicy.HOUR_MS

    /** Capture started two hours ago — the settling window is long over. */
    private val captureStartMs = nightStartMs - 2 * hourMs

    /**
     * Normal-budget event over a −40 dBFS floor, outside the settling window (threshold −30).
     * Default peak −35 keeps the loudness reserve out of the ordinary-path tests.
     */
    private fun ClipPolicy.loud(
        meanDbfs: Double = -20.0,
        peakDbfs: Double = -35.0,
        at: Long = nightStartMs,
        floor: Double = -40.0,
    ) = shouldWrite(meanDbfs, floor, peakDbfs, at, captureStartMs)

    @Test
    fun `events below the relative gate never produce clips`() {
        val policy = ClipPolicy()
        assertFalse(policy.loud(meanDbfs = -40.0, floor = -40.0))  // at the floor: no margin
        assertFalse(policy.loud(meanDbfs = -31.0, floor = -40.0))  // under floor + 10
        assertFalse(policy.loud(meanDbfs = -92.0, floor = -92.0))  // mic self-noise
    }

    @Test
    fun `events at or above floor plus margin may produce clips`() {
        val policy = ClipPolicy()
        assertTrue(policy.loud(meanDbfs = -30.0, floor = -40.0))  // gate is inclusive
        assertTrue(policy.loud(meanDbfs = -20.0, floor = -40.0))
    }

    @Test
    fun `hard absolute floor gates near-silence even in a silent room`() {
        val policy = ClipPolicy()
        // Floor −92 → floor + margin = −82, below audibility: the −60 hard floor applies.
        assertFalse(policy.loud(meanDbfs = -61.0, floor = -92.0))
        assertTrue(policy.loud(meanDbfs = -59.0, floor = -92.0))
    }

    @Test
    fun `louder rooms recalibrate the gate upward`() {
        val policy = ClipPolicy()
        // −50 dBFS in a −45 dBFS bedroom is junk the old absolute −55 gate labeled SNORE.
        assertFalse(policy.loud(meanDbfs = -50.0, floor = -45.0))
        assertTrue(policy.loud(meanDbfs = -34.0, floor = -45.0))  // threshold −35
    }

    @Test
    fun `settling window is stricter for the first 30 minutes of capture`() {
        val policy = ClipPolicy()
        // Threshold −30 without settling; −24 during it: a −28 event passes later, not at bedtime.
        assertFalse(policy.loud(meanDbfs = -28.0, at = captureStartMs + 60_000))
        assertTrue(policy.loud(meanDbfs = -28.0, at = captureStartMs + 31 * 60_000))
        // The window ends exactly after 30 minutes.
        assertTrue(policy.loud(meanDbfs = -28.0, at = captureStartMs + 30 * 60_000))
    }

    @Test
    fun `settling strictness cannot undercut the hard absolute floor`() {
        val policy = ClipPolicy()
        // Settling margin 16 dB over −92 = −76, but the threshold never goes below −60.
        assertTrue(policy.loud(meanDbfs = -59.0, at = captureStartMs + 60_000, floor = -92.0))
        assertFalse(policy.loud(meanDbfs = -61.0, at = captureStartMs + 60_000, floor = -92.0))
    }

    @Test
    fun `unknown capture start disables settling`() {
        val policy = ClipPolicy()
        assertTrue(policy.shouldWrite(-28.0, -40.0, -35.0, nightStartMs, captureStartEpochMs = 0L))
    }

    // --- Loudness reserve (night 3: budget spent before the loudest snore) -----------

    @Test
    fun `loud peak bypasses a full hourly budget`() {
        val policy = ClipPolicy(maxPerHour = 1)
        assertTrue(policy.loud())                       // normal write fills the hour
        policy.onWritten(-35.0, nightStartMs)
        assertFalse(policy.loud())                      // hour spent, ordinary event skipped
        assertTrue(policy.loud(peakDbfs = -16.6))       // night 3's loudest snore gets its clip
    }

    @Test
    fun `loud peak bypasses the nightly backstop too`() {
        val policy = ClipPolicy(maxTotal = 1, maxPerHour = 8)
        assertTrue(policy.loud())
        policy.onWritten(-35.0, nightStartMs)
        assertFalse(policy.loud())                      // total cap reached
        assertTrue(policy.loud(peakDbfs = -23.3))       // reserve still grants
        policy.onWritten(-23.3, nightStartMs, viaLoudReserve = true)
        assertEquals(2, policy.totalWritten)
        assertEquals(1, policy.loudWritten)
    }

    @Test
    fun `loud reserve is capped per night`() {
        val policy = ClipPolicy(maxPerHour = 1, loudReservePerNight = 2)
        repeat(2) {
            assertTrue(policy.loud(peakDbfs = -20.0))
            policy.onWritten(-20.0, nightStartMs + it * 60_000, viaLoudReserve = true)
        }
        // Reserve spent: the same loud event now falls through to the (full) hourly budget.
        assertFalse(policy.loud(peakDbfs = -20.0))
        assertEquals(2, policy.loudWritten)
    }

    @Test
    fun `reserve threshold is the peak, boundary inclusive`() {
        val policy = ClipPolicy(maxPerHour = 1)
        policy.onWritten(-35.0, nightStartMs)           // burn the hour
        assertFalse(policy.loud(peakDbfs = -25.1))      // just under: no reserve
        val boundary = ClipPolicy(maxPerHour = 1, loudReservePerNight = 1)
        boundary.onWritten(-35.0, nightStartMs)
        assertTrue(boundary.loud(peakDbfs = -25.0))     // exactly at: reserve applies
    }

    @Test
    fun `level gate rejects a momentary peak inside rumble`() {
        val policy = ClipPolicy()
        // Mean −80 over a −40 floor is deep rumble; one loud transient must not earn a clip.
        assertFalse(policy.loud(meanDbfs = -80.0, peakDbfs = -10.0))
    }

    @Test
    fun `ordinary writes do not consume the reserve`() {
        val policy = ClipPolicy(maxPerHour = 1, loudReservePerNight = 1)
        policy.onWritten(-35.0, nightStartMs)           // ordinary write
        assertEquals(0, policy.loudWritten)
        assertTrue(policy.loud(peakDbfs = -16.0))       // reserve untouched
    }

    // --- Hourly / nightly budgets (unchanged paths) ----------------------------------

    @Test
    fun `hourly budget blocks after the per-hour maximum`() {
        val policy = ClipPolicy(maxPerHour = 3)
        for (i in 0 until 3) {
            assertTrue(policy.loud(at = nightStartMs + i * 60_000))
            policy.onWritten(-35.0, nightStartMs + i * 60_000)
        }
        // Same wall-clock hour: budget spent.
        assertFalse(policy.loud(at = nightStartMs + 4 * 60_000))
        // Next hour: budget re-opens.
        assertTrue(policy.loud(at = nightStartMs + hourMs))
    }

    @Test
    fun `hourly budget keys on the absolute hour, not write order`() {
        val policy = ClipPolicy(maxPerHour = 2)
        policy.onWritten(-35.0, nightStartMs)              // hour H: 1 used
        policy.onWritten(-35.0, nightStartMs + 60_000)     // hour H: 2 used (full)
        policy.onWritten(-35.0, nightStartMs + hourMs)     // hour H+1: 1 used
        assertFalse(policy.loud(at = nightStartMs + 2 * 60_000))       // hour H: full
        assertTrue(policy.loud(at = nightStartMs + hourMs + 60_000))   // hour H+1: open
    }

    @Test
    fun `nightly backstop holds across hours`() {
        val policy = ClipPolicy(maxTotal = 4, maxPerHour = 2)
        val hours = List(3) { nightStartMs + it * hourMs }
        hours.take(2).forEach { h ->
            repeat(2) {
                assertTrue(policy.loud(at = h))
                policy.onWritten(-35.0, h)
            }
        }
        // 4 clips written, cap reached: further events get no clip even in a fresh hour.
        assertFalse(policy.loud(at = hours[2]))
        assertEquals(4, policy.totalWritten)
    }

    @Test
    fun `initialTotal seeds the nightly backstop for resumed sessions`() {
        // START_STICKY restart: the session already spent its whole budget before the crash.
        val policy = ClipPolicy(maxTotal = 50, initialTotal = 50)
        assertFalse(policy.loud(at = nightStartMs + hourMs))
        // Partial inheritance leaves exactly the remainder.
        val resumed = ClipPolicy(maxTotal = 50, maxPerHour = 8, initialTotal = 45)
        repeat(5) {
            assertTrue(resumed.loud(at = nightStartMs))
            resumed.onWritten(-35.0, nightStartMs)
        }
        assertFalse(resumed.loud(at = nightStartMs))
        assertEquals(50, resumed.totalWritten)
    }

    @Test
    fun `level-gated events do not consume the hourly budget`() {
        val policy = ClipPolicy(maxPerHour = 2)
        // Sub-gate events are rejected; the caller never calls onWritten for them.
        repeat(10) { assertFalse(policy.loud(meanDbfs = -80.0)) }
        assertTrue(policy.loud())
    }

    @Test
    fun `decide distinguishes level skips from budget skips`() {
        val policy = ClipPolicy(maxPerHour = 1)
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -35.0, nightStartMs, captureStartMs))
        assertEquals(ClipPolicy.Decision.SKIP_LEVEL, policy.decide(-60.0, -40.0, -35.0, nightStartMs, captureStartMs))
        policy.onWritten(-35.0, nightStartMs) // hour budget spent
        // Both exhausted: a quiet event is a LEVEL skip (not budget), a loud one a BUDGET skip.
        assertEquals(ClipPolicy.Decision.SKIP_LEVEL, policy.decide(-60.0, -40.0, -35.0, nightStartMs, captureStartMs))
        assertEquals(ClipPolicy.Decision.SKIP_BUDGET, policy.decide(-20.0, -40.0, -35.0, nightStartMs, captureStartMs))
        // Budget skips reset with the hour; the level gate never does.
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -35.0, nightStartMs + hourMs, captureStartMs))
        assertEquals(
            ClipPolicy.Decision.SKIP_LEVEL,
            policy.decide(-60.0, -40.0, -35.0, nightStartMs + hourMs, captureStartMs),
        )
    }
}
