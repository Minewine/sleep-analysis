package com.sleepanalysis.data.files

import com.sleepanalysis.monitoring.audio.WavWriter
import java.io.File

/**
 * App-private clip storage: `<root>/session_<id>/evt_<startMs>.wav`.
 * Retention is enforced by RetentionWorker and delete paths (docs/DATA_MODEL.md §6).
 */
class ClipStore(private val rootDir: File) {

    init {
        rootDir.mkdirs()
    }

    val root: File get() = rootDir

    fun sessionDir(sessionId: Long): File = File(rootDir, "session_$sessionId")

    /** Writes a PCM16 mono WAV clip; returns the file. Blocking file I/O — call from an IO dispatcher. */
    fun writeClip(sessionId: Long, eventStartMs: Long, samples: ShortArray, sampleRate: Int): File {
        val dir = sessionDir(sessionId).apply { mkdirs() }
        val file = File(dir, "evt_$eventStartMs.wav")
        WavWriter.writePcm16Mono(file, samples, sampleRate)
        return file
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
}
