package com.sleepanalysis.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clinician-escalation rule (docs/INSIGHTS.md §Escalation): the acoustic arm (≥3 consecutive
 * eligible nights with snoreShare > 0.25 AND longestBout > 10 min — APP CONVENTIONS), the
 * self-report arm (any YES among the three STOP-BANG-derived questions), and their combination.
 */
class EscalationRuleTest {

    private fun night(
        id: Long,
        snoreShare: Double? = 0.30,
        longestBoutMs: Long? = 15 * 60_000L,
    ): InsightRules.NightRecord = InsightRules.NightRecord(
        sessionId = id,
        startedAtMs = 1_790_000_000_000L + id * 86_400_000L,
        endedAtMs = 1_790_000_000_000L + id * 86_400_000L + 8 * 3_600_000L,
        nightDate = "2026-11-%02d".format(id),
        zoneId = "UTC",
        grade = "A",
        detectorVersion = "energy-heuristic-v2-burst",
        scoreVersion = "snore-score-v3",
        snoreSpans = emptyList(),
        snoreShare = snoreShare,
        longestBoutMs = longestBoutMs,
        score = 30,
        garminAwakeMinutes = 45.0,
        garminSleepMinutes = 400.0,
        rMssdMs = 40.0,
        garminWindow = null,
        alcoholLogged = false,
        caffeineAfter14Logged = false,
        exerciseLogged = false,
        stressLevel = null,
    )

    private fun qualifying(id: Long) = night(id)
    private fun quiet(id: Long) = night(id, snoreShare = 0.05, longestBoutMs = 60_000L)

    // ---- Acoustic arm ----------------------------------------------------------

    @Test
    fun `three consecutive qualifying nights trigger the acoustic arm`() {
        val result = EscalationRule.evaluate(
            nightRecords = listOf(qualifying(1), qualifying(2), qualifying(3)),
            selfReport = null,
        )
        assertTrue(result.escalate)
        assertTrue(result.acousticTriggered)
        assertFalse(result.selfReportTriggered)
        assertEquals(listOf(1L, 2L, 3L), result.acousticNightIds)
    }

    @Test
    fun `fewer than three qualifying nights never trigger`() {
        val result = EscalationRule.evaluate(
            nightRecords = listOf(qualifying(1), qualifying(2)),
            selfReport = null,
        )
        assertFalse(result.escalate)
        assertFalse(result.acousticTriggered)
        assertTrue(result.acousticNightIds.isEmpty())
    }

    @Test
    fun `a failing night breaks the consecutive streak`() {
        val result = EscalationRule.evaluate(
            nightRecords = listOf(qualifying(1), qualifying(2), quiet(3), qualifying(4), qualifying(5)),
            selfReport = null,
        )
        assertFalse(result.escalate) // best streak = 2
        assertFalse(result.acousticTriggered)
    }

    @Test
    fun `the share trigger is strict - exactly 0_25 does not qualify`() {
        val result = EscalationRule.evaluate(
            nightRecords = listOf(
                night(1, snoreShare = 0.25),
                night(2, snoreShare = 0.25),
                night(3, snoreShare = 0.25),
            ),
            selfReport = null,
        )
        assertFalse(result.escalate)
    }

    @Test
    fun `the bout trigger is strict - exactly 10 minutes does not qualify`() {
        val result = EscalationRule.evaluate(
            nightRecords = listOf(
                night(1, longestBoutMs = 10 * 60_000L),
                night(2, longestBoutMs = 10 * 60_000L),
                night(3, longestBoutMs = 10 * 60_000L),
            ),
            selfReport = null,
        )
        assertFalse(result.escalate)
    }

    @Test
    fun `nights missing values cannot qualify and break the streak`() {
        val result = EscalationRule.evaluate(
            nightRecords = listOf(qualifying(1), qualifying(2), night(3, snoreShare = null, longestBoutMs = null)),
            selfReport = null,
        )
        assertFalse(result.escalate)
    }

    @Test
    fun `a later streak supersedes an earlier one - the latest evidence is cited`() {
        val result = EscalationRule.evaluate(
            nightRecords = listOf(qualifying(1), qualifying(2), qualifying(3), quiet(4), qualifying(5), qualifying(6), qualifying(7)),
            selfReport = null,
        )
        assertTrue(result.acousticTriggered)
        assertEquals(listOf(5L, 6L, 7L), result.acousticNightIds)
    }

    // ---- Self-report arm -------------------------------------------------------

    @Test
    fun `any YES among the three questions triggers on its own`() {
        val selfReport = EscalationRule.SelfReport(snoringLoud = false, witnessedPauses = true, daytimeSleepiness = false)
        val result = EscalationRule.evaluate(emptyList(), selfReport)
        assertTrue(result.escalate)
        assertTrue(result.selfReportTriggered)
        assertFalse(result.acousticTriggered)
    }

    @Test
    fun `all NO answers never trigger`() {
        val selfReport = EscalationRule.SelfReport(snoringLoud = false, witnessedPauses = false, daytimeSleepiness = false)
        val result = EscalationRule.evaluate(listOf(qualifying(1)), selfReport)
        assertFalse(result.escalate)
        assertFalse(result.selfReportTriggered)
    }

    @Test
    fun `no answers yet - the self-report arm stays silent`() {
        val result = EscalationRule.evaluate(listOf(qualifying(1)), selfReport = null)
        assertFalse(result.escalate)
        assertFalse(result.selfReportTriggered)
    }

    // ---- Combined arms ---------------------------------------------------------

    @Test
    fun `self-report and acoustic together flag both arms`() {
        val result = EscalationRule.evaluate(
            nightRecords = listOf(qualifying(1), qualifying(2), qualifying(3)),
            selfReport = EscalationRule.SelfReport(snoringLoud = true, witnessedPauses = false, daytimeSleepiness = false),
        )
        assertTrue(result.escalate)
        assertTrue(result.acousticTriggered)
        assertTrue(result.selfReportTriggered)
        assertEquals(listOf(1L, 2L, 3L), result.acousticNightIds)
    }
}
