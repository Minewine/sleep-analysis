package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.dao.UserFactorDao
import com.sleepanalysis.data.db.entity.UserFactorEntity
import com.sleepanalysis.domain.factors.DailyFactor
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

    /** Upserts ONE factor for [date], keyed by its factorKey, leaving the date's other rows
     *  untouched — the report's morning card writes rating/tags key-by-key and must not
     *  clobber what the Daily log saved for the same day. Scale kinds (STRESS,
     *  MORNING_RATING) are clamped into 1..5 so a stray writer can never store a value the
     *  "/ 5" UI cannot render. */
    suspend fun saveFactorForDate(date: LocalDate, factor: UserFactorEntity) = withContext(Dispatchers.IO) {
        val clamped = if (DailyFactor.fromKey(factor.factorKey)?.kind == DailyFactor.Kind.SCALE_1_5) {
            factor.copy(valueNum = factor.valueNum?.coerceIn(1.0, 5.0))
        } else {
            factor
        }
        mergeForDate(date, factor.factorKey) { others -> others + clamped }
    }

    /** Removes ONE key's row for [date], leaving its other rows untouched — clearing a tag or
     *  the rating means writing no row (absence = not logged, same doctrine as saveForDate). */
    suspend fun removeFactorForDate(date: LocalDate, key: String) = withContext(Dispatchers.IO) {
        mergeForDate(date, key) { others -> others }
    }

    /** Read-merge-replace on a single key: the delete+insert is the DAO's @Transaction replace,
     *  so the day is never partially written; only two concurrent writers could interleave,
     *  and the morning card is the sole user of this path (one tap at a time). */
    private suspend fun mergeForDate(
        date: LocalDate,
        key: String,
        combine: (List<UserFactorEntity>) -> List<UserFactorEntity>,
    ) {
        val d = date.toString()
        val others = dao.forDate(d).filterNot { it.factorKey == key }
        dao.replaceForDate(d, combine(others).map { it.copy(date = d) })
    }
}
