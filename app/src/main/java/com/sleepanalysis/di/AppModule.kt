package com.sleepanalysis.di

import android.content.Context
import com.sleepanalysis.data.db.AppDatabase
import com.sleepanalysis.data.db.dao.AcousticEventDao
import com.sleepanalysis.data.db.dao.AudioClipDao
import com.sleepanalysis.data.db.dao.ClinicalBaselineDao
import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.dao.SleepSessionDao
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.monitoring.detection.EnergySnoreDetector
import com.sleepanalysis.monitoring.detection.SnoreDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.build(context)

    @Provides fun provideSessionDao(db: AppDatabase): SleepSessionDao = db.sleepSessionDao()

    @Provides fun provideEventDao(db: AppDatabase): AcousticEventDao = db.acousticEventDao()

    @Provides fun provideClipDao(db: AppDatabase): AudioClipDao = db.audioClipDao()

    @Provides fun provideMeasurementDao(db: AppDatabase): MeasurementDao = db.measurementDao()

    @Provides fun provideBaselineDao(db: AppDatabase): ClinicalBaselineDao = db.clinicalBaselineDao()

    @Provides fun provideRawPayloadDao(db: AppDatabase): com.sleepanalysis.data.db.dao.RawPayloadDao = db.rawPayloadDao()

    @Provides fun provideUserFactorDao(db: AppDatabase): com.sleepanalysis.data.db.dao.UserFactorDao = db.userFactorDao()

    @Provides
    @Singleton
    fun provideClipStore(@ApplicationContext context: Context): ClipStore =
        ClipStore(File(context.filesDir, "clips"))

    @Provides
    @Singleton
    fun provideSessionLogger(@ApplicationContext context: Context): com.sleepanalysis.data.logs.SessionLogger =
        com.sleepanalysis.data.logs.SessionLogger(File(context.filesDir, "logs"))

    /**
     * Phase 1 detector binding — the swap point for Phase 2 (docs/MVP_SCOPE.md F4): replace
     * this provider with a TFLite-backed SnoreDetector; nothing else changes.
     */
    @Provides
    @Singleton
    fun provideSnoreDetector(): SnoreDetector = EnergySnoreDetector()
}
