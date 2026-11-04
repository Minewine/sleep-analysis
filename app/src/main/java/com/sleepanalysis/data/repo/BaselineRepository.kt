package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.dao.ClinicalBaselineDao
import com.sleepanalysis.data.db.entity.ClinicalBaselineEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Read-mostly access to the immutable clinical baseline (docs/DATA_MODEL.md §4).
 * No update/delete methods exist — corrections are new superseding entries, and rows are
 * additionally protected by DB triggers (AppDatabase).
 */
@Singleton
class BaselineRepository @Inject constructor(
    private val dao: ClinicalBaselineDao,
) {
    fun observeAll(): Flow<List<ClinicalBaselineEntity>> = dao.observeAll()

    suspend fun all(): List<ClinicalBaselineEntity> = dao.all()

    /** Insert-only. Returns the new row id. */
    suspend fun add(entry: ClinicalBaselineEntity): Long = withContext(Dispatchers.IO) {
        dao.insert(entry)
    }
}
