package com.sleepanalysis.data.logs

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val logger get() = SessionLogger(tmp.root)

    @Test
    fun `appends timestamped lines and reads them back`() {
        logger.log(7, "recording_start", "storageFreeMB=12000")
        logger.log(7, "thermal", "status=MODERATE throttled=true")

        val text = logger.readSessionLog(7)!!
        assertTrue(text.contains("[recording_start] storageFreeMB=12000"))
        assertTrue(text.contains("[thermal] status=MODERATE throttled=true"))
        // ISO-8601 timestamps, one per line, in order.
        val lines = text.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0] < lines[1]) // timestamps sort lexicographically within the same day
        assertTrue(lines[0].startsWith("20")) // year prefix of the ISO timestamp
    }

    @Test
    fun `timestamps are ISO-8601 UTC and stamped at write time`() {
        val before = Instant.now()
        logger.log(7, "recording_stop", "source=manual status=COMPLETED")
        val after = Instant.now()

        val line = logger.readSessionLog(7)!!.trim().lines().single()
        // First token up to the space: "2026-…" + " [recording_stop] …" per the log() format.
        val ts = line.substringBefore(' ')
        // Instant.parse rejects any non-UTC offset (e.g. '+01:00'), so this asserts ISO-8601 UTC
        // with a 'Z' suffix — no zone-aware formatter can slip in unnoticed.
        val parsed = Instant.parse(ts)
        assertTrue("timestamp ${parsed} predates the write by >2s", parsed >= before.minusSeconds(2))
        assertTrue("timestamp ${parsed} lags the write by >2s", parsed <= after.plusSeconds(2))
    }

    @Test
    fun `sessions are isolated by id`() {
        logger.log(7, "a")
        logger.log(8, "b")
        assertEquals(1, logger.readSessionLog(7)!!.trim().lines().size)
        assertTrue(logger.readSessionLog(8)!!.contains("[b]"))
    }

    @Test
    fun `missing log reads as null`() {
        assertNull(logger.readSessionLog(999))
    }

    @Test
    fun `logging never throws even when the root is unwritable`() {
        val blocker = File(tmp.root, "not-a-dir").apply { writeText("x") }
        val broken = SessionLogger(blocker) // mkdirs() will fail: a file is in the way
        broken.log(1, "event", "detail") // must not throw
        assertNull(broken.readSessionLog(1))
    }

    @Test
    fun `purge removes only files older than the cutoff`() {
        logger.log(1, "old")
        logger.log(2, "old")
        val old = logger.logFile(1).apply { setLastModified(1_000L) }
        logger.logFile(2).setLastModified(System.currentTimeMillis())

        assertEquals(1, logger.purgeOlderThan(10_000L))
        assertTrue(!old.exists())
        assertTrue(logger.logFile(2).exists())
    }

    @Test
    fun `deleteSessionLog removes only that session's log`() {
        logger.log(1, "a")
        logger.log(2, "b")

        assertTrue(logger.deleteSessionLog(1))
        assertNull(logger.readSessionLog(1))
        assertTrue(logger.readSessionLog(2)?.contains("[b]") == true)
    }

    @Test
    fun `deleteSessionLog of a missing log reports false without throwing`() {
        assertFalse(logger.deleteSessionLog(999))

        // Root path blocked by a regular file: the filesystem refuses, still no throw.
        val blocker = File(tmp.root, "not-a-dir").apply { writeText("x") }
        val broken = SessionLogger(blocker)
        assertFalse(broken.deleteSessionLog(1))
    }

    @Test
    fun `deleteAll removes every log and reports the count`() {
        logger.log(1, "a")
        logger.log(2, "b")
        logger.log(3, "c")

        assertEquals(3, logger.deleteAll())
        assertNull(logger.readSessionLog(1))
        assertNull(logger.readSessionLog(2))
        assertNull(logger.readSessionLog(3))
        assertEquals(0, logger.deleteAll()) // empty again: still no throw, zero removed
    }
}
