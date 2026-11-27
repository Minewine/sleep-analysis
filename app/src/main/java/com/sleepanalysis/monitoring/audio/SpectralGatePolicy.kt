package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.monitoring.detection.DetectorConfig

/**
 * Decides whether one analysis window needs the stage-2 spectral features (31×1024-pt FFT +
 * one full-window autocorrelation — [com.sleepanalysis.monitoring.features.SpectralFeatures]) or
 * can skip them. Pure — unit tested (SpectralGatePolicyTest).
 *
 * Battery gate (battery audit 2026-11: the overnight drain is dominated by the SoC never
 * suspending, the per-window spectral compute is the second lever): most overnight windows are
 * quiet room tone sitting at the rolling noise floor, far below every detection threshold, yet
 * each still paid the full FFT.
 *
 * A window skips the spectral compute only when ALL THREE clauses hold:
 *
 *  1. **Below the event gate threshold** — [DetectorConfig.gateThresholdDbfs] (floor +
 *     [DetectorConfig.GATE_MARGIN_DB], never below [DetectorConfig.MIN_GATE_DBFS]): the same
 *     relative-margin semantics the event label downgrade and [ClipPolicy] use.
 *  2. **The detector is fully idle** — [com.sleepanalysis.monitoring.detection.EventDetector.isIdle]:
 *     no machine has pending/active windows (an event is being entered or held open) and no
 *     deferred final awaits merge-expiry. A busy machine always gets spectral compute, both so
 *     its active windows accumulate the persisted features_json spectral means exactly as
 *     before and so detection itself never sees the null-feature fallback mid-event.
 *  3. **Below the detector's own loud line** (floor + [DetectorConfig.LOUD_DELTA_DB]). This
 *     clause carries the persisted-output safety proof: for a window with
 *     `windowDbfs < noiseFloor + loudDeltaDb` the detector's classify() is invariant to the
 *     spectral fields — `loud` is false, so energyOk is false, so `snore` is false and
 *     `loudAcousticEvent` is false whether flatness/harmonicity are computed or null, and
 *     confidence is 0.0 on both the v1 and the null-feature (v0) path (SnoreDetector KDoc).
 *     Feeding such a window null spectral features therefore yields byte-identical
 *     classification AND identical machine evolution: a skipped window is active on neither
 *     machine, so it can neither seed nor sustain an event and never enters the per-window
 *     EventFeatures sums — every window whose features can end up persisted in an event
 *     (features_json spectral means) was spectral-computed exactly as before.
 *
 * Clause 3 is deliberately stricter than clause 1 in ultra-quiet rooms (floor below
 * [DetectorConfig.MIN_GATE_DBFS] − [DetectorConfig.LOUD_DELTA_DB]): there the hard floor caps
 * the gate threshold above the loud line, so a window can be "loud"
 * (floor + LOUD_DELTA_DB ≤ level < MIN_GATE_DBFS) yet pass clause 1 — such a window could
 * classify differently without its spectral features (the v0 fallback is more permissive than
 * the v1 spectral gates), so it computes. Skipping less in that corner is the conservative
 * trade for a provable output equivalence instead of an approximation.
 */
object SpectralGatePolicy {

    /**
     * True when the window must run [com.sleepanalysis.monitoring.features.SpectralFeatures.Workspace.compute]
     * before classification; false when it may classify with null spectral fields (the
     * detector's documented v0-equivalent path — provably the same result for quiet, idle
     * windows, see the class KDoc).
     */
    fun shouldComputeSpectral(
        windowDbfs: Double,
        noiseFloorDbfs: Double,
        detectorBusy: Boolean,
        gateMarginDb: Double = DetectorConfig.GATE_MARGIN_DB,
        gateHardFloorDbfs: Double = DetectorConfig.MIN_GATE_DBFS,
        loudDeltaDb: Double = DetectorConfig.LOUD_DELTA_DB,
    ): Boolean =
        detectorBusy ||
            windowDbfs >= DetectorConfig.gateThresholdDbfs(noiseFloorDbfs, gateMarginDb, gateHardFloorDbfs) ||
            windowDbfs >= noiseFloorDbfs + loudDeltaDb
}
