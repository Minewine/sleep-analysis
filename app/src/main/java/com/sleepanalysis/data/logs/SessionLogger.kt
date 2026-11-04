package com.sleepanalysis.data.logs

import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight, local-only session logging (docs/STATUS.md observability): one append-only text
 * file per session under `<filesDir>/logs/session_<id>.log`. Every method is no-throw — if the
 * filesystem refuses, logging is silently skipped and the app keeps working. Nothing here ever
 * leaves the device.
 *
 * Blocking file I/O — callers are on background dispatchers.
 */
@Singleton
class SessionLogger @Inject constructor(private val rootDir: File) {

    /** Appends one timestamped line. Never throws. */
    fun log(sessionId: Long, event: String, detail: String = "") {
        runCatching {
            rootDir.mkdirs()
            val ts = Instant.now().toString()
            File(rootDir, fileName(sessionId)).appendText("$ts [$event] ${detail.trim()}\n")
        }
    }

    /** Full log text for a session, or null when none exists. Never throws. */
    fun readSessionLog(sessionId: Long): String? = runCatching {
        logFile(sessionId).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun logFile(sessionId: Long): File = File(rootDir, fileName(sessionId))

    /** Deletes one session's log file; false when none existed or the filesystem refused. Never throws. */
    fun deleteSessionLog(sessionId: Long): Boolean = runCatching {
        logFile(sessionId).delete()
    }.getOrDefault(false)

    /** Deletes every session log file. Returns the number removed. Never throws. */
    fun deleteAll(): Int = runCatching {
        rootDir.listFiles()
            ?.filter { it.isFile }
            ?.onEach { it.delete() }
            ?.size
    }.getOrNull() ?: 0

    /** Deletes log files last modified before [cutoffMs]. Returns the number removed. Never throws. */
    fun purgeOlderThan(cutoffMs: Long): Int = runCatching {
        rootDir.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoffMs }
            ?.onEach { it.delete() }
            ?.size
    }.getOrNull() ?: 0

    private fun fileName(sessionId: Long) = "session_$sessionId.log"
}
