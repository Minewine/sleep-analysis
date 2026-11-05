package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.monitoring.detection.AudioFeatures
import com.sleepanalysis.monitoring.detection.DetectionResult
import com.sleepanalysis.monitoring.detection.DetectorConfig
import com.sleepanalysis.monitoring.detection.EventDetector
import com.sleepanalysis.monitoring.replay.ReplayEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Config coherence between the engines and the detector they construct: AudioEngine and
 * ReplayEngine must pass their ACTUAL windowMs/hopMs into [EventDetector] — the detector's
 * event-start backdating (firstPendingTs − windowMs) and hold bounds silently assume them.
 * The engines' Config defaults equal the detector defaults, so the pass-through is
 * behavior-preserving; these tests pin both halves of that invariant.
 */
class DetectorConfigCoherenceTest {

    @Test
    fun `engine config defaults equal the detector defaults`() {
        assertEquals(DetectorConfig.WINDOW_MS, AudioEngine.Config().windowMs)
        assertEquals(DetectorConfig.HOP_MS, AudioEngine.Config().hopMs)
        assertEquals(DetectorConfig.WINDOW_MS, ReplayEngine.Config().windowMs)
        assertEquals(DetectorConfig.HOP_MS, ReplayEngine.Config().hopMs)
    }

    @Test
    fun `explicit detector config with the documented defaults behaves identically to the default constructor`() {
        // The exact arguments AudioEngine/ReplayEngine now pass, fed the merge-gap pattern
        // (exercises start backdating, deferred emission, and gap merging in one pass).
        val explicit = EventDetector(
            enterWindows = DetectorConfig.ENTER_WINDOWS,
            exitWindows = DetectorConfig.EXIT_WINDOWS,
            minDurationMs = DetectorConfig.MIN_DURATION_MS,
            mergeGapMs = DetectorConfig.MERGE_GAP_MS,
            windowMs = DetectorConfig.WINDOW_MS,
            hopMs = DetectorConfig.HOP_MS,
            gateMarginDb = DetectorConfig.GATE_MARGIN_DB,
            gateHardFloorDbfs = DetectorConfig.MIN_GATE_DBFS,
        )
        val pattern = List(4) { true to true } + List(5) { false to false } +
            List(4) { true to true } + List(4) { false to false }

        fun run(detector: EventDetector): List<Pair<Long, Long>> {
            val stamps = mutableListOf<Pair<Long, Long>>()
            pattern.forEachIndexed { i, (snore, loud) ->
                val ts = 1000L + i * 500
                stamps += detector.update(ts, features(ts), result(snore, loud)).map { it.startMs to it.endMs }
            }
            return stamps + detector.flush().map { it.startMs to it.endMs }
        }

        // Both merge the two spells into one event spanning 0..7000 (starts backdated by the
        // same windowMs), emitted at the same stamps.
        assertEquals(listOf(0L to 7000L), run(EventDetector()))
        assertEquals(listOf(0L to 7000L), run(explicit))
    }

    private fun features(ts: Long) = AudioFeatures(
        windowDbfs = -15.0,
        noiseFloorDbfs = -40.0,
        lowBandRatio = 0.8,
        timestampMs = ts,
    )

    private fun result(snore: Boolean, loud: Boolean) =
        DetectionResult(snore = snore, loudAcousticEvent = loud, confidence = if (snore) 0.8 else 0.0)
}
