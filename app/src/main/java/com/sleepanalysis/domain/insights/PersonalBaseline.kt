package com.sleepanalysis.domain.insights

/**
 * Personal baseline (docs/INSIGHTS.md §Baseline method): per-metric robust bands over the
 * user's own eligible nights — median ± 2·1.4826·MAD — so every insight compares tonight
 * against the user's OWN usual range, never against a population norm or a clinical range.
 *
 * Eligibility (docs/INSIGHTS.md §Eligibility): validity grade A or B (grade ≥ B — the night's
 * capture is trustworthy) AND the night's detector + score versions match the CURRENT
 * registry versions (schema v12, docs/DATA_MODEL.md §3.8). The caller resolves the current
 * versions ([com.sleepanalysis.data.repo.SessionRepository.currentModelVersions]); the pure
 * function applies the comparison — a null current version matches only null night versions,
 * so a caller that failed to resolve cannot silently broaden eligibility. The kept window is
 * the LAST [WINDOW_NIGHTS] eligible nights (rolling; older eligible nights never leak in).
 *
 * State: COLLECTING until [MIN_READY_NIGHTS] eligible nights exist (below that, bands exist
 * for the UI's transparency but the state says they are still forming; rules that need the
 * baseline gate on READY). All floors are APP CONVENTIONS so a stable personal metric's band
 * can never collapse to zero width (MAD = 0) — they are not literature thresholds.
 */
object PersonalBaseline {

    /** Rolling window over eligible nights (app convention: two dogfood weeks). */
    const val WINDOW_NIGHTS = 14

    /** Eligible nights needed before the baseline is READY (docs/INSIGHTS.md: ≥10). */
    const val MIN_READY_NIGHTS = 10

    /** Scale factor making MAD comparable to a standard deviation (normal distribution). */
    const val MAD_SCALE = 1.4826

    /** Band half-width = 2 robust sigmas. */
    const val BAND_SIGMAS = 2.0

    // Band floors (band half-width minimums; APP CONVENTIONS, see object KDoc).
    const val FLOOR_SNORE_SHARE = 0.01
    const val FLOOR_LONGEST_BOUT_MS = 60_000.0
    const val FLOOR_SCORE = 5.0
    const val FLOOR_GARMIN_AWAKE_MIN = 10.0
    const val FLOOR_RMSSD_MS = 5.0

    enum class State { COLLECTING, READY }

    /** Metric keys used in [Baseline.bands] and the rules' detail JSON (docs/INSIGHTS.md). */
    object Metrics {
        const val SNORE_SHARE = "snore_share"
        const val LONGEST_BOUT_MS = "longest_bout_ms"
        const val SCORE = "score"
        const val GARMIN_AWAKE_MIN = "garmin_awake_min"
        const val RMSSD_MS = "rmssd_ms"
    }

    /**
     * One night's summary — the inputs the baseline and rules need. Metric nulls mean "not
     * known for this night" (no Garmin import, no snoring, no HRV) and are skipped per metric.
     */
    data class Input(
        /** ISO yyyy-MM-dd local start date ([com.sleepanalysis.domain.report.NightDates.factorDay]). */
        val nightDate: String,
        val sessionId: Long,
        /** NIGHT_VALIDITY grade letter A–D; null = no row (pre-validity night). */
        val grade: String?,
        val detectorVersion: String?,
        val scoreVersion: String?,
        /** Label-aware union snore seconds / captured analysis seconds, 0..1 (SnoreMath). */
        val snoreShare: Double?,
        val longestBoutMs: Long?,
        /** Stored SNORE_SCORE 0–100. */
        val score: Int?,
        val garminAwakeMinutes: Double?,
        val rMssdMs: Double?,
    )

    /**
     * Robust band for one metric: [median] ± max([BAND_SIGMAS]·1.4826·MAD, metric floor).
     * [low]/[high] are the ±2-robust-σ "usual range" EDGES the UI renders. The excursion
     * trigger is deliberately a DIFFERENT sensitivity: rules fire on |robustZ| >
     * [InsightRules.ROBUST_Z_EXCURSION] (2.5), not on leaving the 2σ band — [isOutside] is the
     * display contract, [robustZ] the rule contract.
     */
    data class MetricBand(val median: Double, val mad: Double, val low: Double, val high: Double) {

        /**
         * Robust z-score of [value] against this band: (value − median) / (1.4826·MAD), where
         * the spread denominator never drops below the metric's floor so a zero-MAD band still
         * yields a finite, meaningful z (the |z| > 2.5 trigger lives in the rules).
         */
        fun robustZ(value: Double, floor: Double): Double {
            val spread = maxOf(MAD_SCALE * mad, floor / BAND_SIGMAS)
            return (value - median) / spread
        }

        /** True when [value] sits outside the ±2-robust-σ band edges — display only, NOT the rules' |z| > 2.5 trigger. */
        fun isOutside(value: Double): Boolean = value < low || value > high
    }

    data class Baseline(
        val state: State,
        val eligibleCount: Int,
        /** Bands keyed by [Metrics] ids; a metric with no eligible value has no band. */
        val bands: Map<String, MetricBand>,
    )

    /**
     * The eligible subset of [nights]: validity grade A or B AND detector/score versions
     * matching [currentDetectorVersion]/[currentScoreVersion], oldest first, last
     * [WINDOW_NIGHTS] kept. Pure — callers pass the raw summaries.
     */
    fun eligibleNights(
        nights: List<Input>,
        currentDetectorVersion: String?,
        currentScoreVersion: String?,
    ): List<Input> = nights.asSequence()
        .filter { it.grade == "A" || it.grade == "B" }
        .filter { it.detectorVersion == currentDetectorVersion && it.scoreVersion == currentScoreVersion }
        .sortedBy { it.nightDate }
        .toList()
        .takeLast(WINDOW_NIGHTS)

    /**
     * Builds the baseline from the RAW night summaries: eligibility and the rolling window are
     * applied here ([eligibleNights]), then one robust band per metric over the values that
     * exist. Zero eligible nights → COLLECTING with no bands.
     */
    fun compute(
        nights: List<Input>,
        currentDetectorVersion: String?,
        currentScoreVersion: String?,
    ): Baseline {
        val eligible = eligibleNights(nights, currentDetectorVersion, currentScoreVersion)
        val bands = buildMap {
            fun band(key: String, values: List<Double>, floor: Double) {
                if (values.isEmpty()) return
                val median = median(values)
                val mad = median(values.map { kotlin.math.abs(it - median) })
                val half = maxOf(BAND_SIGMAS * MAD_SCALE * mad, floor)
                put(key, MetricBand(median, mad, median - half, median + half))
            }
            band(Metrics.SNORE_SHARE, eligible.mapNotNull { it.snoreShare }, FLOOR_SNORE_SHARE)
            band(Metrics.LONGEST_BOUT_MS, eligible.mapNotNull { it.longestBoutMs?.toDouble() }, FLOOR_LONGEST_BOUT_MS)
            band(Metrics.SCORE, eligible.mapNotNull { it.score?.toDouble() }, FLOOR_SCORE)
            band(Metrics.GARMIN_AWAKE_MIN, eligible.mapNotNull { it.garminAwakeMinutes }, FLOOR_GARMIN_AWAKE_MIN)
            band(Metrics.RMSSD_MS, eligible.mapNotNull { it.rMssdMs }, FLOOR_RMSSD_MS)
        }
        return Baseline(
            state = if (eligible.size >= MIN_READY_NIGHTS) State.READY else State.COLLECTING,
            eligibleCount = eligible.size,
            bands = bands,
        )
    }

    /** Median (mean of the two middle values for even counts). */
    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
    }
}
