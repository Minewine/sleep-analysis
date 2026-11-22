package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.domain.report.ClientSilencedSpan
import com.sleepanalysis.monitoring.detection.EnergySnoreDetector
import com.sleepanalysis.monitoring.detection.EventDetector
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Silenced-client span accounting (P2-4): when Android silences this client (mic privacy
 * toggle, a call, another app taking the mic) the OS delivers zeros without any error — the
 * engine must record the exact spans so the report can show them as NOT captured. The unit
 * JVM has no audio hardware, so the [AudioManager.AudioRecordingCallback] path is driven
 * through its seam ([AudioEngine.onClientSilenceChanged]) instead of a real callback.
 */
class AudioEngineSilenceSpanTest {

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

    @Test
    fun `a silenced-client span is recorded and closed`() {
        val engine = engine()
        engine.onClientSilenceChanged(true, 1_000L)
        engine.onClientSilenceChanged(false, 61_000L)
        engine.onClientSilenceChanged(true, 70_000L)
        engine.onClientSilenceChanged(false, 130_000L)

        assertEquals(
            listOf(ClientSilencedSpan(1_000, 61_000), ClientSilencedSpan(70_000, 130_000)),
            engine.stats().clientSilencedSpans,
        )
    }

    @Test
    fun `a span still open at stats time is not reported until closed`() {
        val engine = engine()
        engine.onClientSilenceChanged(true, 1_000L)
        assertEquals(0, engine.stats().clientSilencedSpans.size)
        engine.onClientSilenceChanged(false, 61_000L)
        assertEquals(listOf(ClientSilencedSpan(1_000, 61_000)), engine.stats().clientSilencedSpans)
    }

    @Test
    fun `no silence produces no spans`() {
        val engine = engine()
        engine.onClientSilenceChanged(false, 1_000L)
        engine.onClientSilenceChanged(false, 61_000L)
        assertEquals(0, engine.stats().clientSilencedSpans.size)
    }
}
