package com.sleepanalysis.domain.insights

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SRI epoch math and the approximate social jetlag (docs/INSIGHTS.md §SRI/SJL). */
class SleepRegularityTest {

    private val utc = ZoneId.of("UTC")

    private fun ms(date: String, hour: Int, minute: Int = 0, zone: ZoneId = utc): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** [count] identical 23:00→07:00 nights starting on 2026-01-01 (absolute epochs). */
    private fun regularWindows(count: Int, shiftMs: Long = 0, shiftedIndex: Int = -1): List<SleepRegularity.NightWindow> =
        (0 until count).map { d ->
            val day = LocalDate.of(2026, 1, 1).plusDays(d.toLong())
            val shift = if (d == shiftedIndex) shiftMs else 0L
            SleepRegularity.NightWindow(
                date = day.plusDays(1).toString(), // wake date label
                startMs = ms(day.toString(), 23) + shift,
                endMs = ms(day.plusDays(1).toString(), 7) + shift,
            )
        }

    @Test
    fun `perfectly regular nights give SRI 100`() {
        val result = SleepRegularity.compute(regularWindows(6), utc)
        assertEquals(SleepRegularity.State.READY, result.state)
        assertEquals(100.0, result.sri!!, 1e-9)
        assertTrue(result.comparedEpochPairs > 0)
        assertEquals(6, result.dayCount)
    }

    @Test
    fun `a shifted night lowers the SRI to the exact expected value`() {
        // One night of six shifted +2 h: 6241 epoch-hours are compared (the inclusive grid from
        // horizonStart to horizonEnd − 24 h), and exactly 480 epochs mismatch — both edges
        // around the shift, in both pairing directions ([71,73)+[95,97), [79,81)+[103,105),
        // [95,97)+[119,121), [103,105)+[127,129)); the final edge pair (127 h ↔ 151 h) is
        // awake↔awake and matches, hence 6241 compared, not 6240.
        val shifted = regularWindows(6, shiftMs = 2 * 60 * 60_000L, shiftedIndex = 3)
        val result = SleepRegularity.compute(shifted, utc)
        assertNotNull(result.sri)
        assertEquals(5761.0 / 6241.0 * 100.0, result.sri!!, 1e-6)
    }

    @Test
    fun `fewer than 5 valid days stays COLLECTING with no SRI`() {
        val result = SleepRegularity.compute(regularWindows(4), utc)
        assertEquals(SleepRegularity.State.COLLECTING, result.state)
        assertNull(result.sri)
        // exactly 5 is the boundary
        assertEquals(100.0, SleepRegularity.compute(regularWindows(5), utc).sri!!, 1e-9)
    }

    @Test
    fun `degenerate windows are dropped and duplicates collapse by date`() {
        val windows = regularWindows(5) +
            listOf(
                SleepRegularity.NightWindow("2026-01-06", 100L, 50L), // end <= start
                SleepRegularity.NightWindow("2026-01-06", 100L, 50L), // duplicate of the above
            )
        assertEquals(5, SleepRegularity.compute(windows, utc).dayCount)
    }

    @Test
    fun `window crossing midnight is the normal case (covered by regular nights) `() {
        // Every synthetic window spans 23:00 → 07:00 next day; SRI 100 means the midnight
        // crossing itself never breaks the epoch pairing.
        assertEquals(100.0, SleepRegularity.compute(regularWindows(5), utc).sri!!, 1e-9)
    }

    @Test
    fun `DST spring-forward shortens the horizon by one hour without corrupting the index`() {
        // Wall-clock-identical 23:00→07:00 nights keep matching under absolute-epoch pairing
        // (the state sequence shifts WITH the wall clock), so SRI stays 100 — but the DST day
        // has one fewer absolute epoch hour, so exactly 60 fewer pairs are compared.
        val zurich = ZoneId.of("Europe/Zurich") // 2026-03-29 02:00 → 03:00 CET→CEST
        val dstWindows = listOf(
            SleepRegularity.NightWindow(
                "2026-03-29",
                ms("2026-03-28", 23, zone = zurich),
                ms("2026-03-29", 7, zone = zurich), // 7 absolute hours (8 wall-clock)
            ),
            SleepRegularity.NightWindow("2026-03-30", ms("2026-03-29", 23, zone = zurich), ms("2026-03-30", 7, zone = zurich)),
            SleepRegularity.NightWindow("2026-03-31", ms("2026-03-30", 23, zone = zurich), ms("2026-03-31", 7, zone = zurich)),
            SleepRegularity.NightWindow("2026-04-01", ms("2026-03-31", 23, zone = zurich), ms("2026-04-01", 7, zone = zurich)),
            SleepRegularity.NightWindow("2026-04-02", ms("2026-04-01", 23, zone = zurich), ms("2026-04-02", 7, zone = zurich)),
        )
        val regular = regularWindows(5)
        val dstResult = SleepRegularity.compute(dstWindows, utc)
        val regularResult = SleepRegularity.compute(regular, utc)
        assertEquals(100.0, dstResult.sri!!, 1e-9)
        assertEquals(100.0, regularResult.sri!!, 1e-9)
        assertEquals(regularResult.comparedEpochPairs - 60, dstResult.comparedEpochPairs)
    }

    @Test
    fun `social jetlag - weekday 23-07 vs weekend 01-09 gives 2h and flags`() {
        // 2026-01-05 is a Monday. Weekday windows end Tue–Fri (midpoint 03:00 = 15 h after the
        // preceding noon); weekend windows end Sat/Sun 01:00→09:00 (midpoint 05:00 = 17 h).
        val windows = listOf(
            SleepRegularity.NightWindow("2026-01-06", ms("2026-01-05", 23), ms("2026-01-06", 7)),
            SleepRegularity.NightWindow("2026-01-07", ms("2026-01-06", 23), ms("2026-01-07", 7)),
            SleepRegularity.NightWindow("2026-01-08", ms("2026-01-07", 23), ms("2026-01-08", 7)),
            SleepRegularity.NightWindow("2026-01-09", ms("2026-01-08", 23), ms("2026-01-09", 7)),
            SleepRegularity.NightWindow("2026-01-10", ms("2026-01-10", 1), ms("2026-01-10", 9)),
            SleepRegularity.NightWindow("2026-01-11", ms("2026-01-11", 1), ms("2026-01-11", 9)),
        )
        val result = SleepRegularity.compute(windows, utc)
        assertEquals(2.0, result.socialJetlagHours!!, 1e-9)
        assertTrue(result.socialJetlagFlagged)
    }

    @Test
    fun `social jetlag needs at least 2 windows per group`() {
        val one = listOf(
            SleepRegularity.NightWindow("2026-01-06", ms("2026-01-05", 23), ms("2026-01-06", 7)),
            SleepRegularity.NightWindow("2026-01-10", ms("2026-01-10", 1), ms("2026-01-10", 9)),
            SleepRegularity.NightWindow("2026-01-07", ms("2026-01-06", 23), ms("2026-01-07", 7)),
        )
        assertNull(SleepRegularity.compute(one, utc).socialJetlagHours)
        val two = one + SleepRegularity.NightWindow("2026-01-11", ms("2026-01-11", 1), ms("2026-01-11", 9))
        val result = SleepRegularity.compute(two, utc)
        assertEquals(2.0, result.socialJetlagHours!!, 1e-9)
        assertTrue(result.socialJetlagFlagged)
    }

    @Test
    fun `social jetlag within the convention threshold does not flag`() {
        val windows = listOf(
            SleepRegularity.NightWindow("2026-01-06", ms("2026-01-05", 23), ms("2026-01-06", 7)),
            SleepRegularity.NightWindow("2026-01-07", ms("2026-01-06", 23), ms("2026-01-07", 7)),
            SleepRegularity.NightWindow("2026-01-10", ms("2026-01-09", 23, 30), ms("2026-01-10", 7, 30)),
            SleepRegularity.NightWindow("2026-01-11", ms("2026-01-10", 23, 30), ms("2026-01-11", 7, 30)),
        )
        val result = SleepRegularity.compute(windows, utc)
        assertEquals(0.5, result.socialJetlagHours!!, 1e-9)
        assertFalse(result.socialJetlagFlagged)
    }
}
