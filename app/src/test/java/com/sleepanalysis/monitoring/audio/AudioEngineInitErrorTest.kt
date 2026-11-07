package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.monitoring.detection.EnergySnoreDetector
import com.sleepanalysis.monitoring.detection.EventDetector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AudioEngine init-failure routing, on the JVM: the unit-test JVM has no audio hardware, so
 * every android.media call throws — exactly the "init throws" mode the capture loop must
 * survive. The failure must reach [AudioEngine.Listener.onCaptureError] (so the service
 * finalizes the session INTERRUPTED) instead of escaping the capture thread and killing the
 * process via the default uncaught handler.
 */
class AudioEngineInitErrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `init failure is reported via onCaptureError instead of killing the capture thread`() {
        val reported = CountDownLatch(1)
        val engine = AudioEngine(
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

                override fun onCaptureError(throwable: Throwable) {
                    reported.countDown()
                }

                override fun onLog(event: String, detail: String) = Unit
            },
        )
        engine.start()
        assertTrue(
            "capture thread died without reporting the init failure to the listener",
            reported.await(10, TimeUnit.SECONDS),
        )
        engine.stop()
    }
}
