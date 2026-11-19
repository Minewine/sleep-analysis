package com.sleepanalysis.domain.policy

import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import java.time.Instant
import java.time.ZoneId

/**
 * How the night before "today" went — the Home missed-night banner's input. Skipped and
 * recorded nights render nothing; only the real problems get copy.
 */
sealed interface MissedNightResult {
    /** Nothing eligible was recorded: no finalized session woke that morning. */
    data object Missed : MissedNightResult

    /** Eligible session(s) woke that morning, but none is night-like — the total in-bed time. */
    data class Short(val capturedInBedMs: Long) : MissedNightResult

    /** That night's scheduled start was skipped on purpose — a deliberate absence, not a failure. */
    data object Skipped : MissedNightResult

    /** An eligible, night-like session covers that night. */
    data object Recorded : MissedNightResult
}

/**
 * Pure decision for "was last night recorded?" (dogfood 2026-11-18→19: recording only began
 * at 01:02 and the report first read as if nothing had happened — absence must be visible,
 * the same P0 silent-failure discipline as the pre-flight card).
 *
 * A night is identified by its WAKE date: the local calendar day its session(s) ended on (the
 * same anchor Garmin keys nights by), so a recording started after midnight still belongs to
 * the night before. The skip set holds START-evening epoch days (the scheduled alarm's
 * occurrence day), so the night waking on [todayEpochDay] was skipped iff the day before is
 * in the set. Eligibility and night-likeness reuse the previous-night comparison semantics:
 * COMPLETED/RECOVERED with an end time, night-like = ≥ [NIGHT_LIKE_MIN_IN_BED_MS] in bed.
 */
object MissedNightPolicy {

    /**
     * Night-like threshold — mirrors ReportAssembler.PreviousNight.NIGHT_LIKE_MIN_IN_BED_MS
     * (kept equal; the test pins the two together). Kept here because the report package
     * already depends on the policy package, never the reverse.
     */
    const val NIGHT_LIKE_MIN_IN_BED_MS: Long = 4 * 3_600_000L

    /**
     * The verdict for the night that woke on [todayEpochDay]: SKIPPED wins over everything (a
     * deliberate absence must not read as a failure), then RECORDED when any eligible session
     * of that night is night-like, SHORT when eligible sessions exist but none is, MISSED
     * otherwise. Sessions from other nights and ineligible ones (open, INTERRUPTED, RECORDING)
     * never silence the banner.
     */
    fun evaluate(
        sessions: List<SleepSessionEntity>,
        todayEpochDay: Long,
        skippedDays: Set<Long>,
    ): MissedNightResult {
        if (todayEpochDay - 1 in skippedDays) return MissedNightResult.Skipped
        val eligible = sessions.filter {
            it.endedAt != null && wakeDay(it) == todayEpochDay &&
                (it.status == SessionStatus.COMPLETED || it.status == SessionStatus.RECOVERED)
        }
        return when {
            eligible.any { it.endedAt!! - it.startedAt >= NIGHT_LIKE_MIN_IN_BED_MS } -> MissedNightResult.Recorded
            eligible.isNotEmpty() -> MissedNightResult.Short(eligible.sumOf { it.endedAt!! - it.startedAt })
            else -> MissedNightResult.Missed
        }
    }

    /** The night's wake epoch day — the local date the session ended on, in its own zone. */
    private fun wakeDay(session: SleepSessionEntity): Long =
        Instant.ofEpochMilli(session.endedAt!!).atZone(ZoneId.of(session.timeZone)).toLocalDate().toEpochDay()
}
