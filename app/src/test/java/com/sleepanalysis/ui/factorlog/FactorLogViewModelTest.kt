package com.sleepanalysis.ui.factorlog

import com.sleepanalysis.data.db.dao.UserFactorDao
import com.sleepanalysis.data.db.entity.UserFactorEntity
import com.sleepanalysis.data.repo.FactorLogRepository
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Daily log's load→save round trip (FactorLogViewModel) against the REAL repository and a
 * thread-safe fake DAO. Main is an unconfined test dispatcher and the repository's IO hops are
 * awaited on the state they produce (bounded poll — fails, never hangs). Pins: the three
 * morning-feature fields (sick / sleptOnBack / morningRating) survive the round trip; the
 * stress and morning-rating cards are written by the SAME whole-day save so neither can
 * clobber the other; a deselected rating removes only its row; an invalid date writes nothing.
 */
class FactorLogViewModelTest {

    private val date = LocalDate.parse("2026-01-12")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Bounded wait for the repository's IO hops to land on the fake DAO / state flows. */
    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            Thread.sleep(5)
        }
    }

    private class FakeUserFactorDao : UserFactorDao {
        private val rows = Collections.synchronizedList(mutableListOf<UserFactorEntity>())

        override suspend fun insertAll(factors: List<UserFactorEntity>) {
            synchronized(rows) { rows += factors }
        }

        // Matches the real DAO's `ORDER BY factorKey`.
        override suspend fun forDate(date: String): List<UserFactorEntity> =
            synchronized(rows) { rows.filter { it.date == date }.sortedBy { it.factorKey } }

        override suspend fun deleteForDate(date: String) {
            synchronized(rows) { rows.removeAll { it.date == date } }
        }

        override suspend fun deleteAll() {
            synchronized(rows) { rows.clear() }
        }

        fun snapshot(): List<UserFactorEntity> = synchronized(rows) { rows.toList() }
    }

    private fun row(key: String, num: Double? = null, text: String? = null) =
        UserFactorEntity(date = date.toString(), factorKey = key, valueNum = num, valueText = text)

    @Test
    fun `load to save round trip preserves the three morning fields and the rest of the day`() = runBlocking {
        val dao = FakeUserFactorDao()
        val repo = FactorLogRepository(dao)
        val vm = FactorLogViewModel(repo)
        repo.saveForDate(
            date,
            listOf(
                row("ALCOHOL", 2.0),
                row("LATE_MEAL", 1.0),
                row("SICK", 1.0),
                row("STRESS", 3.0),
                row("MORNING_RATING", 4.0),
                row("NOTES", text = "knee pain"),
            ),
        )

        vm.loadDate(date.toString())
        await { vm.ui.value.sick } // the seeded load has landed
        val loaded = vm.ui.value
        assertTrue(loaded.alcohol)
        assertEquals("2", loaded.alcoholUnits)
        assertTrue(loaded.lateMeal)
        assertTrue(loaded.sick)
        assertFalse(loaded.sleptOnBack)
        assertEquals(3, loaded.stress)
        assertEquals(4, loaded.morningRating)
        assertEquals("knee pain", loaded.notes)

        // Toggle the new switch and change the rating; stress stays as loaded.
        vm.update { it.copy(sleptOnBack = true, morningRating = 2) }
        vm.save()
        await { vm.saved.value }

        val rows = dao.snapshot().filter { it.date == date.toString() }
        assertEquals(
            setOf("ALCOHOL", "LATE_MEAL", "SICK", "SLEPT_ON_BACK", "STRESS", "MORNING_RATING", "NOTES"),
            rows.map { it.factorKey }.toSet(),
        )
        assertEquals(2.0, rows.first { it.factorKey == "ALCOHOL" }.valueNum)
        assertEquals(1.0, rows.first { it.factorKey == "SLEPT_ON_BACK" }.valueNum)
        assertEquals(1.0, rows.first { it.factorKey == "SICK" }.valueNum)
        // The stress and morning-rating cards coexist: one save writes both, neither wins.
        assertEquals(3.0, rows.first { it.factorKey == "STRESS" }.valueNum)
        assertEquals(2.0, rows.first { it.factorKey == "MORNING_RATING" }.valueNum)
        assertEquals("knee pain", rows.first { it.factorKey == "NOTES" }.valueText)
    }

    @Test
    fun `deselected morning rating removes only the rating row`() = runBlocking {
        val dao = FakeUserFactorDao()
        val vm = FactorLogViewModel(FactorLogRepository(dao))
        FactorLogRepository(dao).saveForDate(
            date,
            listOf(row("STRESS", 3.0), row("MORNING_RATING", 4.0), row("NOTES", text = "slept badly")),
        )

        vm.loadDate(date.toString())
        await { vm.ui.value.stress == 3 }
        // Tap the selected rating chip again to unset, then save.
        vm.update { it.copy(morningRating = null) }
        vm.save()
        await { vm.saved.value }

        val keys = dao.snapshot().filter { it.date == date.toString() }.map { it.factorKey }
        // Whole-day replace with the rating omitted = rating removed, everything else intact.
        assertEquals(setOf("STRESS", "NOTES"), keys.toSet())
        assertFalse(keys.contains("MORNING_RATING"))
    }

    @Test
    fun `non-integer stored rating truncates on load and normalizes on save`() = runBlocking {
        val dao = FakeUserFactorDao()
        val vm = FactorLogViewModel(FactorLogRepository(dao))
        FactorLogRepository(dao).saveFactorForDate(
            date,
            UserFactorEntity(date = date.toString(), factorKey = "MORNING_RATING", valueNum = 2.5),
        )

        vm.loadDate(date.toString())
        await { vm.ui.value.morningRating == 2 }
        assertEquals(2, vm.ui.value.morningRating) // toInt truncation, in range, no crash

        vm.save()
        await { vm.saved.value }
        val ratings = dao.snapshot().filter { it.factorKey == "MORNING_RATING" }
        assertEquals(1, ratings.size)
        assertEquals(2.0, ratings.single().valueNum) // rewritten as the chip's integer
    }

    @Test
    fun `save with an invalid date writes nothing`() = runBlocking {
        val dao = FakeUserFactorDao()
        val vm = FactorLogViewModel(FactorLogRepository(dao))
        vm.update { it.copy(date = "not-a-date", notes = "should never land") }

        vm.save() // date fails the parse guard: returns before any write is launched
        assertTrue(dao.snapshot().isEmpty())
        assertEquals("should never land", vm.ui.value.notes) // the draft itself is untouched
    }
}
