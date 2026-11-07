package com.sleepanalysis.data.files

import com.sleepanalysis.monitoring.audio.WavWriter
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * App-private clip storage: `<root>/session_<id>/evt_<type>_<startMs>_<n>.wav`.
 * Retention is enforced by RetentionWorker and delete paths (docs/DATA_MODEL.md §6).
 *
 * Clips are written from RAW (pre-gain) ring samples: [writeClip] re-applies the mic gain in
 * float and block-peak-limits the whole clip to [MAX_CLIP_TARGET_DBFS], so a loud event can
 * never clip the stored file to full-scale PCM (session 4 dogfood: 5/60 clips written with
 * flat-topped 0 dBFS peaks because the old in-place int16 gain saturated before the write).
 * Rows written before schema v11 carry the fixed in-place 2.5× int16 gain as `appliedGain`
 * (that default is truthful history) and a NULL `filePeakDbfs`.
 */
class ClipStore(private val rootDir: File) {

    /** [writeClip] result: the file plus the audio_clip metadata of what was stored. */
    data class WrittenClip(
        val file: File,
        /** Total gain applied to the stored samples: micGain × block peak-limit scale. */
        val appliedGain: Float,
        /** Peak of the stored samples in dBFS (≈ [MAX_CLIP_TARGET_DBFS] for limited clips). */
        val filePeakDbfs: Double,
    )

    /** Pure gain + limit result — [gainAndLimit] output, before any file I/O. */
    class GainedClip(
        val samples: ShortArray,
        /** Total gain applied to [samples]: micGain × block peak-limit scale. */
        val appliedGain: Float,
        /** Peak of [samples] in dBFS ([SILENT_DBFS] for digital silence). */
        val filePeakDbfs: Double,
    )

    init {
        rootDir.mkdirs()
    }

    val root: File get() = rootDir

    fun sessionDir(sessionId: Long): File = File(rootDir, "session_$sessionId")

    /** Process-lifetime clip counter — disambiguates events that share a start timestamp. */
    private val clipCounter = AtomicLong()

    /**
     * Writes a PCM16 mono WAV clip from RAW (pre-gain) samples; returns the file plus the
     * persisted audio_clip metadata. [micGain] is applied in FLOAT per sample and the whole
     * clip is block-peak-limited to [MAX_CLIP_TARGET_DBFS] — one scale factor for the clip,
     * never per-sample limiting — so loud events keep their waveform shape (no 0 dBFS flat
     * tops) at ≈ −1 dBFS peak. Quiet clips (gained float peak already under the target) are
     * written exactly as the legacy in-place int16 × micGain path did, with the full mic
     * gain. Blocking file I/O — call from an IO dispatcher. Names are collision-safe: two
     * events can share an eventStartMs (a snore ending exactly as another event begins,
     * merged windows), so the name carries the event type and a monotonic counter, never
     * just the timestamp.
     */
    fun writeClip(
        sessionId: Long,
        eventType: String,
        eventStartMs: Long,
        rawSamples: ShortArray,
        sampleRate: Int,
        micGain: Float,
    ): WrittenClip {
        val gained = gainAndLimit(rawSamples, micGain)
        val dir = sessionDir(sessionId).apply { mkdirs() }
        val name = "evt_${eventType.lowercase()}_${eventStartMs}_${clipCounter.incrementAndGet()}.wav"
        val file = File(dir, name)
        WavWriter.writePcm16Mono(file, gained.samples, sampleRate)
        return WrittenClip(file, gained.appliedGain, gained.filePeakDbfs)
    }

    fun absolutePath(relativePath: String): File = File(rootDir, relativePath)

    fun totalBytes(): Long = rootDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun deleteSessionClips(sessionId: Long) {
        sessionDir(sessionId).deleteRecursively()
    }

    fun deleteAll() {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
    }

    /** Relative path (for DB storage) of a file inside the clip root. */
    fun relativePath(file: File): String = file.relativeTo(rootDir).invariantSeparatorsPath

    /**
     * Removes clip files that have no database row (orphan sweep). Returns bytes freed.
     * [isSweepable] vets each orphan by its age in ms (now − lastModified) so the caller owns
     * the sweep policy — RetentionWorker never deletes files an active session may not have
     * inserted yet. Empty session directories are pruned afterwards.
     */
    fun deleteOrphans(
        referencedRelativePaths: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
        isSweepable: (fileAgeMs: Long) -> Boolean = { true },
    ): Long {
        var freed = 0L
        rootDir.walkBottomUp().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(rootDir).invariantSeparatorsPath
            if (rel !in referencedRelativePaths && isSweepable(nowMs - file.lastModified())) {
                freed += file.length()
                file.delete()
            }
        }
        // Prune empty session directories.
        rootDir.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }?.forEach { it.delete() }
        return freed
    }

    companion object {
        /**
         * Block peak-limit target for written clips — 1 dB of headroom below full scale.
         * Applied as ONE scale factor per clip ([gainAndLimit]), never per-sample limiting,
         * so a limited clip keeps its waveform shape.
         */
        const val MAX_CLIP_TARGET_DBFS = -1.0

        /** Full-scale amplitude of [MAX_CLIP_TARGET_DBFS] (int16, 32768 = 0 dBFS): ≈ 29203.6. */
        private val MAX_CLIP_TARGET_AMP = 32768.0 * 10.0.pow(MAX_CLIP_TARGET_DBFS / 20.0)

        /** dBFS reported for digital silence — AudioEngine's window-dBFS convention. */
        private const val SILENT_DBFS = -100.0

        /**
         * Pure gain + peak-limit step of the clip write path: applies [micGain] to [raw] in
         * float, then — when the gained float peak exceeds [MAX_CLIP_TARGET_AMP] — scales the
         * WHOLE block down to it. Per-sample conversion is the same truncate-toward-zero int16
         * clamp the legacy in-place gain used, so an un-limited clip (gained peak ≤ target,
         * block scale exactly 1.0) is bit-identical to the pre-v11 writer's output.
         *
         * Real-time note: runs once per finalized event on the capture thread (not per window
         * hop) — the two passes and the output allocation are amortized the same way as the
         * ring snapshot and the file write that bracket them.
         */
        fun gainAndLimit(raw: ShortArray, micGain: Float): GainedClip {
            var peak = 0.0f
            for (s in raw) {
                val v = abs(s * micGain)
                if (v > peak) peak = v
            }
            val blockScale = if (peak > MAX_CLIP_TARGET_AMP) (MAX_CLIP_TARGET_AMP / peak).toFloat() else 1.0f
            val appliedGain = micGain * blockScale
            val out = ShortArray(raw.size)
            var writtenPeak = 0
            for (i in raw.indices) {
                val scaled = (raw[i] * appliedGain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i] = scaled.toShort()
                if (abs(scaled) > writtenPeak) writtenPeak = abs(scaled)
            }
            val filePeakDbfs = if (writtenPeak == 0) SILENT_DBFS else 20 * log10(writtenPeak / 32768.0)
            return GainedClip(out, appliedGain, filePeakDbfs)
        }
    }
}
