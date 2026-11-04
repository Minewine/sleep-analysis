package com.sleepanalysis.monitoring.audio

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Minimal PCM16 mono WAV writer (44-byte RIFF header). Pure JVM — unit tested.
 */
object WavWriter {

    fun writePcm16Mono(file: File, samples: ShortArray, sampleRate: Int) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            val dataSize = samples.size * 2
            out.writeBytes("RIFF")
            writeIntLe(out, 36 + dataSize)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeIntLe(out, 16)          // fmt chunk size
            writeShortLe(out, 1)         // PCM
            writeShortLe(out, 1)         // mono
            writeIntLe(out, sampleRate)
            writeIntLe(out, sampleRate * 2) // byte rate
            writeShortLe(out, 2)         // block align
            writeShortLe(out, 16)        // bits per sample
            out.writeBytes("data")
            writeIntLe(out, dataSize)
            for (s in samples) {
                writeShortLe(out, s.toInt() and 0xFFFF)
            }
        }
    }

    private fun writeIntLe(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLe(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
