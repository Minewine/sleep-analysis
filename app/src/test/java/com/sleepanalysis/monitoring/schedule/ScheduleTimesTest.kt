package com.sleepanalysis.monitoring.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleTimesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Zurich")

    private fun at(date: String, time: String): ZonedDateTime =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone)

    @Test
    fun `same-day future occurrence stays today`() {
        val now = at("2026-11-01", "10:00")
        assertEquals(at("2026-11-01", "22:30"), ScheduleTimes.nextOccurrence(now, 22 * 60 + 30))
    }

    @Test
    fun `past occurrence moves to tomorrow`() {
        val now = at("2026-11-01", "23:00")
        assertEquals(at("2026-11-02", "22:30"), ScheduleTimes.nextOccurrence(now, 22 * 60 + 30))
    }

    @Test
    fun `skip marker advances the start by one day then expires`() {
        val now = at("2026-11-01", "10:00")
        val today = now.toLocalDate().toEpochDay()
        assertEquals(at("2026-11-02", "22:30"), ScheduleTimes.nextStart(now, 22 * 60 + 30, today))
        // A marker for any other day is ignored.
        assertEquals(at("2026-11-01", "22:30"), ScheduleTimes.nextStart(now, 22 * 60 + 30, today - 1))
    }

    @Test
    fun `occurrences keep the wall-clock time across the DST spring-forward`() {
        // Europe/Zurich springs forward on 2026-03-29 (02:00 → 03:00 local).
        val before = ScheduleTimes.nextOccurrence(at("2026-03-28", "10:00"), 22 * 60 + 30)
        assertEquals(LocalTime.of(22, 30), before.toLocalTime())
        assertEquals(at("2026-03-28", "22:30"), before)
        val after = ScheduleTimes.nextOccurrence(at("2026-03-29", "10:00"), 22 * 60 + 30)
        assertEquals(LocalTime.of(22, 30), after.toLocalTime())
        assertEquals(at("2026-03-29", "22:30"), after)
    }

    // --- minutesOfDay bounds (0 and 1439) ---

    @Test
    fun `minutesOfDay helper spans the full valid range`() {
        assertEquals(0, ScheduleTimes.minutesOfDay(0, 0))
        assertEquals(1439, ScheduleTimes.minutesOfDay(23, 59))
        assertEquals(1350, ScheduleTimes.minutesOfDay(22, 30))
        assertEquals(390, ScheduleTimes.minutesOfDay(6, 30))
    }

    @Test
    fun `midnight occurrence (0) is tomorrow when now is past midnight`() {
        val now = at("2026-11-01", "10:00")
        assertEquals(at("2026-11-02", "00:00"), ScheduleTimes.nextOccurrence(now, 0))
    }

    @Test
    fun `last minute of the day (1439) stays today while future and rolls over exactly at its time`() {
        val now = at("2026-11-01", "10:00")
        assertEquals(at("2026-11-01", "23:59"), ScheduleTimes.nextOccurrence(now, 1439))
        // now == the target is not strictly after, so it moves to tomorrow.
        assertEquals(
            at("2026-11-02", "23:59"),
            ScheduleTimes.nextOccurrence(at("2026-11-01", "23:59"), 1439),
        )
    }

    // --- corrupt out-of-range minutes are clamped, not thrown ---

    @Test
    fun `out-of-range minutes are clamped into the valid day range`() {
        val now = at("2026-11-01", "10:00")
        assertEquals(at("2026-11-01", "23:59"), ScheduleTimes.nextOccurrence(now, 10_000))
        // Clamped to midnight, which is already past 10:00 today → tomorrow.
        assertEquals(at("2026-11-02", "00:00"), ScheduleTimes.nextOccurrence(now, -5))
    }

    // --- start times within a minute of now (strictly-after boundary) ---

    @Test
    fun `occurrence exactly now rolls to tomorrow`() {
        // A start receiver re-arms right at the bedtime; the next start must be tomorrow.
        val now = at("2026-11-01", "22:30")
        assertEquals(at("2026-11-02", "22:30"), ScheduleTimes.nextOccurrence(now, 22 * 60 + 30))
    }

    @Test
    fun `occurrence a minute in the future stays today`() {
        val now = at("2026-11-01", "22:29")
        assertEquals(at("2026-11-01", "22:30"), ScheduleTimes.nextOccurrence(now, 22 * 60 + 30))
    }

    // --- DST: spring forward 2026-03-29 and fall back 2026-10-25 (Europe/Zurich) ---

    @Test
    fun `bedtime inside the spring-forward gap resolves to the post-gap instant`() {
        // 02:00-02:59 does not exist on 2026-03-29 (02:00 CET -> 03:00 CEST); java.time's
        // documented gap resolution shifts the wall clock forward by the gap length.
        val now = at("2026-03-29", "01:30") // still CET
        val result = ScheduleTimes.nextOccurrence(now, 2 * 60 + 30)
        assertEquals(at("2026-03-29", "03:30"), result)
        assertEquals(java.time.Instant.parse("2026-03-29T01:30:00Z"), result.toInstant())
        assertEquals(java.time.ZoneOffset.of("+02:00"), result.offset)
    }

    @Test
    fun `duplicated wall-clock time on the fall-back day resolves to the earlier offset`() {
        // 02:00-02:59 happens twice on 2026-10-25; java.time picks the earlier offset.
        val now = at("2026-10-25", "01:00") // CEST
        val result = ScheduleTimes.nextOccurrence(now, 2 * 60 + 30)
        assertEquals(LocalTime.of(2, 30), result.toLocalTime())
        assertEquals(java.time.ZoneOffset.of("+02:00"), result.offset)
        assertEquals(java.time.Instant.parse("2026-10-25T00:30:00Z"), result.toInstant())
    }

    // --- skip marker edge cases ---

    @Test
    fun `skip marker for tomorrow does not affect tonight`() {
        val now = at("2026-11-01", "10:00")
        val tomorrow = now.toLocalDate().plusDays(1).toEpochDay()
        assertEquals(at("2026-11-01", "22:30"), ScheduleTimes.nextStart(now, 22 * 60 + 30, tomorrow))
    }

    @Test
    fun `skip marker applies to tomorrow's occurrence once tonight is past`() {
        val now = at("2026-11-01", "23:00") // tonight's 22:30 already gone
        val tomorrow = now.toLocalDate().plusDays(1).toEpochDay()
        assertEquals(at("2026-11-03", "22:30"), ScheduleTimes.nextStart(now, 22 * 60 + 30, tomorrow))
    }

    @Test
    fun `sentinel zero and far-past skip markers are ignored`() {
        val now = at("2026-11-01", "10:00")
        // 0 is the "no skip" default and can never be a real epoch day (1970-01-01).
        assertEquals(at("2026-11-01", "22:30"), ScheduleTimes.nextStart(now, 22 * 60 + 30, 0L))
        val farPast = now.toLocalDate().toEpochDay() - 400
        assertEquals(at("2026-11-01", "22:30"), ScheduleTimes.nextStart(now, 22 * 60 + 30, farPast))
    }

    @Test
    fun `far-future skip marker is ignored`() {
        val now = at("2026-11-01", "10:00")
        val farFuture = now.toLocalDate().toEpochDay() + 30
        assertEquals(at("2026-11-01", "22:30"), ScheduleTimes.nextStart(now, 22 * 60 + 30, farFuture))
    }

    @Test
    fun `huge skip marker terminates the day-by-day advance immediately`() {
        // Guards the while loop: a marker that never matches must not loop forever.
        val now = at("2026-11-01", "10:00")
        assertEquals(at("2026-11-01", "22:30"), ScheduleTimes.nextStart(now, 22 * 60 + 30, Long.MAX_VALUE))
    }
}
