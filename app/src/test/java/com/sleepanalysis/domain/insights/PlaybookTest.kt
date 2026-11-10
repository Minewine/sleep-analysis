package com.sleepanalysis.domain.insights

import com.sleepanalysis.domain.experiments.ExperimentMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playbook catalog and ranking (docs/INSIGHTS.md §Playbook): rule-confidence × recency
 * ordering, dismissed-rule filtering, the starter fallback pair, and the catalog's own invariants
 * (unique ids, real verification metrics, evidence keys resolvable, context-only never ranks).
 */
class PlaybookTest {

    private fun insight(ruleId: String, tier: ConfidenceTier): Insight = Insight(
        ruleId = ruleId,
        copyKey = "insight_rule_$ruleId",
        metricName = null,
        direction = InsightDirection.ASSOCIATION,
        evidenceNightIds = listOf(1L),
        confidenceTier = tier,
        detailJson = "{}",
    )

    // ---- Ranking ---------------------------------------------------------------

    @Test
    fun `no fired rules fall back to the fixed starter pair`() {
        val ranked = Playbook.rank(insights = emptyList(), dismissedRules = emptySet())
        assertEquals(listOf("fixed_wake", "stimulus_control"), ranked.take(2).map { it.id })
        assertTrue(ranked.size <= 3)
    }

    @Test
    fun `a MODERATE related rule outranks a LOW one`() {
        val ranked = Playbook.rank(
            insights = listOf(
                insight(InsightRules.RuleIds.STRESS_HIGH, ConfidenceTier.LOW),
                insight(InsightRules.RuleIds.ALCOHOL_NIGHTS, ConfidenceTier.MODERATE),
            ),
            dismissedRules = emptySet(),
        )
        assertEquals("no_evening_alcohol", ranked.first().id) // alcohol_nights → no_evening_alcohol
        assertTrue(ranked.any { it.id == "slow_breathing" || it.id == "mindfulness" })
    }

    @Test
    fun `a fired related rule outranks an unfired one at equal confidence`() {
        val ranked = Playbook.rank(
            insights = listOf(insight(InsightRules.RuleIds.STRESS_HIGH, ConfidenceTier.LOW)),
            dismissedRules = emptySet(),
        )
        // slow_breathing/mindfulness score 2×2 = 4; unfired actions score 1×1 = 1.
        assertTrue(ranked.first().id == "slow_breathing" || ranked.first().id == "mindfulness")
    }

    @Test
    fun `actions whose related rule is dismissed drop out of the ranking`() {
        val ranked = Playbook.rank(
            insights = emptyList(),
            dismissedRules = setOf(InsightRules.RuleIds.ALCOHOL_NIGHTS),
        )
        assertFalse(ranked.any { it.id == "no_evening_alcohol" })
    }

    @Test
    fun `context-only actions never rank`() {
        val ranked = Playbook.rank(
            insights = listOf(insight(InsightRules.RuleIds.BASELINE_EXCURSION, ConfidenceTier.MODERATE)),
            dismissedRules = emptySet(),
        )
        assertFalse(ranked.any { !it.experimentCapable })
    }

    @Test
    fun `ranking never exceeds three actions`() {
        val ranked = Playbook.rank(
            insights = listOf(insight(InsightRules.RuleIds.BASELINE_EXCURSION, ConfidenceTier.MODERATE)),
            dismissedRules = emptySet(),
        )
        assertTrue(ranked.size in 1..3)
    }

    // ---- Catalog invariants ----------------------------------------------------

    @Test
    fun `action ids are unique`() {
        assertEquals(Playbook.ACTIONS.size, Playbook.ACTIONS.map { it.id }.distinct().size)
    }

    @Test
    fun `every verification metric is a real baseline metric`() {
        val metrics = setOf(
            PersonalBaseline.Metrics.SNORE_SHARE,
            PersonalBaseline.Metrics.LONGEST_BOUT_MS,
            PersonalBaseline.Metrics.SCORE,
            PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            PersonalBaseline.Metrics.RMSSD_MS,
        )
        Playbook.ACTIONS.forEach { action ->
            assertTrue("${action.id} verify metric", action.verifyMetricId in metrics)
        }
    }

    @Test
    fun `experiment-capable actions verify a metric the baseline can carry`() {
        Playbook.ACTIONS.filter { it.experimentCapable }.forEach { action ->
            assertTrue(ExperimentMath.metricFloor(action.verifyMetricId) != null)
        }
    }

    @Test
    fun `weight context is the only context-only action`() {
        assertEquals(listOf("weight_context"), Playbook.ACTIONS.filter { !it.experimentCapable }.map { it.id })
    }

    @Test
    fun `own factor keys - the manipulation's flag never confounds itself`() {
        assertEquals(setOf("ALCOHOL"), Playbook.ownFactorKeys("no_evening_alcohol"))
        assertEquals(setOf("MEDICATION_OR_DEVICE"), Playbook.ownFactorKeys("nasal_dilator"))
        assertEquals(emptySet<String>(), Playbook.ownFactorKeys("fixed_wake"))
    }

    @Test
    fun `every action carries an evidence key and at most one citation is shared`() {
        // EvidenceBaseDocTest asserts each key appears in docs/EVIDENCE_BASE.md; here we pin the
        // catalog's own shape: every action cites something, and exactly one citation
        // (edinger_2021 — the AASM guideline) deliberately backs two related actions.
        Playbook.ACTIONS.forEach { assertTrue("${it.id} evidenceKey", it.evidenceKey.isNotBlank()) }
        val shared = Playbook.ACTIONS.groupBy { it.evidenceKey }.filter { it.value.size > 1 }
        assertEquals(mapOf("edinger_2021" to 2), shared.mapValues { entry -> entry.value.size })
    }

    @Test
    fun `action lookup by id resolves every ranked action`() {
        Playbook.ACTIONS.forEach { assertEquals(it, Playbook.action(it.id)) }
        assertTrue(Playbook.action("unknown") == null)
    }
}
