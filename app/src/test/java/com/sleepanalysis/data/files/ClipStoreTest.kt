package com.sleepanalysis.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
