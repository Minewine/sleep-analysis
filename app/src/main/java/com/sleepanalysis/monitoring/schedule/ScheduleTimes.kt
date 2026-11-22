package com.sleepanalysis.monitoring.schedule

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Pure scheduling math for nightly recording windows — no Android imports, unit-testable.
 *
 * All construction goes through LocalDate + LocalTime + atZone (never plusMinutes on
 * ZonedDateTime) so DST shifts move the offset, never the wall-clock bedtime.
 */
object ScheduleTimes {

    private const val MINUTES_PER_DAY = 24 * 60

    fun minutesOfDay(hour: Int, minute: Int): Int = hour * 60 + minute

    /**
     * The next occurrence of [minutesOfDay] strictly after [now]: today at that time if
     * still in the future, otherwise tomorrow. Out-of-range minutes (corrupt pref) are
     * clamped into the valid day range instead of throwing.
     */
    fun nextOccurrence(now: ZonedDateTime, minutesOfDay: Int): ZonedDateTime {
        val today = now.toLocalDate().atTime(normalizedTime(minutesOfDay)).atZone(now.zone)
        return if (today.isAfter(now)) today
        else now.toLocalDate().plusDays(1).atTime(normalizedTime(minutesOfDay)).atZone(now.zone)
    }

    /**
     * Next start honoring the one-night skip marker: advances day-by-day while the
     * candidate's INTENDED WAKE date falls on [skipEpochDay] (a marker for any other day is
     * ignored). The marker keys the wake date — the wake day a post-midnight start shares
     * with its occurrence day — so a skipped night is suppressed consistently everywhere it
     * is read (banner, trends, here), regardless of whether the schedule starts before or
     * after midnight (P2-6).
     */
    fun nextStart(now: ZonedDateTime, startMinutes: Int, endMinutes: Int, skipEpochDay: Long): ZonedDateTime {
        var candidate = nextOccurrence(now, startMinutes)
        while (intendedWakeDay(candidate, endMinutes) == skipEpochDay) {
            candidate = candidate.toLocalDate().plusDays(1)
                .atTime(normalizedTime(startMinutes)).atZone(now.zone)
        }
        return candidate
    }

    /**
     * The local epoch day the night starting at [startOccurrence] would WAKE on, given the
     * schedule's end time: the next end-time occurrence after the start — the day after for
     * an evening start, the same day for a start after midnight (P2-6). Skip markers key
     * this date.
     */
    fun intendedWakeDay(startOccurrence: ZonedDateTime, endMinutes: Int): Long =
        nextOccurrence(startOccurrence, endMinutes).toLocalDate().toEpochDay()

    private fun normalizedTime(minutesOfDay: Int): LocalTime {
        val m = minutesOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        return LocalTime.of(m / 60, m % 60)
    }
}
