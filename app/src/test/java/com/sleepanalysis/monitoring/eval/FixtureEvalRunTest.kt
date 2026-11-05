package com.sleepanalysis.monitoring.eval

import com.sleepanalysis.monitoring.detection.EnergySnoreDetector
import com.sleepanalysis.monitoring.eval.FixtureEvaluator.FixtureEntry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real-fixture eval (docs/NEXT_STEPS.md P2): replays the labeled clips exported from a
 * dogfood session (`tools/export_fixtures.py` → `fixtures/<session>/evt_*.wav` + manifest.json)
 * through the current detector and prints the per-label precision/recall table.
 *
 * Skips silently when no fixture manifest exists — CI runs this without fixtures and it must
 * pass; on a machine with exported fixtures it gates detector changes (with small labeled
 * sets the numbers are informational, so a note is printed below MIN_LABELED_CLIPS).
 *
 * Point at a different fixture directory with
 * `-PsleepFixturesDir=...` / `-Dsleep.fixtures.dir=...` (see the `evalFixtures` task).
 */
class FixtureEvalRunTest {

    private fun fixturesDir(): File {
        val prop = System.getProperty("sleep.fixtures.dir")
        if (prop != null) return File(prop)
        // Gradle test JVMs run in the module dir; fixtures live at the repo root.
        val cwd = File(System.getProperty("user.dir"))
        val repoRoot = if (cwd.name == "app") cwd.parentFile else cwd
        return File(repoRoot, "fixtures")
    }

    @Test
    fun `real fixture eval runs against the exported labeled clips`() {
        val dir = fixturesDir()
        val manifestFile = File(dir, "manifest.json")
        assumeTrue("no fixtures/manifest.json — run tools/export_fixtures.py first", manifestFile.exists())

        val manifest = JSONObject(manifestFile.readText())
        val entries = manifest.getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                FixtureEntry(
                    file = File(dir, e.getString("clip")),
                    eventType = e.optString("type", "UNKNOWN"),
                    userLabel = if (e.isNull("user_label")) null else e.optString("user_label"),
                )
            }
        }

        val evaluator = FixtureEvaluator()
        // The session's recorded floor pre-warms the replay's noise-floor ring — without it
        // every clip replays cold and the detector is deaf (see ReplayEngine KDoc).
        val floorDbfs = if (manifest.has("floor_dbfs") && !manifest.isNull("floor_dbfs"))
            manifest.getDouble("floor_dbfs") else null
        val report = evaluator.evaluate(entries, EnergySnoreDetector(), initialFloorDbfs = floorDbfs)
        evaluator.writeJson(report, File("build/eval-report.json"))
        println(evaluator.summaryText(report))

        val labeledCount = report.scores.count { it.userLabel != null }
        if (labeledCount < MIN_LABELED_CLIPS) {
            println(
                "note: only $labeledCount labeled fixture clip(s) (< $MIN_LABELED_CLIPS) — " +
                    "these gates are informational until more clips are labeled"
            )
        }

        // Every manifest entry whose clip actually exists produced a score (or was skipped).
        val existing = entries.count { it.file.exists() }
        assertEquals(existing, report.scores.size + report.skippedClips)

        // Per label: TP + FN must equal that label's clip count.
        for (m in report.metrics) {
            assertEquals("label ${m.label}: TP+FN != clips", m.clips, m.truePositive + m.falseNegative)
        }
    }

    private companion object {
        const val MIN_LABELED_CLIPS = 10
    }
}
