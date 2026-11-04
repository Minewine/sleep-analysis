package com.sleepanalysis.domain.scoring

/**
 * Snore score 0–100 (docs/ARCHITECTURE.md §4, domain/scoring). Deterministic, provenance-honest:
 * a pure function of the night's established snoring measurements.
 *
 * Formula:
 *   shareScore      = min(60, snoringShareOfNight * 600)   // 10% of the night snoring saturates
 *   intensityScore  = meanIntensityWeight * 40             // tier-weighted loudness contribution
 *   score           = truncate(min(100, shareScore + intensityScore))
 *
 * Phase 2 may recalibrate this against user feedback; the formula stays documented here.
 */
object SnoreScore {

    private const val SHARE_SATURATION = 0.10      // 10% of the night
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

    fun compute(snoreSeconds: Long, nightSeconds: Long, tierCounts: Map<String, Int>): Int {
        if (nightSeconds <= 0) return 0
        val share = snoreSeconds.toDouble() / nightSeconds
        val shareScore = minOf(SHARE_MAX_POINTS, share / SHARE_SATURATION * SHARE_MAX_POINTS)

        val total = tierCounts.values.sum()
        val meanWeight = if (total == 0) 0.0 else {
            tierCounts.entries.sumOf { (tier, count) -> (TIER_WEIGHTS[tier] ?: 0.0) * count } / total
        }
        val intensityScore = meanWeight * INTENSITY_MAX_POINTS

        return (shareScore + intensityScore).coerceIn(0.0, 100.0).toInt()
    }
}
