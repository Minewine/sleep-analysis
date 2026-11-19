package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.dao.UserFactorDao
import com.sleepanalysis.data.db.entity.UserFactorEntity
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun `single-key upsert preserves the date's other rows`() = runTest {
        // The report's morning card saves rating/tags key-by-key; the Daily log's entries for
        // the same day (alcohol with units, a note) must survive untouched.
        repo.saveForDate(date, listOf(f("ALCOHOL", 2.0), f("NOTES", valueText = "partner snoring")))
        repo.saveFactorForDate(date, f("MORNING_RATING", 4.0))
        repo.saveFactorForDate(date, f("SICK", 1.0))

        val factors = repo.factorsForDate(date)
        assertEquals(4, factors.size)
        assertEquals(
            setOf("ALCOHOL", "MORNING_RATING", "NOTES", "SICK"),
            factors.map { it.factorKey }.toSet(),
        )
        assertEquals(2.0, factors.first { it.factorKey == "ALCOHOL" }.valueNum)
        assertEquals("partner snoring", factors.first { it.factorKey == "NOTES" }.valueText)
    }

    @Test
    fun `single-key upsert overwrites an existing key without duplicating it`() = runTest {
        repo.saveForDate(date, listOf(f("STRESS", 3.0)))
        repo.saveFactorForDate(date, f("MORNING_RATING", 2.0))
        repo.saveFactorForDate(date, f("MORNING_RATING", 5.0))

        val ratings = repo.factorsForDate(date).filter { it.factorKey == "MORNING_RATING" }
        assertEquals(1, ratings.size)
        assertEquals(5.0, ratings.single().valueNum)
        // The untouched STRESS row is still there.
        assertEquals(1, repo.factorsForDate(date).count { it.factorKey == "STRESS" })
    }

    @Test
    fun `removing a key leaves the date's other rows intact`() = runTest {
        // Clearing = writing no row for that key (absence = not logged).
        repo.saveForDate(date, listOf(f("SICK", 1.0), f("EXERCISE", 1.0)))
        repo.removeFactorForDate(date, "SICK")
        assertEquals(listOf("EXERCISE"), repo.factorsForDate(date).map { it.factorKey })

        // Removing a key that has no row is a no-op.
        repo.removeFactorForDate(date, "NOTES")
        assertEquals(listOf("EXERCISE"), repo.factorsForDate(date).map { it.factorKey })
    }

    @Test
    fun `single-key save clamps scale factors into the 1-5 range`() = runTest {
        // Scale kinds (STRESS, MORNING_RATING) are 1..5 by definition; clamped here so a stray
        // writer can never store a value the "/ 5" UI cannot render. Booleans pass through.
        repo.saveFactorForDate(date, f("MORNING_RATING", 9.0))
        repo.saveFactorForDate(date, f("STRESS", 0.0))
        repo.saveFactorForDate(date, f("SICK", 42.0))

        val factors = repo.factorsForDate(date)
        assertEquals(5.0, factors.first { it.factorKey == "MORNING_RATING" }.valueNum)
        assertEquals(1.0, factors.first { it.factorKey == "STRESS" }.valueNum)
        assertEquals(42.0, factors.first { it.factorKey == "SICK" }.valueNum)
    }

    @Test
    fun `clamp boundaries - 0_5 and 6 coerce into 1-5, in-range values pass through unchanged`() = runTest {
        // The code CLAMPS (coerceIn), never rejects: an out-of-range write still lands as a
        // row, just with the nearest legal level — pinning that so nobody swaps it silently.
        repo.saveFactorForDate(date, f("MORNING_RATING", 0.5))
        assertEquals(1.0, repo.factorsForDate(date).single().valueNum)

        repo.saveFactorForDate(date, f("MORNING_RATING", 6.0))
        assertEquals(5.0, repo.factorsForDate(date).single().valueNum)

        repo.saveFactorForDate(date, f("MORNING_RATING", 1.0))
        assertEquals(1.0, repo.factorsForDate(date).single().valueNum)

        repo.saveFactorForDate(date, f("MORNING_RATING", 5.0))
        assertEquals(5.0, repo.factorsForDate(date).single().valueNum)
    }

    @Test
    fun `scale row with null valueNum stays null - no invented value, no crash`() = runTest {
        // valueNum?.coerceIn keeps null: a stray STRESS/MORNING_RATING row without a number
        // passes through as-is rather than becoming a fake level.
        repo.saveFactorForDate(date, f("STRESS"))
        assertNull(repo.factorsForDate(date).single().valueNum)
    }

    @Test
    fun `non-scale rows pass through untouched - boolean nulls kept, unknown keys never clamped`() = runTest {
        // Only SCALE_1_5 kinds are clamped; a boolean written without a value stays valueless,
        // and a key outside the enum (future factor, sync artifact) is stored verbatim.
        repo.saveFactorForDate(date, f("SICK"))
        repo.saveFactorForDate(date, f("FUTURE_FACTOR", 42.0))
        repo.saveFactorForDate(date, f("NOTES", valueText = "late snack"))

        val factors = repo.factorsForDate(date)
        assertNull(factors.first { it.factorKey == "SICK" }.valueNum)
        assertEquals(42.0, factors.first { it.factorKey == "FUTURE_FACTOR" }.valueNum)
        assertEquals("late snack", factors.first { it.factorKey == "NOTES" }.valueText)
    }

    @Test
    fun `single-key save normalizes the row onto the requested date`() = runTest {
        // Same normalization the whole-day save does: the entity's own date field is ignored,
        // so a morning-card write can never land on the wrong day.
        repo.saveFactorForDate(date, f("MORNING_RATING", 4.0, date = "1999-01-01"))
        assertEquals("2026-01-12", repo.factorsForDate(date).single().date)
        assertTrue(repo.factorsForDate(LocalDate.parse("1999-01-01")).isEmpty())
    }

    @Test
    fun `removing a key on one date leaves the same key's row on other dates`() = runTest {
        val other = LocalDate.parse("2026-01-13")
        repo.saveForDate(date, listOf(f("SICK", 1.0)))
        repo.saveForDate(other, listOf(f("SICK", 1.0)))

        repo.removeFactorForDate(date, "SICK")

        assertTrue(repo.factorsForDate(date).none { it.factorKey == "SICK" })
        assertEquals(1.0, repo.factorsForDate(other).single().valueNum)
    }

    @Test
    fun `untagging stress deletes the daily-log level instead of overwriting it`() = runTest {
        // The Daily log refined stress to 3; the morning card's untag plan writes no row for
        // the key, and the merge removal deletes the row outright — removal wins, the
        // refinement is gone (never silently replaced by the quick tag's 5.0 or kept stale).
        repo.saveForDate(date, listOf(f("STRESS", 3.0)))
        repo.removeFactorForDate(date, "STRESS")
        assertTrue(repo.factorsForDate(date).none { it.factorKey == "STRESS" })
    }

    @Test
    fun `a dao failure mid-merge propagates and leaves the previous rows intact`() = runTest {
        // Room's @Transaction replaceForDate rolls the whole day back on failure — the fake
        // models that by failing BEFORE any mutation. The repository must not swallow the
        // error (the morning save would otherwise "succeed" while nothing was written), and
        // the caller must still see the pre-save rows, never a half-written day.
        val explodingDao = ExplodingUserFactorDao()
        val explodingRepo = FactorLogRepository(explodingDao)
        explodingRepo.saveForDate(date, listOf(f("STRESS", 3.0), f("MORNING_RATING", 4.0)))
        explodingDao.failReplace = true

        try {
            explodingRepo.saveFactorForDate(date, f("SICK", 1.0))
            fail("expected the DAO failure to propagate out of saveFactorForDate")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(
            listOf("MORNING_RATING", "STRESS"),
            explodingRepo.factorsForDate(date).map { it.factorKey },
        )

        try {
            explodingRepo.removeFactorForDate(date, "STRESS")
            fail("expected the DAO failure to propagate out of removeFactorForDate")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(
            listOf("MORNING_RATING", "STRESS"),
            explodingRepo.factorsForDate(date).map { it.factorKey },
        )
    }

    private fun f(factorKey: String, valueNum: Double? = null, valueText: String? = null, date: String = "") =
        UserFactorEntity(date = date, factorKey = factorKey, valueNum = valueNum, valueText = valueText)

    private open class FakeUserFactorDao : UserFactorDao {
        val rows = mutableListOf<UserFactorEntity>()
        override suspend fun insertAll(factors: List<UserFactorEntity>) {
            rows += factors
        }

        // Matches the real DAO's `ORDER BY factorKey`, so row order is predictable.
        override suspend fun forDate(date: String): List<UserFactorEntity> =
            rows.filter { it.date == date }.sortedBy { it.factorKey }

        override suspend fun deleteForDate(date: String) {
            rows.removeAll { it.date == date }
        }

        override suspend fun deleteAll() = rows.clear()
    }

    /** Fake DAO whose atomic replace fails before touching any row — what Room's
     *  @Transaction guarantees the caller sees when the write errors out. */
    private class ExplodingUserFactorDao : FakeUserFactorDao() {
        var failReplace = false

        override suspend fun replaceForDate(date: String, factors: List<UserFactorEntity>) {
            if (failReplace) throw IllegalStateException("simulated room disk io error")
            super.replaceForDate(date, factors)
        }
    }
}
