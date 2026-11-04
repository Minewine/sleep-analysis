package com.sleepanalysis.domain.report

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class NightDatesTest {

    private val zone = ZoneId.of("Europe/Zurich")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `session crossing midnight joins the wake date`() {
        // 21:52 on day D → 07:10 on day D+1: Garmin keys this night under D+1.
        val start = ms(2026, 11, 3, 21, 52)
        val end = ms(2026, 11, 4, 7, 10)
        assertEquals(LocalDate.of(2026, 11, 4), NightDates.garminWakeDate(start, end, zone.id))
    }

    @Test
    fun `open session anchors on start plus twelve hours`() {
        val start = ms(2026, 11, 3, 23, 30)
        assertEquals(LocalDate.of(2026, 11, 4), NightDates.garminWakeDate(start, null, zone.id))
    }

    @Test
    fun `afternoon session wakes the same calendar day`() {
        val start = ms(2026, 11, 3, 14, 0)
        val end = ms(2026, 11, 3, 15, 30)
        assertEquals(LocalDate.of(2026, 11, 3), NightDates.garminWakeDate(start, end, zone.id))
    }

    @Test
    fun `self-logged factors key on the start day`() {
        val start = ms(2026, 11, 3, 21, 52)
        assertEquals(LocalDate.of(2026, 11, 3), NightDates.factorDay(start, zone.id))
    }
}
