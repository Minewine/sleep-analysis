package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Burst/bout grouping, cross-checked against the real night of 2026-11-05→06 (session 3:
 * 220 snore bursts → 118 bouts at the 10 s gap, longest 21m32s = 03:05:05–03:26:37 with 17
 * bursts — the synthetic cases below pin the same math on hand-computable inputs).
 */
class BoutsTest {

    private val t0 = 1_730_000_000_000L

    private fun snore(id: Long, startOffsetMs: Long, durationMs: Long, peak: Double = -30.0) =
        AcousticEventEntity(
            id = id, sessionId = 1, type = EventType.SNORE,
            startMs = t0 + startOffsetMs, endMs = t0 + startOffsetMs + durationMs,
            offsetMs = startOffsetMs, durationMs = durationMs,
            peakDbfs = peak, meanDbfs = peak - 5, intensityTier = "LIGHT",
            confidence = 0.9, modelVersion = "energy-heuristic-v1-spectral",
        )

    @Test
    fun `bursts within the gap chain into one bout`() {
        // 3 bursts, 3 s gaps between consecutive ends/starts → one bout.
        val events = listOf(
            snore(1, 0, 8_000),
            snore(2, 11_000, 8_000), // gap 3 s
            snore(3, 22_000, 8_000), // gap 3 s
        )
        val bouts = groupBouts(events)
        assertEquals(1, bouts.size)
        assertEquals(t0, bouts[0].startMs)
        assertEquals(t0 + 30_000, bouts[0].endMs)
        assertEquals(24L, bouts[0].snoreSeconds) // no overlap: 3 × 8 s
        assertEquals(-30.0, bouts[0].peakDbfs, 0.0001)
    }

    @Test
    fun `a gap beyond the threshold starts a new bout`() {
        val events = listOf(
            snore(1, 0, 8_000),
            snore(2, 18_100, 8_000), // gap 10.1 s > BOUT_GAP_MS → separate
        )
        assertEquals(2, groupBouts(events).size)
    }

    @Test
    fun `the threshold itself still chains (gap exactly 10s)`() {
        val events = listOf(snore(1, 0, 8_000), snore(2, 18_000, 8_000)) // gap exactly 10 s
        assertEquals(1, groupBouts(events).size)
    }

    @Test
    fun `overlapping bursts count their audio once`() {
        val events = listOf(snore(1, 0, 30_000), snore(2, 10_000, 30_000))
        val bouts = groupBouts(events)
        assertEquals(1, bouts.size)
        assertEquals(40L, bouts[0].snoreSeconds) // union 0–40 s, not 60 s
    }

    @Test
    fun `non-snore events never join a bout`() {
        val noise = snore(2, 3_000, 4_000).copy(type = EventType.OTHER_NOISE)
        val bouts = groupBouts(listOf(snore(1, 0, 2_000), noise))
        assertEquals(1, bouts.size)
        assertEquals(1, bouts[0].bursts.size)
    }

    @Test
    fun `stats reflect the session-3 shape`() {
        // Hand-computable replica of the real night's headline: one long train of 14
        // back-to-back bursts (4 s gaps — the 17m02s bout) plus one lone outlier burst.
        val train = (0L until 14).map { i ->
            snore(100 + i, i * 76_000, 72_000) // each burst ends 4 s before the next starts
        }
        val lone = snore(999, 10 * 3_600_000, 2_000, peak = -14.5)
        val stats = boutStats(groupBouts(train + lone))!!

        assertEquals(15, stats.burstCount)
        assertEquals(2, stats.boutCount)
        assertEquals(72_000L, stats.medianBurstMs) // 14×72 s + one 2 s outlier → median 72 s
        assertEquals(72_000L, stats.longestBurstMs)
        assertEquals(1_060_000L, stats.longestBoutMs) // 13 × 76 s + 72 s = 17m40s
        assertEquals(14, stats.longestBoutBursts)
        assertEquals(t0, stats.longestBoutStartMs)
    }

    @Test
    fun `empty nights have no stats and no bouts`() {
        assertNull(boutStats(emptyList()))
        assertTrue(groupBouts(emptyList()).isEmpty())
    }
}
