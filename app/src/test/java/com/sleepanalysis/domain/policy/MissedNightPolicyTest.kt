package com.sleepanalysis.domain.policy

import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Decision table for the missed-night banner (dogfood 2026-11-18→19: recording only began at
 * 01:02 and the report first read as "nothing recorded"). A night is identified by its WAKE
 * date (the local day the session ended on — the anchor Garmin keys nights by), the skip set
 * holds START-evening epoch days, and night-like means ≥ 4 h in bed.
 */
class MissedNightPolicyTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val today = LocalDate.of(2026, 11, 19)

    private fun ms(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(
        startedAt: Long,
        endedAt: Long?,
        status: String = SessionStatus.COMPLETED,
    ) = SleepSessionEntity(
        id = 1, startedAt = startedAt, endedAt = endedAt, status = status,
        timeZone = zone.id, deviceModel = "Test", osVersion = "30", appVersion = "0.1.0",
    )

    private fun evaluate(sessions: List<SleepSessionEntity>, skippedDays: Set<Long> = emptySet()) =
        MissedNightPolicy.evaluate(sessions, today.toEpochDay(), skippedDays)

    private fun evaluateOn(
        date: LocalDate,
        sessions: List<SleepSessionEntity>,
        skippedDays: Set<Long> = emptySet(),
    ) = MissedNightPolicy.evaluate(sessions, date.toEpochDay(), skippedDays)

    @Test
    fun `recorded when a night-like session woke today`() {
        val s = session(ms(today.minusDays(1), 22, 14), ms(today, 7, 0))
        assertEquals(MissedNightResult.Recorded, evaluate(listOf(s)))
    }

    @Test
    fun `exactly 4h in bed is night-like`() {
        val s = session(ms(today.minusDays(1), 23, 0), ms(today, 3, 0))
        assertEquals(MissedNightResult.Recorded, evaluate(listOf(s)))
    }

    @Test
    fun `a late start after midnight still belongs to the night before`() {
        // The dogfood shape: recording only started at 01:02 today — the night still counts
        // as recorded (its wake date is today), the report's Garmin line tells the rest.
        val s = session(ms(today, 1, 2), ms(today, 7, 0))
        assertEquals(MissedNightResult.Recorded, evaluate(listOf(s)))
    }

    @Test
    fun `under 4h is short and reports the captured in-bed time`() {
        val died = session(ms(today.minusDays(1), 23, 30), ms(today, 1, 30), SessionStatus.RECOVERED)
        val result = evaluate(listOf(died))
        assertTrue(result is MissedNightResult.Short)
        assertEquals(2 * 3_600_000L, (result as MissedNightResult.Short).capturedInBedMs)
    }

    @Test
    fun `short sums multiple sessions of the same night`() {
        // A process death at 01:00 (RECOVERED) and a restarted hour: 2 h + 1 h in bed, neither
        // session night-like on its own — the banner reports the total captured time.
        val first = session(ms(today.minusDays(1), 23, 0), ms(today, 1, 0), SessionStatus.RECOVERED)
        val second = session(ms(today, 1, 30), ms(today, 2, 30))
        val result = evaluate(listOf(first, second))
        assertEquals(MissedNightResult.Short(3 * 3_600_000L), result)
    }

    @Test
    fun `non-night-like statuses never silence the banner`() {
        // STARTED (RECORDING) and INTERRUPTED sessions are not eligible: an open or aborted
        // night cannot stand in for the missing one.
        val recording = session(ms(today.minusDays(1), 23, 0), null, SessionStatus.RECORDING)
        val interrupted = session(ms(today.minusDays(1), 23, 0), ms(today, 2, 0), SessionStatus.INTERRUPTED)
        assertEquals(MissedNightResult.Missed, evaluate(listOf(recording, interrupted)))
    }

    @Test
    fun `missed when only sessions of other nights exist`() {
        val olderNight = session(ms(today.minusDays(2), 22, 0), ms(today.minusDays(1), 7, 0))
        assertEquals(MissedNightResult.Missed, evaluate(listOf(olderNight)))
        assertEquals(MissedNightResult.Missed, evaluate(emptyList()))
    }

    @Test
    fun `skipped wins — the start evening before the wake date decides`() {
        // Skipped on purpose must stay quiet even though a short session exists for the night.
        val short = session(ms(today.minusDays(1), 23, 30), ms(today, 1, 30), SessionStatus.RECOVERED)
        assertEquals(MissedNightResult.Skipped, evaluate(listOf(short), skippedDays = setOf(today.minusDays(1).toEpochDay())))
        // The skip key is the START evening: today's own epoch day does not skip last night.
        assertEquals(MissedNightResult.Short(2 * 3_600_000L), evaluate(listOf(short), skippedDays = setOf(today.toEpochDay())))
    }

    @Test
    fun `a skipped night with nothing recorded is still skipped, not missed`() {
        assertEquals(MissedNightResult.Skipped, evaluate(emptyList(), skippedDays = setOf(today.minusDays(1).toEpochDay())))
    }

    @Test
    fun `the night-like threshold mirrors the previous-night constant`() {
        // The banner and the previous-night comparison must agree on what "a full night" is.
        assertEquals(
            com.sleepanalysis.domain.report.PreviousNight.NIGHT_LIKE_MIN_IN_BED_MS,
            MissedNightPolicy.NIGHT_LIKE_MIN_IN_BED_MS,
        )
    }

    // ---- DST / clock-shift nights (Europe/Zurich: 2026-03-29 spring forward, 2026-10-25 back) ----

    @Test
    fun `spring-forward night counts epoch time, not wall time`() {
        // The 23 h day: 23:30 → 03:30 reads 4 h on the wall but is only 3 h of epoch time
        // (02:00→03:00 never happened). In-bed time is epoch-based, so it is a 3 h Short.
        val springWake = LocalDate.of(2026, 3, 29)
        val s = session(ms(LocalDate.of(2026, 3, 28), 23, 30), ms(springWake, 3, 30))
        val result = evaluateOn(springWake, listOf(s))
        assertEquals(MissedNightResult.Short(3 * 3_600_000L), result)
        // The wake-date anchor is pure calendar arithmetic — the shift cannot push the
        // session onto the wrong day (only Mar 28's skip key silences this night).
        assertEquals(MissedNightResult.Skipped, evaluateOn(springWake, listOf(s), setOf(springWake.minusDays(1).toEpochDay())))
        assertEquals(MissedNightResult.Short(3 * 3_600_000L), evaluateOn(springWake, listOf(s), setOf(springWake.toEpochDay())))
    }

    @Test
    fun `fall-back night keeps the extra hour and anchors the wake day`() {
        // The 25 h day: 22:30 → 07:00 is 9.5 h of epoch time (01:00→02:00 happened twice).
        // The night still wakes on Oct 25 and reads night-like.
        val fallWake = LocalDate.of(2026, 10, 25)
        val s = session(ms(LocalDate.of(2026, 10, 24), 22, 30), ms(fallWake, 7, 0))
        assertEquals(MissedNightResult.Recorded, evaluateOn(fallWake, listOf(s)))
        assertEquals(MissedNightResult.Missed, evaluateOn(fallWake.minusDays(1), listOf(s)))
    }

    // ---- sum-vs-single: the night-like check is per session, the sum only feeds Short ----

    @Test
    fun `an evening hour plus an after-midnight stretch sums but never promotes`() {
        // 1 h ending exactly on midnight (23:00→00:00 — the end instant anchors the NEW day)
        // + 3.5 h (00:30→04:00): both wake today, 4.5 h together, but neither session is
        // night-like on its own — the sum reports Short, never Recorded.
        val evening = session(ms(today.minusDays(1), 23, 0), ms(today, 0, 0))
        val afterMidnight = session(ms(today, 0, 30), ms(today, 4, 0))
        val result = evaluate(listOf(evening, afterMidnight))
        assertEquals(MissedNightResult.Short((4 * 3_600_000L) + (3_600_000L / 2)), result)
    }

    @Test
    fun `one night-like session among short ones silences the banner`() {
        // A full 21:00→07:00 recording plus a later 3.5 h fragment: the night-like session
        // alone decides Recorded — the banner only speaks when NOTHING was night-like.
        val fullNight = session(ms(today.minusDays(1), 21, 0), ms(today, 7, 0))
        val fragment = session(ms(today, 0, 30), ms(today, 4, 0))
        assertEquals(MissedNightResult.Recorded, evaluate(listOf(fullNight, fragment)))
    }

    @Test
    fun `today in the skip set cannot skip last night when nothing was recorded`() {
        // Off-by-one probe, the empty variant: the skip key is the START evening, so today's
        // own epoch day leaves a nothing-recorded night MISSED, not Skipped.
        assertEquals(MissedNightResult.Missed, evaluate(emptyList(), skippedDays = setOf(today.toEpochDay())))
    }
}
