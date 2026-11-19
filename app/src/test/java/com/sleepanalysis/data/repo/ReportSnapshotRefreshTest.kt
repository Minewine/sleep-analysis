package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.db.entity.UserLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report snapshot refresh contract (docs/DATA_MODEL.md §3.7): the pure headline builder
 * behind finalize AND the labelEvent recompute — a label change must LOWER the stored snapshot
 * aggregates exactly like it lowers the rewritten measurement rows, so the snapshot keeps
 * mirroring the night's rows ("mirror exactly what finalize wrote").
 */
class ReportSnapshotRefreshTest {

    private val startedAt = 1_000_000L
    private val endedAt = startedAt + 8 * 3600_000L
    // 57600 processed windows × 500 ms = 8 h of captured analysis time
    private val captureStats = """{"schema_version":2,"windows_processed":57600,"windows_skipped":0}"""

    private fun session(
        status: String = SessionStatus.COMPLETED,
        capture: String? = captureStats,
    ): SleepSessionEntity = SleepSessionEntity(
        id = 1,
        startedAt = startedAt,
        endedAt = endedAt,
        status = status,
        timeZone = "UTC",
        deviceModel = "TestDevice",
        osVersion = "15",
        appVersion = "0.1.0",
        captureStats = capture,
    )

    private fun snoreEvent(startOffsetMs: Long, userLabel: String? = null): AcousticEventEntity =
        AcousticEventEntity(
            sessionId = 1,
            type = EventType.SNORE,
            startMs = startedAt + startOffsetMs,
            endMs = startedAt + startOffsetMs + 600_000L,
            offsetMs = startOffsetMs,
            durationMs = 600_000L,
            peakDbfs = -10.0,
            meanDbfs = -12.0,
            intensityTier = "LOUD",
            confidence = 0.9,
            modelVersion = "energy-heuristic-v2-burst",
            userLabel = userLabel,
        )

    @Test
    fun `a label-corrected recompute lowers the snapshot aggregates`() {
        val twoSnoreNights = listOf(
            snoreEvent(1 * 3600_000L), // first half
            snoreEvent(6 * 3600_000L), // second half
        )
        val unlabeled = reportSnapshotHeadline(session(), twoSnoreNights, validityGrade = "A")
        assertEquals(1200L, unlabeled.snoreSeconds)
        assertEquals(1200.0 * 100 / 28800, unlabeled.snoreSharePercent!!, 1e-9)

        // The user relabels the first snore as OTHER_NOT_SNORE — the same math the rewritten
        // PHONE rows follow must lower the snapshot the same way.
        val relabeled = reportSnapshotHeadline(
            session(),
            twoSnoreNights.mapIndexed { i, e -> if (i == 0) e.copy(userLabel = UserLabel.OTHER_NOT_SNORE) else e },
            validityGrade = "A",
        )
        assertEquals(600L, relabeled.snoreSeconds)
        assertEquals(600.0 * 100 / 28800, relabeled.snoreSharePercent!!, 1e-9)
        assertTrue(relabeled.snoreScore!! < unlabeled.snoreScore!!)
        // The surviving bout is untouched by the label change.
        assertEquals(600_000L, relabeled.longestBoutMs)
    }

    @Test
    fun `a RECOVERED night with no events stores no share or score in the snapshot`() {
        val headline = reportSnapshotHeadline(
            session(status = SessionStatus.RECOVERED, capture = null),
            emptyList(),
            validityGrade = "D",
        )
        assertEquals("D", headline.validityGrade)
        assertNull(headline.snoreScore)
        assertNull(headline.snoreSharePercent)
        assertEquals(0L, headline.snoreSeconds)
        assertNull(headline.longestBoutMs)
    }

    @Test
    fun `a zero-snore completed night stores share 0 and score 0`() {
        val headline = reportSnapshotHeadline(session(), emptyList(), validityGrade = "A")
        assertEquals(0L, headline.snoreSeconds)
        assertEquals(0.0, headline.snoreSharePercent!!, 1e-9)
        assertEquals(0, headline.snoreScore)
    }
}
