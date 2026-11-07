package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.data.db.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clip gating tests: relative level gate (noise floor + margin, hard absolute floor), the
 * settling window, the loudness reserve (night 3: the loudest snore missed its clip because
 * the hour's budget was spent), the bout cooldown (night 4: a single 110-burst bout ate the
 * budget — clips now follow a per-type cooldown with upgrade writes), the per-wall-clock-hour
 * budget, and the per-night backstop.
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
        // (Checked two minutes on, past the bout cooldown the second write armed, so the skip
        // is purely a budget skip.)
        assertFalse(policy.loud(peakDbfs = -20.0, at = nightStartMs + 2 * 60_000))
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

    // --- Bout cooldown (night 4: one 110-burst bout ate ~15 of the 50 clips) ---------

    /** Median v2-burst event length; the tests' burst trains use it as the clip's anchor. */
    private val burstMs = 2_500L

    /** Burst starts 5.5 s apart (3 s of silence between bursts, as EXIT_WINDOWS splits them). */
    private fun burstStart(index: Int) = nightStartMs + index * (burstMs + 3_000L)

    @Test
    fun `burst train inside the bout cooldown writes once then budget-skips the rest`() {
        val policy = ClipPolicy()
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -35.0, nightStartMs, captureStartMs))
        policy.onWritten(-35.0, nightStartMs, eventEndEpochMs = nightStartMs + burstMs)
        // Bursts 5.5 s apart all start inside the 45 s window (last start: +27.5 s < +47.5 s):
        // one clip per cooldown window, the train's repeats are budget skips.
        for (i in 1 until 6) {
            assertEquals(
                ClipPolicy.Decision.SKIP_COOLDOWN,
                policy.decide(-20.0, -40.0, -35.0, burstStart(i), captureStartMs),
            )
        }
        // Skips book nothing — the bout has cost one clip so far.
        assertEquals(1, policy.totalWritten)
    }

    @Test
    fun `louder burst inside the cooldown is an upgrade and re-anchors`() {
        val policy = ClipPolicy()
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -35.0, nightStartMs, captureStartMs))
        policy.onWritten(-35.0, nightStartMs, eventEndEpochMs = nightStartMs + burstMs)
        val louder = nightStartMs + 10_000L
        // +2.9 dB over the anchor: not an upgrade, still a skip.
        assertEquals(ClipPolicy.Decision.SKIP_COOLDOWN, policy.decide(-20.0, -40.0, -32.1, louder, captureStartMs))
        // Exactly +3 dB (boundary inclusive): the upgrade writes inside the open cooldown.
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -32.0, louder, captureStartMs))
        policy.onWritten(-32.0, louder, eventEndEpochMs = louder + burstMs)
        // Re-anchored at −32: the same level again is no longer an upgrade and skips.
        assertEquals(
            ClipPolicy.Decision.SKIP_COOLDOWN,
            policy.decide(-20.0, -40.0, -32.0, louder + 10_000L, captureStartMs),
        )
        assertEquals(2, policy.totalWritten)
    }

    @Test
    fun `a burst after the bout cooldown expires writes again`() {
        val policy = ClipPolicy()
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -35.0, nightStartMs, captureStartMs))
        policy.onWritten(-35.0, nightStartMs, eventEndEpochMs = nightStartMs + burstMs)
        // 44.9 s after the anchor's end: still inside the window (skipped bursts never re-arm).
        assertEquals(
            ClipPolicy.Decision.SKIP_COOLDOWN,
            policy.decide(-20.0, -40.0, -35.0, nightStartMs + burstMs + 44_999L, captureStartMs),
        )
        // At exactly 45 s the window ends — strict less-than, like the settling window.
        assertEquals(
            ClipPolicy.Decision.WRITE,
            policy.decide(-20.0, -40.0, -35.0, nightStartMs + burstMs + 45_000L, captureStartMs),
        )
    }

    @Test
    fun `reserve-eligible loud event inside the cooldown still writes`() {
        val policy = ClipPolicy()
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -35.0, nightStartMs, captureStartMs))
        policy.onWritten(-35.0, nightStartMs, eventEndEpochMs = nightStartMs + burstMs)
        val loud = nightStartMs + 10_000L
        // Peak −16.6 clears LOUD_CLIP_PEAK_DBFS: the reserve bypasses the cooldown (gate 2
        // answers before gate 3), as night 3's loudest snore deserves.
        assertEquals(ClipPolicy.Decision.WRITE_LOUD_RESERVE, policy.decide(-20.0, -40.0, -16.6, loud, captureStartMs))
        policy.onWritten(-16.6, loud, eventEndEpochMs = loud + burstMs, viaLoudReserve = true)
        assertEquals(2, policy.totalWritten)
        assertEquals(1, policy.loudWritten)
        // The reserve write re-anchors at its own peak: a −28 burst — which against the old
        // −35 anchor would have been a +7 dB upgrade — now skips.
        assertEquals(
            ClipPolicy.Decision.SKIP_COOLDOWN,
            policy.decide(-20.0, -40.0, -28.0, loud + 10_000L, captureStartMs),
        )
    }

    @Test
    fun `worst bout keeps its loudest burst even mid-cooldown`() {
        // The shipped reserve would grant every −20 dBFS peak a reserve write and mask the
        // cooldown under test — zeroed here; in production the reserve composes with the
        // cooldown by re-anchor (see the reserve test above).
        val policy = ClipPolicy(loudReservePerNight = 0)
        // 20 bursts peaking −20 dBFS: one clip per 45 s window (bursts 1, 10, 19 — the window
        // covers 8 burst starts after each anchor's end), skips in between.
        repeat(20) { i ->
            val expected = if (i == 0 || i == 9 || i == 18) {
                ClipPolicy.Decision.WRITE
            } else {
                ClipPolicy.Decision.SKIP_COOLDOWN
            }
            assertEquals(expected, policy.decide(-20.0, -40.0, -20.0, burstStart(i), captureStartMs))
            if (expected == ClipPolicy.Decision.WRITE) {
                policy.onWritten(-20.0, burstStart(i), eventEndEpochMs = burstStart(i) + burstMs)
            }
        }
        assertEquals(3, policy.totalWritten)
        // The bout's worst burst — 8 dB over the train, mid-cooldown by construction — writes.
        val worst = burstStart(19) + 10_000L
        assertTrue(worst < burstStart(18) + burstMs + 45_000L)  // sanity: inside the cooldown
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -12.0, worst, captureStartMs))
        policy.onWritten(-12.0, worst, eventEndEpochMs = worst + burstMs)
        assertEquals(4, policy.totalWritten)
        // Re-anchored at −12: the train's ordinary level is a downgrade and skips again.
        assertEquals(
            ClipPolicy.Decision.SKIP_COOLDOWN,
            policy.decide(-20.0, -40.0, -20.0, worst + 10_000L, captureStartMs),
        )
    }

    @Test
    fun `upgrades still respect the hourly and nightly caps`() {
        // Nightly cap reached: an in-cooldown upgrade is a budget skip, not a free write.
        val nightly = ClipPolicy(maxTotal = 1)
        nightly.onWritten(-35.0, nightStartMs, eventEndEpochMs = nightStartMs + burstMs)
        assertEquals(
            ClipPolicy.Decision.SKIP_BUDGET,
            nightly.decide(-20.0, -40.0, -28.0, nightStartMs + 10_000L, captureStartMs),
        )
        // Hourly cap reached: same skip; a fresh hour re-opens for the upgrade.
        val hourly = ClipPolicy(maxPerHour = 1)
        hourly.onWritten(-35.0, nightStartMs, eventEndEpochMs = nightStartMs + burstMs)
        assertEquals(
            ClipPolicy.Decision.SKIP_BUDGET,
            hourly.decide(-20.0, -40.0, -28.0, nightStartMs + 10_000L, captureStartMs),
        )
        assertEquals(
            ClipPolicy.Decision.WRITE,
            hourly.decide(-20.0, -40.0, -28.0, nightStartMs + hourMs + 10_000L, captureStartMs),
        )
    }

    @Test
    fun `bout cooldown is per type`() {
        val policy = ClipPolicy()
        policy.onWritten(-35.0, nightStartMs, eventEndEpochMs = nightStartMs + burstMs, type = EventType.SNORE)
        // Same instant, different type: the snore train's cooldown does not apply to it…
        assertEquals(
            ClipPolicy.Decision.WRITE,
            policy.decide(-20.0, -40.0, -35.0, nightStartMs, captureStartMs, type = EventType.OTHER_NOISE),
        )
        // …while a same-type burst at that instant is inside the cooldown.
        assertEquals(ClipPolicy.Decision.SKIP_COOLDOWN, policy.decide(-20.0, -40.0, -35.0, nightStartMs, captureStartMs))
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
            // Writes a minute apart — past the bout cooldown each write arms — so both go
            // through the budget path the test exercises.
            repeat(2) { i ->
                assertTrue(policy.loud(at = h + i * 60_000))
                policy.onWritten(-35.0, h + i * 60_000)
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
        // Partial inheritance leaves exactly the remainder. Writes a minute apart to stay
        // outside the bout cooldown each write arms (night 4 change).
        val resumed = ClipPolicy(maxTotal = 50, maxPerHour = 8, initialTotal = 45)
        repeat(5) { i ->
            assertTrue(resumed.loud(at = nightStartMs + i * 60_000))
            resumed.onWritten(-35.0, nightStartMs + i * 60_000)
        }
        assertFalse(resumed.loud(at = nightStartMs + 5 * 60_000))
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
        policy.onWritten(-35.0, nightStartMs) // hour budget spent (also arms the bout cooldown)
        // Both exhausted — checked a minute on, past the bout cooldown, so the loud skip is
        // the budget's: a quiet event is a LEVEL skip (not budget), a loud one a BUDGET skip.
        assertEquals(ClipPolicy.Decision.SKIP_LEVEL, policy.decide(-60.0, -40.0, -35.0, nightStartMs + 60_000, captureStartMs))
        assertEquals(ClipPolicy.Decision.SKIP_BUDGET, policy.decide(-20.0, -40.0, -35.0, nightStartMs + 60_000, captureStartMs))
        // Budget skips reset with the hour; the level gate never does.
        assertEquals(ClipPolicy.Decision.WRITE, policy.decide(-20.0, -40.0, -35.0, nightStartMs + hourMs, captureStartMs))
        assertEquals(
            ClipPolicy.Decision.SKIP_LEVEL,
            policy.decide(-60.0, -40.0, -35.0, nightStartMs + hourMs, captureStartMs),
        )
    }
}
