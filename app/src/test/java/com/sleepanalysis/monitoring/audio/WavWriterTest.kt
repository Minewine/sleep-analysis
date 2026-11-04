package com.sleepanalysis.monitoring.audio

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writes a valid 44-byte-header PCM16 mono wav`() {
        val file = File(tmp.root, "clip.wav")
        val samples = ShortArray(16_000) { (it % 327).toShort() } // 1 s at 16 kHz
        WavWriter.writePcm16Mono(file, samples, 16_000)

        assertEquals(44L + samples.size * 2L, file.length())

        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
            assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
            fun leInt(offset: Int) =
                (header[offset].toInt() and 0xFF) or
                    ((header[offset + 1].toInt() and 0xFF) shl 8) or
                    ((header[offset + 2].toInt() and 0xFF) shl 16) or
                    ((header[offset + 3].toInt() and 0xFF) shl 24)
            fun leShort(offset: Int) =
                (header[offset].toInt() and 0xFF) or ((header[offset + 1].toInt() and 0xFF) shl 8)
            assertEquals(36 + samples.size * 2, leInt(4))    // riff size
            assertEquals(1, leShort(20))                     // PCM format code
            assertEquals(1, leShort(22))                     // mono
            assertEquals(16_000, leInt(24))                  // sample rate
            assertEquals(16, leShort(34))                    // bits
            assertEquals(samples.size * 2, leInt(40))        // data size
            // First data byte must be the low byte of the first sample (file offset 44).
            raf.seek(44)
            assertEquals(samples[0].toInt() and 0xFF, raf.read())
        }
    }

    @Test
    fun `negative samples wrap correctly in little-endian`() {
        val file = File(tmp.root, "neg.wav")
        WavWriter.writePcm16Mono(file, shortArrayOf(-1), 16_000)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(44)
            val low = raf.read()
            val high = raf.read()
            assertEquals(0xFF, low)
            assertEquals(0xFF, high)
        }
        assertTrue(file.length() == 46L)
    }
}
