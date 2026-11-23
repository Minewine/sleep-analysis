package com.sleepanalysis.ui.report

import com.sleepanalysis.domain.report.GarminLinkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure guards behind the "Refresh with Garmin" button (ReportViewModel / ReportScreen). */
class ReportRefreshStateTest {

    @Test
    fun `linked night skips the import - rebuild only`() {
        assertEquals(true, refreshSkipsImport(GarminLinkStatus.LINKED))
    }

    @Test
    fun `metrics-only night still imports - the payload aged out`() {
        assertEquals(false, refreshSkipsImport(GarminLinkStatus.METRICS_ONLY))
    }

    @Test
    fun `missing night imports`() {
        assertEquals(false, refreshSkipsImport(GarminLinkStatus.MISSING))
    }

    @Test
    fun `running phases win over any outcome line`() {
        for (status in GarminLinkStatus.entries) {
            assertEquals(
                RefreshStatusLine.SYNCING,
                refreshStatusLine(RefreshPhase.SYNCING_GARMIN, status, completedAtMs = null),
            )
            assertEquals(
                RefreshStatusLine.UPDATING,
                refreshStatusLine(RefreshPhase.UPDATING_REPORT, status, completedAtMs = 123L),
            )
        }
    }

    @Test
    fun `no status line before any completed refresh - even when data is missing`() {
        // The MISSING note must not imply a failed attempt that never happened; first-open
        // missing data is the quality banner's job.
        for (status in GarminLinkStatus.entries) {
            assertEquals(
                RefreshStatusLine.NONE,
                refreshStatusLine(RefreshPhase.IDLE, status, completedAtMs = null),
            )
        }
    }

    @Test
    fun `missing outcome after an attempt shows not available`() {
        assertEquals(
            RefreshStatusLine.NOT_AVAILABLE,
            refreshStatusLine(RefreshPhase.IDLE, GarminLinkStatus.MISSING, completedAtMs = 123L),
        )
    }

    @Test
    fun `budget-blocked missing outcome names the retry path instead of plain not available`() {
        // The silent budget gate after a failed attempt is what made the 2026-11-23 report
        // read as "no data" while an immediate retry path existed all along.
        assertEquals(
            RefreshStatusLine.RETRY_BLOCKED,
            refreshStatusLine(
                RefreshPhase.IDLE,
                GarminLinkStatus.MISSING,
                completedAtMs = 123L,
                garminBudgetBlocked = true,
            ),
        )
    }

    @Test
    fun `budget block is irrelevant once garmin data is linked`() {
        assertEquals(
            RefreshStatusLine.INCLUDED,
            refreshStatusLine(
                RefreshPhase.IDLE,
                GarminLinkStatus.LINKED,
                completedAtMs = 123L,
                garminBudgetBlocked = true,
            ),
        )
    }

    @Test
    fun `budget-blocked diagnosis fires only for an importable idle budget-exhausted step`() {
        // The one true case: a not-yet-linked night, key stored, nothing in flight, budget closed.
        assertEquals(
            true,
            garminStepBudgetBlocked(skipsImport = false, canImport = true, importing = false, budgetAllowed = false),
        )
        // Budget open → the step runs; not a block.
        assertEquals(
            false,
            garminStepBudgetBlocked(skipsImport = false, canImport = true, importing = false, budgetAllowed = true),
        )
        // LINKED night skips the step by design — nothing to surface.
        assertEquals(
            false,
            garminStepBudgetBlocked(skipsImport = true, canImport = true, importing = false, budgetAllowed = false),
        )
        // No key / import in flight: different causes, the plain not-available line stands.
        assertEquals(
            false,
            garminStepBudgetBlocked(skipsImport = false, canImport = false, importing = false, budgetAllowed = false),
        )
        assertEquals(
            false,
            garminStepBudgetBlocked(skipsImport = false, canImport = true, importing = true, budgetAllowed = false),
        )
    }

    @Test
    fun `linked and metrics-only outcomes show included`() {
        assertEquals(
            RefreshStatusLine.INCLUDED,
            refreshStatusLine(RefreshPhase.IDLE, GarminLinkStatus.LINKED, completedAtMs = 123L),
        )
        assertEquals(
            RefreshStatusLine.INCLUDED,
            refreshStatusLine(RefreshPhase.IDLE, GarminLinkStatus.METRICS_ONLY, completedAtMs = 123L),
        )
    }

    @Test
    fun `completed refresh with neither timeline nor stage totals shows stages pending`() {
        // No stage rows landed from either importer (stageSource null) and Garmin's vendor
        // totals are empty too — the honest line is "try again later", not a failure.
        assertEquals(
            RefreshStatusLine.STAGES_PENDING,
            refreshStatusLine(
                RefreshPhase.IDLE, GarminLinkStatus.LINKED, completedAtMs = 123L,
                hasStageTimeline = false, garminStageMinutes = emptyMap(),
            ),
        )
    }

    @Test
    fun `stage totals alone still count as included`() {
        // Vendor totals can survive while the timeline aged out — that state is the Garmin
        // card's totals-only hint's job, not the button's.
        assertEquals(
            RefreshStatusLine.INCLUDED,
            refreshStatusLine(
                RefreshPhase.IDLE, GarminLinkStatus.LINKED, completedAtMs = 123L,
                hasStageTimeline = false, garminStageMinutes = mapOf("DEEP" to 42L),
            ),
        )
        assertEquals(
            RefreshStatusLine.INCLUDED,
            refreshStatusLine(
                RefreshPhase.IDLE, GarminLinkStatus.METRICS_ONLY, completedAtMs = 123L,
                hasStageTimeline = true, garminStageMinutes = emptyMap(),
            ),
        )
    }

    @Test
    fun `health connect sync phase shows the syncing line`() {
        assertEquals(
            RefreshStatusLine.SYNCING,
            refreshStatusLine(RefreshPhase.SYNCING_HEALTHCONNECT, GarminLinkStatus.LINKED, completedAtMs = 123L),
        )
    }

    @Test
    fun `stages missing is a pure outcome check`() {
        assertEquals(true, stagesStillMissing(hasStageTimeline = false, garminStageMinutes = emptyMap()))
        assertEquals(false, stagesStillMissing(hasStageTimeline = true, garminStageMinutes = emptyMap()))
        assertEquals(false, stagesStillMissing(hasStageTimeline = false, garminStageMinutes = mapOf("LIGHT" to 1L)))
    }
}
