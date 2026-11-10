package com.sleepanalysis.domain.policy

import com.sleepanalysis.domain.experiments.ExperimentMath
import com.sleepanalysis.domain.insights.EscalationRule
import com.sleepanalysis.domain.insights.InsightRules
import com.sleepanalysis.domain.insights.Playbook
import com.sleepanalysis.domain.insights.SleepRegularity
import com.sleepanalysis.domain.validity.NightValidity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documentation drift guard for docs/EVIDENCE_BASE.md (the [ClaimPolicyTest] file-walk
 * mechanism): every encodable parameter's VALUE appears in the doc exactly as the code encodes
 * it, every playbook action's evidence token resolves to a citation row, and the NEVER-CLAIMS
 * list is pinned. Documentation drift is a build failure, not a review comment.
 */
class EvidenceBaseDocTest {

    @Test
    fun `pinned documented constants appear verbatim in the doc`() {
        // Row-scoped literals: each pin names the parameter's own value/criterion cell so a
        // coincidental match elsewhere cannot mask drift.
        listOf(
            "> 0.10", // capture shortfall / clipped-rate WARNING share
            "> 0.40", // capture shortfall CRITICAL share
            "robust z magnitude > 2", // N-of-1 verdict: beyond 2 robust SD
            "≥ 7 usable", // N-of-1 phase length (nights)
            "≥ 5 usable phase points", // WWC single-case floor
            "κ 0.21", // Garmin stage agreement floor (fair-to-moderate)
            "> 0.25", // escalation acoustic arm share trigger
            "> 10 min", // escalation acoustic arm longest-bout trigger
            "> 1 h", // social-jetlag convention flag
            "≥ 5 valid days", // SRI minimum
            "14:00 default cutoff", // caffeine factor convention
            "< 90% distrust flag", // SpO₂ level distrust bound
            "±45 min", // reported-vs-window sleep-time tolerance
        ).forEach { literal ->
            assertTrue(
                "docs/EVIDENCE_BASE.md must contain the pinned value \"$literal\"",
                docText().contains(literal),
            )
        }
    }

    @Test
    fun `documented values match the constants in code`() {
        // Validity thresholds (docs/NIGHT_REPORT.md §Night validity, encoded in NightValidity).
        assertEquals(0.10, NightValidity.SHORTFALL_WARN, 0.0)
        assertEquals(0.40, NightValidity.SHORTFALL_CRITICAL, 0.0)
        assertEquals(0.10, NightValidity.CLIPPED_WARN, 0.0)
        assertEquals(0.30, NightValidity.CLIPPED_CRITICAL, 0.0)
        assertEquals(2.5, NightValidity.AMBIENT_Z_TRIGGER, 0.0)
        // N-of-1 standards.
        assertEquals(7, ExperimentMath.DEFAULT_PLANNED_NIGHTS)
        assertEquals(5, ExperimentMath.MIN_PHASE_NIGHTS)
        assertEquals(2.0, ExperimentMath.VERDICT_ROBUST_Z, 0.0)
        // Escalation acoustic arm (APP CONVENTIONS).
        assertEquals(0.25, EscalationRule.SNORE_SHARE_TRIGGER, 0.0)
        assertEquals(10 * 60_000L, EscalationRule.LONGEST_BOUT_TRIGGER_MS)
        assertEquals(3, EscalationRule.CONSECUTIVE_NIGHTS)
        // Regularity conventions.
        assertEquals(5, SleepRegularity.MIN_SRI_DAYS)
        assertEquals(1.0, SleepRegularity.SOCIAL_JETLAG_FLAG_HOURS, 0.0)
        // Factor conventions: the caffeine cutoff lives in the factor KEY itself.
        assertEquals("CAFFEINE_AFTER_14", com.sleepanalysis.domain.factors.DailyFactor.CAFFEINE_AFTER_14.key)
        assertEquals(4, InsightRules.STRESS_HIGH_LEVEL)
        // Trivial-snore floor (Klaus 2021).
        assertEquals(0.01, InsightRules.TRIVIAL_SNORE_SHARE, 0.0)
    }

    @Test
    fun `every playbook action's evidence key appears in the doc`() {
        val doc = docText()
        val missing = Playbook.ACTIONS.mapNotNull { action ->
            if (doc.contains(action.evidenceKey)) null else action.evidenceKey
        }
        assertEquals(
            "evidence keys missing from docs/EVIDENCE_BASE.md — every citation must be a doc row",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `the NEVER-CLAIMS list is pinned in the doc`() {
        val neverSection = docText().substringAfter("## NEVER-CLAIMS")
        assertTrue("the doc must carry a NEVER-CLAIMS section", neverSection.isNotBlank() && neverSection.length < docText().length)
        listOf(
            "no diagnosis",
            "no AHI",
            "no apnea detection",
            "no desaturation counting",
            "no clinical staging",
            "no absolute",
            "no cure",
            "sleep restriction",
        ).forEach { phrase ->
            assertTrue("NEVER-CLAIMS must pin \"$phrase\"", neverSection.contains(phrase, ignoreCase = true))
        }
    }

    @Test
    fun `the doc pins the honesty rows for calibration and relative-only claims`() {
        val doc = docText()
        listOf(
            "Schyvens 2025", // stage agreement / TST bias
            "Chinoy 2021",
            "Miller 2022", // deep-sleep LoA
            "Lenz 2026", // rMSSD nightly ICC
            "Nuuttila 2022",
            "Jung 2022", // SpO₂ accuracy
            "Rajakariar 2024",
            "WHO 2009", // noise — copy-only reference
            "Klaus 2021", // phone-mic snoring PPV collapse
            "Chiang 2021", // snoring detection accuracy
            "WWC 2010", // single-case design floor
            "Goodwin 2024", // N-of-1 methodology
            "Chung 2012", // STOP-BANG screening
            "Gardiner 2025", // alcohol — no safe cutoff
            "Gardiner 2023", // caffeine meta
            "Stutz 2019", // evening exercise
            "Kim & Dimsdale 2007", // stress
            "Windred 2024", // SRI
            "Roenneberg 2012", // SJL
            "Ebrahim 2013",
            "Peppard 2000",
            "Edinger 2021",
            "Balban 2023",
            "Ravesloot 2017",
            "Mazza 2019",
            "Dutheil 2021",
            "Brosnan 2024",
            "Rusch 2019",
            "Lan 2017",
            "Baniassadi 2023",
            "Souza 2017",
            "Camacho 2015",
        ).forEach { citation ->
            assertTrue(
                "docs/EVIDENCE_BASE.md must cite \"$citation\"",
                doc.contains(citation),
            )
        }
    }

    /** Same repo-root walk as ClaimPolicyTest — the doc lives in the repo on purpose. */
    private fun docText(): String {
        var dir: File? = File(System.getProperty("user.dir")!!)
        while (dir != null) {
            val candidate = File(dir, "docs/EVIDENCE_BASE.md")
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile
        }
        error("docs/EVIDENCE_BASE.md not found from ${System.getProperty("user.dir")}")
    }
}
