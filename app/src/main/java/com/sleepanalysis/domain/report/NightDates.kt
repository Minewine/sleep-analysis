package com.sleepanalysis.domain.report

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Night↔calendar-day anchoring (docs/DATA_MODEL.md §5.3). Two different days are meaningful
 * for one session, and they are deliberately distinct:
 *
 *  - [garminWakeDate] — the LOCAL WAKE date. Garmin keys a night's payload by its
 *    `calendarDate`, which is the morning the night ENDS: a session starting 21:52 on day D
 *    and ending 07:10 on day D+1 is Garmin's "day D+1" sleep entry. Every Garmin query and
 *    import for a session must anchor here. Open sessions (no endedAt yet) anchor on
 *    startedAt + [OPEN_SESSION_WAKE_OFFSET_MS], which lands on the wake date for any
 *    realistic sleep window.
 *  - [factorDay] — the START date. Self-logged factors ("coffee at 18:00", "stressful day")
 *    are user context about the evening the night began; they key on the day the session
 *    started, not the wake date.
 */
object NightDates {

    /** Wake-date fallback for open sessions (no endedAt yet). */
    const val OPEN_SESSION_WAKE_OFFSET_MS: Long = 12 * 60 * 60 * 1000

    /** The wake calendar day this night belongs to — the join key for all Garmin metrics. */
    fun garminWakeDate(startedAtMs: Long, endedAtMs: Long?, timeZoneId: String): LocalDate {
        val wakeInstant = endedAtMs ?: (startedAtMs + OPEN_SESSION_WAKE_OFFSET_MS)
        return Instant.ofEpochMilli(wakeInstant).atZone(ZoneId.of(timeZoneId)).toLocalDate()
    }

    /** Self-logged factors key on the day the session started (user context, not measurements). */
    fun factorDay(startedAtMs: Long, timeZoneId: String): LocalDate =
        Instant.ofEpochMilli(startedAtMs).atZone(ZoneId.of(timeZoneId)).toLocalDate()
}
