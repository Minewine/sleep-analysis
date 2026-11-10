package com.sleepanalysis.domain.insights

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-rule trigger / no-trigger / insufficient-evidence / ineligible-excluded checks
 * (docs/INSIGHTS.md §Rule catalog). All rules must stay in the LOW/MODERATE tiers.
 */
class InsightRulesTest {

    private val utc = ZoneId.of("UTC")

    private fun ms(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(utc).toInstant().toEpochMilli()

    private fun band(median: Double, mad: Double, floor: Double): PersonalBaseline.MetricBand {
        val half = maxOf(2.0 * PersonalBaseline.MAD_SCALE * mad, floor)
        return PersonalBaseline.MetricBand(median, mad, median - half, median + half)
    }

    /** Bands with zero MAD — the floors decide, making trigger arithmetic exact. */
    private val bands = mapOf(
        PersonalBaseline.Metrics.SNORE_SHARE to band(0.10, 0.0, 0.01),
        PersonalBaseline.Metrics.LONGEST_BOUT_MS to band(120_000.0, 0.0, 60_000.0),
        PersonalBaseline.Metrics.SCORE to band(30.0, 0.0, 5.0),
        PersonalBaseline.Metrics.GARMIN_AWAKE_MIN to band(45.0, 0.0, 10.0),
        PersonalBaseline.Metrics.RMSSD_MS to band(40.0, 0.0, 5.0),
    )

    private fun record(
        sessionId: Long = 1,
        startDate: String = "2026-11-02",
        startHour: Int = 22,
        startMinute: Int = 30,
        grade: String? = "A",
        detector: String? = "energy-heuristic-v2-burst",
        scoreVersion: String? = "snore-score-v3",
        snoreSpans: List<Pair<Long, Long>> = emptyList(),
        snoreShare: Double? = 0.10,
        longestBoutMs: Long? = 120_000L,
        score: Int? = 30,
        awakeMin: Double? = 45.0,
        sleepMin: Double? = 400.0,
        rMssd: Double? = 40.0,
        garminWindow: Pair<Long, Long>? = null,
        alcohol: Boolean = false,
        caffeine: Boolean = false,
        exercise: Boolean = false,
        stress: Int? = null,
    ): InsightRules.NightRecord {
        val startedAt = ms(startDate, startHour, startMinute)
        return InsightRules.NightRecord(
            sessionId = sessionId,
            startedAtMs = startedAt,
            endedAtMs = startedAt + 8 * 3600_000L,
            nightDate = startDate,
            zoneId = "UTC",
            grade = grade,
            detectorVersion = detector,
            scoreVersion = scoreVersion,
            snoreSpans = snoreSpans,
            snoreShare = snoreShare,
            longestBoutMs = longestBoutMs,
            score = score,
            garminAwakeMinutes = awakeMin,
            garminSleepMinutes = sleepMin,
            rMssdMs = rMssd,
            garminWindow = garminWindow,
            alcoholLogged = alcohol,
            caffeineAfter14Logged = caffeine,
            exerciseLogged = exercise,
            stressLevel = stress,
        )
    }

    private fun context(
        nights: List<InsightRules.NightRecord>,
        baselineState: PersonalBaseline.State = PersonalBaseline.State.READY,
        bands: Map<String, PersonalBaseline.MetricBand> = this.bands,
        sri: Double? = 80.0,
        sjl: Double? = null,
    ): InsightRules.EvalContext = InsightRules.EvalContext(
        nights = nights,
        baseline = PersonalBaseline.Baseline(baselineState, nights.size, bands),
        regularity = SleepRegularity.Result(
            state = if (sri == null) SleepRegularity.State.COLLECTING else SleepRegularity.State.READY,
            sri = sri,
            comparedEpochPairs = 1000,
            socialJetlagHours = sjl,
            socialJetlagFlagged = (sjl ?: 0.0) > SleepRegularity.SOCIAL_JETLAG_FLAG_HOURS,
            dayCount = nights.size,
        ),
    )

    /** A regular Garmin window (23:00 → 07:00 UTC) for day [i] of November 2026. */
    private fun window(i: Int, shiftMs: Long = 0): Pair<Long, Long> {
        val day = LocalDate.of(2026, 11, 1).plusDays(i.toLong()).toString()
        return ms(day, 23) + shiftMs to ms(LocalDate.of(2026, 11, 1).plusDays(i + 1L).toString(), 7) + shiftMs
    }

    // ---- R1 bedtime_variability ----

    private fun bedtimeSeries(count: Int, tonightHour: Int): List<InsightRules.NightRecord> =
        (0 until count).map { i ->
            val date = LocalDate.of(2026, 10, 20).plusDays(i.toLong()).toString()
            if (i == count - 1) {
                record(sessionId = 999, startDate = date, startHour = tonightHour)
            } else {
                record(sessionId = i.toLong(), startDate = date, startHour = 22, startMinute = 30)
            }
        }

    @Test
    fun `R1 triggers when tonight's bedtime scatter lands in the own-history worst tertile`() {
        val insights = InsightRules.evaluate(context(bedtimeSeries(12, tonightHour = 1)))
            .filter { it.ruleId == InsightRules.RuleIds.BEDTIME_VARIABILITY }
        assertEquals(1, insights.size)
        assertEquals(listOf(999L), insights.single().evidenceNightIds)
        assertEquals(ConfidenceTier.LOW, insights.single().confidenceTier)
    }

    @Test
    fun `R1 does not trigger on a consistent bedtime`() {
        // 12 nights all at 22:30 — tonight's std equals the historical stds (0), not above them.
        val nights = (0 until 12).map { i ->
            record(sessionId = i.toLong(), startDate = LocalDate.of(2026, 10, 20).plusDays(i.toLong()).toString())
        }
        assertTrue(InsightRules.evaluate(context(nights)).none { it.ruleId == InsightRules.RuleIds.BEDTIME_VARIABILITY })
    }

    @Test
    fun `R1 with insufficient history produces nothing (no absolute threshold ever)`() {
        // 10 nights = fewer than BEDTIME_WINDOW + MIN_BEDTIME_HISTORY_STDS historical windows.
        val insights = InsightRules.evaluate(context(bedtimeSeries(10, tonightHour = 1)))
            .filter { it.ruleId == InsightRules.RuleIds.BEDTIME_VARIABILITY }
        assertTrue(insights.isEmpty())
    }

    // ---- R2 sri_dip ----

    @Test
    fun `R2 triggers when tonight's SRI is in the own-history worst tertile`() {
        val nights = (0 until 10).map { i ->
            record(sessionId = i.toLong(), startDate = LocalDate.of(2026, 11, 1).plusDays(i.toLong()).toString(), garminWindow = window(i))
        } + record(
            sessionId = 999,
            startDate = "2026-11-11",
            garminWindow = window(10, shiftMs = 2 * 3600_000L),
        )
        val insights = InsightRules.evaluate(context(nights, sri = 85.0))
            .filter { it.ruleId == InsightRules.RuleIds.SRI_DIP }
        assertEquals(1, insights.size)
        assertEquals(InsightDirection.BELOW, insights.single().direction)
    }

    @Test
    fun `R2 does not trigger when regularity held steady`() {
        val nights = (0 until 11).map { i ->
            record(sessionId = i.toLong(), startDate = LocalDate.of(2026, 11, 1).plusDays(i.toLong()).toString(), garminWindow = window(i))
        }
        assertTrue(InsightRules.evaluate(context(nights)).none { it.ruleId == InsightRules.RuleIds.SRI_DIP })
    }

    @Test
    fun `R2 with fewer than 5 historical SRIs produces nothing`() {
        val nights = (0 until 8).map { i ->
            record(sessionId = i.toLong(), startDate = LocalDate.of(2026, 11, 1).plusDays(i.toLong()).toString(), garminWindow = window(i, shiftMs = 2 * 3600_000L))
        }
        assertTrue(InsightRules.evaluate(context(nights)).none { it.ruleId == InsightRules.RuleIds.SRI_DIP })
    }

    // ---- R3 social_jetlag ----

    @Test
    fun `R3 triggers above the convention threshold and passes below it`() {
        val tonight = record()
        assertTrue(
            InsightRules.evaluate(context(listOf(tonight), sjl = 2.0))
                .any { it.ruleId == InsightRules.RuleIds.SOCIAL_JETLAG },
        )
        assertTrue(
            InsightRules.evaluate(context(listOf(tonight), sjl = 0.5))
                .none { it.ruleId == InsightRules.RuleIds.SOCIAL_JETLAG },
        )
        assertTrue(
            InsightRules.evaluate(context(listOf(tonight), sjl = null))
                .none { it.ruleId == InsightRules.RuleIds.SOCIAL_JETLAG },
        )
    }

    // ---- R4 second_half_worse ----

    @Test
    fun `R4 triggers on second-half-dominant snoring - untagged variant is hedged`() {
        val start = ms("2026-11-02", 22)
        val insight = InsightRules.evaluate(
            context(
                listOf(record(snoreSpans = listOf(start + 5 * 3600_000L to start + 7 * 3600_000L))),
                baselineState = PersonalBaseline.State.COLLECTING,
            ),
        ).filter { it.ruleId == InsightRules.RuleIds.SECOND_HALF_WORSE }
        assertEquals(1, insight.size)
        assertEquals(InsightRules.CopyKeys.SECOND_HALF_UNTAGGED, insight.single().copyKey)
    }

    @Test
    fun `R4 alcohol-logged night uses the evidence-backed variant`() {
        val start = ms("2026-11-02", 22)
        val insight = InsightRules.evaluate(
            context(
                listOf(record(alcohol = true, snoreSpans = listOf(start + 5 * 3600_000L to start + 7 * 3600_000L))),
                baselineState = PersonalBaseline.State.COLLECTING,
            ),
        ).filter { it.ruleId == InsightRules.RuleIds.SECOND_HALF_WORSE }
        assertEquals(1, insight.size)
        assertEquals(InsightRules.CopyKeys.SECOND_HALF_ALCOHOL, insight.single().copyKey)
    }

    @Test
    fun `R4 does not trigger on uniform snoring or trivial second halves`() {
        val start = ms("2026-11-02", 22)
        // One span across the whole night — shares equal, ratio never reached.
        assertTrue(
            InsightRules.evaluate(
                context(
                    listOf(record(snoreSpans = listOf(start to start + 8 * 3600_000L))),
                    baselineState = PersonalBaseline.State.COLLECTING,
                ),
            ).none { it.ruleId == InsightRules.RuleIds.SECOND_HALF_WORSE },
        )
        // No snoring at all — below the Klaus 2021 trivial-snore floor.
        assertTrue(
            InsightRules.evaluate(
                context(listOf(record()), baselineState = PersonalBaseline.State.COLLECTING),
            ).none { it.ruleId == InsightRules.RuleIds.SECOND_HALF_WORSE },
        )
    }

    // ---- R5 baseline_excursion ----

    @Test
    fun `R5 fires per metric outside its band and marks the direction`() {
        val insights = InsightRules.evaluate(
            context(listOf(record(awakeMin = 60.0))),
        ).filter { it.ruleId == InsightRules.RuleIds.BASELINE_EXCURSION }
        assertEquals(1, insights.size)
        assertEquals(PersonalBaseline.Metrics.GARMIN_AWAKE_MIN, insights.single().metricName)
        assertEquals(InsightDirection.ABOVE, insights.single().direction)
    }

    @Test
    fun `R5 suppresses snoring-metric excursions on trivial-snore nights`() {
        // score far above band (z = 12) but snoreShare < 1% (Klaus 2021) — suppressed.
        val insights = InsightRules.evaluate(
            context(listOf(record(snoreShare = 0.005, score = 60, awakeMin = 45.0))),
        )
        assertTrue(insights.isEmpty())
    }

    @Test
    fun `R5 emits one insight per metric - two excursions yield two distinct metric names`() {
        // rMSSD below band (z = −4) + awake minutes above band (z = +3): both fire, each with
        // its own metricName — the UI keys cards on ruleId+metric, so this must never collapse.
        val insights = InsightRules.evaluate(
            context(listOf(record(awakeMin = 60.0, rMssd = 30.0))),
        ).filter { it.ruleId == InsightRules.RuleIds.BASELINE_EXCURSION }
        assertEquals(2, insights.size)
        assertEquals(
            setOf(PersonalBaseline.Metrics.GARMIN_AWAKE_MIN, PersonalBaseline.Metrics.RMSSD_MS),
            insights.map { it.metricName }.toSet(),
        )
    }

    @Test
    fun `R5 stays silent inside the band and while the baseline is COLLECTING`() {
        assertTrue(InsightRules.evaluate(context(listOf(record()))).isEmpty())
        assertTrue(
            InsightRules.evaluate(
                context(listOf(record(awakeMin = 60.0)), baselineState = PersonalBaseline.State.COLLECTING),
            ).isEmpty(),
        )
    }

    // ---- R6 alcohol_nights ----

    @Test
    fun `R6 triggers at 3-with 6-without at LOW and upgrades to MODERATE at 5-5`() {
        val with = (0 until 3).map { i -> record(sessionId = 100L + i, alcohol = true, awakeMin = 60.0) }
        val without = (0 until 6).map { i -> record(sessionId = 200L + i, awakeMin = 45.0) }
        val insight = InsightRules.evaluate(context(with + without))
            .filter { it.ruleId == InsightRules.RuleIds.ALCOHOL_NIGHTS }
        assertEquals(1, insight.size)
        assertEquals(ConfidenceTier.LOW, insight.single().confidenceTier)
        assertEquals(listOf(100L, 101L, 102L), insight.single().evidenceNightIds)

        val moderate = InsightRules.evaluate(
            context(
                (0 until 5).map { i -> record(sessionId = 100L + i, alcohol = true, awakeMin = 60.0) } +
                    (0 until 6).map { i -> record(sessionId = 200L + i, awakeMin = 45.0) },
            ),
        ).filter { it.ruleId == InsightRules.RuleIds.ALCOHOL_NIGHTS }
        assertEquals(1, moderate.size)
        assertEquals(ConfidenceTier.MODERATE, moderate.single().confidenceTier)
    }

    @Test
    fun `R6 insufficient groups and no-difference nights produce nothing`() {
        val with = (0 until 2).map { i -> record(sessionId = 100L + i, alcohol = true, awakeMin = 60.0) }
        val without = (0 until 6).map { i -> record(sessionId = 200L + i, awakeMin = 45.0) }
        assertTrue(InsightRules.evaluate(context(with + without)).none { it.ruleId == InsightRules.RuleIds.ALCOHOL_NIGHTS })
        val flat = (0 until 3).map { i -> record(sessionId = 100L + i, alcohol = true, awakeMin = 45.0) } +
            (0 until 6).map { i -> record(sessionId = 200L + i, awakeMin = 45.0) }
        assertTrue(InsightRules.evaluate(context(flat)).none { it.ruleId == InsightRules.RuleIds.ALCOHOL_NIGHTS })
    }

    @Test
    fun `R6 bout medians exclude trivial-snore nights while the awake trigger keeps full groups`() {
        // with-group: two normal nights (bouts 100 s / 300 s) plus one trivial-snore night with
        // a huge bout — the trivial night is suppressed from the bout median (snoring-metric
        // input, docs §Rule catalog) but still counts toward the awake-minute trigger.
        val with = listOf(
            record(sessionId = 100L, alcohol = true, awakeMin = 60.0, snoreShare = 0.10, longestBoutMs = 100_000L),
            record(sessionId = 101L, alcohol = true, awakeMin = 60.0, snoreShare = 0.12, longestBoutMs = 300_000L),
            record(sessionId = 102L, alcohol = true, awakeMin = 60.0, snoreShare = 0.005, longestBoutMs = 900_000L),
        ) + (0 until 6).map { i ->
            record(sessionId = 200L + i, awakeMin = 45.0, snoreShare = 0.10, longestBoutMs = 200_000L)
        }
        val insight = InsightRules.evaluate(context(with))
            .filter { it.ruleId == InsightRules.RuleIds.ALCOHOL_NIGHTS }
        assertEquals(1, insight.size)
        // bout median with = median(100 s, 300 s) = 200 s — the trivial night's 900 s never enters
        assertTrue(insight.single().detailJson.contains("\"bout_median_with_ms\":200000.0"))
        assertTrue(insight.single().detailJson.contains("\"nights_with\":3"))
    }

    // ---- R7 caffeine_nights ----

    @Test
    fun `R7 triggers on awake-time or sleep-duration deltas and needs both groups`() {
        val trigger = (0 until 3).map { i -> record(sessionId = 100L + i, caffeine = true, awakeMin = 60.0) } +
            (0 until 3).map { i -> record(sessionId = 200L + i, awakeMin = 45.0) }
        assertEquals(
            1,
            InsightRules.evaluate(context(trigger)).count { it.ruleId == InsightRules.RuleIds.CAFFEINE_NIGHTS },
        )
        val insufficient = trigger.take(2) + trigger.drop(3).take(2)
        assertTrue(InsightRules.evaluate(context(insufficient)).none { it.ruleId == InsightRules.RuleIds.CAFFEINE_NIGHTS })
    }

    @Test
    fun `R7 triggers on shorter vendor sleep even with equal awake time`() {
        val nights = (0 until 3).map { i -> record(sessionId = 100L + i, caffeine = true, sleepMin = 360.0) } +
            (0 until 3).map { i -> record(sessionId = 200L + i, sleepMin = 400.0) }
        assertEquals(
            1,
            InsightRules.evaluate(context(nights)).count { it.ruleId == InsightRules.RuleIds.CAFFEINE_NIGHTS },
        )
    }

    // ---- R8 exercise_evening ----

    @Test
    fun `R8 flags an exercise night only when awake time exceeds the band`() {
        assertTrue(
            InsightRules.evaluate(context(listOf(record(exercise = true, awakeMin = 60.0))))
                .any { it.ruleId == InsightRules.RuleIds.EXERCISE_EVENING },
        )
        assertTrue(
            InsightRules.evaluate(context(listOf(record(exercise = true, awakeMin = 45.0))))
                .none { it.ruleId == InsightRules.RuleIds.EXERCISE_EVENING },
        )
        assertTrue(
            InsightRules.evaluate(context(listOf(record(awakeMin = 60.0))))
                .none { it.ruleId == InsightRules.RuleIds.EXERCISE_EVENING },
        )
        // Association, not mechanism: no rule while the baseline is still COLLECTING.
        assertTrue(
            InsightRules.evaluate(
                context(listOf(record(exercise = true, awakeMin = 60.0)), baselineState = PersonalBaseline.State.COLLECTING),
            ).none { it.ruleId == InsightRules.RuleIds.EXERCISE_EVENING },
        )
    }

    // ---- R9 stress_high ----

    @Test
    fun `R9 requires both markers - rMSSD below band AND awake above band`() {
        val both = record(stress = 4, rMssd = 30.0, awakeMin = 60.0)
        val insights = InsightRules.evaluate(context(listOf(both)))
            .filter { it.ruleId == InsightRules.RuleIds.STRESS_HIGH }
        assertEquals(1, insights.size)
        assertEquals(ConfidenceTier.LOW, insights.single().confidenceTier)
        // One marker missing → nothing.
        assertTrue(
            InsightRules.evaluate(context(listOf(record(stress = 4, rMssd = 40.0, awakeMin = 60.0))))
                .none { it.ruleId == InsightRules.RuleIds.STRESS_HIGH },
        )
        assertTrue(
            InsightRules.evaluate(context(listOf(record(stress = 3, rMssd = 30.0, awakeMin = 60.0))))
                .none { it.ruleId == InsightRules.RuleIds.STRESS_HIGH },
        )
        // Enough stress-tagged nights in the window → MODERATE.
        val window = (0 until 3).map { record(sessionId = 300L + it, stress = 4) } + both.copy(sessionId = 999)
        assertEquals(
            ConfidenceTier.MODERATE,
            InsightRules.evaluate(context(window))
                .first { it.ruleId == InsightRules.RuleIds.STRESS_HIGH }.confidenceTier,
        )
    }

    // ---- secondHalfShares helper + cross-cutting guarantees ----

    @Test
    fun `secondHalfShares clips spans at the midpoint and uses each half's own duration`() {
        val start = 0L
        val end = 8 * 3600_000L
        assertEquals(
            0.25 to 0.25,
            InsightRules.secondHalfShares(listOf(start to start + 3600_000L, start + 7 * 3600_000L to end), start, end),
        )
        // One span crossing the midpoint counts on BOTH sides, clipped.
        assertEquals(
            0.25 to 0.25,
            InsightRules.secondHalfShares(listOf(start + 3 * 3600_000L to start + 5 * 3600_000L), start, end),
        )
        assertNull(InsightRules.secondHalfShares(emptyList(), start, start))
    }

    @Test
    fun `every produced insight stays below HIGH confidence and carries its rule id`() {
        val nights = bedtimeSeries(12, tonightHour = 1) +
            (0 until 5).map { i -> record(sessionId = 100L + i, alcohol = true, awakeMin = 60.0, startDate = LocalDate.of(2026, 10, 1).plusDays(i.toLong()).toString()) } +
            (0 until 6).map { i -> record(sessionId = 200L + i, awakeMin = 45.0, startDate = LocalDate.of(2026, 10, 10).plusDays(i.toLong()).toString()) }
        val insights = InsightRules.evaluate(context(nights, sjl = 2.0))
        assertTrue(insights.isNotEmpty())
        insights.forEach {
            assertTrue(it.confidenceTier == ConfidenceTier.LOW || it.confidenceTier == ConfidenceTier.MODERATE)
            assertTrue(it.evidenceNightIds.isNotEmpty())
        }
    }

    @Test
    fun `evaluate on an empty eligible list returns no insights without consulting tonight`() {
        // The repository filters eligibility upstream; evaluate on an empty list must not crash.
        assertTrue(InsightRules.evaluate(context(emptyList())).isEmpty())
    }
}
