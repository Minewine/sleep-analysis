package com.sleepanalysis.monitoring.detection

/**
 * Swappable detection interface (docs/MVP_SCOPE.md F4). Phase 1 ships an energy/band heuristic;
 * Phase 2 swaps in a TFLite model (e.g. YAMNet-mapped or a custom log-mel CNN) without touching
 * the capture pipeline, storage, or report logic.
 */
interface SnoreDetector {
    /**
     * Classifies one analysis window.
     *
     * @param features framewise acoustic features extracted from the PCM window.
     * @return detection result; [DetectionResult.snore] and [DetectionResult.loudAcousticEvent]
     *         are per-window booleans fed into event shaping.
     */
    fun classify(features: AudioFeatures): DetectionResult

    /** Called when a monitoring session starts, so adaptive state (noise floor) can reset. */
    fun reset()
}

/** Framewise features computed by the audio pipeline. Pure data — unit-testable. */
data class AudioFeatures(
    /** Overall window level in dBFS (relative, not calibrated SPL). */
    val windowDbfs: Double,
    /** Rolling noise floor estimate in dBFS (low percentile of recent windows). */
    val noiseFloorDbfs: Double,
    /** Fraction of window energy below ~1 kHz (1.0 = all low-band). */
    val lowBandRatio: Double,
    val timestampMs: Long,
    /**
     * Spectral flatness 0..1 (1 = white noise), stage 2 — see SpectralFeatures. Null when the
     * producer did not compute it (older recorded sessions, stub tests) so every existing
     * construction site keeps compiling; the detector falls back to v0 energy-only behavior
     * on null.
     */
    val spectralFlatness: Double? = null,
    /** Peak normalized autocorrelation 60-250 Hz, 0..1 — null under the same conditions as
     *  [spectralFlatness] (the two are always computed together in practice). */
    val harmonicRatio: Double? = null,
)

data class DetectionResult(
    val snore: Boolean,
    val loudAcousticEvent: Boolean,
    /** 0..1 detector confidence (heuristic-derived in Phase 1). */
    val confidence: Double,
)

/**
 * Stage 2 v1 detector: v0 energy + band-ratio heuristic plus two spectral gates and a
 * breath-cycle rhythm gate. Thresholds were tuned via the offline FixtureEvaluator on dogfood
 * clips (docs/NEXT_STEPS.md P2); real labels do not exist yet, so they are a starting point.
 *
 * Logic (all thresholds relative to a rolling noise floor, never absolute SPL):
 *  - loud   := windowDbfs >= noiseFloor + LOUD_DELTA_DB
 *  - energy := loud AND snr >= SNORE_MIN_SNR_DB AND lowBandRatio >= LOW_BAND_RATIO_MIN   (v0)
 *  - spectral := spectralFlatness <= SPECTRAL_FLATNESS_MAX AND harmonicRatio >= HARMONICITY_MIN
 *    (only when the features are present; snore energy is tonal/peaked while broadband noise
 *    drives flatness toward 1 — this kills the v0 false positives from household noise)
 *  - rhythm := snr >= STRONG_SNR_DB, OR a previous snore window occurred within
 *    RHYTHM_WINDOW_MS (real snoring is a periodic breath train, so once one window passes all
 *    gates, adjacent marginal windows in the same train are believed; isolated marginal
 *    spikes are not). Windows failing the energy or spectral gates never enter the memory, so
 *    broadband noise cannot seed it.
 *
 * Null-feature fallback (backwards compatible): when [AudioFeatures.spectralFlatness] is null
 * the producer did not compute spectral features (older sessions, stub tests) and classify
 * behaves exactly as v0 — spectral gate skipped, rhythm gate skipped, no state written.
 *
 * v0→v1 confidence: same 0.5 base + margin shape, weights still sum to 1. When spectral
 * features are present the formula is 0.5 + 0.25*snrMargin + 0.15*bandMargin + 0.10*flatnessMargin
 * (v0: 0.5 + 0.3*snrMargin + 0.2*bandMargin): flatness is the primary new discriminator so it
 * earns weight, while the harmonic gate is binary (already passed) and scales nothing.
 *
 * Known limits (documented, by design): speech close to the phone is also tonal and rhythmic;
 * very distant snoring may be missed. Clip playback lets the user verify; user labels stored
 * on events feed Phase 2 calibration. Because every threshold here is relative to a rolling
 * floor, a silent room (floor ≈ mic self-noise) makes noise-floor rumble classify as snore —
 * the relative level gate in [EventDetector] (floor + DetectorConfig.GATE_MARGIN_DB, capped by
 * MIN_GATE_DBFS) downgrades such events at finalization.
 */
class EnergySnoreDetector(
    private val config: DetectorConfigValues = DetectorConfigValues(),
) : SnoreDetector {

    /** Injectable constants for tests. */
    data class DetectorConfigValues(
        val loudDeltaDb: Double = DetectorConfig.LOUD_DELTA_DB,
        val snoreMinSnrDb: Double = DetectorConfig.SNORE_MIN_SNR_DB,
        val lowBandRatioMin: Double = DetectorConfig.LOW_BAND_RATIO_MIN,
        /** A/B toggle for the flatness/harmonicity gate (fixture eval). */
        val spectralGatingEnabled: Boolean = true,
        /** A/B toggle for the breath-cycle rhythm gate (fixture eval). */
        val rhythmEnabled: Boolean = DetectorConfig.RHYTHM_ENABLED,
    )

    /**
     * Rhythm memory: end-timestamps of recently accepted snore windows, pruned to
     * [DetectorConfig.RHYTHM_WINDOW_MS] on every classify. Capture-thread-confined live; the
     * fixture evaluator calls [reset] between clips so state never leaks across them.
     *
     * Sustain property (documented, deliberately not changed — a detection-policy question
     * requiring labeled-fixture evaluation, docs/NEXT_STEPS.md P2): every ACCEPTED window
     * re-enters this memory, so the rhythm gate needs only ONE strong window
     * (snr ≥ STRONG_SNR_DB) to seed a train that marginal windows can then sustain
     * indefinitely — each acceptance refreshes the timestamps, and as long as consecutive
     * accepted windows stay within RHYTHM_WINDOW_MS (8 s at defaults) the memory never
     * empties, so no further strong window is ever required. What keeps this bounded:
     * windows failing the energy or spectral gates never enter the memory (broadband noise
     * can neither seed nor sustain it), and an isolated marginal spike without history is
     * still rejected. Whether a train of marginal-only windows should decay without a fresh
     * strong window is exactly the open question deferred to fixture evaluation.
     */
    private val snoreWindowTimestamps = ArrayDeque<Long>()

    override fun classify(features: AudioFeatures): DetectionResult {
        val snr = features.windowDbfs - features.noiseFloorDbfs
        val loud = features.windowDbfs >= features.noiseFloorDbfs + config.loudDeltaDb
        val energyOk = loud &&
            snr >= config.snoreMinSnrDb &&
            features.lowBandRatio >= config.lowBandRatioMin

        val flatness = features.spectralFlatness
        // Null spectral features => exact v0 path: no spectral gate, no rhythm gate, no state.
        val spectralGateActive = flatness != null && config.spectralGatingEnabled

        pruneRhythmMemory(features.timestampMs)

        val spectralOk = if (spectralGateActive && flatness != null) {
            val harmonic = features.harmonicRatio
            // harmonicRatio is always produced alongside spectralFlatness; default to passing
            // so a hypothetical flatness-only producer keeps spectral gate semantics sane.
            flatness <= DetectorConfig.SPECTRAL_FLATNESS_MAX &&
                (harmonic == null || harmonic >= DetectorConfig.HARMONICITY_MIN)
        } else {
            true
        }

        val rhythmOk = if (spectralGateActive && config.rhythmEnabled) {
            // STRONG_SNR_DB margin bypasses the gate: an unambiguous snore needs no corroboration.
            snr >= DetectorConfig.STRONG_SNR_DB || snoreWindowTimestamps.isNotEmpty()
        } else {
            true
        }

        val snore = energyOk && spectralOk && rhythmOk
        if (snore) {
            snoreWindowTimestamps.addLast(features.timestampMs)
        }

        val confidence = if (!snore) {
            0.0
        } else {
            val snrMargin = ((snr - config.snoreMinSnrDb) / 12.0).coerceIn(0.0, 1.0)
            val bandMargin = ((features.lowBandRatio - config.lowBandRatioMin) / 0.45).coerceIn(0.0, 1.0)
            // The blend keys on whether the spectral gate is ACTIVE, not merely on feature
            // presence: with spectralGatingEnabled = false (the A/B arm) passing windows must
            // score on the exact v0 scale so A/B confidence distributions stay comparable.
            val confidence = if (spectralGateActive && flatness != null) {
                // Weights sum to 1 (see class KDoc): base 0.5 + 0.25 + 0.15 + 0.10.
                val flatnessMargin =
                    ((DetectorConfig.SPECTRAL_FLATNESS_MAX - flatness) / DetectorConfig.SPECTRAL_FLATNESS_MAX)
                        .coerceIn(0.0, 1.0)
                0.5 + 0.25 * snrMargin + 0.15 * bandMargin + 0.10 * flatnessMargin
            } else {
                0.5 + 0.3 * snrMargin + 0.2 * bandMargin
            }
            confidence.coerceIn(0.0, 1.0)
        }
        return DetectionResult(snore = snore, loudAcousticEvent = loud, confidence = confidence)
    }

    /** Drops rhythm-memory entries older than [DetectorConfig.RHYTHM_WINDOW_MS]. */
    private fun pruneRhythmMemory(nowMs: Long) {
        val windowMs = DetectorConfig.RHYTHM_WINDOW_MS
        while (snoreWindowTimestamps.isNotEmpty() && nowMs - snoreWindowTimestamps.first() > windowMs) {
            snoreWindowTimestamps.removeFirst()
        }
    }

    /**
     * Clears adaptive state. The noise floor itself still lives in the audio pipeline
     * (AudioEngine), as in v0 — this now also resets the rhythm memory so replayed clips (the
     * fixture evaluator resets between clips) and restarted sessions never inherit breath-train
     * history from the previous clip.
     */
    override fun reset() {
        snoreWindowTimestamps.clear()
    }
}
