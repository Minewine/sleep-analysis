package com.sleepanalysis.monitoring.detection

import com.sleepanalysis.domain.scoring.SnoreScore

/**
 * Tuning constants for the Phase 1 acoustic pipeline. The v0 detector is a documented
 * energy/band heuristic — detection QUALITY is explicitly a Phase 2 concern; the interface
 * (SnoreDetector) is the swap point (docs/MVP_SCOPE.md F4, docs/ARCHITECTURE.md §8).
 */
object DetectorConfig {
    const val SAMPLE_RATE = 16_000
    const val WINDOW_MS = 1_000L
    const val HOP_MS = 500L

    /** Clip length retained per detected event. */
    const val CLIP_MS = 8_000L
    const val MAX_CLIPS_PER_NIGHT = 50

    /**
     * Relative level gate (backlog #2, self-calibrating). An absolute −55 dBFS cutoff ate real
     * snores in louder rooms (a −50 dBFS snore in a −45 dBFS bedroom is clearly audible yet
     * gated) while being nearly redundant in silent ones — the v0 detector's thresholds are
     * already relative to the rolling noise floor. Events must now clear the event's own mean
     * noise floor by [GATE_MARGIN_DB]; [MIN_GATE_DBFS] caps how low that threshold may fall so
     * a dead-quiet room (floor ≈ mic self-noise, −92 dBFS) still never gates open — and never
     * persists a clip for — near-silent junk (see EventDetector/ClipPolicy).
     */
    const val GATE_MARGIN_DB = 10.0
    const val MIN_GATE_DBFS = -60.0

    /**
     * Gate threshold for an event over [noiseFloorDbfs]: floor + margin, never below
     * [hardFloorDbfs]. Pure — shared by the event label downgrade and the clip policy.
     */
    fun gateThresholdDbfs(
        noiseFloorDbfs: Double,
        marginDb: Double = GATE_MARGIN_DB,
        hardFloorDbfs: Double = MIN_GATE_DBFS,
    ): Double = maxOf(noiseFloorDbfs + marginDb, hardFloorDbfs)

    /**
     * Bedtime/settling protection: for the first [SETTLING_MINUTES] after capture begins, the
     * clip gate is stricter by [SETTLING_EXTRA_DB]. Bedtime noise is typically the night's
     * loudest ambient period (night 1 spent its whole first hourly budget in 42 minutes); the
     * start delay removes most of it, this absorbs what remains. Event labels are unaffected —
     * only clip persistence is held back during settling.
     */
    const val SETTLING_MINUTES = 30L
    const val SETTLING_EXTRA_DB = 6.0

    /**
     * Clip budget per wall-clock hour. Night 1 spent all 50 clips on the first 42 minutes of
     * bedtime noise, leaving nothing for the actual sleep window; this spreads the budget so
     * any burst can only claim a bounded share (MAX_CLIPS_PER_NIGHT stays as the backstop).
     */
    const val MAX_CLIPS_PER_HOUR = 8

    /**
     * Loudness reserve (night 3 dogfood): a first-come-first-served budget starves exactly the
     * events worth hearing — night 3's loudest snore (peak −16.6 dBFS) missed its clip because
     * the 23:00 hour's 8 were already spent. Events whose PEAK clears [LOUD_CLIP_PEAK_DBFS]
     * bypass the hourly/nightly budgets while a small nightly reserve lasts; the mean-level
     * gate above still applies (a momentary peak in rumble earns no clip). Peak, not mean:
     * event means drag toward the quiet windows between breath cycles.
     */
    const val LOUD_CLIP_PEAK_DBFS = -25.0
    const val LOUD_CLIP_RESERVE_PER_NIGHT = 15

    // Energy detector thresholds (relative to the rolling noise floor — never absolute SPL).
    const val NOISE_FLOOR_PERCENTILE = 0.20
    const val NOISE_FLOOR_WINDOW_COUNT = 240      // ~2 minutes of hops
    const val LOUD_DELTA_DB = 12.0
    const val SNORE_MIN_SNR_DB = 8.0

    /** Fraction of window energy below ~1 kHz expected for snoring vs broadband noise. */
    const val LOW_BAND_RATIO_MIN = 0.55

    /**
     * One-pole low-pass coefficient for a ~1 kHz cutoff at 16 kHz (alpha = 1 − e^(−2π·fc/fs) ≈ 0.32).
     * Verified live 2026-11-01: the earlier 0.038 gave a ~97 Hz cutoff, which filtered out the
     * harmonics of real snore content and pushed every window below the low-band-ratio threshold.
     */
    const val LOW_PASS_ALPHA = 0.32

    // Spectral discriminators (stage 2, tuned via fixture eval — docs/NEXT_STEPS.md P2).
    // Unlabeled dogfood clips so far; revisit once user labels accumulate.

    /**
     * Max spectral flatness for a snore window (1.0 = white noise). Snore energy is tonal/
     * peaked; broadband household noise drives the geometric/arithmetic mean ratio toward 1.
     */
    const val SPECTRAL_FLATNESS_MAX = 0.55

    /** Min peak normalized autocorrelation (60-250 Hz) — snore breath cycles are periodic. */
    const val HARMONICITY_MIN = 0.30

    /**
     * Breath-cycle memory: a marginal window (passing loud+snr+band+spectral but below
     * [STRONG_SNR_DB]) is believed only when another snore window occurred within
     * [RHYTHM_WINDOW_MS] — snoring arrives in periodic trains, isolated spikes do not.
     */
    const val RHYTHM_ENABLED = true
    const val RHYTHM_WINDOW_MS = 8_000L

    /** SNR margin above SNORE_MIN_SNR_DB that bypasses the rhythm gate. */
    const val STRONG_SNR_DB = 20.0

    // Event shaping (docs/ARCHITECTURE.md §4 EventDetector).
    const val ENTER_WINDOWS = 2       // 2 consecutive active hops (1.0 s) to open an event
    const val EXIT_WINDOWS = 4        // 4 consecutive quiet hops (2.0 s) to close it
    const val MIN_DURATION_MS = 2_000L
    const val MERGE_GAP_MS = 2_000L

    /** Relative intensity bands (docs/MEASUREMENT_RELIABILITY.md M2 — relative, not SPL). */
    const val EPIC_DBFS = -10.0
    const val LOUD_DBFS = -20.0
    const val LIGHT_DBFS = -30.0

    fun intensityTier(dbfs: Double): String = when {
        dbfs > EPIC_DBFS -> SnoreScore.IntensityTiers.EPIC
        dbfs > LOUD_DBFS -> SnoreScore.IntensityTiers.LOUD
        dbfs > LIGHT_DBFS -> SnoreScore.IntensityTiers.LIGHT
        else -> SnoreScore.IntensityTiers.QUIET
    }
}
