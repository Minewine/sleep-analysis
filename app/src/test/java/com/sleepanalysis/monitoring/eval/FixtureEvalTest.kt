package com.sleepanalysis.monitoring.eval

import com.sleepanalysis.monitoring.audio.WavReader
import com.sleepanalysis.monitoring.audio.WavWriter
import com.sleepanalysis.monitoring.detection.AudioFeatures
import com.sleepanalysis.monitoring.detection.DetectionResult
import com.sleepanalysis.monitoring.detection.EnergySnoreDetector
import com.sleepanalysis.monitoring.detection.SnoreDetector
import com.sleepanalysis.monitoring.eval.FixtureEvaluator.FixtureEntry
import com.sleepanalysis.monitoring.replay.ReplayEngine
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Detector stub that records every window it sees (for asserting exact feature math). */
private class RecordingDetector(
    private val result: DetectionResult = DetectionResult(snore = false, loudAcousticEvent = false, confidence = 0.0),
) : SnoreDetector {
    val seen = mutableListOf<AudioFeatures>()
    var resets = 0

    override fun classify(features: AudioFeatures): DetectionResult {
        seen.add(features)
        return result
    }

    override fun reset() {
        resets++
    }
}

/** Snore-positive iff the window is dominated by low-band energy (200 Hz sines are). */
private class LowBandDetector(private val ratioMin: Double = 0.9) : SnoreDetector {
    override fun classify(features: AudioFeatures): DetectionResult {
        val snore = features.lowBandRatio >= ratioMin
        return DetectionResult(snore = snore, loudAcousticEvent = snore, confidence = if (snore) 0.9 else 0.0)
    }

    override fun reset() = Unit
}

class FixtureEvalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- WavReader ---------------------------------------------------------

    @Test
    fun `wav reader round-trips wav writer output`() {
        val file = File(tmp.root, "clip.wav")
        val samples = ShortArray(8_000) { ((it % 251) * 100).coerceAtMost(Short.MAX_VALUE.toInt()).toShort() }
        WavWriter.writePcm16Mono(file, samples, 16_000)

        val wav = WavReader.readPcm16Mono(file)
        assertEquals(16_000, wav.sampleRate)
        assertEquals(samples.size, wav.samples.size)
        assertTrue(wav.samples.contentEquals(samples))
    }

    @Test
    fun `wav reader walks chunks instead of assuming offset 44`() {
        val file = File(tmp.root, "extra-chunk.wav")
        val samples = shortArrayOf(1, -1, 2, -2)
        WavWriter.writePcm16Mono(file, samples, 16_000)
        // Splice a 12-byte LIST chunk between fmt and data, like some tooling does.
        val bytes = file.readBytes()
        val out = ByteArray(bytes.size + 12)
        System.arraycopy(bytes, 0, out, 0, 36) // header up to end of fmt
        out[36] = 'L'.code.toByte(); out[37] = 'I'.code.toByte(); out[38] = 'S'.code.toByte(); out[39] = 'T'.code.toByte()
        out[40] = 4; out[41] = 0; out[42] = 0; out[43] = 0
        out[44] = 1; out[45] = 2; out[46] = 3; out[47] = 4
        System.arraycopy(bytes, 36, out, 48, bytes.size - 36)
        file.writeBytes(out)

        val wav = WavReader.readPcm16Mono(file)
        assertTrue(wav.samples.contentEquals(samples))
    }

    @Test
    fun `wav reader skips the riff alignment pad byte after an odd-sized chunk`() {
        val file = File(tmp.root, "odd-list.wav")
        val samples = shortArrayOf(1, -1, 2, -2)
        WavWriter.writePcm16Mono(file, samples, 16_000)
        // Splice a 5-byte LIST chunk (odd size -> one RIFF alignment pad byte must follow)
        // between fmt and data; a reader that ignores the pad desyncs onto the data chunk.
        val bytes = file.readBytes()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val out = ByteArray(bytes.size + 8 + payload.size + 1) // id+size + payload + pad
        System.arraycopy(bytes, 0, out, 0, 36) // header up to end of fmt
        var o = 36
        out[o++] = 'L'.code.toByte(); out[o++] = 'I'.code.toByte(); out[o++] = 'S'.code.toByte(); out[o++] = 'T'.code.toByte()
        out[o++] = 5; out[o++] = 0; out[o++] = 0; out[o++] = 0
        System.arraycopy(payload, 0, out, o, payload.size); o += payload.size
        out[o++] = 0 // alignment pad
        System.arraycopy(bytes, 36, out, o, bytes.size - 36)
        file.writeBytes(out)

        val wav = WavReader.readPcm16Mono(file)
        assertTrue(wav.samples.contentEquals(samples))
    }

    @Test
    fun `wav reader accepts other valid rates — the evaluator skips them by rate`() {
        // WavReader mirrors WavWriter, which writes any rate; rejecting 44.1 kHz here would
        // make a rate check impossible at the eval layer (it needs the actual rate to skip).
        val file = File(tmp.root, "44k.wav")
        val samples = shortArrayOf(0, 1, -1)
        WavWriter.writePcm16Mono(file, samples, 44_100)
        val wav = WavReader.readPcm16Mono(file)
        assertEquals(44_100, wav.sampleRate)
        assertTrue(wav.samples.contentEquals(samples))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wav reader rejects a truncated file`() {
        val file = File(tmp.root, "short.wav")
        WavWriter.writePcm16Mono(file, ShortArray(100), 16_000)
        file.writeBytes(file.readBytes().copyOfRange(0, 60)) // cut mid-data
        WavReader.readPcm16Mono(file)
    }

    @Test
    fun `wav reader rejects a data chunk larger than the file without a huge allocation`() {
        // Corrupt header: the data chunk size field (offset 40 in the 44-byte header) claims
        // 1 GiB on a 52-byte file — bounded against the file length, failing loudly instead
        // of allocating toward an OOM in the eval harness.
        val file = File(tmp.root, "corrupt-size.wav")
        WavWriter.writePcm16Mono(file, shortArrayOf(1, -1), 16_000)
        val bytes = file.readBytes()
        bytes[40] = 0x00
        bytes[41] = 0x00
        bytes[42] = 0x00
        bytes[43] = 0x40
        file.writeBytes(bytes)

        val thrown = try {
            WavReader.readPcm16Mono(file)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("expected a descriptive IllegalArgumentException", thrown != null)
        assertTrue(thrown!!.message!!.contains("exceeds file length"))
    }

    // --- ReplayEngine ------------------------------------------------------

    private fun sineSamples(count: Int, freqHz: Int, amplitude: Int): ShortArray {
        val phasePerSample = 2.0 * PI * freqHz / 16_000.0
        return ShortArray(count) { (amplitude * sin(it * phasePerSample)).toInt().toShort() }
    }

    @Test
    fun `replay feature math matches the live window formulas`() {
        val detector = RecordingDetector()
        // 2 s of a 200 Hz sine at amplitude 8000: rms = 8000/sqrt(2) -> -15.26 dBFS, and the
        // one-pole low pass (alpha 0.32, fc ~1.2 kHz at 16 kHz) passes 200 Hz nearly unattenuated.
        val result = ReplayEngine(detector).replay(sineSamples(32_000, 200, 8_000))

        assertEquals(3, result.windows.size)
        for (w in result.windows) {
            assertEquals(-15.26, w.windowDbfs, 0.1)
            assertTrue("low-band ratio for a 200 Hz sine must be near 1", w.lowBandRatio > 0.9)
        }
        // Noise floor = 20th percentile of the windows seen so far (ring starts empty per clip).
        val w1 = result.windows[0]
        val w2 = result.windows[1]
        val w3 = result.windows[2]
        assertEquals(w1.windowDbfs, w1.noiseFloorDbfs, 0.0)                    // floor = itself
        assertEquals(minOf(w1.windowDbfs, w2.windowDbfs), w2.noiseFloorDbfs, 0.0)
        assertEquals(minOf(w1.windowDbfs, w2.windowDbfs, w3.windowDbfs), w3.noiseFloorDbfs, 1e-9)
    }

    @Test
    fun `replay window counts and window-end timestamps mirror the live loop`() {
        val detector = RecordingDetector()
        val engine = ReplayEngine(detector)

        assertEquals(1, engine.replay(ShortArray(16_000)).windows.size)
        assertEquals(listOf(1000L), detector.seen.map { it.timestampMs })

        detector.seen.clear()
        val result = engine.replay(ShortArray(32_000))
        assertEquals(3, result.windows.size) // window ends after 16000/24000/32000 samples
        assertEquals(listOf(1000L, 1500L, 2000L), detector.seen.map { it.timestampMs })
        assertEquals(result.windows.map { it.timestampMs }, detector.seen.map { it.timestampMs })
    }

    @Test
    fun `replay ignores a clip shorter than one full window`() {
        val detector = RecordingDetector()
        val result = ReplayEngine(detector).replay(ShortArray(15_999))
        assertEquals(0, result.windows.size)
        assertTrue(result.events.isEmpty())
        assertTrue(detector.seen.isEmpty())
    }

    @Test
    fun `replay detects a snore-like sine and emits a flush-closed event`() {
        // 1.5 s of near-silence then 3 s of sine: the noise floor is pinned at the silence
        // level, so the sine windows clear every relative threshold; the event closes via
        // flush() at the end of the clip (not the exit-window timeout).
        val samples = ShortArray(72_000).also {
            val sine = sineSamples(56_000, 200, 8_000)
            System.arraycopy(sine, 0, it, 16_000, 56_000)
        }
        val result = ReplayEngine(EnergySnoreDetector()).replay(samples)

        assertEquals(8, result.windows.size) // window ends 1000..4500 ms, hop 500 ms
        assertTrue(result.windows.drop(1).take(4).all { it.snore }) // windows 2..5
        assertEquals(1, result.events.size)
        val event = result.events.single()
        assertEquals("SNORE", event.type) // well above the relative level gate
        assertEquals(500L, event.startMs) // backdated from the first pending window end (1500)
        assertEquals(3000L, event.endMs)  // last active window end (window 5)
    }

    // --- FixtureEvaluator --------------------------------------------------

    private fun clip(name: String, snoreLike: Boolean): File {
        val file = File(tmp.root, name)
        val samples = if (snoreLike) {
            sineSamples(160_000, 200, 8_000) // 10 s of low-band tone -> ratio ~0.98
        } else {
            ShortArray(160_000).also { Random(42).let { r -> for (i in it.indices) it[i] = (r.nextInt(600) - 300).toShort() } }
        }
        WavWriter.writePcm16Mono(file, samples, 16_000)
        return file
    }

    @Test
    fun `evaluator computes per-label precision and recall one-vs-rest`() {
        val snoreA = clip("snore_a.wav", snoreLike = true)
        val snoreB = clip("snore_b.wav", snoreLike = true)
        val cough = clip("cough.wav", snoreLike = true) // misclassified: low-band like a snore
        val speech = clip("speech.wav", snoreLike = false)
        val unlabeled = clip("unknown.wav", snoreLike = false)
        val entries = listOf(
            FixtureEntry(snoreA, "SNORE", "SNORE"),
            FixtureEntry(snoreB, "SNORE", "SNORE"),
            FixtureEntry(cough, "OTHER_NOISE", "COUGH"),
            FixtureEntry(speech, "OTHER_NOISE", "SPEECH"),
            FixtureEntry(unlabeled, "OTHER_NOISE", null),
        )

        val report = FixtureEvaluator().evaluate(entries, LowBandDetector())

        assertEquals(5, report.scores.size) // unlabeled clip still scored...
        assertEquals(1, report.unlabeledClips)
        assertEquals(listOf("COUGH", "SNORE", "SPEECH"), report.metrics.map { it.label })

        val snore = report.metrics.first { it.label == "SNORE" }
        assertEquals(2, snore.clips)
        assertEquals(2, snore.truePositive)
        assertEquals(0, snore.falseNegative)
        assertEquals(1, snore.falsePositive) // the cough clip reads snore-positive
        assertEquals(1, snore.trueNegative)  // the speech clip
        assertEquals(1.0, snore.recall()!!, 1e-9)
        assertEquals(2.0 / 3.0, snore.precision()!!, 1e-9)

        val coughMetrics = report.metrics.first { it.label == "COUGH" }
        assertEquals(1, coughMetrics.clips)
        assertEquals(1, coughMetrics.truePositive) // misclassified clip is a TP for COUGH itself
        assertEquals(2, coughMetrics.falsePositive) // the two SNORE clips against COUGH-as-class
        assertEquals(1.0, coughMetrics.recall()!!, 1e-9)
        assertEquals(1.0 / 3.0, coughMetrics.precision()!!, 1e-9)

        val speechMetrics = report.metrics.first { it.label == "SPEECH" }
        assertEquals(0.0, speechMetrics.recall()!!, 1e-9)
        assertEquals(0.0, speechMetrics.precision()!!, 1e-9) // no TP, 3 FP
    }

    @Test
    fun `clip positive threshold applies to the snore window ratio`() {
        // Flags exactly every other window -> ratio 0.5: positive at 0.5 (>=), negative at 0.6.
        val alternating = object : SnoreDetector {
            private var i = 0
            override fun classify(features: AudioFeatures): DetectionResult =
                DetectionResult(i++ % 2 == 0, false, 0.0)

            override fun reset() {
                i = 0
            }
        }
        val file = clip("half.wav", snoreLike = false) // audio content irrelevant to the stub
        val entry = listOf(FixtureEntry(file, "SNORE", "SNORE"))
        val evaluator = FixtureEvaluator()

        assertTrue(evaluator.evaluate(entry, alternating, 0.5).scores.single().snorePositive)
        assertFalse(evaluator.evaluate(entry, alternating, 0.6).scores.single().snorePositive)
    }

    @Test
    fun `evaluator skips clips at other sample rates`() {
        val wrongRate = File(tmp.root, "44k.wav")
        WavWriter.writePcm16Mono(wrongRate, ShortArray(1600), 44_100)
        val report = FixtureEvaluator().evaluate(
            listOf(FixtureEntry(wrongRate, "SNORE", "SNORE")),
            LowBandDetector(),
        )
        assertTrue(report.scores.isEmpty())
        assertEquals(1, report.skippedClips)
        assertEquals(0, report.metrics.size)
    }

    @Test
    fun `evaluator counts missing decode-failed and rate-mismatched clips separately`() {
        val missing = File(tmp.root, "absent.wav") // never created: pulled without its clip
        val corrupt = File(tmp.root, "corrupt.wav")
        WavWriter.writePcm16Mono(corrupt, shortArrayOf(1, -1), 16_000)
        val bytes = corrupt.readBytes()
        bytes[40] = 0x00
        bytes[41] = 0x00
        bytes[42] = 0x00
        bytes[43] = 0x40 // data chunk size 1 GiB on a tiny file — the corrupt-header path
        corrupt.writeBytes(bytes)
        val wrongRate = File(tmp.root, "44k.wav")
        WavWriter.writePcm16Mono(wrongRate, ShortArray(1600), 44_100)
        val good = clip("good.wav", snoreLike = false)

        val report = FixtureEvaluator().evaluate(
            listOf(
                FixtureEntry(missing, "SNORE", "SNORE"),
                FixtureEntry(corrupt, "SNORE", "SNORE"),
                FixtureEntry(wrongRate, "SNORE", "SNORE"),
                FixtureEntry(good, "SNORE", "SNORE"),
            ),
            LowBandDetector(),
        )

        assertEquals(1, report.missingFiles)
        assertEquals(1, report.decodeFailures)
        assertEquals(1, report.rateMismatches)
        assertEquals(2, report.skippedClips) // aggregate over existing-but-unscored clips, unchanged
        assertEquals(1, report.scores.size)

        // The breakdown is additive in the JSON report shape.
        val json = FixtureEvaluator().toJson(report)
        assertEquals(1, json.getInt("missing_files"))
        assertEquals(1, json.getInt("decode_failures"))
        assertEquals(1, json.getInt("rate_mismatches"))
        assertTrue(FixtureEvaluator().summaryText(report).contains("decode_failed=1"))
    }

    @Test
    fun `json report round-trips`() {
        val entries = listOf(
            FixtureEntry(clip("s.wav", snoreLike = true), "SNORE", "SNORE"),
            FixtureEntry(clip("n.wav", snoreLike = false), "OTHER_NOISE", "SPEECH"),
        )
        val evaluator = FixtureEvaluator()
        val report = evaluator.evaluate(entries, LowBandDetector())

        val json = evaluator.toJson(report)
        assertEquals(report.detectorVersion, json.getString("detector_version"))
        assertEquals(report.clipPositiveThreshold, json.getDouble("clip_positive_threshold"), 0.0)
        assertEquals(report.scores.size, json.getJSONArray("scores").length())
        assertEquals(report.metrics.size, json.getJSONArray("metrics").length())

        val snore = json.getJSONArray("metrics").getJSONObject(0) // labels sorted: SNORE < SPEECH
        assertEquals("SNORE", snore.getString("label"))
        assertEquals(1, snore.getInt("tp"))
        assertEquals(1.0, snore.getDouble("recall"), 1e-9)
        assertFalse(json.getJSONArray("scores").getJSONObject(1).getBoolean("snore_positive"))
    }

    @Test
    fun `summary text lists every label row`() {
        val entries = listOf(
            FixtureEntry(clip("s.wav", snoreLike = true), "SNORE", "SNORE"),
            FixtureEntry(clip("n.wav", snoreLike = false), "OTHER_NOISE", "SPEECH"),
        )
        val report = FixtureEvaluator().evaluate(entries, LowBandDetector())
        val summary = FixtureEvaluator().summaryText(report)

        assertTrue(summary.contains("label"))
        assertTrue(summary.contains("SNORE"))
        assertTrue(summary.contains("SPEECH"))
        assertTrue(summary.contains("threshold=0.5"))
    }
}
