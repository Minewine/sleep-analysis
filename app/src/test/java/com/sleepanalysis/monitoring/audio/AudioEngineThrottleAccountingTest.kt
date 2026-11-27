package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.domain.report.CaptureStats
import com.sleepanalysis.monitoring.detection.DetectorConfig
import com.sleepanalysis.monitoring.detection.EnergySnoreDetector
import com.sleepanalysis.monitoring.detection.EventDetector
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Hop accounting under thermal throttling, on the JVM (docs/DATA_MODEL.md §5.1). The unit-test
 * JVM has no audio hardware, so the test feeds silent windows straight through the analysis
 * path instead of starting the capture thread. The contract: each captured hop increments
 * exactly one of the two counters (capture_stats schema v5), so N hops with throttling on
 * capture exactly N × hop of analysis time. Before the fix a throttled hop incremented both
 * counters and the captured time inflated to (N + skipped) × hop.
 */
class AudioEngineThrottleAccountingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun engine(): AudioEngine = AudioEngine(
        sessionId = 1L,
        detector = EnergySnoreDetector(),
        clipStore = ClipStore(tmp.root),
        listener = object : AudioEngine.Listener {
            override fun onEventFinalized(
                draft: EventDetector.EventDraft,
                clipRelativePath: String?,
                clipDurationMs: Long,
                clipSizeBytes: Long,
                appliedGain: Float?,
                filePeakDbfs: Double?,
            ) = Unit

            override fun onCaptureError(throwable: Throwable) = Unit
            override fun onLog(event: String, detail: String) = Unit
        },
    )

    /** Feeds [count] silent windows through [AudioEngine.analyzeWindow] without the capture thread. */
    private fun feedSilentHops(engine: AudioEngine, count: Int) {
        val silence = ShortArray((DetectorConfig.SAMPLE_RATE * DetectorConfig.WINDOW_MS / 1000).toInt())
        for (hop in 0 until count) engine.analyzeWindow(silence, hop.toLong())
    }

    @Test
    fun `n hops with thermal throttling capture exactly n hops of analysis time`() {
        val engine = engine()
        engine.setThrottled(true)
        feedSilentHops(engine, 100)

        val stats = engine.stats()
        assertEquals(50L, stats.windowsProcessed) // every 2nd hop is analyzed while throttled
        assertEquals(50L, stats.windowsSkipped)

        // Through the real write path: finalize JSON → parsed stats → captured analysis time.
        val capturedMs = CaptureStats.parse(captureStatsJson(stats))!!.capturedAnalysisMs()
        assertEquals(100 * CaptureStats.HOP_MS, capturedMs)
    }

    @Test
    fun `n unthrottled hops capture exactly n hops of analysis time`() {
        val engine = engine()
        feedSilentHops(engine, 100)

        val stats = engine.stats()
        assertEquals(100L, stats.windowsProcessed)
        assertEquals(0L, stats.windowsSkipped)
        assertEquals(100 * CaptureStats.HOP_MS, CaptureStats.parse(captureStatsJson(stats))!!.capturedAnalysisMs())
    }
}
