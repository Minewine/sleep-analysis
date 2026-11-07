package com.sleepanalysis.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SnoreScore v2 ("snore-score-v2"): share component saturating at 40% of the night,
 * intensity weighted by per-tier snoring TIME. Recalculation anchors from the five dogfood
 * nights are pinned in the last test — the formula must keep reproducing them.
 */
class SnoreScoreTest {

    private val T = SnoreScore.IntensityTiers
    private val night = 8 * 3600L // 8 h night

    @Test
    fun `silent night scores zero`() {
        val r = SnoreScore.compute(0, night, emptyMap())
        assertEquals(0, r.score)
        assertEquals(0.0, r.sharePoints, 0.0001)
        assertEquals(0.0, r.intensityPoints, 0.0001)
    }

    @Test
    fun `zero night duration is safe`() {
        assertEquals(0, SnoreScore.compute(100, 0, mapOf(T.LOUD to 5L)).score)
    }

    @Test
    fun `score is capped at 100`() {
        // 100% of the night snoring, all epic: share 60 + intensity 40.
        val r = SnoreScore.compute(night, night, mapOf(T.EPIC to night))
        assertEquals(100, r.score)
        assertEquals(60.0, r.sharePoints, 0.0001)
        assertEquals(40.0, r.intensityPoints, 0.0001)
    }

    @Test
    fun `score grows with snoring share through the 15-40 percent range`() {
        fun at(shareOfNight: Double): Int {
            val seconds = (shareOfNight * night).toLong()
            return SnoreScore.compute(seconds, night, mapOf(T.LIGHT to seconds)).score
        }
        val s15 = at(0.15)
        val s25 = at(0.25)
        val s35 = at(0.35)
        assertTrue("15% ($s15) < 25% ($s25) < 35% ($s35)", s15 < s25 && s25 < s35)
    }

    @Test
    fun `share component saturates at 40 percent not 10`() {
        // 10% share: v1 saturated here (60 pts); v2 gives 15. The whole point of v2.
        val r10 = SnoreScore.compute((0.10 * night).toLong(), night, mapOf(T.LIGHT to (0.10 * night).toLong()))
        assertEquals(15.0, r10.sharePoints, 0.0001)
        // 40% and beyond: share component locks at 60.
        assertEquals(60.0, SnoreScore.compute((0.40 * night).toLong(), night, emptyMap()).sharePoints, 0.0001)
        assertEquals(60.0, SnoreScore.compute(night, night, emptyMap()).sharePoints, 0.0001)
    }

    @Test
    fun `score grows with intensity at constant share`() {
        val quiet = SnoreScore.compute(1800, night, mapOf(T.QUIET to 1800L))
        val loud = SnoreScore.compute(1800, night, mapOf(T.LOUD to 1800L))
        assertTrue(loud.score > quiet.score)
        assertEquals(0.15, quiet.meanTierWeight, 0.0001)
        assertEquals(0.65, loud.meanTierWeight, 0.0001)
    }

    @Test
    fun `intensity follows snoring TIME not spell count`() {
        // One 60 s LOUD stretch must outweigh sixty 1 s QUIET spells — v1 (count-weighted)
        // would have scored the quiet night higher.
        val oneLoudMinute = SnoreScore.compute(60, night, mapOf(T.LOUD to 60L))
        val manyQuietSeconds = SnoreScore.compute(60, night, mapOf(T.QUIET to 60L))
        assertTrue(oneLoudMinute.score > manyQuietSeconds.score)
    }

    @Test
    fun `breakdown decomposes exactly into the score`() {
        val r = SnoreScore.compute(7233, 28_800, mapOf(T.QUIET to 7238L, T.LIGHT to 1992L))
        assertEquals((r.sharePoints + r.intensityPoints).toInt(), r.score)
    }

    @Test
    fun `recalculated dogfood nights are distinguishable in the 15-40 percent range`() {
        // Anchors computed from the real sessions (share = stored SNORE_SHARE_OF_NIGHT,
        // tier seconds = per-tier union snoring time):
        //   s10 13.05% (3718 QUIET s) -> 25   | s6 15.91% (4833 QUIET s) -> 29
        //   s11 27.05% (6902 Q / 1432 L) -> 47 | s3 29.23% (3405 Q / 2929 L / 9 O) -> 53
        //   s1 30.46% (7238 Q / 1992 L / 106 O) -> 53     (v1 gave 66/66/66/67/67)
        val s10 = SnoreScore.compute((0.130497 * 28_675).toLong(), 28_675, mapOf(T.QUIET to 3718L, T.LIGHT to 12L, T.LOUD to 12L))
        val s6 = SnoreScore.compute((0.159147 * 30_607).toLong(), 30_607, mapOf(T.QUIET to 4833L, T.LIGHT to 29L, T.LOUD to 10L))
        val s11 = SnoreScore.compute((0.270537 * 30_835).toLong(), 30_835, mapOf(T.QUIET to 6902L, T.LIGHT to 1432L, T.LOUD to 8L))
        val s3 = SnoreScore.compute(6343L, 21_703, mapOf(T.QUIET to 3405L, T.LIGHT to 2929L, T.LOUD to 9L))
        val s1 = SnoreScore.compute(9336L, 30_649, mapOf(T.QUIET to 7238L, T.LIGHT to 1992L, T.LOUD to 106L))
        assertEquals(25, s10.score)
        assertEquals(29, s6.score)
        assertEquals(47, s11.score)
        assertEquals(53, s3.score)
        assertEquals(53, s1.score)
        // The score formula identity is stored on new SNORE_SCORE rows.
        assertEquals("snore-score-v2", SnoreScore.FORMULA_VERSION)
    }
}
