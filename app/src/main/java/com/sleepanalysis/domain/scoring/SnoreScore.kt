package com.sleepanalysis.domain.scoring

/**
 * Snore score 0–100 (docs/ARCHITECTURE.md §4, domain/scoring). Deterministic, provenance-honest:
 * a pure function of the night's established snoring measurements. [FORMULA_VERSION] is stored
 * on the SNORE_SCORE measurement row (never the detector's model version — the two change on
 * independent schedules and trend comparability depends on telling them apart).
 *
 * Formula v2 ("snore-score-v2"):
 *   sharePoints     = min(60, snoringShareOfNight / 0.40 * 60)   // saturates at 40% of the night
 *   intensityPoints = meanTierWeight * 40                        // tier weights over snoring TIME
 *   score           = truncate(min(100, sharePoints + intensityPoints))
 *
 * v1 (retroactive name; every row stored before 2026-11-06) saturated the share component at
 * 10% and weighted tiers by EVENT COUNT — every dogfood night landed at 66–67 and the score
 * could not distinguish a 13% night from a 30% night. v2 moves saturation to 40% (the observed
 * dogfood range is 13–31%) and weights the intensity term by per-tier snoring TIME (union
 * seconds), so a night whose loud stretches persist earns more than one with many quiet
 * spells. The five recalculation anchors: 13%→25, 16%→29, 27%→47, 29%→53, 30%→53 (v1: all 66–67).
 *
 * The breakdown ([Result]) is explainable and reproducible: share points + intensity points,
 * each capped, stored verbatim in the SNORE_SCORE row's notes.
 */
object SnoreScore {

    /** Formula identity stored on SNORE_SCORE rows; bump on ANY recalibration (docs/NIGHT_REPORT.md). */
    const val FORMULA_VERSION = "snore-score-v2"

    /** Share of the night at which the share component saturates. */
    private const val SHARE_SATURATION = 0.40
    private const val SHARE_MAX_POINTS = 60.0
    private const val INTENSITY_MAX_POINTS = 40.0

    private val TIER_WEIGHTS = mapOf(
        IntensityTiers.QUIET to 0.15,
        IntensityTiers.LIGHT to 0.35,
        IntensityTiers.LOUD to 0.65,
        IntensityTiers.EPIC to 1.00,
    )

    object IntensityTiers {
        const val QUIET = "QUIET"
        const val LIGHT = "LIGHT"
        const val LOUD = "LOUD"
        const val EPIC = "EPIC"
    }

    /** The explainable decomposition behind [Result.score]. */
    data class Result(
        val score: Int,
        /** Points earned from the share component (0–60). */
        val sharePoints: Double,
        /** Points earned from the intensity component (0–40). */
        val intensityPoints: Double,
        /** The tier-time mean weight behind [intensityPoints] (0–1). */
        val meanTierWeight: Double,
    )

    /**
     * [snoreSeconds] is the night's union snoring time (SnoreMath) and [nightSeconds] the same
     * denominator the share measurement uses (captured analysis time). [tierSeconds] maps each
     * tier to its union snoring seconds — the per-tier unions partition [snoreSeconds], so the
     * weights describe where the snoring TIME sat, not how many spells fired.
     */
    fun compute(snoreSeconds: Long, nightSeconds: Long, tierSeconds: Map<String, Long>): Result {
        if (nightSeconds <= 0) return Result(0, 0.0, 0.0, 0.0)
        val share = snoreSeconds.toDouble() / nightSeconds
        val sharePoints = minOf(SHARE_MAX_POINTS, share / SHARE_SATURATION * SHARE_MAX_POINTS)

        val totalTierSeconds = tierSeconds.values.sum()
        val meanWeight = if (totalTierSeconds <= 0) 0.0 else {
            tierSeconds.entries.sumOf { (tier, seconds) ->
                (TIER_WEIGHTS[tier] ?: 0.0) * seconds
            } / totalTierSeconds
        }
        val intensityPoints = meanWeight * INTENSITY_MAX_POINTS

        return Result(
            score = (sharePoints + intensityPoints).coerceIn(0.0, 100.0).toInt(),
            sharePoints = sharePoints,
            intensityPoints = intensityPoints,
            meanTierWeight = meanWeight,
        )
    }
}
