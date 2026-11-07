package com.sleepanalysis.data.files

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The clip write path's gain + peak-limit math ([ClipStore.gainAndLimit], [ClipStore.writeClip]).
 * Session 4 dogfood regression: 5/60 clips were written with full-scale PCM (LOUD snore peak
 * −10.3 dBFS recorded at 0 dBFS, 1,129 full-scale samples) because the old pipeline fed the
 * ring already-int16-gained samples. Clips are now written from RAW samples: mic gain applied
 * in float, then ONE block scale so the file peak lands at [ClipStore.MAX_CLIP_TARGET_DBFS].
 */
class ClipAudioTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val micGain = 2.5f

    /** The legacy AudioEngine.applyGain per-sample math — the pre-v11 clip content. */
    private fun legacyInt16Gain(raw: ShortArray, gain: Float = 2.5f): ShortArray =
        ShortArray(raw.size) { i ->
            val scaled = (raw[i] * gain).toInt()
            scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

    private fun peakOf(samples: ShortArray): Int = samples.maxOf { abs(it.toInt()) }

    private fun dbfs(amplitude: Int): Double = 20 * log10(amplitude / 32768.0)

    private fun fullScaleTargetAmp(): Int = (32768.0 * 10.0.pow(ClipStore.MAX_CLIP_TARGET_DBFS / 20.0)).toInt()

    // --- (a) loud signal: block-limited to the target with a reduced appliedGain ---

    @Test
    fun `a signal whose gained peak would exceed full scale is block-limited to the target`() {
        // Raw peak 19611: 19611 × 2.5 = 49027.5 ≈ +3.5 dBFS gained — far over int16 full scale.
        val raw = ShortArray(1_000) { if (it % 2 == 0) 19611 else -19611 }

        val gained = ClipStore.gainAndLimit(raw, micGain)

        assertTrue("appliedGain ${gained.appliedGain} must drop below the mic gain", gained.appliedGain < micGain)
        assertEquals(ClipStore.MAX_CLIP_TARGET_DBFS, gained.filePeakDbfs, 0.01)
        val peak = peakOf(gained.samples)
        assertTrue("peak $peak exceeds the −1 dBFS target", peak <= fullScaleTargetAmp())
        // ONE block scale, not per-sample limiting: every positive sample scales alike (and
        // every negative one), so the waveform shape survives — no flat tops.
        assertTrue(gained.samples.all { it == gained.samples[0] || it == gained.samples[1] })
    }

    // --- (b) quiet signal: bit-identical to the legacy int16 gain, full mic gain kept ---

    @Test
    fun `a quiet signal is bit-identical to the legacy int16 gain and keeps the full mic gain`() {
        // Gained float peak 11681 × 2.5 = 29202.5, just under the ≈29203.6 target → scale 1.0.
        // 101 × 2.5 = 252.5 probes the truncate-toward-zero parity with applyGain.
        val raw = shortArrayOf(0, 1, -1, 3, -7, 101, -101, 4095, -4095, 10000, -10000, 11681, -11681)
        val legacy = legacyInt16Gain(raw)

        val gained = ClipStore.gainAndLimit(raw, micGain)

        assertTrue(
            "quiet clips must be bit-identical to the legacy int16 ×2.5-clamped output",
            gained.samples.contentEquals(legacy),
        )
        assertEquals(2.5f, gained.appliedGain)
        assertEquals(dbfs(peakOf(legacy)), gained.filePeakDbfs, 1e-9)
    }

    // --- (c) THE key case: formerly-clipping raw stays under full scale ---

    @Test
    fun `raw samples that saturate under the legacy gain write with zero full-scale samples`() {
        // The session-4 clipping shape: raw peaks at the int16 edges. Under the legacy in-place
        // 2.5× int16 gain these clamped to ±32767 (0 dBFS flat tops) before the write.
        val raw = shortArrayOf(
            32767, -32768, 32767, -32767, 16000, -16000, 100, -100, 0, 32766, -32766,
        )
        assertTrue(
            "precondition: the legacy gain must clip this input (0 dBFS full-scale samples)",
            legacyInt16Gain(raw).any { abs(it.toInt()) >= 32767 },
        )

        val gained = ClipStore.gainAndLimit(raw, micGain)

        assertTrue(
            "no full-scale sample may survive: ${gained.samples.toList()}",
            gained.samples.none { abs(it.toInt()) >= 32767 },
        )
        assertEquals(ClipStore.MAX_CLIP_TARGET_DBFS, gained.filePeakDbfs, 0.01)
        assertTrue(gained.appliedGain < micGain)
    }

    // --- (d) int16 edge cases ---

    @Test
    fun `Short_MIN_VALUE input scales instead of wrapping or clamping`() {
        // −32768 × 2.5 = −81920: the legacy path clamps to −32768; the limiter must scale the
        // block so the negative peak also stays 1 dB under full scale.
        val gained = ClipStore.gainAndLimit(shortArrayOf(Short.MIN_VALUE), micGain)

        assertTrue(abs(gained.samples[0].toInt()) < 32767)
        assertEquals(ClipStore.MAX_CLIP_TARGET_DBFS, gained.filePeakDbfs, 0.01)
        assertTrue(gained.appliedGain < micGain)
    }

    @Test
    fun `all-max input scales uniformly to the target`() {
        val raw = ShortArray(64) { Short.MAX_VALUE }

        val gained = ClipStore.gainAndLimit(raw, micGain)

        // Single block scale: a uniform input stays uniform (no per-sample limiting artifacts).
        assertTrue(gained.samples.all { it == gained.samples[0] })
        assertEquals(ClipStore.MAX_CLIP_TARGET_DBFS, gained.filePeakDbfs, 0.01)
    }

    @Test
    fun `digital silence keeps the full mic gain and reports the silence floor`() {
        val gained = ClipStore.gainAndLimit(ShortArray(16), micGain)

        assertTrue(gained.samples.all { it.toInt() == 0 })
        assertEquals(2.5f, gained.appliedGain) // peak 0 must not divide the block scale by zero
        assertEquals(-100.0, gained.filePeakDbfs, 0.0)
    }

    // --- writeClip end-to-end: the FILE carries the limited samples ---

    @Test
    fun `writeClip persists the limited samples and returns the row metadata`() {
        val store = ClipStore(tmp.root)
        val raw = ShortArray(64) { if (it % 2 == 0) 32767 else -32768 }

        val written = store.writeClip(1, "SNORE", 1_000, raw, 16_000, micGain)

        assertTrue(written.file.exists())
        assertTrue(written.appliedGain < micGain)
        assertEquals(ClipStore.MAX_CLIP_TARGET_DBFS, written.filePeakDbfs, 0.01)
        // Read the data chunk back: no full-scale sample may reach the file.
        val bytes = written.file.readBytes()
        val writtenSamples = ShortArray((bytes.size - 44) / 2) { i ->
            val lo = bytes[44 + 2 * i].toInt() and 0xFF
            val hi = bytes[45 + 2 * i].toInt()
            (((hi shl 8) or lo).toShort())
        }
        assertEquals(raw.size, writtenSamples.size)
        assertTrue(writtenSamples.none { abs(it.toInt()) >= 32767 })
    }

    @Test
    fun `writeClip on a quiet signal reports the untouched mic gain`() {
        val store = ClipStore(tmp.root)
        val raw = ShortArray(64) { (it % 100).toShort() }

        val written = store.writeClip(1, "SNORE", 1_000, raw, 16_000, micGain)

        assertEquals(2.5f, written.appliedGain)
        assertEquals(dbfs(peakOf(legacyInt16Gain(raw))), written.filePeakDbfs, 1e-9)
    }
}
