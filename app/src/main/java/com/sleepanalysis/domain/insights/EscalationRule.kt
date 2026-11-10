package com.sleepanalysis.domain.insights

/**
 * The clinician-escalation signal (docs/INSIGHTS.md §Escalation): a pure rule over the SAME
 * eligible night set the insights ran on plus the user's once-collected self-report. Two
 * independent arms, either one triggers:
 *  - **Acoustic** (APP CONVENTIONS — no literature threshold is encoded): ≥
 *    [CONSECUTIVE_NIGHTS] consecutive ELIGIBLE nights with snoreShare > [SNORE_SHARE_TRIGGER]
 *    AND longestBout > [LONGEST_BOUT_TRIGGER_MS]. A night failing either criterion (or missing
 *    a value) breaks the streak; eligibility ran upstream (grade ≥ B, registry-matched).
 *  - **Self-report**: any YES among three STOP-BANG-derived screening questions (S/O/T —
 *    Chung 2012, Anesthesiology), persisted in DataStore, collected once and editable.
 *
 * Copy contract: "worth discussing with a clinician — snoring is common and usually harmless,
 * but these signals warrant a professional opinion". Evaluation, never diagnosis: no condition
 * is named, no test result is implied, the questions are screening context (STOP-BANG),
 * not a score. Eligibility ran upstream — a night with missing values cannot qualify.
 */
object EscalationRule {

    /** Acoustic arm, share-of-night trigger (APP CONVENTION; strictly greater). */
    const val SNORE_SHARE_TRIGGER = 0.25

    /** Acoustic arm, longest-bout trigger (APP CONVENTION; strictly greater). */
    const val LONGEST_BOUT_TRIGGER_MS = 10 * 60_000L

    /** Acoustic arm, consecutive eligible nights needed (APP CONVENTION). */
    const val CONSECUTIVE_NIGHTS = 3

    /**
     * The three STOP-BANG-derived questions (S/O/T of Chung 2012's screening tool — screening
     * context only; the app never computes a STOP-BANG score and never diagnoses).
     */
    data class SelfReport(
        /** S: "snoring louder than talking or heard through closed doors?" */
        val snoringLoud: Boolean,
        /** O: "pauses or gasping witnessed during sleep?" */
        val witnessedPauses: Boolean,
        /** T: "daytime sleepiness most days?" */
        val daytimeSleepiness: Boolean,
    ) {
        val anyYes: Boolean get() = snoringLoud || witnessedPauses || daytimeSleepiness
    }

    data class Result(
        val escalate: Boolean,
        val acousticTriggered: Boolean,
        val selfReportTriggered: Boolean,
        /** The consecutive eligible nights of the acoustic streak (latest qualifying streak; empty without it). */
        val acousticNightIds: List<Long>,
    )

    fun evaluate(
        nightRecords: List<InsightRules.NightRecord>,
        selfReport: SelfReport?,
    ): Result {
        val acousticNightIds = acousticStreak(nightRecords)
        val acousticTriggered = acousticNightIds.size >= CONSECUTIVE_NIGHTS
        val selfReportTriggered = selfReport?.anyYes == true
        return Result(
            escalate = acousticTriggered || selfReportTriggered,
            acousticTriggered = acousticTriggered,
            selfReportTriggered = selfReportTriggered,
            acousticNightIds = acousticNightIds,
        )
    }

    /**
     * Longest run of consecutive eligible nights meeting BOTH acoustic criteria; a qualifying
     * run extends the streak, any non-qualifying night resets it. Returns the latest streak
     * that reached [CONSECUTIVE_NIGHTS] (the evidence the card cites), empty when none did.
     */
    internal fun acousticStreak(nightRecords: List<InsightRules.NightRecord>): List<Long> {
        var streak = emptyList<Long>()
        var best = emptyList<Long>()
        nightRecords.forEach { night ->
            val qualifies = (night.snoreShare ?: 0.0) > SNORE_SHARE_TRIGGER &&
                (night.longestBoutMs ?: 0L) > LONGEST_BOUT_TRIGGER_MS
            streak = if (qualifies) streak + night.sessionId else emptyList()
            if (streak.size >= CONSECUTIVE_NIGHTS) best = streak
        }
        return best
    }
}
