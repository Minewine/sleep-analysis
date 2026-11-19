package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.monitoring.detection.AudioFeatures
import com.sleepanalysis.monitoring.detection.EnergySnoreDetector
import com.sleepanalysis.monitoring.detection.EventDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-spectral level gate ([SpectralGatePolicy]): quiet + fully-idle windows skip the FFT,
 * everything else computes. Also pins the persisted-output safety proof end to end: a window
 * the gate skips classifies IDENTICALLY with null spectral features as with computed ones, so
 * the gated pipeline emits the same events (and the same persisted features_json) as the
 * compute-everything pipeline.
 */
class SpectralGatePolicyTest {

    private val floor = -50.0
    private val gateThreshold = -40.0 // floor + GATE_MARGIN_DB (10), above the −60 hard floor
    private val loudLine = -38.0 // floor + LOUD_DELTA_DB (12)

    @Test
    fun `quiet idle window below the gate skips spectral compute`() {
        // Room tone: below the event gate threshold AND below the detector's loud line.
        assertFalse(
            SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = -55.0, noiseFloorDbfs = floor, detectorBusy = false,
            ),
        )
    }

    @Test
    fun `loud window computes`() {
        assertTrue(
            SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = -30.0, noiseFloorDbfs = floor, detectorBusy = false,
            ),
        )
    }

    @Test
    fun `busy detector always computes even when quiet`() {
        // A machine pending/active or a held deferred final must never see the null-feature path.
        assertTrue(
            SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = -55.0, noiseFloorDbfs = floor, detectorBusy = true,
            ),
        )
    }

    @Test
    fun `window between the gate threshold and the loud line computes`() {
        // The event-gate clause is mandatory: [gateThreshold, loudLine) computes even though it
        // would classify identically without spectral features (conservative by design).
        assertTrue(
            SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = -39.0, noiseFloorDbfs = floor, detectorBusy = false,
            ),
        )
    }

    @Test
    fun `exactly at the gate threshold computes`() {
        assertTrue(
            SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = gateThreshold, noiseFloorDbfs = floor, detectorBusy = false,
            ),
        )
    }

    @Test
    fun `exactly at the loud line computes`() {
        assertTrue(
            SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = loudLine, noiseFloorDbfs = floor, detectorBusy = false,
            ),
        )
    }

    @Test
    fun `ultra-quiet room loud-but-gated window computes`() {
        // The conservative corner (floor below MIN_GATE_DBFS - LOUD_DELTA_DB): the hard floor
        // caps the gate threshold at -60, so a -65 dBFS window over a -80 dBFS floor passes the
        // gate clause yet IS loud (floor + 12 = -68) — such a window could classify differently
        // without its spectral features, so clause 3 forces the compute.
        val quietFloor = -80.0
        assertTrue(-65.0 >= quietFloor + 12.0) // loud per the detector
        assertTrue(-65.0 < maxOf(quietFloor + 10.0, -60.0)) // yet below the event gate threshold
        assertTrue(
            SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = -65.0, noiseFloorDbfs = quietFloor, detectorBusy = false,
            ),
        )
    }

    @Test
    fun `ultra-quiet room deep-quiet window still skips`() {
        assertFalse(
            SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = -85.0, noiseFloorDbfs = -80.0, detectorBusy = false,
            ),
        )
    }

    @Test
    fun `every skippable window classifies identically without its spectral features`() {
        // The safety proof at the classifier level: when the gate says skip, `loud` is false,
        // so the classify result (snore=false, loud=false, confidence=0) is independent of the
        // spectral fields — pinned across a matrix of spectral values, passing and failing.
        val detector = EnergySnoreDetector()
        val spectralMatrix = listOf(
            null to null, // the skip path
            0.42 to 0.77, // passing the spectral gate
            0.90 to 0.05, // failing it (white-noise flatness)
            0.30 to null,
        )
        // Quiet windows over three room regimes, all below the detector's loud line.
        val quietWindows = listOf(
            -55.0 to -50.0, // normal room
            -85.0 to -80.0, // ultra-quiet room, below the loud line
            -95.0 to -90.0, // mic self-noise floor
        )
        for ((dbfs, noiseFloor) in quietWindows) {
            assertTrue(SpectralGatePolicy.shouldComputeSpectral(dbfs, noiseFloor, detectorBusy = false) == false)
            val reference = detector.classify(
                features(dbfs, noiseFloor, flatness = 0.42, harmonic = 0.77),
            )
            for ((flatness, harmonic) in spectralMatrix) {
                val r = detector.classify(features(dbfs, noiseFloor, flatness, harmonic))
                assertEquals(reference, r)
                assertFalse(r.snore)
                assertFalse(r.loudAcousticEvent)
            }
        }
    }

    @Test
    fun `gated pipeline emits byte-identical events to the compute-everything pipeline`() {
        // The full persisted-output equivalence: two pipelines over the same synthetic night,
        // one always computing spectral features (today's behavior), one skipping per the gate.
        // Same drafts (type/times/levels AND aggregated EventFeatures incl. the spectral means)
        // must come out — a skipped window is active on neither machine, so no persisted
        // feature ever comes from a skipped window.
        val quietBase = -80.0
        val floorDbfs = -80.0
        // (dbfs, lowBandRatio) per window: room tone, rumble (still below the loud line), the
        // ultra-quiet-room corner (loud yet below the gate threshold — the gate computes it),
        // a snore train, and the quiet gaps that close it.
        val sequence = buildList {
            repeat(12) { add(quietBase - 1.5 to 0.3) } // room tone: skip
            repeat(4) { add(quietBase + 2.0 to 0.4) } // rumble at -78: skip (!loud)
            repeat(3) { add(-65.0 to 0.6) } // loud corner: compute
            repeat(6) { add(-45.0 to 0.85) } // snore train: compute
            repeat(6) { add(quietBase - 1.0 to 0.3) } // close + hold: skip
            repeat(2) { add(-67.0 to 0.5) } // loud line boundary region: compute (loud, gated-open)
            repeat(8) { add(quietBase to 0.3) } // tail: skip
        }
        val computed = runDetector(sequence, floorDbfs, spectralForComputed = 0.42 to 0.77, gate = false)
        val gated = runDetector(sequence, floorDbfs, spectralForComputed = 0.42 to 0.77, gate = true)
        assertEquals(computed, gated)
        assertTrue(computed.isNotEmpty()) // sanity: the sequence really produced events
        // And the persisted spectral means exist in both — they came only from computed windows.
        assertTrue(computed.all { it.features.spectralFlatnessMean != null || it.type != "SNORE" })
    }

    /** Drives [EventDetector]+[EnergySnoreDetector] over [sequence]; [gate] applies the policy. */
    private fun runDetector(
        sequence: List<Pair<Double, Double>>,
        floorDbfs: Double,
        spectralForComputed: Pair<Double, Double>,
        gate: Boolean,
    ): List<EventDetector.EventDraft> {
        val detector = EnergySnoreDetector()
        val events = EventDetector()
        val drafts = mutableListOf<EventDetector.EventDraft>()
        sequence.forEachIndexed { i, (dbfs, ratio) ->
            val ts = 1000L + i * 500L
            val compute = !gate || SpectralGatePolicy.shouldComputeSpectral(
                windowDbfs = dbfs,
                noiseFloorDbfs = floorDbfs,
                detectorBusy = !events.isIdle(),
            )
            val (flatness, harmonic) = if (compute) spectralForComputed else (null to null)
            val result = detector.classify(
                AudioFeatures(
                    windowDbfs = dbfs,
                    noiseFloorDbfs = floorDbfs,
                    lowBandRatio = ratio,
                    timestampMs = ts,
                    spectralFlatness = flatness,
                    harmonicRatio = harmonic,
                ),
            )
            drafts += events.update(ts, features = AudioFeatures(
                windowDbfs = dbfs,
                noiseFloorDbfs = floorDbfs,
                lowBandRatio = ratio,
                timestampMs = ts,
                spectralFlatness = flatness,
                harmonicRatio = harmonic,
            ), result = result)
        }
        return drafts + events.flush()
    }

    private fun features(dbfs: Double, floor: Double, flatness: Double?, harmonic: Double?) =
        AudioFeatures(
            windowDbfs = dbfs,
            noiseFloorDbfs = floor,
            lowBandRatio = 0.8,
            timestampMs = 1000L,
            spectralFlatness = flatness,
            harmonicRatio = harmonic,
        )
}
