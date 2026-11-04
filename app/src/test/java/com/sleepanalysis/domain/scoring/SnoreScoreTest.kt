package com.sleepanalysis.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoreScoreTest {

    private val T = SnoreScore.IntensityTiers

    @Test
    fun `silent night scores zero`() {
        assertEquals(0, SnoreScore.compute(0, 8 * 3600, emptyMap()))
    }

    @Test
    fun `zero night duration is safe`() {
        assertEquals(0, SnoreScore.compute(100, 0, mapOf(T.LOUD to 5)))
    }

    @Test
    fun `score is capped at 100`() {
        // 100% of the night snoring, all epic.
        assertEquals(100, SnoreScore.compute(8 * 3600, 8 * 3600, mapOf(T.EPIC to 500)))
    }

    @Test
    fun `score grows with snoring share`() {
        val s1 = SnoreScore.compute(600, 8 * 3600, mapOf(T.LIGHT to 20))
        val s2 = SnoreScore.compute(1800, 8 * 3600, mapOf(T.LIGHT to 60))
        val s3 = SnoreScore.compute(3600, 8 * 3600, mapOf(T.LIGHT to 120))
        assertTrue(s1 < s2 && s2 < s3)
    }

    @Test
    fun `score grows with intensity at constant share`() {
        val quiet = SnoreScore.compute(1800, 8 * 3600, mapOf(T.QUIET to 40))
        val loud = SnoreScore.compute(1800, 8 * 3600, mapOf(T.LOUD to 40))
        assertTrue(loud > quiet)
    }

    @Test
    fun `ten percent moderate snoring lands near seventy`() {
        val score = SnoreScore.compute(2880, 8 * 3600, mapOf(T.LOUD to 100))
        assertTrue("got $score", score in 70..95)
    }
}
