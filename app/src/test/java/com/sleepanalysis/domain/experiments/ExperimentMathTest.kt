package com.sleepanalysis.domain.experiments

import com.sleepanalysis.domain.insights.ConfidenceTier
import com.sleepanalysis.domain.insights.PersonalBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The N-of-1 verdict math (docs/INSIGHTS.md §Playbook, docs/EVIDENCE_BASE.md N-of-1 rows):
 * the WWC floor, the 7-night default, the |z| > 2 verdict, confound exclusion, and the A-B-A
 * confirmation upgrade. The JSON codecs are covered too (resultJson is persisted state).
 */
class ExperimentMathTest {

    private val band = ExperimentMath.Band(median = 0.10, mad = 0.02, low = 0.04, high = 0.16)

    private fun night(
        id: Long,
        value: Double?,
        vararg factors: String,
    ): ExperimentMath.PhaseNight = ExperimentMath.PhaseNight(
        sessionId = id,
        startedAtMs = id * 86_400_000L,
        value = value,
        factorKeys = factors.toSet(),
    )

    private fun phase(vararg values: Double): List<ExperimentMath.PhaseNight> =
        values.mapIndexed { i, v -> night((i + 1).toLong(), v) }

    // ---- Verdict boundaries ----------------------------------------------------

    @Test
    fun `phase inside the band variation gives no change verdict`() {
        val result = ExperimentMath.evaluatePhase(
            nights = phase(0.10, 0.11, 0.10, 0.12, 0.10, 0.11, 0.10),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertTrue(result.ready)
        val verdict = result.verdict!!
        assertFalse(verdict.changeDetected)
        assertNull(verdict.direction)
        assertEquals(ConfidenceTier.LOW, verdict.confidence)
        assertEquals(7, result.usedNightIds.size)
        assertTrue(result.excludedNightIds.isEmpty())
    }

    @Test
    fun `phase median beyond 2 robust SD up is a CHANGE_DETECTED up verdict`() {
        val result = ExperimentMath.evaluatePhase(
            nights = phase(0.30, 0.31, 0.30, 0.29, 0.30, 0.31, 0.30),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        val verdict = result.verdict!!
        assertTrue(verdict.changeDetected)
        assertEquals(ExperimentMath.Direction.UP, verdict.direction)
        // z = (0.30 − 0.10) / (1.4826·0.02) ≈ 6.7 — far beyond the 2-robust-SD trigger.
        assertTrue(verdict.robustZ!! > 2.0)
        // LOW, never "improved" — the change wording only (docs/INSIGHTS.md §Playbook).
        assertEquals(ConfidenceTier.LOW, verdict.confidence)
    }

    @Test
    fun `phase median below the band is a change down verdict`() {
        val result = ExperimentMath.evaluatePhase(
            nights = phase(0.01, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        val verdict = result.verdict!!
        assertTrue(verdict.changeDetected)
        assertEquals(ExperimentMath.Direction.DOWN, verdict.direction)
    }

    @Test
    fun `verdict waits for the planned usable nights`() {
        val result = ExperimentMath.evaluatePhase(
            nights = phase(0.30, 0.31, 0.30, 0.31, 0.30, 0.31),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertFalse(result.ready)
        assertNull(result.verdict)
        assertEquals(6, result.usedNightIds.size)
    }

    @Test
    fun `planned nights are clamped to the WWC floor of five`() {
        val result = ExperimentMath.evaluatePhase(
            nights = phase(0.30, 0.31, 0.30, 0.31, 0.30),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 3, // below floor — clamped up
        )
        assertTrue(result.ready) // 5 usable ≥ clamped planned 5 (WWC 2010)
    }

    // ---- Confound exclusion ----------------------------------------------------

    @Test
    fun `nights with other logged factors are excluded from the verdict`() {
        val result = ExperimentMath.evaluatePhase(
            nights = listOf(
                night(1, 0.30),
                night(2, 0.50, "ALCOHOL"), // confound — excluded
                night(3, 0.31),
                night(4, 0.30),
                night(5, 0.30),
                night(6, 0.31),
                night(7, 0.30),
                night(8, 0.31, "EXERCISE"), // confound — excluded
            ),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertFalse(result.ready) // 6 usable < 7 planned — the verdict honestly waits
        assertEquals(listOf(2L, 8L), result.excludedNightIds)
        assertEquals(6, result.usedNightIds.size)
        assertNull(result.verdict)
    }

    @Test
    fun `the experiment's own factor does not exclude its nights`() {
        val result = ExperimentMath.evaluatePhase(
            nights = listOf(
                night(1, 0.30, "ALCOHOL"), // own factor for no_evening_alcohol
                night(2, 0.50, "STRESS"), // other factor — excluded
                night(3, 0.31, "ALCOHOL"),
                night(4, 0.30),
                night(5, 0.30, "ALCOHOL"),
                night(6, 0.31),
                night(7, 0.30, "ALCOHOL"),
            ),
            ownFactorKeys = setOf("ALCOHOL"),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertEquals(listOf(2L), result.excludedNightIds)
        assertEquals(6, result.usedNightIds.size)
        // 6 < 7 planned → not ready, but the own-factor nights clearly stayed usable.
        assertFalse(result.ready)
    }

    @Test
    fun `NOTES never counts as a confound`() {
        val (usable, excluded, _) = ExperimentMath.partitionNights(
            nights = listOf(night(1, 0.30, "NOTES")),
            ownFactorKeys = emptySet(),
        )
        assertTrue(excluded.isEmpty())
        assertEquals(1, usable.size)
    }

    @Test
    fun `MORNING_RATING never counts as a confound`() {
        // The morning rating is an OUTCOME (1-5 self-report), not a manipulation: every
        // morning gets rated, so treating it as confoundable would silently exclude every
        // rated night and stall all N-of-1 experiments at zero usable nights.
        val (usable, excluded, _) = ExperimentMath.partitionNights(
            nights = listOf(night(1, 0.30, "MORNING_RATING")),
            ownFactorKeys = emptySet(),
        )
        assertTrue(excluded.isEmpty())
        assertEquals(1, usable.size)
    }

    @Test
    fun `nights missing the metric value are not usable but not confounds`() {
        val result = ExperimentMath.evaluatePhase(
            nights = listOf(
                night(1, 0.30),
                night(2, null), // no Garmin import that night
                night(3, 0.31),
                night(4, 0.30),
                night(5, 0.30),
                night(6, 0.31),
                night(7, 0.30),
            ),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertFalse(result.ready)
        assertEquals(listOf(2L), result.missingValueNightIds)
        assertTrue(result.excludedNightIds.isEmpty())
    }

    // ---- Zero-MAD safety -------------------------------------------------------

    @Test
    fun `a zero-MAD band still yields a finite verdict z via the metric floor`() {
        val flat = ExperimentMath.Band(median = 0.10, mad = 0.0, low = 0.05, high = 0.15)
        val result = ExperimentMath.evaluatePhase(
            nights = phase(0.30, 0.30, 0.30, 0.30, 0.30, 0.30, 0.30),
            ownFactorKeys = emptySet(),
            band = flat,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        val verdict = result.verdict!!
        assertTrue(verdict.changeDetected)
        // spread = max(0, floor/2) > 0 — a real number, not Infinity/NaN.
        assertTrue(verdict.robustZ!!.isFinite())
    }

    @Test
    fun `identical phase median on a zero-MAD band is no change`() {
        val flat = ExperimentMath.Band(median = 0.10, mad = 0.0, low = 0.05, high = 0.15)
        val result = ExperimentMath.evaluatePhase(
            nights = phase(0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.10),
            ownFactorKeys = emptySet(),
            band = flat,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertFalse(result.verdict!!.changeDetected)
        assertEquals(0.0, result.verdict!!.robustZ!!, 0.0001)
    }

    // ---- Verdict strictness boundary ------------------------------------------------

    @Test
    fun `a phase median at exactly 2 robust SD is NOT a change - the trigger is strict`() {
        // v = median₀ + 2·(1.4826·MAD₀) yields z == 2.0 EXACTLY in double arithmetic
        // (the division round-trips); |z| > 2 is strict, so the edge itself never fires.
        val edge = band.median + 2 * (PersonalBaseline.MAD_SCALE * band.mad)
        val at = ExperimentMath.evaluatePhase(
            nights = phase(edge, edge, edge, edge, edge, edge, edge),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertEquals(2.0, at.verdict!!.robustZ!!, 0.0)
        assertFalse(at.verdict!!.changeDetected)
        // One ulp above the edge crosses the strict trigger.
        val justAbove = Math.nextUp(edge)
        val over = ExperimentMath.evaluatePhase(
            nights = phase(justAbove, justAbove, justAbove, justAbove, justAbove, justAbove, justAbove),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertTrue(over.verdict!!.robustZ!! > 2.0)
        assertTrue(over.verdict!!.changeDetected)
    }

    // ---- A-B-A confirmation ----------------------------------------------------

    @Test
    fun `return-to-baseline median back inside the band upgrades to MODERATE`() {
        val change = ExperimentMath.PhaseVerdict(
            changeDetected = true,
            direction = ExperimentMath.Direction.UP,
            confidence = ConfidenceTier.LOW,
            phaseMedian = 0.30,
            robustZ = 6.7,
        )
        val phase2 = ExperimentMath.evaluatePhase(
            nights = phase(0.11, 0.10, 0.12, 0.10, 0.11, 0.10, 0.11),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        val outcome = ExperimentMath.evaluateConfirm(phase2, band, change, plannedNights = 7)
        assertTrue(outcome.ready)
        assertTrue(outcome.returnedToBand)
        assertTrue(outcome.confirmed)
        assertEquals(ConfidenceTier.MODERATE, outcome.confidence)
    }

    @Test
    fun `return-to-baseline median staying outside keeps LOW - effect not confirmed`() {
        val change = ExperimentMath.PhaseVerdict(
            changeDetected = true,
            direction = ExperimentMath.Direction.UP,
            confidence = ConfidenceTier.LOW,
            phaseMedian = 0.30,
            robustZ = 6.7,
        )
        val phase2 = ExperimentMath.evaluatePhase(
            nights = phase(0.30, 0.31, 0.30, 0.29, 0.30, 0.31, 0.30),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        val outcome = ExperimentMath.evaluateConfirm(phase2, band, change, plannedNights = 7)
        assertFalse(outcome.returnedToBand)
        assertFalse(outcome.confirmed)
        assertEquals(ConfidenceTier.LOW, outcome.confidence)
    }

    // ---- JSON codecs (persisted resultJson / baselineStatsJson / confirmPhaseJson) --------

    @Test
    fun `baseline json round-trips`() {
        val json = experimentBaselineJson("snore_share", band)
        val (metric, parsed) = parseExperimentBaseline(json)!!
        assertEquals("snore_share", metric)
        assertEquals(band, parsed)
        assertNull(parseExperimentBaseline(null))
        assertNull(parseExperimentBaseline("not json"))
    }

    @Test
    fun `verdict json round-trips with confound and confirm fields`() {
        val result = ExperimentMath.evaluatePhase(
            nights = listOf(
                night(1, 0.30),
                night(2, 0.31, "STRESS"), // confound — excluded, still listed in the JSON
                night(3, 0.30),
                night(4, 0.30),
                night(5, 0.31),
                night(6, 0.30),
                night(7, 0.30),
                night(8, 0.30),
            ),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        assertTrue(result.ready) // 7 usable after the exclusion
        val json = experimentVerdictJson(result, plannedNights = 7, confirmed = true, confirmMedian = 0.11)
        val row = parseExperimentVerdict(json)!!
        assertTrue(row.verdict!!.changeDetected)
        assertEquals(ExperimentMath.Direction.UP, row.verdict!!.direction)
        assertEquals(ConfidenceTier.LOW, row.verdict!!.confidence)
        assertEquals(listOf(2L), row.excludedNightIds)
        assertEquals(7, row.usedNightIds.size)
        assertEquals(7, row.plannedNights)
        assertEquals(true, row.confirmed)
        assertEquals(0.11, row.confirmMedian!!, 0.0001)
        assertFalse(row.kept)
    }

    @Test
    fun `kept flag survives a verdict json round-trip`() {
        val result = ExperimentMath.evaluatePhase(
            nights = phase(0.10, 0.11, 0.10, 0.12, 0.10, 0.11, 0.10),
            ownFactorKeys = emptySet(),
            band = band,
            metric = PersonalBaseline.Metrics.SNORE_SHARE,
            plannedNights = 7,
        )
        val row = parseExperimentVerdict(experimentVerdictJson(result, 7, kept = true))!!
        assertTrue(row.kept)
        assertFalse(row.verdict!!.changeDetected)
    }

    @Test
    fun `confirm phase json collects first and finalizes with the outcome`() {
        val collecting = confirmPhaseJson(startedAt = 1_000L, outcome = null)
        assertEquals(1_000L, parseConfirmPhase(collecting)!!.startedAt)

        val outcome = ExperimentMath.ConfirmOutcome(
            ready = true,
            phase2Median = 0.11,
            returnedToBand = true,
            confirmed = true,
            confidence = ConfidenceTier.MODERATE,
        )
        val final = confirmPhaseJson(startedAt = 1_000L, outcome = outcome)
        assertEquals(1_000L, parseConfirmPhase(final)!!.startedAt)

        assertNull(parseConfirmPhase(null))
        assertNull(parseConfirmPhase("{}")) // no CONFIRM phase marker
    }

    // ---- Confirm-finalize rebuild (kept-preservation) --------------------------

    /** A phase-1 result with a detected change (the confirm phase's precondition). */
    private fun changeResult(): ExperimentMath.PhaseResult = ExperimentMath.evaluatePhase(
        nights = phase(0.30, 0.31, 0.30, 0.29, 0.30, 0.31, 0.30),
        ownFactorKeys = emptySet(),
        band = band,
        metric = PersonalBaseline.Metrics.SNORE_SHARE,
        plannedNights = 7,
    )

    @Test
    fun `confirm finalize rebuild preserves a Keep tapped before or during the confirm phase`() {
        // A Keep written while the row was ACTIVE (recomposition window) or before the confirm
        // started: the finalize rebuilds the WHOLE resultJson — the kept flag must survive it.
        val phase1Row = parseExperimentVerdict(experimentVerdictJson(changeResult(), 7, kept = true))!!
        val outcome = ExperimentMath.ConfirmOutcome(
            ready = true,
            phase2Median = 0.11,
            returnedToBand = true,
            confirmed = true,
            confidence = ConfidenceTier.MODERATE,
        )
        val final = parseExperimentVerdict(confirmFinalResultJson(phase1Row, 7, outcome))!!
        assertTrue(final.kept) // the user's Keep is not silently lost by the rebuild
        assertEquals(ConfidenceTier.MODERATE, final.verdict!!.confidence)
        assertEquals(true, final.confirmed)
        assertTrue(final.verdict!!.changeDetected)
    }

    @Test
    fun `confirm finalize rebuild stays unkept when no Keep was tapped`() {
        val phase1Row = parseExperimentVerdict(experimentVerdictJson(changeResult(), 7))!!
        val outcome = ExperimentMath.ConfirmOutcome(
            ready = true,
            phase2Median = 0.30,
            returnedToBand = false,
            confirmed = false,
            confidence = ConfidenceTier.LOW,
        )
        val final = parseExperimentVerdict(confirmFinalResultJson(phase1Row, 7, outcome))!!
        assertFalse(final.kept)
        assertEquals(ConfidenceTier.LOW, final.verdict!!.confidence)
        assertEquals(false, final.confirmed)
    }
}
