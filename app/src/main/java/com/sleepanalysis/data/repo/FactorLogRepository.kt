package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.dao.UserFactorDao
import com.sleepanalysis.data.db.entity.UserFactorEntity
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Self-logged daily lifestyle factors (docs/DATA_MODEL.md §3.6). Saving a date replaces that
 * date's rows entirely, so the log for any day is exactly what the user last saved.
 * Deliberately not mirrored into Measurement yet — that happens with Phase 4 analysis.
 */
@Singleton
class FactorLogRepository @Inject constructor(
    private val dao: UserFactorDao,
) {
    suspend fun factorsForDate(date: LocalDate): List<UserFactorEntity> = withContext(Dispatchers.IO) {
        dao.forDate(date.toString())
    }

    /** Replaces all factors for [date] with [factors]; atomic in the DAO so a crash mid-save
     *  can never leave the day partially deleted. */
    suspend fun saveForDate(date: LocalDate, factors: List<UserFactorEntity>) = withContext(Dispatchers.IO) {
        dao.replaceForDate(date.toString(), factors.map { it.copy(date = date.toString()) })
    }
}
