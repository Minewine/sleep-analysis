package com.sleepanalysis.monitoring.audio

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

/**
 * Minimal PCM16 mono WAV reader mirroring [WavWriter] (44-byte RIFF header) — the inverse
 * direction of the same format, so the fixture-eval harness (docs/NEXT_STEPS.md P2, the offline
 * replay of recorded clips through [com.sleepanalysis.monitoring.detection.SnoreDetector]) can
 * read back exactly what the app writes. Pure JVM — unit tested.
 *
 * Chunks are walked rather than assumed at offset 44: the writer only ever emits fmt+data, but
 * some tooling inserts extra chunks (LIST, fact) and a reader that hard-codes offset 44 would
 * silently decode garbage. Anything not PCM/mono/16-bit throws [IllegalArgumentException] —
 * a fixture that decodes wrong is worse than one that fails loudly.
 */
object WavReader {

    /** Decoded PCM16 mono audio. */
    data class Pcm16Wav(val samples: ShortArray, val sampleRate: Int) {
        override fun equals(other: Any?): Boolean =
            other is Pcm16Wav && sampleRate == other.sampleRate && samples.contentEquals(other.samples)

        override fun hashCode(): Int = 31 * sampleRate + samples.contentHashCode()
    }

    fun readPcm16Mono(file: File): Pcm16Wav =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { src ->
            expectTag(src, "RIFF", "not a RIFF file")
            val riffSize = readIntLe(src)
            expectTag(src, "WAVE", "not a WAVE file")
            if (riffSize < 4) throw IllegalArgumentException("$file: RIFF size ${riffSize} too small")

            var fmt: FmtChunk? = null
            var data: ByteArray? = null
            while (fmt == null || data == null) {
                val id = try {
                    readTag(src)
                } catch (_: EOFException) {
                    null
                } ?: throw IllegalArgumentException(
                    "$file: " + if (fmt == null) "no fmt chunk" else "no data chunk"
                )
                val size = readIntLe(src)
                when (id) {
                    "fmt " -> {
                        if (size < 16) throw IllegalArgumentException("$file: fmt chunk truncated ($size bytes)")
                        val audioFormat = readShortLe(src)
                        val channels = readShortLe(src)
                        val sampleRate = readIntLe(src)
                        // Byte rate / block align are read past big-endian and intentionally
                        // ignored: decoding needs only sample rate, channel count, and bit depth.
                        src.readInt()          // byte rate
                        src.readShort()        // block align
                        val bitsPerSample = readShortLe(src)
                        // RIFF pads every odd-sized chunk to even length with one byte; the pad
                        // must be skipped too or every later chunk id/size reads one byte off.
                        skipFully(src, size - 16 + (size and 1))
                        if (audioFormat != 1) {
                            throw IllegalArgumentException("$file: not PCM (format code $audioFormat)")
                        }
                        if (channels != 1) {
                            throw IllegalArgumentException("$file: not mono ($channels channels)")
                        }
                        if (bitsPerSample != 16) {
                            throw IllegalArgumentException("$file: not 16-bit ($bitsPerSample-bit)")
                        }
                        fmt = FmtChunk(sampleRate)
                    }
                    "data" -> {
                        if (size < 0) throw IllegalArgumentException("$file: bad data chunk size $size")
                        // Corrupt-header bound: a data chunk can never exceed the file it lives
                        // in. Allocating [size] bytes from a garbage size field (e.g. 0x7FFFFFFF)
                        // would OOM the eval harness — fail loudly instead, before allocating.
                        val fileLength = file.length()
                        if (size.toLong() > fileLength) {
                            throw IllegalArgumentException(
                                "$file: data chunk size $size exceeds file length $fileLength (corrupt header)",
                            )
                        }
                        val bytes = try {
                            ByteArray(size).also { src.readFully(it) }
                        } catch (_: EOFException) {
                            throw IllegalArgumentException("$file: truncated data chunk (expected $size bytes)")
                        }
                        data = bytes
                        // Same alignment pad after an odd-sized data chunk — only relevant while
                        // the chunk walk continues (a missing fmt would otherwise desync us).
                        if (size and 1 == 1 && fmt == null) skipFully(src, 1)
                    }
                    else -> skipFully(src, size + (size and 1))
                }
            }

            val bytes = data!!
            val samples = ShortArray(bytes.size / 2) { i ->
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt() and 0xFF
                ((hi shl 8) or lo).toShort()
            }
            Pcm16Wav(samples, fmt.sampleRate)
        }

    private data class FmtChunk(val sampleRate: Int)

    private fun readTag(src: DataInputStream): String {
        val b = ByteArray(4)
        src.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun expectTag(src: DataInputStream, tag: String, message: String) {
        val b = ByteArray(4)
        try {
            src.readFully(b)
        } catch (_: EOFException) {
            throw IllegalArgumentException("truncated file: expected $tag")
        }
        val got = String(b, Charsets.US_ASCII)
        if (got != tag) throw IllegalArgumentException("$message (found \"$got\", expected \"$tag\")")
    }

    private fun readIntLe(src: DataInputStream): Int {
        val b = ByteArray(4)
        src.readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLe(src: DataInputStream): Int {
        val b = ByteArray(2)
        src.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun skipFully(src: DataInputStream, n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = src.skip(remaining.toLong())
            if (skipped <= 0) throw IllegalArgumentException("truncated chunk (expected $n more bytes)")
            remaining -= skipped.toInt()
        }
    }
}
