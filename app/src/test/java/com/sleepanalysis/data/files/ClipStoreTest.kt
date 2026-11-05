package com.sleepanalysis.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Orphan-sweep plumbing of the clip store: the caller's per-file policy decides deletion. */
class ClipStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_730_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun store() = ClipStore(tmp.root)

    private fun write(rel: String, lastModifiedMs: Long): File {
        val file = File(tmp.root, rel)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(10))
        file.setLastModified(lastModifiedMs)
        return file
    }

    @Test
    fun `old unreferenced orphans are deleted and their bytes freed`() {
        val store = store()
        val orphan = write("session_1/evt_1.wav", now - 2 * hour)
        val orphanBytes = orphan.length()
        val referenced = write("session_1/evt_2.wav", now - 2 * hour)

        val freed = store.deleteOrphans(
            referencedRelativePaths = setOf("session_1/evt_2.wav"),
            nowMs = now,
            isSweepable = { it >= hour },
        )

        assertEquals(orphanBytes, freed)
        assertFalse(orphan.exists())
        assertTrue(referenced.exists())
    }

    @Test
    fun `orphans failing the sweep policy survive the sweep`() {
        val store = store()
        // A file written seconds ago: exactly the writeClip→insert window the guard protects.
        val fresh = write("session_1/evt_3.wav", now - 1_000)

        val freed = store.deleteOrphans(
            referencedRelativePaths = emptySet(),
            nowMs = now,
            isSweepable = { it >= hour },
        )

        assertEquals(0, freed)
        assertTrue(fresh.exists())
    }

    @Test
    fun `a session directory left empty by the sweep is pruned`() {
        val store = store()
        val orphan = write("session_1/evt_1.wav", now - 2 * hour)
        val keptDir = write("session_2/evt_2.wav", now - 1_000) // fresh orphan: dir must stay

        store.deleteOrphans(
            referencedRelativePaths = emptySet(),
            nowMs = now,
            isSweepable = { it >= hour },
        )

        assertFalse(orphan.exists())
        assertFalse(File(tmp.root, "session_1").exists())
        assertTrue(File(tmp.root, "session_2").exists())
    }

    // --- collision-safe clip naming (writeClip) ---

    @Test
    fun `two events with the same start ms produce distinct files`() {
        val store = store()
        val first = store.writeClip(10, "SNORE", 1_793_747_290_769, shortArrayOf(0, 0), 16_000)
        val second = store.writeClip(10, "SNORE", 1_793_747_290_769, shortArrayOf(0, 0), 16_000)
        assertNotEquals(first.name, second.name)
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun `event type is part of the filename`() {
        val store = store()
        val snore = store.writeClip(10, "SNORE", 1_000, shortArrayOf(0), 16_000)
        val cough = store.writeClip(10, "COUGH", 1_000, shortArrayOf(0), 16_000)
        assertTrue(snore.name.startsWith("evt_snore_"))
        assertTrue(cough.name.startsWith("evt_cough_"))
        assertNotEquals(snore.name, cough.name)
    }

    @Test
    fun `relative paths of written clips are session-scoped and resolvable`() {
        val store = store()
        val file = store.writeClip(7, "SNORE", 1_000, shortArrayOf(0), 16_000)
        val rel = store.relativePath(file)
        assertTrue(rel.startsWith("session_7/"))
        assertEquals(file, store.absolutePath(rel))
    }

    @Test
    fun `written clip is a readable RIFF wave file`() {
        val store = store()
        val file = store.writeClip(10, "SNORE", 1_000, shortArrayOf(1, -1, 2, -2), 16_000)
        assertEquals("RIFF", file.readBytes().copyOfRange(0, 4).decodeToString())
    }
}
