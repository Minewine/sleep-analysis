package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.db.entity.UserLabel
import com.sleepanalysis.data.recorder.MeasurementRecorder
import com.sleepanalysis.domain.scoring.SnoreScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label-corrected recompute ([labelCorrectedSnoreEstimates]) must produce exactly the rows
 * finalizeSession would have written for the same events under the label-aware snore set —
 * including row presence conditions — so the morning report and trends cannot tell a corrected
 * night from a freshly finalized one except by the `label_corrected` notes flag.
 */
class LabelCorrectedSnoreEstimatesTest {

    private val start = 1_794_084_303_526L
    private val end = start + 23_386_000L // ~6.5 h capture window, the Nov-8 night's shape

    private fun session(
        status: String = SessionStatus.COMPLETED,
        endedAt: Long? = end,
        captureStats: String? = DEFAULT_CAPTURE_STATS,
    ) = SleepSessionEntity(
        id = 5,
        startedAt = start,
        endedAt = endedAt,
        status = status,
        timeZone = "Europe/Zurich",
        deviceModel = "test",
        osVersion = "16",
        appVersion = "test",
        captureStats = captureStats,
    )

    /**
     * A real captured night's stats: 46,772 processed windows × 0.5 s ≈ 6.5 h — the captured
     * night the recompute must mirror. Since the zero-window gate (P2-7), finalize writes no
     * snore-time/share/score rows without processed windows, so the mirror tests run against
     * a session that actually captured.
     */
    private val DEFAULT_CAPTURE_STATS =
        """{"schema_version":5,"windows_processed":46772,"windows_skipped":0}"""

    private fun event(
        type: String = EventType.SNORE,
        startMs: Long = 0,
        durationMs: Long = 10_000,
        tier: String = "LIGHT",
        peak: Double = -25.0,
        mean: Double = -35.0,
        label: String? = null,
    ) = AcousticEventEntity(
        sessionId = 5,
        type = type,
        startMs = startMs,
        endMs = startMs + durationMs,
        offsetMs = startMs,
        durationMs = durationMs,
        peakDbfs = peak,
        meanDbfs = mean,
        intensityTier = tier,
        confidence = 0.9,
        modelVersion = "test",
        userLabel = label,
    )

    private fun value(estimates: List<MeasurementRecorder.PhoneEstimate>, type: String) =
        estimates.single { it.type.name == type }.value

    private fun has(estimates: List<MeasurementRecorder.PhoneEstimate>, type: String) =
        estimates.any { it.type.name == type }

    @Test
    fun `unlabeled events reproduce the finalize aggregates`() {
        // 100 s + 50 s disjoint snoring in a 23,386 s captured night.
        val events = listOf(event(startMs = 0, durationMs = 100_000), event(startMs = 200_000, durationMs = 50_000))

        val estimates = labelCorrectedSnoreEstimates(session(), events)

        assertEquals(2.0, value(estimates, "SNORE_EVENT_COUNT"), 0.0001)
        assertEquals(150.0, value(estimates, "SNORE_TIME"), 0.0001)
        assertEquals(150.0 * 100.0 / 23_386.0, value(estimates, "SNORE_SHARE_OF_NIGHT"), 0.0001)
        assertEquals(-25.0, value(estimates, "SNORE_INTENSITY_PEAK"), 0.0001)
        assertEquals(-35.0, value(estimates, "SNORE_INTENSITY_MEAN"), 0.0001)
        val expected = SnoreScore.compute(
            150L,
            23_386L,
            mapOf("LIGHT" to 150_000L),
        )
        assertEquals(expected.score.toDouble(), value(estimates, "SNORE_SCORE"), 0.0001)
    }

    @Test
    fun `a COUGH label on the loudest event removes it from every aggregate`() {
        // The Nov-8 dogfood scenario: the night's loudest detector snore was really a cough.
        val events = listOf(
            event(startMs = 0, durationMs = 100_000, tier = "LOUD", peak = -12.0, mean = -24.7, label = UserLabel.COUGH),
            event(startMs = 200_000, durationMs = 50_000, peak = -26.0),
        )

        val estimates = labelCorrectedSnoreEstimates(session(), events)

        assertEquals(1.0, value(estimates, "SNORE_EVENT_COUNT"), 0.0001)
        assertEquals(50.0, value(estimates, "SNORE_TIME"), 0.0001)
        assertEquals(-26.0, value(estimates, "SNORE_INTENSITY_PEAK"), 0.0001)
        assertEquals(-35.0, value(estimates, "SNORE_INTENSITY_MEAN"), 0.0001)
    }

    @Test
    fun `a SNORE label includes an event the detector called other noise`() {
        val events = listOf(event(EventType.OTHER_NOISE, label = UserLabel.SNORE, peak = -18.0))

        val estimates = labelCorrectedSnoreEstimates(session(), events)

        assertEquals(1.0, value(estimates, "SNORE_EVENT_COUNT"), 0.0001)
        assertEquals(10.0, value(estimates, "SNORE_TIME"), 0.0001)
        assertEquals(-18.0, value(estimates, "SNORE_INTENSITY_PEAK"), 0.0001)
    }

    @Test
    fun `no snoring left writes zero rows and no intensity rows`() {
        val events = listOf(event(label = UserLabel.SPEECH))

        val estimates = labelCorrectedSnoreEstimates(session(), events)

        assertEquals(0.0, value(estimates, "SNORE_EVENT_COUNT"), 0.0001)
        assertEquals(0.0, value(estimates, "SNORE_TIME"), 0.0001)
        assertFalse(has(estimates, "SNORE_INTENSITY_PEAK"))
        assertFalse(has(estimates, "SNORE_INTENSITY_MEAN"))
        // Score row stays (a zero score is data), matching finalize's row conditions.
        assertEquals(0.0, value(estimates, "SNORE_SCORE"), 0.0001)
    }

    @Test
    fun `recovered session with no events writes only the count and time rows`() {
        val estimates = labelCorrectedSnoreEstimates(session(status = SessionStatus.RECOVERED), emptyList())

        assertTrue(has(estimates, "SNORE_EVENT_COUNT"))
        assertTrue(has(estimates, "SNORE_TIME"))
        assertFalse(has(estimates, "SNORE_SHARE_OF_NIGHT"))
        assertFalse(has(estimates, "SNORE_SCORE"))
        assertFalse(has(estimates, "SNORE_INTENSITY_PEAK"))
    }

    @Test
    fun `score row alone carries the formula version, matching finalize`() {
        val estimates = labelCorrectedSnoreEstimates(session(), listOf(event()))

        assertTrue(estimates.single { it.type.name == "SNORE_SCORE" }.modelVersion == SnoreScore.FORMULA_VERSION)
        assertTrue(estimates.single { it.type.name == "SNORE_TIME" }.modelVersion == null)
    }

    @Test
    fun `still-recording session produces nothing`() {
        val estimates = labelCorrectedSnoreEstimates(session(endedAt = null), listOf(event()))

        assertTrue(estimates.isEmpty())
    }

    @Test
    fun `a zero-window night writes no snore-time share or score rows on recompute`() {
        // Stop during the start delay: the stats stub exists but the engine never processed a
        // window — finalize wrote no share/score/snore-time (P2-7), so the recompute mirrors
        // exactly that even if events somehow exist.
        val zeroWindow = """{"schema_version":1}"""
        val estimates = labelCorrectedSnoreEstimates(session(captureStats = zeroWindow), listOf(event()))

        assertTrue(has(estimates, "SNORE_EVENT_COUNT"))
        assertFalse(has(estimates, "SNORE_TIME"))
        assertFalse(has(estimates, "SNORE_SHARE_OF_NIGHT"))
        assertFalse(has(estimates, "SNORE_SCORE"))
        // Intensity rows stay event-derived in both finalize and the recompute: stored events
        // are evidence even when the stats blob was lost. The gate covers only the
        // denominator-relative aggregates (share, score) and snore time.
    }

    @Test
    fun `zero processed windows with skipped windows still writes no snore rows`() {
        // windows_skipped > 0 means capture ran but classified nothing — no detection
        // evidence, no snore aggregates (P2-7).
        val skippedOnly = """{"schema_version":6,"windows_processed":0,"windows_skipped":40}"""
        val estimates = labelCorrectedSnoreEstimates(session(captureStats = skippedOnly), listOf(event()))

        assertFalse(has(estimates, "SNORE_TIME"))
        assertFalse(has(estimates, "SNORE_SHARE_OF_NIGHT"))
        assertFalse(has(estimates, "SNORE_SCORE"))
    }
}
