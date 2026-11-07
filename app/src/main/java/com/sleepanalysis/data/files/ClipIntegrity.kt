package com.sleepanalysis.data.files

import com.sleepanalysis.monitoring.audio.WavReader
import java.io.File

/**
 * Clip-audio quality check for the report's data-quality banners (docs/NIGHT_REPORT.md).
 * Non-destructive read-only scan: a clip is CLIPPED when its PCM16 samples reach full scale
 * (|sample| ≥ 32767) — the fixed capture gain saturates the loudest events, and the report
 * must say so next to the play button. Missing/unreadable files are reported as such rather
 * than thrown: the banner is non-blocking evidence about storage, not a playback gate.
 *
 * Runs off the capture path on already-persisted files; the report ViewModel calls it on a
 * background dispatcher and never blocks first render.
 */
object ClipIntegrity {

    /**
     * Full-scale PCM16 magnitude — a reached sample means the waveform was clipped. The
     * negative check accepts −32767 (one LSB conservative on the negative side: a sample at
     * −32767 is indistinguishable from a clipped ramp at this resolution).
     */
    const val PCM16_FULL_SCALE = 32_767

    data class ClipCheck(
        val filePath: String,
        val exists: Boolean,
        /** True when the decoded audio contains full-scale samples (digital clipping). */
        val clipped: Boolean = false,
        /** How many samples sit at full scale (0 when not clipped). */
        val clippedSamples: Long = 0,
        /** False when the file exists but could not be decoded (corrupt header, truncation). */
        val readable: Boolean = true,
    )

    /** Full-scale samples in decoded PCM16 mono — the pure core, unit tested. */
    fun countFullScaleSamples(samples: ShortArray): Long = samples.count { s ->
        val v = s.toInt()
        v >= PCM16_FULL_SCALE || v <= -PCM16_FULL_SCALE
    }.toLong()

    /** Scans one clip file; missing/unreadable files degrade to a non-clipped, flagged check. */
    fun check(file: File, filePath: String): ClipCheck {
        if (!file.exists()) return ClipCheck(filePath, exists = false)
        val pcm = try {
            WavReader.readPcm16Mono(file)
        } catch (_: Exception) {
            return ClipCheck(filePath, exists = true, readable = false)
        }
        val clipped = countFullScaleSamples(pcm.samples)
        return ClipCheck(
            filePath = filePath,
            exists = true,
            clipped = clipped > 0,
            clippedSamples = clipped,
        )
    }
}
