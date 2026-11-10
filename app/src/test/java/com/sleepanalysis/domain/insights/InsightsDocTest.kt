package com.sleepanalysis.domain.insights

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documentation drift guard for docs/INSIGHTS.md (the [ClaimPolicyTest] mechanism): the rule
 * ids in code must equal the doc's rule-catalog ids, and every doc copy key must exist as a
 * string resource — documentation drift is a build failure, not a review comment.
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

    /**
     * The doc's rule-catalog rows: lines like `` | `bedtime_variability` |
     * `insight_rule_bedtime_variability` | ... `` — first column the rule id, second the copy
     * key. Only the rule-catalog table uses this backticked-pair shape.
     */
    private fun ruleCatalogRows(): List<List<String>> {
        val row = Regex(
            "^\\|\\s*`([a-z_]+)`\\s*\\|\\s*`([a-z0-9_]+)`\\s*\\|",
            RegexOption.MULTILINE,
        )
        return row.findAll(docText()).map { listOf(it.groupValues[1], it.groupValues[2]) }.toList()
    }

    private fun ruleCatalogIds(): Set<String> = ruleCatalogRows().map { it[0] }.toSet()

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
