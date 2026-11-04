package com.sleepanalysis.domain.policy

/**
 * Measurement tiers from docs/MEASUREMENT_RELIABILITY.md §1. This enum is the code mirror of
 * the binding classification table; ClaimPolicyTest asserts the two stay in sync.
 */
enum class MeasurementTier {
    /** Phone/sensor can credibly support it; shown as a primary measurement. */
    ESTABLISHED,

    /** Under-validated; Phase 2+, only behind the experimental toggle, always badged. */
    EXPERIMENTAL,

    /**
     * Never computed, never stored, never displayed (docs/MEASUREMENT_RELIABILITY.md §2, M14–M17).
     * MeasurementRecorder refuses to write these — no code path exists.
     */
    OUT_OF_SCOPE_FOR_CLAIMS,
}

enum class MeasurementUnit {
    SECONDS,
    MINUTES,
    PERCENT,
    COUNT,
    PER_HOUR,
    DBFS,
    INDEX_0_100,
    INDEX_0_10,
    MS,
    BPM,
    BREATHS_PER_MINUTE,
}

/**
 * The measurement taxonomy (docs/DATA_MODEL.md §3.3). Each type carries its tier and unit so
 * provenance cannot drift from the claim policy.
 */
enum class MeasurementType(val tier: MeasurementTier, val unit: MeasurementUnit) {
    // ---- Phase 1, Tier 1 (phone) ----
    TIME_IN_BED(MeasurementTier.ESTABLISHED, MeasurementUnit.SECONDS),
    SNORE_EVENT_COUNT(MeasurementTier.ESTABLISHED, MeasurementUnit.COUNT),
    SNORE_TIME(MeasurementTier.ESTABLISHED, MeasurementUnit.SECONDS),
    SNORE_SHARE_OF_NIGHT(MeasurementTier.ESTABLISHED, MeasurementUnit.PERCENT),
    SNORE_INTENSITY_PEAK(MeasurementTier.ESTABLISHED, MeasurementUnit.DBFS),
    SNORE_INTENSITY_MEAN(MeasurementTier.ESTABLISHED, MeasurementUnit.DBFS),
    SNORE_SCORE(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),
    NOISE_MEDIAN(MeasurementTier.ESTABLISHED, MeasurementUnit.DBFS),
    NOISE_P95(MeasurementTier.ESTABLISHED, MeasurementUnit.DBFS),
    ACOUSTIC_EVENT_COUNT(MeasurementTier.ESTABLISHED, MeasurementUnit.COUNT),

    // ---- Phase 2, Tier 2 (experimental; behind the toggle, never headline) ----
    BREATHING_IRREGULARITY_INDICATOR(MeasurementTier.EXPERIMENTAL, MeasurementUnit.INDEX_0_10),
    RESPIRATORY_PATTERN_INDICATOR(MeasurementTier.EXPERIMENTAL, MeasurementUnit.INDEX_0_10),

    // ---- Phase 3: Garmin (device-reported pass-through, shown as "Garmin estimate") ----
    // docs/MEASUREMENT_RELIABILITY.md M10–M13 + M18: vendor algorithms are opaque; values are
    // context, never compared numerically with clinical data. source=GARMIN, method=DEVICE_REPORTED.
    VENDOR_SLEEP_DURATION(MeasurementTier.ESTABLISHED, MeasurementUnit.SECONDS),
    VENDOR_SLEEP_SCORE(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),
    VENDOR_HRV_RMSSD(MeasurementTier.ESTABLISHED, MeasurementUnit.MS),
    VENDOR_BODY_BATTERY(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),
    VENDOR_RESTING_HEART_RATE(MeasurementTier.ESTABLISHED, MeasurementUnit.BPM),
    // M13: vendor sleep stages — device-reported context only, never compared to PSG stages.
    VENDOR_DEEP_SLEEP(MeasurementTier.ESTABLISHED, MeasurementUnit.SECONDS),
    VENDOR_LIGHT_SLEEP(MeasurementTier.ESTABLISHED, MeasurementUnit.SECONDS),
    VENDOR_REM_SLEEP(MeasurementTier.ESTABLISHED, MeasurementUnit.SECONDS),
    VENDOR_AWAKE_SLEEP(MeasurementTier.ESTABLISHED, MeasurementUnit.SECONDS),
    // M10: nocturnal heart rate / respiration as device-reported values.
    VENDOR_AVG_HEART_RATE_SLEEP(MeasurementTier.ESTABLISHED, MeasurementUnit.BPM),
    VENDOR_RESPIRATION_AVG_SLEEP(MeasurementTier.ESTABLISHED, MeasurementUnit.BREATHS_PER_MINUTE),
    // M11-analog: vendor stress composites (opaque vendor algorithms).
    VENDOR_SLEEP_STRESS_AVG(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),
    VENDOR_STRESS_DAILY_AVG(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),
    // M12: SpO₂ LEVELS ONLY — trend display, never a desaturation measurement; the sleep DTO's
    // numberOfEventsBelowThreshold is deliberately NOT imported (M12/M17).
    VENDOR_SPO2_AVG_SLEEP(MeasurementTier.ESTABLISHED, MeasurementUnit.PERCENT),
    VENDOR_SPO2_LOWEST_SLEEP(MeasurementTier.ESTABLISHED, MeasurementUnit.PERCENT),
    VENDOR_SLEEP_AWAKENINGS(MeasurementTier.ESTABLISHED, MeasurementUnit.COUNT),
    VENDOR_NAP_TIME(MeasurementTier.ESTABLISHED, MeasurementUnit.SECONDS),
    VENDOR_SLEEP_NEED(MeasurementTier.ESTABLISHED, MeasurementUnit.MINUTES),
    // Sleep-score factor breakdown (sub-scores 0–100; shape verified on first real night).
    VENDOR_SLEEP_FACTOR_DURATION(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),
    VENDOR_SLEEP_FACTOR_QUALITY(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),
    VENDOR_SLEEP_FACTOR_STRESS(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),
    VENDOR_SLEEP_FACTOR_TIMING(MeasurementTier.ESTABLISHED, MeasurementUnit.INDEX_0_100),

    // ---- Tier 3: OUT OF SCOPE FOR CLAIMS — the recorder refuses these. ----
    // docs/MEASUREMENT_RELIABILITY.md M14–M17: a phone cannot claim these, and computing
    // them would also cross the medical-device boundary (docs/PRIVACY_AND_REGULATORY.md §6).
    AHI_ESTIMATE(MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS, MeasurementUnit.PER_HOUR),
    RDI_ESTIMATE(MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS, MeasurementUnit.PER_HOUR),
    SLEEP_STAGE_ESTIMATE(MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS, MeasurementUnit.MINUTES),
    AROUSAL_INDEX_ESTIMATE(MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS, MeasurementUnit.PER_HOUR),
    SPO2_DESATURATION_ESTIMATE(MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS, MeasurementUnit.COUNT),
}
