package com.sleepanalysis.domain.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Decision table for the manual-stop confirmation (docs/DATA_MODEL.md, dogfood 2026-11-07→08:
 * a 04:13 manual stop cut 2.5 h the watch still recorded). A stop before tonight's scheduled
 * end asks first; after it, or with the schedule off, it doesn't.
 */
class NightStopPolicyTest {

    private val zone = ZoneId.of("Europe/Zurich")

    private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, 8, hour, minute, 0, 0, zone)

    private fun endToday(hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, 8, hour, minute, 0, 0, zone)

    @Test
    fun `stop during the night confirms`() {
        // The dogfood shape: 04:13 stop, scheduled end 06:30 still ahead.
        assertTrue(NightStopPolicy.shouldConfirmStop(at(4, 13), true, endToday(6, 30)))
        assertTrue(NightStopPolicy.shouldConfirmStop(at(23, 0), true, endToday(6, 30).plusDays(1)))
    }

    @Test
    fun `stop after tonight's end does not confirm`() {
        // 07:00 with a 06:30 end: the night is over. The next occurrence is TOMORROW — 23.5 h
        // away — so the horizon check, not the gap alone, keeps this confirm-free.
        assertFalse(NightStopPolicy.shouldConfirmStop(at(7, 0), true, endToday(6, 30).plusDays(1)))
    }

    @Test
    fun `stops far from any night shape do not confirm`() {
        // 14:00, end tomorrow 06:30 — 16.5 h away, outside the night window.
        assertFalse(NightStopPolicy.shouldConfirmStop(at(14, 0), true, endToday(6, 30).plusDays(1)))
    }

    @Test
    fun `no schedule never confirms`() {
        assertFalse(NightStopPolicy.shouldConfirmStop(at(4, 13), false, endToday(6, 30)))
        assertFalse(NightStopPolicy.shouldConfirmStop(at(4, 13), true, null))
    }

    @Test
    fun `the night window is bounded`() {
        // Exactly at the horizon boundary: 12 h ahead is outside (a 23:30 stop with a 11:30
        // end is not "still tonight" in any useful sense).
        val end = at(4, 0).plus(Duration.ofHours(12))
        assertFalse(NightStopPolicy.shouldConfirmStop(at(4, 0), true, end))
    }
}
