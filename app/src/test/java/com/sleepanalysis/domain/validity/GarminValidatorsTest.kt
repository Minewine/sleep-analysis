package com.sleepanalysis.domain.validity

import com.sleepanalysis.data.garmin.GarminJsonParsers
import com.sleepanalysis.domain.policy.MeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GarminValidators: plausibility (drop + warning), stage-span rejection, stage-sum and TST
 * consistency, freshness, and zero-overlap — each at its pass/fail boundary.
 */
class GarminValidatorsTest {

    private fun metric(type: MeasurementType, value: Double) =
        GarminJsonParsers.ParsedMetric(type, value)

    private fun span(startMs: Long, endMs: Long) =
        GarminJsonParsers.SleepLevelSpan(startMs, endMs, "DEEP")

    private fun window(startMs: Long, endMsMs: Long) =
        GarminJsonParsers.GarminSleepWindow(startMs, endMsMs)

    // ---- plausibility -----------------------------------------------------------

    @Test
    fun `heart rate bounds are 30 to 220 and out-of-range values are dropped`() {
        val v = GarminValidators.validate(
            metrics = listOf(
                metric(MeasurementType.VENDOR_AVG_HEART_RATE_SLEEP, 30.0), // boundary in
                metric(MeasurementType.VENDOR_RESTING_HEART_RATE, 220.0), // boundary in
                metric(MeasurementType.VENDOR_AVG_HEART_RATE_SLEEP, 29.0), // out
                metric(MeasurementType.VENDOR_RESTING_HEART_RATE, 221.0), // out
            ),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertEquals(listOf(30.0, 220.0), v.keptMetrics.map { it.value })
        val w = v.warnings.single { it.id == "garmin_implausible" }
        assertTrue(w.detail.contains("29.0") && w.detail.contains("221.0"))
    }

    @Test
    fun `rmssd bounds are 2 to 300 ms`() {
        val v = GarminValidators.validate(
            metrics = listOf(
                metric(MeasurementType.VENDOR_HRV_RMSSD, 2.0),
                metric(MeasurementType.VENDOR_HRV_RMSSD, 300.0),
                metric(MeasurementType.VENDOR_HRV_RMSSD, 1.9),
                metric(MeasurementType.VENDOR_HRV_RMSSD, 300.5),
            ),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertEquals(listOf(2.0, 300.0), v.keptMetrics.map { it.value })
        assertTrue(v.warnings.any { it.id == "garmin_implausible" })
    }

    @Test
    fun `spo2 within 70-100 is kept and below 90 carries the distrust flag`() {
        val v = GarminValidators.validate(
            metrics = listOf(
                metric(MeasurementType.VENDOR_SPO2_AVG_SLEEP, 90.0), // boundary: no distrust
                metric(MeasurementType.VENDOR_SPO2_LOWEST_SLEEP, 70.0), // in range, distrust
            ),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertEquals(2, v.keptMetrics.size)
        val distrust = v.warnings.single { it.id == "garmin_spo2_distrust" }
        assertTrue(distrust.detail.contains("70.0"))
        assertFalse(distrust.detail.contains("90.0"))
    }

    @Test
    fun `spo2 outside 70-100 is dropped not distrusted`() {
        val v = GarminValidators.validate(
            metrics = listOf(
                metric(MeasurementType.VENDOR_SPO2_AVG_SLEEP, 69.0),
                metric(MeasurementType.VENDOR_SPO2_AVG_SLEEP, 101.0),
            ),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertTrue(v.keptMetrics.isEmpty())
        assertTrue(v.warnings.any { it.id == "garmin_implausible" })
        assertFalse(v.warnings.any { it.id == "garmin_spo2_distrust" })
    }

    @Test
    fun `opaque vendor composites are never plausibility-gated`() {
        val v = GarminValidators.validate(
            metrics = listOf(
                metric(MeasurementType.VENDOR_SLEEP_SCORE, 500.0),
                metric(MeasurementType.VENDOR_BODY_BATTERY, -7.0),
            ),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertTrue(v.warnings.isEmpty())
        assertEquals(2, v.keptMetrics.size)
    }

    // ---- stage spans ------------------------------------------------------------

    @Test
    fun `negative and overlapping stage spans are rejected, the earliest kept`() {
        val v = GarminValidators.validate(
            metrics = emptyList(),
            stageSpans = listOf(
                span(0, 600_000), // kept
                span(300_000, 900_000), // overlaps the kept one → rejected
                span(900_000, 600_000), // negative → rejected
                span(600_000, 1_200_000), // kept (touches the first span's end)
            ),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertEquals(listOf(0L to 600_000L, 600_000L to 1_200_000L), v.validSpans.map { it.startMs to it.endMs })
        val w = v.warnings.single { it.id == "garmin_span_overlap" }
        assertTrue(w.detail.contains("2 stage span(s)"))
    }

    @Test
    fun `well-formed non-overlapping spans pass without warnings`() {
        val v = GarminValidators.validate(
            metrics = emptyList(),
            stageSpans = listOf(span(0, 600_000), span(600_000, 1_200_000)),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertTrue(v.warnings.isEmpty())
        assertEquals(2, v.validSpans.size)
    }

    // ---- stage-sum vs reported duration -------------------------------------------

    private fun stageScenario(stageSeconds: Long): GarminValidators.GarminValidity =
        GarminValidators.validate(
            metrics = listOf(metric(MeasurementType.VENDOR_SLEEP_DURATION, 8 * 3600.0)),
            stageSpans = listOf(span(0, stageSeconds * 1000)),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )

    @Test
    fun `stage sum within 10 percent of reported duration passes`() {
        // 7.5 h of stages vs 8 h reported = 6.25% off.
        assertTrue(stageScenario(stageSeconds = 7 * 3600 + 1800).warnings.isEmpty())
    }

    @Test
    fun `stage sum off by more than 10 percent warns`() {
        // 7 h of stages vs 8 h reported = 12.5% off (boundary: strictly beyond ±10%).
        val v = stageScenario(stageSeconds = 7 * 3600)
        val w = v.warnings.single { it.id == "garmin_stage_mismatch" }
        assertTrue(w.detail.contains("7.0 h") && w.detail.contains("8.0 h"))
    }

    @Test
    fun `stage sum check needs both a reported duration and spans`() {
        val noReport = GarminValidators.validate(
            metrics = emptyList(),
            stageSpans = listOf(span(0, 3600_000)),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertTrue(noReport.warnings.isEmpty())
        val noSpans = GarminValidators.validate(
            metrics = listOf(metric(MeasurementType.VENDOR_SLEEP_DURATION, 8 * 3600.0)),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertTrue(noSpans.warnings.isEmpty())
    }

    // ---- reported TST vs sleep window -----------------------------------------------

    @Test
    fun `reported tst within 45 minutes of the window passes`() {
        // Window 8 h, reported 7 h 20 min → 40 min under (boundary: at/below ±45 min).
        val v = GarminValidators.validate(
            metrics = listOf(metric(MeasurementType.VENDOR_SLEEP_DURATION, (8 * 3600 - 40 * 60).toDouble())),
            stageSpans = emptyList(),
            sleepWindow = window(0, 8 * 3600_000L),
            nightEndMs = null,
            fetchedAtMs = null,
        )
        assertFalse(v.warnings.any { it.id == "garmin_tst_mismatch" })
    }

    @Test
    fun `reported tst beyond 45 minutes of the window warns`() {
        // Window 8 h, reported 7 h → 60 min under (the ~+40 min validation bias, exceeded).
        val v = GarminValidators.validate(
            metrics = listOf(metric(MeasurementType.VENDOR_SLEEP_DURATION, 7 * 3600.0)),
            stageSpans = emptyList(),
            sleepWindow = window(0, 8 * 3600_000L),
            nightEndMs = null,
            fetchedAtMs = null,
        )
        val w = v.warnings.single { it.id == "garmin_tst_mismatch" }
        assertTrue(w.detail.contains("420") && w.detail.contains("480"))
    }

    // ---- freshness -------------------------------------------------------------------

    @Test
    fun `import within 24 h of the night end is fresh`() {
        val nightEnd = 1_000_000L
        val v = GarminValidators.validate(
            metrics = emptyList(),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = nightEnd,
            fetchedAtMs = nightEnd + GarminValidators.FRESHNESS_LIMIT_MS, // exactly 24 h: pass
        )
        assertTrue(v.warnings.isEmpty())
    }

    @Test
    fun `import more than 24 h after the night end is stale`() {
        val nightEnd = 1_000_000L
        val v = GarminValidators.validate(
            metrics = emptyList(),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = nightEnd,
            fetchedAtMs = nightEnd + GarminValidators.FRESHNESS_LIMIT_MS + 1,
        )
        val w = v.warnings.single { it.id == "garmin_stale" }
        assertTrue(w.detail.contains("24.0 h"))
    }

    @Test
    fun `freshness needs both anchors`() {
        val v = GarminValidators.validate(
            metrics = emptyList(),
            stageSpans = emptyList(),
            sleepWindow = null,
            nightEndMs = null,
            fetchedAtMs = 1_000_000L,
        )
        assertTrue(v.warnings.isEmpty())
    }

    // ---- zero overlap -----------------------------------------------------------------

    @Test
    fun `disjoint sleep window and capture window warn as no overlap`() {
        val v = GarminValidators.validate(
            metrics = emptyList(),
            stageSpans = emptyList(),
            sleepWindow = window(10_000, 20_000),
            nightEndMs = null,
            fetchedAtMs = null,
            captureStartMs = 30_000,
            captureEndMs = 40_000,
        )
        assertTrue(v.warnings.any { it.id == "garmin_no_overlap" })
    }

    @Test
    fun `overlapping windows do not warn`() {
        val v = GarminValidators.validate(
            metrics = emptyList(),
            stageSpans = emptyList(),
            sleepWindow = window(10_000, 35_000),
            nightEndMs = null,
            fetchedAtMs = null,
            captureStartMs = 30_000,
            captureEndMs = 40_000,
        )
        assertFalse(v.warnings.any { it.id == "garmin_no_overlap" })
    }

    @Test
    fun `noOverlap factory carries the canonical id`() {
        assertEquals("garmin_no_overlap", GarminValidators.noOverlap().id)
    }

    // ---- determinism -------------------------------------------------------------------

    @Test
    fun `warnings come out in a deterministic order`() {
        val nightEnd = 0L
        val v = GarminValidators.validate(
            metrics = listOf(
                metric(MeasurementType.VENDOR_HRV_RMSSD, 0.5), // implausible
                metric(MeasurementType.VENDOR_SLEEP_DURATION, 3 * 3600.0),
                metric(MeasurementType.VENDOR_SPO2_AVG_SLEEP, 85.0), // distrust
            ),
            stageSpans = listOf(span(0, 600_000), span(100_000, 200_000)), // overlap
            sleepWindow = window(0, 8 * 3600_000L), // stage sum + tst mismatch + overlap=0? capture absent
            nightEndMs = nightEnd,
            fetchedAtMs = nightEnd + 48 * 3_600_000L, // stale
        )
        assertEquals(
            listOf(
                "garmin_stale",
                "garmin_stage_mismatch",
                "garmin_tst_mismatch",
                "garmin_span_overlap",
                "garmin_implausible",
                "garmin_spo2_distrust",
            ),
            v.warnings.map { it.id },
        )
    }
}
