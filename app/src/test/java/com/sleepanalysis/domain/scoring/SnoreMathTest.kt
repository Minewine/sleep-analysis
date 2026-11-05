package com.sleepanalysis.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

class SnoreMathTest {

    @Test
    fun `empty input is zero`() {
        assertEquals(0L, SnoreMath.unionDurationMs(emptyList()))
    }

    @Test
    fun `disjoint intervals sum`() {
        assertEquals(15_000L, SnoreMath.unionDurationMs(listOf(0L to 10_000L, 15_000L to 20_000L)))
    }

    @Test
    fun `overlapping intervals count the overlap once`() {
        // 0–30 s snore delivered as two overlapping windows: 30 s, not 50 s.
        assertEquals(30_000L, SnoreMath.unionDurationMs(listOf(0L to 20_000L, 10_000L to 30_000L)))
    }

    @Test
    fun `contained intervals add nothing`() {
        assertEquals(60_000L, SnoreMath.unionDurationMs(listOf(0L to 60_000L, 10_000L to 20_000L)))
    }

    @Test
    fun `unsorted input is handled`() {
        assertEquals(30_000L, SnoreMath.unionDurationMs(listOf(10_000L to 30_000L, 0L to 20_000L)))
    }

    @Test
    fun `chain of touching intervals merges into one span`() {
        // Touching (s == previous end) is an overlap, not a gap.
        assertEquals(30_000L, SnoreMath.unionDurationMs(listOf(0L to 10_000L, 10_000L to 20_000L, 20_000L to 30_000L)))
    }

    @Test
    fun `degenerate intervals contribute nothing`() {
        assertEquals(
            10_000L,
            SnoreMath.unionDurationMs(listOf(5_000L to 2_000L, 0L to 10_000L)),
        )
    }
}
