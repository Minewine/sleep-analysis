package com.sleepanalysis.data.garmin

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class GarminDayWindowTest {

    @Test
    fun `window brackets the local day in the given zone`() {
        val zone = ZoneId.of("Europe/Zurich")
        val date = LocalDate.of(2026, 11, 3)
        val (start, end) = GarminImportManager.dayWindowMs(date, zone)
        assertEquals(
            ZonedDateTime.of(2026, 11, 3, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            start,
        )
        assertEquals(Duration.ofHours(24).toMillis(), end - start)
    }

    @Test
    fun `same date in two zones yields different windows`() {
        val date = LocalDate.of(2026, 11, 3)
        val zurich = GarminImportManager.dayWindowMs(date, ZoneId.of("Europe/Zurich"))
        val utc = GarminImportManager.dayWindowMs(date, ZoneId.of("UTC"))
        // Zurich (UTC+1 in November) starts its local day one hour earlier in epoch terms.
        assertEquals(Duration.ofHours(1).toMillis(), utc.first - zurich.first)
    }

    @Test
    fun `day of the autumn clock change is twentyfive hours`() {
        // Europe/Zurich went back to CET on 2026-10-25: the local day is 25 h long.
        val zone = ZoneId.of("Europe/Zurich")
        val (_, end) = GarminImportManager.dayWindowMs(LocalDate.of(2026, 10, 25), zone)
        assertEquals(Duration.ofHours(25).toMillis(), end - GarminImportManager.dayWindowMs(LocalDate.of(2026, 10, 25), zone).first)
    }

    @Test
    fun `day of the spring clock change is twentythree hours`() {
        // Europe/Zurich jumped to CEST on 2026-03-29: the local day is 23 h long.
        val zone = ZoneId.of("Europe/Zurich")
        val window = GarminImportManager.dayWindowMs(LocalDate.of(2026, 3, 29), zone)
        assertEquals(Duration.ofHours(23).toMillis(), window.second - window.first)
    }
}
