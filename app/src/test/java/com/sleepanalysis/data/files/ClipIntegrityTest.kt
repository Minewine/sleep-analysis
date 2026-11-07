package com.sleepanalysis.data.files

import com.sleepanalysis.monitoring.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Clip-integrity scan: clipping detection at PCM16 full scale, plus graceful degradation for
 * missing/corrupt files. Uses the real WavWriter format (the app writes exactly this).
 */
class ClipIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `full-scale samples are counted as clipped`() {
        val samples = shortArrayOf(100, -200, 32_767, -32_768, 500)
        // -32768 and +32767 are both full-scale; note -32768 magnitude is 32768 ≥ 32767.
        assertEquals(2L, ClipIntegrity.countFullScaleSamples(samples))
    }

    @Test
    fun `ordinary audio is not clipped`() {
        assertEquals(0L, ClipIntegrity.countFullScaleSamples(shortArrayOf(0, 1, -1, 32_766, -32_766)))
    }

    @Test
    fun `a clipped written clip is detected and a clean one is not`() {
        val clean = tmp.newFile("clean.wav")
        val hot = tmp.newFile("hot.wav")
        val rate = 16_000
        WavWriter.writePcm16Mono(clean, ShortArray(rate * 2) { (it % 100 - 50).toShort() }, rate)
        WavWriter.writePcm16Mono(hot, ShortArray(rate) { 32_767 }, rate)

        val cleanCheck = ClipIntegrity.check(clean, "clean.wav")
        val hotCheck = ClipIntegrity.check(hot, "hot.wav")
        assertFalse(cleanCheck.clipped)
        assertTrue(hotCheck.clipped)
        assertEquals(rate.toLong(), hotCheck.clippedSamples)
        assertTrue(cleanCheck.exists && cleanCheck.readable)
    }

    @Test
    fun `missing and corrupt files degrade to flagged checks`() {
        val missing = ClipIntegrity.check(tmp.root.resolve("nope.wav"), "nope.wav")
        assertFalse(missing.exists)
        assertFalse(missing.clipped)

        val corrupt = tmp.newFile("corrupt.wav").apply { writeBytes(ByteArray(64) { 0x55 }) }
        val corruptCheck = ClipIntegrity.check(corrupt, "corrupt.wav")
        assertTrue(corruptCheck.exists)
        assertFalse(corruptCheck.readable)
        assertFalse(corruptCheck.clipped)
    }

    @Test
    fun `a valid non-mono or non-16-bit wav is unreadable not clipped`() {
        // Hand-built 2-channel PCM16 wav (WavWriter only emits mono): the reader must refuse
        // it loudly and the scan must degrade to readable=false rather than crash or lie.
        val stereo = tmp.newFile("stereo.wav")
        val sampleData = ByteArray(4) { 0 } // two mono-ish frames, interleaved
        val header = java.io.ByteArrayOutputStream().let { out ->
            fun le32(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
            fun le16(v: Int) { out.write(v); out.write(v shr 8) }
            out.write("RIFF".toByteArray()); le32(36 + sampleData.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(2) // PCM, stereo
            le32(16_000); le32(16_000 * 2 * 2); le16(4); le16(16)
            out.write("data".toByteArray()); le32(sampleData.size); out.write(sampleData)
            out.toByteArray()
        }
        stereo.writeBytes(header)
        val check = ClipIntegrity.check(stereo, "stereo.wav")
        assertTrue(check.exists)
        assertFalse(check.readable)
        assertFalse(check.clipped)
    }
}
