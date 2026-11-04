package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.dao.UserFactorDao
import com.sleepanalysis.data.db.entity.UserFactorEntity
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactorLogRepositoryTest {

    private val dao = FakeUserFactorDao()
    private val repo = FactorLogRepository(dao)
    private val date = LocalDate.parse("2026-01-12")

    @Test
    fun `saving a date replaces its previous entries`() = runTest {
        repo.saveForDate(
            date,
            listOf(f("ALCOHOL", 2.0), f("EXERCISE", 1.0)),
        )
        assertEquals(2, repo.factorsForDate(date).size)

        // Correct a mistake: only stress was actually true that day.
        repo.saveForDate(date, listOf(f("STRESS", 3.0)))
        val factors = repo.factorsForDate(date)
        assertEquals(1, factors.size)
        assertEquals("STRESS", factors.single().factorKey)
    }

    @Test
    fun `other dates are untouched by a save`() = runTest {
        val other = LocalDate.parse("2026-01-13")
        repo.saveForDate(date, listOf(f("NOTES", valueText = "knee pain")))
        repo.saveForDate(other, listOf(f("STRESS", 5.0)))
        repo.saveForDate(date, emptyList())

        assertTrue(repo.factorsForDate(date).isEmpty())
        assertEquals(1, repo.factorsForDate(other).size)
    }

    @Test
    fun `saving is normalized to the requested date regardless of entity date field`() = runTest {
        repo.saveForDate(date, listOf(f("EXERCISE", 1.0, date = "1999-01-01")))
        assertEquals(date.toString(), repo.factorsForDate(date).single().date)
    }

    private fun f(factorKey: String, valueNum: Double? = null, valueText: String? = null, date: String = "") =
        UserFactorEntity(date = date, factorKey = factorKey, valueNum = valueNum, valueText = valueText)

    private class FakeUserFactorDao : UserFactorDao {
        val rows = mutableListOf<UserFactorEntity>()
        override suspend fun insertAll(factors: List<UserFactorEntity>) {
            rows += factors
        }

        override suspend fun forDate(date: String): List<UserFactorEntity> =
            rows.filter { it.date == date }

        override suspend fun deleteForDate(date: String) {
            rows.removeAll { it.date == date }
        }

        override suspend fun deleteAll() = rows.clear()
    }
}
