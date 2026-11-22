package com.sleepanalysis.domain.insights

import com.sleepanalysis.domain.factors.DailyFactor

/**
 * The evidence-graded action catalog (docs/INSIGHTS.md §Playbook): concrete, suggestive changes
 * the user could try, each with the evidence behind it and the metric that would verify it.
 * Copy lives in strings.xml (titleKey/instructionKey = string-resource names, drift-tested by
 * InsightsDocTest); evidenceKey is a stable token that MUST appear in docs/EVIDENCE_BASE.md
 * (drift-tested by EvidenceBaseDocTest).
 *
 * Tone contract: suggestions, never cures, never promises — "try this for a week and watch
 * your own number". The NEVER list (docs/INSIGHTS.md §Playbook never-list) is drift-pinned:
 * no sleep-restriction/consolidation suggestions (professional-guided only), no sleep-stage
 * claims, and every instruction stays inside its citation's allowed-claim language.
 *
 * Ranking (pure, [rank]): rule confidence (MODERATE > LOW) × recency of the related rule
 * firing within the current 14-eligible-night window (the InsightsReport set), with dismissed
 * rules excluded. When no rule fired, the fixed starter pair leads (docs/INSIGHTS.md §Playbook).
 */
object Playbook {

    enum class Category { SLEEP_QUALITY, SNORING }

    /**
     * @param id                stable id — the sleep_experiment.playbook_action_id and the
     *                          doc catalog id (drift-tested)
     * @param titleKey          string-resource NAME of the short title
     * @param instructionKey    string-resource NAME of the evidence-backed one-liner
     * @param evidenceKey       token that docs/EVIDENCE_BASE.md must contain (citation row)
     * @param verifyMetricId    [PersonalBaseline.Metrics] id the experiment verifies
     * @param relatedRuleId     [InsightRules.RuleIds] id feeding the ranking; null = ranks last
     * @param experimentCapable false = context-only (cannot be run as an N-of-1 experiment)
     */
    data class PlaybookAction(
        val id: String,
        val category: Category,
        val titleKey: String,
        val instructionKey: String,
        val evidenceKey: String,
        val verifyMetricId: String,
        val relatedRuleId: String? = null,
        val experimentCapable: Boolean = true,
    )

    /** Evidence tokens — each one is a table row in docs/EVIDENCE_BASE.md. */
    object EvidenceKeys {
        const val EDINGER_2021 = "edinger_2021"
        const val WINDRED_2024 = "windred_2024"
        const val ROENNEBERG_2012 = "roenneberg_2012"
        const val BALBAN_2023 = "balban_2023"
        const val RUSCH_2019 = "rusch_2019"
        const val LAN_2017 = "lan_2017"
        const val DUTHEIL_2021 = "dutheil_2021"
        const val BROSNAN_2024 = "brosnan_2024"
        const val RAVESLOOT_2017 = "ravesloot_2017"
        const val CAMACHO_2015 = "camacho_2015"
        const val SOUZA_2017 = "souza_2017"
        const val MAZZA_2019 = "mazza_2019"
        const val EBRAHIM_2013 = "ebrahim_2013"
        const val PEPPARD_2000 = "peppard_2000"
    }

    val ACTIONS: List<PlaybookAction> = listOf(
        // ---- Sleep quality -----------------------------------------------------
        PlaybookAction(
            id = "stimulus_control",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_stimulus_control_title",
            instructionKey = "playbook_stimulus_control_instruction",
            evidenceKey = EvidenceKeys.EDINGER_2021,
            verifyMetricId = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            relatedRuleId = InsightRules.RuleIds.BASELINE_EXCURSION,
        ),
        PlaybookAction(
            id = "fixed_wake",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_fixed_wake_title",
            instructionKey = "playbook_fixed_wake_instruction",
            evidenceKey = EvidenceKeys.WINDRED_2024,
            verifyMetricId = PersonalBaseline.Metrics.SCORE,
            relatedRuleId = InsightRules.RuleIds.SRI_DIP,
        ),
        PlaybookAction(
            id = "morning_light",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_morning_light_title",
            instructionKey = "playbook_morning_light_instruction",
            evidenceKey = EvidenceKeys.ROENNEBERG_2012,
            verifyMetricId = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            relatedRuleId = InsightRules.RuleIds.SOCIAL_JETLAG,
        ),
        PlaybookAction(
            id = "wind_down",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_wind_down_title",
            instructionKey = "playbook_wind_down_instruction",
            evidenceKey = EvidenceKeys.EDINGER_2021,
            verifyMetricId = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            relatedRuleId = InsightRules.RuleIds.BEDTIME_VARIABILITY,
        ),
        PlaybookAction(
            id = "slow_breathing",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_slow_breathing_title",
            instructionKey = "playbook_slow_breathing_instruction",
            evidenceKey = EvidenceKeys.BALBAN_2023,
            verifyMetricId = PersonalBaseline.Metrics.RMSSD_MS,
            relatedRuleId = InsightRules.RuleIds.STRESS_HIGH,
        ),
        PlaybookAction(
            id = "mindfulness",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_mindfulness_title",
            instructionKey = "playbook_mindfulness_instruction",
            evidenceKey = EvidenceKeys.RUSCH_2019,
            verifyMetricId = PersonalBaseline.Metrics.RMSSD_MS,
            relatedRuleId = InsightRules.RuleIds.STRESS_HIGH,
        ),
        PlaybookAction(
            id = "bedroom_temp",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_bedroom_temp_title",
            instructionKey = "playbook_bedroom_temp_instruction",
            evidenceKey = EvidenceKeys.LAN_2017,
            verifyMetricId = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            relatedRuleId = InsightRules.RuleIds.BASELINE_EXCURSION,
        ),
        PlaybookAction(
            id = "nap_limit",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_nap_limit_title",
            instructionKey = "playbook_nap_limit_instruction",
            evidenceKey = EvidenceKeys.DUTHEIL_2021,
            verifyMetricId = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            relatedRuleId = InsightRules.RuleIds.BASELINE_EXCURSION,
        ),
        PlaybookAction(
            id = "screens_optional",
            category = Category.SLEEP_QUALITY,
            titleKey = "playbook_screens_optional_title",
            instructionKey = "playbook_screens_optional_instruction",
            evidenceKey = EvidenceKeys.BROSNAN_2024,
            verifyMetricId = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            relatedRuleId = InsightRules.RuleIds.BASELINE_EXCURSION,
        ),
        // ---- Snoring -----------------------------------------------------------
        PlaybookAction(
            id = "side_sleeping",
            category = Category.SNORING,
            titleKey = "playbook_side_sleeping_title",
            instructionKey = "playbook_side_sleeping_instruction",
            evidenceKey = EvidenceKeys.RAVESLOOT_2017,
            verifyMetricId = PersonalBaseline.Metrics.SNORE_SHARE,
            relatedRuleId = InsightRules.RuleIds.SECOND_HALF_WORSE,
        ),
        PlaybookAction(
            id = "myofunctional",
            category = Category.SNORING,
            titleKey = "playbook_myofunctional_title",
            instructionKey = "playbook_myofunctional_instruction",
            evidenceKey = EvidenceKeys.CAMACHO_2015,
            verifyMetricId = PersonalBaseline.Metrics.SNORE_SHARE,
            relatedRuleId = InsightRules.RuleIds.BASELINE_EXCURSION,
        ),
        PlaybookAction(
            id = "bed_elevation",
            category = Category.SNORING,
            titleKey = "playbook_bed_elevation_title",
            instructionKey = "playbook_bed_elevation_instruction",
            evidenceKey = EvidenceKeys.SOUZA_2017,
            verifyMetricId = PersonalBaseline.Metrics.SNORE_SHARE,
            relatedRuleId = InsightRules.RuleIds.SECOND_HALF_WORSE,
        ),
        PlaybookAction(
            id = "nasal_dilator",
            category = Category.SNORING,
            titleKey = "playbook_nasal_dilator_title",
            instructionKey = "playbook_nasal_dilator_instruction",
            evidenceKey = EvidenceKeys.MAZZA_2019,
            verifyMetricId = PersonalBaseline.Metrics.SNORE_SHARE,
            relatedRuleId = InsightRules.RuleIds.BASELINE_EXCURSION,
        ),
        PlaybookAction(
            id = "no_evening_alcohol",
            category = Category.SNORING,
            titleKey = "playbook_no_evening_alcohol_title",
            instructionKey = "playbook_no_evening_alcohol_instruction",
            evidenceKey = EvidenceKeys.EBRAHIM_2013,
            verifyMetricId = PersonalBaseline.Metrics.SNORE_SHARE,
            relatedRuleId = InsightRules.RuleIds.ALCOHOL_NIGHTS,
        ),
        PlaybookAction(
            id = "weight_context",
            category = Category.SNORING,
            titleKey = "playbook_weight_context_title",
            instructionKey = "playbook_weight_context_instruction",
            evidenceKey = EvidenceKeys.PEPPARD_2000,
            verifyMetricId = PersonalBaseline.Metrics.SNORE_SHARE,
            experimentCapable = false, // CONTEXT ONLY — the app cannot verify weight change
        ),
    )

    /** Starter actions when no rule fired (docs/INSIGHTS.md §Playbook ranking). */
    val STARTER_ACTION_IDS = listOf("fixed_wake", "stimulus_control")

    fun action(id: String): PlaybookAction? = ACTIONS.firstOrNull { it.id == id }

    /**
     * The experiment's "own factor" ([DailyFactor] keys): nights logged with any OTHER
     * confoundable factor are excluded from the phase verdict (confound exclusion,
     * docs/INSIGHTS.md §Playbook, ExperimentTracker). The manipulation's own flag does not
     * confound its own experiment (no_evening_alcohol → ALCOHOL-logged nights stay in);
     * a device test keeps MEDICATION_OR_DEVICE nights in, and the positional experiment
     * keeps SLEPT_ON_BACK nights in (adherence-relevant state, not an outside confounder).
     */
    fun ownFactorKeys(actionId: String): Set<String> = when (actionId) {
        "no_evening_alcohol" -> setOf(DailyFactor.ALCOHOL.key)
        "nasal_dilator" -> setOf(DailyFactor.MEDICATION_OR_DEVICE.key)
        "side_sleeping" -> setOf(DailyFactor.SLEPT_ON_BACK.key)
        else -> emptySet()
    }

    /**
     * Top [limit] actions for the current report: rule confidence (MODERATE 2 > LOW 1) ×
     * recency (related rule fired within the report's 14-eligible-night window 2 > not 1),
     * actions whose related rule is dismissed drop out, context-only actions never rank.
     * Empty insights → the starter pair leads, then the rest by score.
     */
    fun rank(
        insights: List<Insight>,
        dismissedRules: Set<String>,
        limit: Int = 3,
    ): List<PlaybookAction> {
        val firedByRule = insights.groupBy { it.ruleId }
        val ranked = ACTIONS.asSequence()
            .filter { it.experimentCapable }
            .filter { it.relatedRuleId == null || it.relatedRuleId !in dismissedRules }
            .sortedByDescending { score(it, firedByRule) }
            .toList()
        return if (insights.isEmpty()) {
            (STARTER_ACTION_IDS.mapNotNull(::action) + ranked).distinct().take(limit)
        } else {
            ranked.take(limit)
        }
    }

    /** Score per [PlaybookAction] — never surfaced as a number, ordering only (app convention). */
    internal fun score(action: PlaybookAction, firedByRule: Map<String, List<Insight>>): Int {
        val ruleInsights = action.relatedRuleId?.let { firedByRule[it] }
        val confidence = ruleInsights?.maxOfOrNull { insight ->
            if (insight.confidenceTier == ConfidenceTier.MODERATE) 2 else 1
        } ?: 1
        val recency = if (ruleInsights != null) 2 else 1
        return confidence * recency
    }
}
