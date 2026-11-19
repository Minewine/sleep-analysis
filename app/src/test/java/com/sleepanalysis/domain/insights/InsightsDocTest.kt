package com.sleepanalysis.domain.insights

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documentation drift guard for docs/INSIGHTS.md (the [ClaimPolicyTest] mechanism): the rule
 * ids in code must equal the doc's rule-catalog ids, every doc copy key must exist as a string
 * resource — and (Slice 3) the playbook catalog must stay in sync too. Documentation drift is a
 * build failure, not a review comment.
 */
class InsightsDocTest {

    @Test
    fun `rule ids in InsightRules match the docs INSIGHTS rule catalog`() {
        val docIds = ruleCatalogIds()
        val codeIds = setOf(
            InsightRules.RuleIds.BEDTIME_VARIABILITY,
            InsightRules.RuleIds.SRI_DIP,
            InsightRules.RuleIds.SOCIAL_JETLAG,
            InsightRules.RuleIds.SECOND_HALF_WORSE,
            InsightRules.RuleIds.BASELINE_EXCURSION,
            InsightRules.RuleIds.ALCOHOL_NIGHTS,
            InsightRules.RuleIds.CAFFEINE_NIGHTS,
            InsightRules.RuleIds.EXERCISE_EVENING,
            InsightRules.RuleIds.STRESS_HIGH,
        )
        assertEquals(
            "rule ids in code but missing from docs/INSIGHTS.md",
            emptySet<String>(),
            codeIds - docIds,
        )
        assertEquals(
            "rule ids in docs/INSIGHTS.md but not in code",
            emptySet<String>(),
            docIds - codeIds,
        )
    }

    @Test
    fun `every doc copy key exists as a string resource`() {
        val docRows = ruleCatalogRows()
        assertTrue("expected the rule catalog rows in docs/INSIGHTS.md", docRows.isNotEmpty())
        val stringsXml = stringsXmlText()
        val missing = docRows.mapNotNull { row ->
            val copyKey = row[1] // second column: the copy key
            if (Regex("name=\"$copyKey\"").containsMatchIn(stringsXml)) null else copyKey
        }
        assertEquals("copy keys missing from app/src/main/res/values/strings.xml", emptyList<String>(), missing)
    }

    @Test
    fun `every copy key referenced by code exists in the doc catalog`() {
        val docCopyKeys = ruleCatalogRows().map { it[1] }.toSet()
        val codeCopyKeys = setOf(
            InsightRules.CopyKeys.BEDTIME_VARIABILITY,
            InsightRules.CopyKeys.SRI_DIP,
            InsightRules.CopyKeys.SOCIAL_JETLAG,
            InsightRules.CopyKeys.SECOND_HALF_ALCOHOL,
            InsightRules.CopyKeys.SECOND_HALF_UNTAGGED,
            InsightRules.CopyKeys.BASELINE_EXCURSION,
            InsightRules.CopyKeys.ALCOHOL_NIGHTS,
            InsightRules.CopyKeys.CAFFEINE_NIGHTS,
            InsightRules.CopyKeys.EXERCISE_EVENING,
            InsightRules.CopyKeys.STRESS_HIGH,
        )
        assertEquals(emptySet<String>(), codeCopyKeys - docCopyKeys)
    }

    // ---- Playbook catalog (Slice 3, docs/INSIGHTS.md §Playbook) -------------------------------

    @Test
    fun `playbook action ids in Playbook match the docs playbook catalog`() {
        val docIds = playbookIds()
        val codeIds = Playbook.ACTIONS.map { it.id }.toSet()
        assertEquals(
            "playbook action ids in code but missing from docs/INSIGHTS.md §Playbook",
            emptySet<String>(),
            codeIds - docIds,
        )
        assertEquals(
            "playbook action ids in docs/INSIGHTS.md §Playbook but not in code",
            emptySet<String>(),
            docIds - codeIds,
        )
    }

    @Test
    fun `every playbook title and instruction key exists as a string resource`() {
        val stringsXml = stringsXmlText()
        val missing = Playbook.ACTIONS.flatMap { action -> listOf(action.titleKey, action.instructionKey) }
            .filterNot { key -> Regex("name=\"$key\"").containsMatchIn(stringsXml) }
        assertEquals(
            "playbook copy keys missing from app/src/main/res/values/strings.xml",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `the playbook never-list is pinned in the doc`() {
        val playbookSection = playbookSection()
        listOf(
            "no sleep restriction",
            "no AHI",
            "no apnea detection",
            "no desaturation counting",
            "no cure language",
        ).forEach { phrase ->
            assertTrue(
                "docs/INSIGHTS.md §Playbook must pin \"$phrase\"",
                playbookSection.contains(phrase, ignoreCase = true),
            )
        }
    }

    @Test
    fun `the doc names the ranking rule and the starter fallback`() {
        val playbookSection = playbookSection()
        assertTrue(playbookSection.contains("fixed_wake"))
        assertTrue(playbookSection.contains("stimulus_control"))
    }

    // ---- Parsers --------------------------------------------------------------------------------

    /**
     * The doc's rule-catalog rows: lines like `` | `bedtime_variability` |
     * `insight_rule_bedtime_variability` | ... `` — first column the rule id, second the copy
     * key. Scoped to the "## Rule catalog" section only: the playbook catalog (§Playbook) uses
     * its own table shape and is checked by the playbook tests above.
     */
    private fun ruleCatalogRows(): List<List<String>> {
        val row = Regex(
            "^\\|\\s*`([a-z_]+)`\\s*\\|\\s*`([a-z0-9_]+)`\\s*\\|",
            RegexOption.MULTILINE,
        )
        val section = docText()
            .substringAfter("## Rule catalog")
            .substringBefore("## Guardrails")
        return row.findAll(section).map { listOf(it.groupValues[1], it.groupValues[2]) }.toList()
    }

    private fun ruleCatalogIds(): Set<String> = ruleCatalogRows().map { it[0] }.toSet()

    /** The doc's §Playbook section — playbook table rows: `` | `action_id` | ... `` (first cell backticked). */
    private fun playbookIds(): Set<String> {
        val row = Regex("^\\|\\s*`([a-z_]+)`\\s*\\|", RegexOption.MULTILINE)
        return row.findAll(playbookSection()).map { it.groupValues[1] }.toSet()
    }

    private fun playbookSection(): String =
        docText().substringAfter("## Playbook").substringBefore("## Guardrails")

    /** Same repo-root walk as ClaimPolicyTest — the doc lives in the repo on purpose. */
    private fun docText(): String {
        var dir: File? = File(System.getProperty("user.dir")!!)
        while (dir != null) {
            val candidate = File(dir, "docs/INSIGHTS.md")
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile
        }
        error("docs/INSIGHTS.md not found from ${System.getProperty("user.dir")}")
    }

    private fun stringsXmlText(): String {
        var dir: File? = File(System.getProperty("user.dir")!!)
        while (dir != null) {
            val candidate = File(dir, "app/src/main/res/values/strings.xml")
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile
        }
        error("app/src/main/res/values/strings.xml not found from ${System.getProperty("user.dir")}")
    }
}
