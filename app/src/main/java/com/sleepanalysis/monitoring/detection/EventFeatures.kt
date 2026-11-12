package com.sleepanalysis.monitoring.detection

import org.json.JSONObject

/**
 * The v0 detector's decision inputs, aggregated over one event's windows
 * (docs/NEXT_STEPS.md P0: "make the existing decisions visible").
 *
 * Persisted as JSON on acoustic_event.features_json (schema v5) so the numbers the detector
 * actually saw can be inspected next to the clip after a night. The shape is version-bound to
 * the model id (energy-heuristic-v0); future models get their own shape. Keys are additive:
 * exit/open/close window counts were added 2026-11-02 so hysteresis can be retuned from stored
 * events without reprocessing audio; readers must tolerate their absence on legacy rows.
 */
data class EventFeatures(
    /** Consecutive-window requirement that was active when the event opened. */
    val enterWindows: Int,
    /** Consecutive-quiet-window requirement that was active (pairs [enterWindows]). */
    val exitWindows: Int,
    /** Windows pending when the event opened (= enterWindows; sanity value for retuning analysis). */
    val openWindows: Int,
    /**
     * Quiet windows accumulated when the event closed: = exitWindows on the normal timeout
     * path, lower when flush or snore-precedence closed it early. −1 for legacy rows that
     * predate the field.
     */
    val closeWindows: Int,
    val snrPeakDb: Double,
    val snrMeanDb: Double,
    val lowBandRatioPeak: Double,
    val lowBandRatioMean: Double,
    /** Mean rolling noise floor (dBFS) across the event's windows. */
    val noiseFloorDbfsMean: Double,
    /**
     * Mean spectral flatness (0..1, 1 = white noise) over the event's analyzed windows — the
     * v1 gate's primary discriminator, persisted so thresholds can be retuned from real
     * nights. Null when the producer did not compute spectral features (legacy/stub rows).
     */
    val spectralFlatnessMean: Double? = null,
    /** Mean harmonic ratio (60–250 Hz autocorrelation, 0..1) — null under the same conditions. */
    val harmonicRatioMean: Double? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("enter_windows", enterWindows)
        put("exit_windows", exitWindows)
        put("open_windows", openWindows)
        put("close_windows", closeWindows)
        put("snr_peak_db", snrPeakDb)
        put("snr_mean_db", snrMeanDb)
        put("low_band_ratio_peak", lowBandRatioPeak)
        put("low_band_ratio_mean", lowBandRatioMean)
        put("noise_floor_dbfs", noiseFloorDbfsMean)
        spectralFlatnessMean?.let { put("spectral_flatness_mean", it) }
        harmonicRatioMean?.let { put("harmonic_ratio_mean", it) }
    }.toString()

    companion object {
        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

        fun fromJson(json: String?): EventFeatures? = runCatching {
            if (json.isNullOrBlank()) return@runCatching null
            val o = JSONObject(json)
            val enter = o.optInt("enter_windows", DetectorConfig.ENTER_WINDOWS)
            EventFeatures(
                enterWindows = enter,
                exitWindows = o.optInt("exit_windows", DetectorConfig.EXIT_WINDOWS),
                openWindows = o.optInt("open_windows", enter),
                closeWindows = o.optInt("close_windows", -1),
                snrPeakDb = o.optDouble("snr_peak_db"),
                snrMeanDb = o.optDouble("snr_mean_db"),
                lowBandRatioPeak = o.optDouble("low_band_ratio_peak"),
                lowBandRatioMean = o.optDouble("low_band_ratio_mean"),
                noiseFloorDbfsMean = o.optDouble("noise_floor_dbfs"),
                spectralFlatnessMean = o.optDoubleOrNull("spectral_flatness_mean"),
                harmonicRatioMean = o.optDoubleOrNull("harmonic_ratio_mean"),
            )
        }.getOrNull()
    }
}
