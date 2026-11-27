package com.sleepanalysis.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Band math, eligibility and the rolling/collecting gates (docs/INSIGHTS.md §Baseline method). */
class PersonalBaselineTest {

    private val detector = "energy-heuristic-v2-burst"
    private val scoreFormula = "snore-score-v3"

    private fun night(
        i: Int,
        grade: String? = "A",
        detectorVersion: String? = detector,
        scoreVersion: String? = scoreFormula,
        snoreShare: Double? = 0.10,
        longestBoutMs: Long? = 120_000L,
        score: Int? = 30,
        garminAwakeMinutes: Double? = 45.0,
        rMssdMs: Double? = 40.0,
    ): PersonalBaseline.Input = PersonalBaseline.Input(
        nightDate = "2026-10-%02d".format(i + 1),
        sessionId = (i + 1).toLong(),
        grade = grade,
        detectorVersion = detectorVersion,
        scoreVersion = scoreVersion,
        snoreShare = snoreShare,
        longestBoutMs = longestBoutMs,
        score = score,
        garminAwakeMinutes = garminAwakeMinutes,
        rMssdMs = rMssdMs,
    )

    @Test
    fun `band math uses median and MAD with a floor so zero-MAD bands never collapse`() {
        // shares 0.02/0.10/0.10/0.10/0.20 → median 0.10, MAD = median(|dev|) = median(0.08,0,0,0,0.10) = 0
        val nights = listOf(0.02, 0.10, 0.10, 0.10, 0.20).mapIndexed { i, share ->
            night(i, snoreShare = share)
        }
        val band = PersonalBaseline.compute(nights, detector, scoreFormula)
            .bands.getValue(PersonalBaseline.Metrics.SNORE_SHARE)
        assertEquals(0.10, band.median, 1e-9)
        assertEquals(0.0, band.mad, 1e-9)
        // half-width = max(2·1.4826·MAD, FLOOR_SNORE_SHARE=0.01) — the floor, not zero
        assertEquals(0.09, band.low, 1e-9)
        assertEquals(0.11, band.high, 1e-9)
    }

    @Test
    fun `band widens with a real MAD`() {
        val nights = listOf(0.0, 0.10, 0.20).mapIndexed { i, share -> night(i, snoreShare = share) }
        val band = PersonalBaseline.compute(nights, detector, scoreFormula)
            .bands.getValue(PersonalBaseline.Metrics.SNORE_SHARE)
        assertEquals(0.10, band.median, 1e-9)
        assertEquals(0.10, band.mad, 1e-9)
        val half = 2.0 * PersonalBaseline.MAD_SCALE * 0.10
        assertEquals(0.10 - half, band.low, 1e-9)
        assertEquals(0.10 + half, band.high, 1e-9)
    }

    @Test
    fun `robustZ keeps the floor as the minimal spread`() {
        val band = PersonalBaseline.MetricBand(median = 0.10, mad = 0.0, low = 0.09, high = 0.11)
        assertEquals(0.0, band.robustZ(0.10, PersonalBaseline.FLOOR_SNORE_SHARE), 1e-9)
        // spread = max(1.4826·MAD, floor/2) = 0.005 → z(0.13) = 6
        assertEquals(6.0, band.robustZ(0.13, PersonalBaseline.FLOOR_SNORE_SHARE), 1e-6)
        assertTrue(band.isOutside(0.13))
        assertFalse(band.isOutside(0.10))
    }

    @Test
    fun `rolling window keeps only the last 14 eligible nights`() {
        val nights = (0 until 20).map { i -> night(i, snoreShare = if (i < 6) 0.50 else 0.20) }
        val baseline = PersonalBaseline.compute(nights, detector, scoreFormula)
        assertEquals(14, baseline.eligibleCount)
        // nights 0–5 (share 0.50) rolled out of the window — the median is the last 14's
        assertEquals(0.20, baseline.bands.getValue(PersonalBaseline.Metrics.SNORE_SHARE).median, 1e-9)
    }

    @Test
    fun `collecting threshold boundary - 9 eligible nights collect, 10 are ready`() {
        val nine = (0 until 9).map { night(it) }
        assertEquals(PersonalBaseline.State.COLLECTING, PersonalBaseline.compute(nine, detector, scoreFormula).state)
        val ten = (0 until 10).map { night(it) }
        val baseline = PersonalBaseline.compute(ten, detector, scoreFormula)
        assertEquals(PersonalBaseline.State.READY, baseline.state)
        assertEquals(10, baseline.eligibleCount)
    }

    @Test
    fun `grades below B and version mismatches are ineligible`() {
        val nights = listOf(
            night(0, grade = "C"),
            night(1, grade = "D"),
            night(2, grade = null),
            night(3, detectorVersion = "energy-heuristic-v1-spectral"),
            night(4, scoreVersion = "snore-score-v2"),
            night(5),
        )
        val baseline = PersonalBaseline.compute(nights, detector, scoreFormula)
        assertEquals(1, baseline.eligibleCount)
        assertEquals(PersonalBaseline.State.COLLECTING, baseline.state)
    }

    @Test
    fun `a zero-snore night with the resolved current versions is eligible`() {
        // A night with no snore events resolves its detector version to the version current at
        // finalize (M1) — it must still count toward the baseline so quiet nights anchor the
        // share band low instead of biasing every band upward (docs/INSIGHTS.md §Eligibility).
        val quiet = PersonalBaseline.Input(
            nightDate = "2026-10-15",
            sessionId = 42,
            grade = "A",
            detectorVersion = detector,
            scoreVersion = scoreFormula,
            snoreShare = null,
            longestBoutMs = null,
            score = null,
            garminAwakeMinutes = 30.0,
            rMssdMs = 45.0,
        )
        val baseline = PersonalBaseline.compute(listOf(quiet), detector, scoreFormula)
        assertEquals(1, baseline.eligibleCount)
        assertEquals(PersonalBaseline.State.COLLECTING, baseline.state)
        assertNull(baseline.bands[PersonalBaseline.Metrics.SNORE_SHARE])
        assertNull(baseline.bands[PersonalBaseline.Metrics.SCORE])
        assertEquals(30.0, baseline.bands.getValue(PersonalBaseline.Metrics.GARMIN_AWAKE_MIN).median, 1e-9)
        assertEquals(45.0, baseline.bands.getValue(PersonalBaseline.Metrics.RMSSD_MS).median, 1e-9)
    }

    @Test
    fun `zero eligible nights produce no bands and a null-safe baseline`() {
        val baseline = PersonalBaseline.compute(emptyList(), detector, scoreFormula)
        assertEquals(0, baseline.eligibleCount)
        assertEquals(PersonalBaseline.State.COLLECTING, baseline.state)
        assertTrue(baseline.bands.isEmpty())
        assertNull(baseline.bands[PersonalBaseline.Metrics.SCORE])
    }
}
