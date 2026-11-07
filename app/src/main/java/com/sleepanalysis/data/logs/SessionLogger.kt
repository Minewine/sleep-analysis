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
 *
 * Timestamps are true UTC: every line is stamped with [Instant.now] at write time and rendered
 * as ISO-8601 UTC (`Instant.toString` — always ends in `Z`, never a local offset), so log lines
 * line up with the epoch-millis columns the DB writes from the same wall clock. Device-clock
 * jumps (NTP corrections) can still create apparent gaps between records written at the same
 * moment: the clock is read fresh on every write, so a correction landing between two writes
 * shifts one timestamp relative to the other (e.g. a stop line stamping 1 h behind the session
 * row finalized milliseconds later).
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

    /** Deletes every session log file. Returns the number actually removed (a delete that fails is not counted). Never throws. */
    fun deleteAll(): Int = runCatching {
        rootDir.listFiles()
            ?.filter { it.isFile }
            ?.count { it.delete() }
    }.getOrNull() ?: 0

    /**
     * Deletes log files last modified before [cutoffMs]. Returns the number actually removed
     * (a delete that fails is not counted). Never throws.
     */
    fun purgeOlderThan(cutoffMs: Long): Int = runCatching {
        rootDir.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoffMs }
            ?.count { it.delete() }
    }.getOrNull() ?: 0

    private fun fileName(sessionId: Long) = "session_$sessionId.log"
}
