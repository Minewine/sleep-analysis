package com.sleepanalysis.domain.insights

import com.sleepanalysis.domain.scoring.SnoreMath
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One produced insight (docs/INSIGHTS.md §Rule catalog). Deterministic; every rule stays in
 * the [ConfidenceTier.LOW]/[ConfidenceTier.MODERATE] tier — NEVER HIGH (see InsightRules KDoc).
 *
 * @param ruleId      stable id, == the id in docs/INSIGHTS.md (drift-tested by InsightsDocTest)
 * @param copyKey     string-resource NAME of the user-facing claim (drift-tested against strings.xml)
 * @param metricName  [PersonalBaseline.Metrics]-style metric the insight is about (null: night-level)
 * @param direction   ABOVE/BELOW relative to the personal baseline, ASSOCIATION for group comparisons
 * @param evidenceNightIds the session ids the claim rests on (tonight; group rules: the tagged nights)
 * @param detailJson  machine-readable evidence (medians, z-scores, counts) — shown expanded, never rendered as a measurement
 */
data class Insight(
    val ruleId: String,
    val copyKey: String,
    val metricName: String?,
    val direction: InsightDirection,
    val evidenceNightIds: List<Long>,
    val confidenceTier: ConfidenceTier,
    val detailJson: String,
)

enum class ConfidenceTier { LOW, MODERATE }

enum class InsightDirection { ABOVE, BELOW, ASSOCIATION }

/**
 * Deterministic insight rules over eligible nights (docs/INSIGHTS.md §Rule catalog). The
 * evidence-review basis is cited per rule below; the common guardrails:
 *  - never [ConfidenceTier.HIGH]-grade certainty exists in this enum — every claim is framed
 *    "your recordings suggest", never diagnosis, never arousal/apnea language (events ≠
 *    arousals, pauses ≠ apneas, docs/MEASUREMENT_RELIABILITY.md §2);
 *  - all SNORING-metric rules require eligible nights only (validity grade A/B, registry-matched
 *    versions — [PersonalBaseline.eligibleNights] runs upstream) and suppress trivial-snore
 *    nights (< [TRIVIAL_SNORE_SHARE] of capture) per Klaus 2021 (IJERPH): phone-mic snoring
 *    reliability collapses for light snorers;
 *  - R1 uses NO absolute bedtime threshold (NSF 2023 endorses none) — triggers are the user's
 *    own worst tertile, descriptive only; the "~1 h stable / ~3 h erratic" copy is the
 *    descriptive Windred quintile context, not a threshold;
 *  - R2 uses association wording only: the SRI's dose–response claim is contested in the
 *    literature, so no rule ever says "more regular is always better";
 *  - R6/R7 compare SELF-LOGGED factor groups — the logged flag says the user logged the
 *    factor that day, nothing about amount (ALCOHOL units are not parsed) or timing (EXERCISE
 *    has no time-of-day), and no safe-amount/cutoff claim is ever made (Gardiner 2025).
 *
 * All thresholds that are not literature-cited are APP CONVENTIONS and named so in their KDoc.
 */
object InsightRules {

    object RuleIds {
        const val BEDTIME_VARIABILITY = "bedtime_variability"
        const val SRI_DIP = "sri_dip"
        const val SOCIAL_JETLAG = "social_jetlag"
        const val SECOND_HALF_WORSE = "second_half_worse"
        const val BASELINE_EXCURSION = "baseline_excursion"
        const val ALCOHOL_NIGHTS = "alcohol_nights"
        const val CAFFEINE_NIGHTS = "caffeine_nights"
        const val EXERCISE_EVENING = "exercise_evening"
        const val STRESS_HIGH = "stress_high"
    }

    object CopyKeys {
        const val BEDTIME_VARIABILITY = "insight_rule_bedtime_variability"
        const val SRI_DIP = "insight_rule_sri_dip"
        const val SOCIAL_JETLAG = "insight_rule_social_jetlag"
        const val SECOND_HALF_ALCOHOL = "insight_rule_second_half_alcohol"
        const val SECOND_HALF_UNTAGGED = "insight_rule_second_half_untagged"
        const val BASELINE_EXCURSION = "insight_rule_baseline_excursion"
        const val ALCOHOL_NIGHTS = "insight_rule_alcohol_nights"
        const val CAFFEINE_NIGHTS = "insight_rule_caffeine_nights"
        const val EXERCISE_EVENING = "insight_rule_exercise_evening"
        const val STRESS_HIGH = "insight_rule_stress_high"
    }

    /** Klaus 2021 trivial-snore suppression floor (share of captured analysis time). */
    const val TRIVIAL_SNORE_SHARE = 0.01

    /** R4: second-half share must exceed this multiple of the first-half share (app convention). */
    const val SECOND_HALF_RATIO = 1.3

    /** R5/R8/R9: |robust z| outside the personal band (docs/INSIGHTS.md §Baseline method). */
    const val ROBUST_Z_EXCURSION = 2.5

    /** R1: rolling window of in-bed start times (nights) whose std is compared (app convention). */
    const val BEDTIME_WINDOW_NIGHTS = 7

    /** R1: historical rolling stds needed to form tertiles (app convention). */
    const val MIN_BEDTIME_HISTORY_STDS = 5

    /** R2: historical rolling SRIs needed to form tertiles (app convention). */
    const val MIN_SRI_HISTORY = 5

    /** R6: tagged/untagged group minimums (app convention; MODERATE needs ≥5 each). */
    const val ALCOHOL_MIN_WITH = 3
    const val ALCOHOL_MIN_WITHOUT = 6
    const val GROUP_MODERATE_MIN = 5

    /** R7: caffeine/none group minimums (app convention). */
    const val CAFFEINE_MIN_PER_GROUP = 3

    /** R9: stress-tagged nights in the window for MODERATE confidence (app convention). */
    const val STRESS_MODERATE_MIN_NIGHTS = 3

    /** High-stress self-log threshold (user's own 1–5 scale, DailyFactor.STRESS). */
    const val STRESS_HIGH_LEVEL = 4

    /** One eligible night as the rules see it — assembled by the caller from stored rows. */
    data class NightRecord(
        val sessionId: Long,
        val startedAtMs: Long,
        val endedAtMs: Long,
        /** ISO yyyy-MM-dd local start date. */
        val nightDate: String,
        /** Session time zone id — clock-time rules (bedtime, SJL grouping) need the local wall. */
        val zoneId: String,
        /** NIGHT_VALIDITY grade letter; carried for transparency — eligibility ran upstream. */
        val grade: String?,
        val detectorVersion: String?,
        val scoreVersion: String?,
        /** Label-aware snore spans (startMs, endMs) — SnoreMath.eventsCountingAsSnore, already filtered. */
        val snoreSpans: List<Pair<Long, Long>>,
        /** Stored SNORE_SHARE_OF_NIGHT / 100 (0..1 of captured analysis time); null when unknown. */
        val snoreShare: Double?,
        val longestBoutMs: Long?,
        val score: Int?,
        /** Garmin device-reported values for the wake date; null = not imported / absent. */
        val garminAwakeMinutes: Double?,
        val garminSleepMinutes: Double?,
        val rMssdMs: Double?,
        /** Garmin nightly sleep window (startMs, endMs); null = not imported / unparseable. */
        val garminWindow: Pair<Long, Long>?,
        // Self-logged factors (docs/DATA_MODEL.md §3.6): the logged FLAG only — no units,
        // no timing, no intensity are recorded by the factor log, and no rule pretends otherwise.
        val alcoholLogged: Boolean,
        val caffeineAfter14Logged: Boolean,
        val exerciseLogged: Boolean,
        /** Self-logged stress 1–5; null when not logged. */
        val stressLevel: Int?,
    )

    data class EvalContext(
        /** Eligible nights (grade A/B, registry-matched versions), OLDEST FIRST; last = tonight. */
        val nights: List<NightRecord>,
        val baseline: PersonalBaseline.Baseline,
        val regularity: SleepRegularity.Result,
    )

    /** Runs every rule over [ctx]; returns the triggered insights (empty when nothing applies). */
    fun evaluate(ctx: EvalContext): List<Insight> {
        if (ctx.nights.isEmpty()) return emptyList() // no eligible nights — no rule sees anything
        return buildList {
            bedtimeVariability(ctx)?.let(::add)
            sriDip(ctx)?.let(::add)
            socialJetlag(ctx)?.let(::add)
            secondHalfWorse(ctx)?.let(::add)
            addAll(baselineExcursions(ctx))
            alcoholNights(ctx)?.let(::add)
            caffeineNights(ctx)?.let(::add)
            exerciseEvening(ctx)?.let(::add)
            stressHigh(ctx)?.let(::add)
        }
    }

    /**
     * R1 bedtime_variability: tonight's rolling std of in-bed start clock-times (anchored to
     * the preceding local noon, so 01:00 and 23:30 starts compare honestly) against the user's
     * own worst tertile of historical rolling stds — descriptive only, NO absolute threshold
     * (NSF 2023 endorses none). Insufficient evidence (fewer than [MIN_BEDTIME_HISTORY_STDS]
     * historical stds, i.e. < [BEDTIME_WINDOW_NIGHTS] + [MIN_BEDTIME_HISTORY_STDS] eligible
     * nights) produces nothing.
     */
    internal fun bedtimeVariability(ctx: EvalContext): Insight? {
        val minutes = ctx.nights.map { anchoredBedMinutes(it.startedAtMs, ZoneId.of(it.zoneId)) }
        if (minutes.size < BEDTIME_WINDOW_NIGHTS + MIN_BEDTIME_HISTORY_STDS) return null
        val last = minutes.size - 1
        val historical = (BEDTIME_WINDOW_NIGHTS - 1 until last)
            .mapNotNull { rollingStd(minutes, it) }
            .sorted()
        if (historical.size < MIN_BEDTIME_HISTORY_STDS) return null
        val tonight = rollingStd(minutes, last) ?: return null
        val cut = historical[historical.size - 1 - historical.size / 3]
        if (tonight <= cut) return null
        val tonightRecord = ctx.nights.last()
        return Insight(
            ruleId = RuleIds.BEDTIME_VARIABILITY,
            copyKey = CopyKeys.BEDTIME_VARIABILITY,
            metricName = "bedtime_std_min",
            direction = InsightDirection.ASSOCIATION,
            evidenceNightIds = listOf(tonightRecord.sessionId),
            confidenceTier = ConfidenceTier.LOW,
            detailJson = json(
                "tonight_std_min" to round1(tonight),
                "historical_median_std_min" to round1(PersonalBaseline.median(historical)),
                "historical_windows" to historical.size,
                "threshold" to "worst_tertile_of_own_history",
            ),
        )
    }

    /**
     * R2 sri_dip: tonight's rolling SRI in the user's own worst tertile vs the historical
     * rolling SRIs of the same growing window. Association wording only (dose–response
     * contested). Needs ≥ [MIN_SRI_HISTORY] historical SRIs and a computable tonight-SRI.
     */
    internal fun sriDip(ctx: EvalContext): Insight? {
        val windows = ctx.nights.mapNotNull { record ->
            record.garminWindow?.let { SleepRegularity.NightWindow(record.nightDate, it.first, it.second) }
        }
        if (windows.size < SleepRegularity.MIN_SRI_DAYS + MIN_SRI_HISTORY) return null
        val last = windows.size - 1
        val historical = (SleepRegularity.MIN_SRI_DAYS - 1 until last)
            .mapNotNull { i -> SleepRegularity.sleepRegularIndex(windows.take(i + 1))?.index }
            .sorted()
        if (historical.size < MIN_SRI_HISTORY) return null
        val tonight = SleepRegularity.sleepRegularIndex(windows)?.index ?: return null
        val cut = historical[historical.size / 3]
        if (tonight >= cut) return null
        return Insight(
            ruleId = RuleIds.SRI_DIP,
            copyKey = CopyKeys.SRI_DIP,
            metricName = "sri",
            direction = InsightDirection.BELOW,
            evidenceNightIds = listOf(ctx.nights.last().sessionId),
            confidenceTier = ConfidenceTier.LOW,
            detailJson = json(
                "tonight_sri" to round1(tonight),
                "historical_median_sri" to round1(PersonalBaseline.median(historical)),
                "historical_windows" to historical.size,
                "threshold" to "worst_tertile_of_own_history",
                "caveat" to "association_only_dose_response_contested",
            ),
        )
    }

    /** R3 social_jetlag: SJL > 1 h (CONVENTION — the app cannot know the work schedule). */
    internal fun socialJetlag(ctx: EvalContext): Insight? {
        val sjl = ctx.regularity.socialJetlagHours ?: return null
        if (!ctx.regularity.socialJetlagFlagged) return null
        return Insight(
            ruleId = RuleIds.SOCIAL_JETLAG,
            copyKey = CopyKeys.SOCIAL_JETLAG,
            metricName = "social_jetlag_h",
            direction = InsightDirection.ABOVE,
            evidenceNightIds = listOf(ctx.nights.last().sessionId),
            confidenceTier = ConfidenceTier.LOW,
            detailJson = json(
                "social_jetlag_h" to round1(sjl),
                "label" to "convention",
                "caveat" to "approximate_no_work_schedule_tag",
            ),
        )
    }

    /**
     * R4 second_half_worse: tonight's second-half snore share > [SECOND_HALF_RATIO] × first
     * half (with the second half above the trivial-snore floor). TWO copy variants: an
     * alcohol-logged night gets the evidence-backed pattern copy (first-half deep-sleep
     * suppression then REM-rebound disturbance — Ebrahim 2013, Alcoholism: Clinical &
     * Experimental Research); an untagged night gets the hedged copy (late-night REM brings
     * irregular breathing and awakenings — normal, not a fault). The rule tests the pattern,
     * never the mechanism.
     */
    internal fun secondHalfWorse(ctx: EvalContext): Insight? {
        val tonight = ctx.nights.last()
        val shares = secondHalfShares(tonight.snoreSpans, tonight.startedAtMs, tonight.endedAtMs) ?: return null
        val (first, second) = shares
        if (second < TRIVIAL_SNORE_SHARE || second <= SECOND_HALF_RATIO * first) return null
        val alcohol = tonight.alcoholLogged
        return Insight(
            ruleId = RuleIds.SECOND_HALF_WORSE,
            copyKey = if (alcohol) CopyKeys.SECOND_HALF_ALCOHOL else CopyKeys.SECOND_HALF_UNTAGGED,
            metricName = "snore_share_second_half",
            direction = InsightDirection.ABOVE,
            evidenceNightIds = listOf(tonight.sessionId),
            confidenceTier = ConfidenceTier.LOW,
            detailJson = json(
                "first_half_share" to round3(first),
                "second_half_share" to round3(second),
                "alcohol_logged" to alcohol,
            ),
        )
    }

    /**
     * R5 baseline_excursion: any personal-baseline metric tonight outside its robust band
     * (|robust z| > [ROBUST_Z_EXCURSION]). Requires the baseline READY (≥10 eligible nights —
     * an excursion against 3 nights is noise, not an insight). Snoring-metric excursions are
     * suppressed on a trivial-snore night (Klaus 2021 floor). Both directions surface, each
     * with its own direction marker.
     */
    internal fun baselineExcursions(ctx: EvalContext): List<Insight> {
        if (ctx.baseline.state != PersonalBaseline.State.READY) return emptyList()
        val tonight = ctx.nights.last()
        val trivialSnore = (tonight.snoreShare ?: 1.0) < TRIVIAL_SNORE_SHARE
        val candidates = listOf(
            Triple(PersonalBaseline.Metrics.SNORE_SHARE, tonight.snoreShare, PersonalBaseline.FLOOR_SNORE_SHARE),
            Triple(PersonalBaseline.Metrics.LONGEST_BOUT_MS, tonight.longestBoutMs?.toDouble(), PersonalBaseline.FLOOR_LONGEST_BOUT_MS),
            Triple(PersonalBaseline.Metrics.SCORE, tonight.score?.toDouble(), PersonalBaseline.FLOOR_SCORE),
            Triple(PersonalBaseline.Metrics.GARMIN_AWAKE_MIN, tonight.garminAwakeMinutes, PersonalBaseline.FLOOR_GARMIN_AWAKE_MIN),
            Triple(PersonalBaseline.Metrics.RMSSD_MS, tonight.rMssdMs, PersonalBaseline.FLOOR_RMSSD_MS),
        )
        return candidates.mapNotNull { (metric, value, floor) ->
            val v = value ?: return@mapNotNull null
            if (metric != PersonalBaseline.Metrics.GARMIN_AWAKE_MIN &&
                metric != PersonalBaseline.Metrics.RMSSD_MS && trivialSnore
            ) {
                return@mapNotNull null
            }
            val band = ctx.baseline.bands[metric] ?: return@mapNotNull null
            val z = band.robustZ(v, floor)
            if (abs(z) <= ROBUST_Z_EXCURSION) return@mapNotNull null
            Insight(
                ruleId = RuleIds.BASELINE_EXCURSION,
                copyKey = CopyKeys.BASELINE_EXCURSION,
                metricName = metric,
                direction = if (z > 0) InsightDirection.ABOVE else InsightDirection.BELOW,
                evidenceNightIds = listOf(tonight.sessionId),
                confidenceTier = ConfidenceTier.LOW,
                detailJson = json(
                    "metric" to metric,
                    "value" to round3(v),
                    "band_low" to round3(band.low),
                    "band_high" to round3(band.high),
                    "robust_z" to round1(z),
                ),
            )
        }
    }

    /**
     * R6 alcohol_nights: ALCOHOL-logged eligible nights vs the rest on Garmin awake minutes
     * and longest bout — a group ASSOCIATION. The logged flag carries no amount (units are
     * not parsed) and no safe-hours cutoff exists anywhere in the literature (Gardiner 2025)
     * — the copy never makes one. Trigger needs ≥ [ALCOHOL_MIN_WITH] tagged and
     * ≥ [ALCOHOL_MIN_WITHOUT] untagged nights with Garmin data; MODERATE at ≥ [GROUP_MODERATE_MIN]
     * each, else LOW.
     */
    internal fun alcoholNights(ctx: EvalContext): Insight? {
        val with = ctx.nights.filter { it.alcoholLogged && it.garminAwakeMinutes != null }
        val without = ctx.nights.filter { !it.alcoholLogged && it.garminAwakeMinutes != null }
        if (with.size < ALCOHOL_MIN_WITH || without.size < ALCOHOL_MIN_WITHOUT) return null
        val awakeWith = PersonalBaseline.median(with.map { it.garminAwakeMinutes!! })
        val awakeWithout = PersonalBaseline.median(without.map { it.garminAwakeMinutes!! })
        if (awakeWith <= awakeWithout) return null
        // Bout medians follow the docs' trivial-snore suppression (snoring-metric inputs are
        // non-trivial nights of the group only); the awake-minute trigger keeps the full groups.
        fun boutMedian(group: List<NightRecord>): Double? {
            val nonTrivial = group.filter { (it.snoreShare ?: 1.0) >= TRIVIAL_SNORE_SHARE }
            val values = nonTrivial.mapNotNull { it.longestBoutMs }
            return if (values.isNotEmpty() && values.size == nonTrivial.size) {
                PersonalBaseline.median(values.map { it.toDouble() })
            } else {
                null
            }
        }
        val boutWith = boutMedian(with)
        val boutWithout = boutMedian(without)
        return Insight(
            ruleId = RuleIds.ALCOHOL_NIGHTS,
            copyKey = CopyKeys.ALCOHOL_NIGHTS,
            metricName = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            direction = InsightDirection.ASSOCIATION,
            evidenceNightIds = with.map { it.sessionId },
            confidenceTier = if (with.size >= GROUP_MODERATE_MIN && without.size >= GROUP_MODERATE_MIN) {
                ConfidenceTier.MODERATE
            } else {
                ConfidenceTier.LOW
            },
            detailJson = json(
                "awake_median_with_min" to round1(awakeWith),
                "awake_median_without_min" to round1(awakeWithout),
                "bout_median_with_ms" to boutWith?.let(::round1),
                "bout_median_without_ms" to boutWithout?.let(::round1),
                "nights_with" to with.size,
                "nights_without" to without.size,
                "caveat" to "logged_flag_no_amount_no_safe_cutoff",
            ),
        )
    }

    /**
     * R7 caffeine_nights: CAFFEINE_AFTER_14-logged nights vs the rest on Garmin awake minutes
     * and sleep duration (research context: meta-analytic TST −45 min — Gardiner 2023, Sleep
     * Medicine Reviews; the copy reports the user's own deltas, never the meta-analytic number
     * as if it were theirs). The flag carries no dose; LOW confidence only.
     */
    internal fun caffeineNights(ctx: EvalContext): Insight? {
        val with = ctx.nights.filter { it.caffeineAfter14Logged && it.garminAwakeMinutes != null }
        val without = ctx.nights.filter { !it.caffeineAfter14Logged && it.garminAwakeMinutes != null }
        if (with.size < CAFFEINE_MIN_PER_GROUP || without.size < CAFFEINE_MIN_PER_GROUP) return null
        val awakeWith = PersonalBaseline.median(with.map { it.garminAwakeMinutes!! })
        val awakeWithout = PersonalBaseline.median(without.map { it.garminAwakeMinutes!! })
        val sleepWith = with.mapNotNull { it.garminSleepMinutes }.takeIf { it.size == with.size }
            ?.let { PersonalBaseline.median(it) }
        val sleepWithout = without.mapNotNull { it.garminSleepMinutes }.takeIf { it.size == without.size }
            ?.let { PersonalBaseline.median(it) }
        val awakeWorse = awakeWith > awakeWithout
        val sleepShorter = sleepWith != null && sleepWithout != null && sleepWith < sleepWithout
        if (!awakeWorse && !sleepShorter) return null
        return Insight(
            ruleId = RuleIds.CAFFEINE_NIGHTS,
            copyKey = CopyKeys.CAFFEINE_NIGHTS,
            metricName = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            direction = InsightDirection.ASSOCIATION,
            evidenceNightIds = with.map { it.sessionId },
            confidenceTier = ConfidenceTier.LOW,
            detailJson = json(
                "awake_median_with_min" to round1(awakeWith),
                "awake_median_without_min" to round1(awakeWithout),
                "sleep_median_with_min" to sleepWith?.let(::round1),
                "sleep_median_without_min" to sleepWithout?.let(::round1),
                "nights_with" to with.size,
                "nights_without" to without.size,
                "caveat" to "logged_flag_no_dose",
            ),
        )
    }

    /**
     * R8 exercise_evening: an EXERCISE-logged night whose Garmin awake minutes sit above the
     * personal band. HONEST LIMIT (KDoc contract): the factor log records only the FACT of
     * exercise — no time-of-day, no intensity — so the <1 h-before-bed mechanism from
     * exercise-sleep research cannot be tested; the rule tests the association only.
     */
    internal fun exerciseEvening(ctx: EvalContext): Insight? {
        if (ctx.baseline.state != PersonalBaseline.State.READY) return null
        val tonight = ctx.nights.last()
        if (!tonight.exerciseLogged) return null
        val awake = tonight.garminAwakeMinutes ?: return null
        val band = ctx.baseline.bands[PersonalBaseline.Metrics.GARMIN_AWAKE_MIN] ?: return null
        val z = band.robustZ(awake, PersonalBaseline.FLOOR_GARMIN_AWAKE_MIN)
        if (z <= ROBUST_Z_EXCURSION) return null
        return Insight(
            ruleId = RuleIds.EXERCISE_EVENING,
            copyKey = CopyKeys.EXERCISE_EVENING,
            metricName = PersonalBaseline.Metrics.GARMIN_AWAKE_MIN,
            direction = InsightDirection.ABOVE,
            evidenceNightIds = listOf(tonight.sessionId),
            confidenceTier = ConfidenceTier.LOW,
            detailJson = json(
                "awake_minutes" to round1(awake),
                "band_high" to round1(band.high),
                "robust_z" to round1(z),
                "caveat" to "association_only_no_timing_no_intensity",
            ),
        )
    }

    /**
     * R9 stress_high: nights the user logged STRESS ≥ 4 with wrist rMSSD below the personal
     * band AND Garmin awake minutes above it (Kim & Dimsdale 2007, Psychosomatic Medicine —
     * stress and sleep physiology; Guo 2022 — wrist-PPV rMSSD context). Both markers must be
     * present — one alone is a coin flip at this sample size. MODERATE at ≥
     * [STRESS_MODERATE_MIN_NIGHTS] stress-tagged nights in the window, else LOW.
     */
    internal fun stressHigh(ctx: EvalContext): Insight? {
        if (ctx.baseline.state != PersonalBaseline.State.READY) return null
        val tonight = ctx.nights.last()
        if ((tonight.stressLevel ?: 0) < STRESS_HIGH_LEVEL) return null
        val rMssd = tonight.rMssdMs ?: return null
        val awake = tonight.garminAwakeMinutes ?: return null
        val rMssdBand = ctx.baseline.bands[PersonalBaseline.Metrics.RMSSD_MS] ?: return null
        val awakeBand = ctx.baseline.bands[PersonalBaseline.Metrics.GARMIN_AWAKE_MIN] ?: return null
        val zRmssd = rMssdBand.robustZ(rMssd, PersonalBaseline.FLOOR_RMSSD_MS)
        val zAwake = awakeBand.robustZ(awake, PersonalBaseline.FLOOR_GARMIN_AWAKE_MIN)
        if (zRmssd >= -ROBUST_Z_EXCURSION || zAwake <= ROBUST_Z_EXCURSION) return null
        val stressNights = ctx.nights.count { (it.stressLevel ?: 0) >= STRESS_HIGH_LEVEL }
        return Insight(
            ruleId = RuleIds.STRESS_HIGH,
            copyKey = CopyKeys.STRESS_HIGH,
            metricName = PersonalBaseline.Metrics.RMSSD_MS,
            direction = InsightDirection.ASSOCIATION,
            evidenceNightIds = listOf(tonight.sessionId),
            confidenceTier = if (stressNights >= STRESS_MODERATE_MIN_NIGHTS) ConfidenceTier.MODERATE else ConfidenceTier.LOW,
            detailJson = json(
                "stress_level" to tonight.stressLevel,
                "rmssd_ms" to round1(rMssd),
                "rmssd_band_low" to round1(rMssdBand.low),
                "rmssd_robust_z" to round1(zRmssd),
                "awake_minutes" to round1(awake),
                "awake_band_high" to round1(awakeBand.high),
                "awake_robust_z" to round1(zAwake),
                "stress_nights_in_window" to stressNights,
            ),
        )
    }

    /**
     * First vs second half union snore SHARES of [windowStartMs]..[windowEndMs] (each half's
     * own duration as denominator — a shorter half is not more snoring). Intervals are clipped
     * to their half; degenerate windows yield null. Pure — shared with the report's future
     * half-by-half views.
     */
    fun secondHalfShares(
        intervals: List<Pair<Long, Long>>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Pair<Double, Double>? {
        if (windowEndMs <= windowStartMs) return null
        val midpoint = windowStartMs + (windowEndMs - windowStartMs) / 2
        val firstMs = (midpoint - windowStartMs).toDouble()
        val secondMs = (windowEndMs - midpoint).toDouble()
        if (firstMs <= 0 || secondMs <= 0) return null
        val first = SnoreMath.unionDurationMs(intervals.map { clipTo(it, windowStartMs, midpoint) }) / firstMs
        val second = SnoreMath.unionDurationMs(intervals.map { clipTo(it, midpoint, windowEndMs) }) / secondMs
        return first to second
    }

    private fun clipTo(interval: Pair<Long, Long>, start: Long, end: Long): Pair<Long, Long> =
        maxOf(interval.first, start) to minOf(interval.second, end)

    /**
     * In-bed start time anchored to the preceding local NOON in minutes (0..1439 → 0..1439
     * after that noon): 22:30 → 630, 01:00 → 780 — evening and after-midnight starts compare
     * without a midnight wrap distorting the spread.
     */
    private fun anchoredBedMinutes(epochMs: Long, zone: ZoneId): Int {
        val minutesOfDay = Instant.ofEpochMilli(epochMs).atZone(zone).let { it.hour * 60 + it.minute }
        return (minutesOfDay - 720 + 1440) % 1440
    }

    /** Sample std of the [InsightRules.BEDTIME_WINDOW_NIGHTS]-night window ending at (inclusive) [endIdx]. */
    private fun rollingStd(minutes: List<Int>, endIdx: Int): Double? {
        val window = minutes.subList(endIdx + 1 - BEDTIME_WINDOW_NIGHTS, endIdx + 1)
        if (window.size < BEDTIME_WINDOW_NIGHTS) return null
        val mean = window.average()
        return sqrt(window.sumOf { (it - mean) * (it - mean) } / window.size)
    }

    private fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

    private fun round3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0

    /** Compact hand-built JSON (same discipline as NightValidity.notes; key order stable). */
    private fun json(vararg pairs: Pair<String, Any?>): String = pairs.joinToString(",", "{", "}") { (key, value) ->
        val rendered = when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else -> value.toString()
        }
        "\"$key\":$rendered"
    }
}

/**
 * The assembled insights bundle the screen renders (docs/INSIGHTS.md): the personal baseline,
 * the regularity metrics and the triggered rules over the SAME eligible night set — one
 * eligibility decision, never per-card disagreement.
 */
data class InsightsReport(
    val baseline: PersonalBaseline.Baseline,
    val regularity: SleepRegularity.Result,
    val insights: List<Insight>,
    /** Eligible nights the analysis ran on (grade ≥ B, registry-matched versions, window cut). */
    val eligibleCount: Int,
    /** Finalized nights examined before the eligibility gate (the collecting copy's denominator). */
    val nightCount: Int,
)
