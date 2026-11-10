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
}
