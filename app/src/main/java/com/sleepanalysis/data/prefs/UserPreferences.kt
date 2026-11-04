package com.sleepanalysis.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sleepanalysis.domain.policy.StartDelayPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class RetentionSettings(
    val clipsRetentionDays: Int = DEFAULT_CLIPS_DAYS,
    val sessionRetentionDays: Int = DEFAULT_SESSION_DAYS,
) {
    companion object {
        const val DEFAULT_CLIPS_DAYS = 7
        const val DEFAULT_SESSION_DAYS = 90
    }
}

@Singleton
class UserPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.dataStore

    private object Keys {
        val DISCLAIMER_ACCEPTED_AT = longPreferencesKey("disclaimer_accepted_at")
        val CLIPS_RETENTION_DAYS = intPreferencesKey("clips_retention_days")
        val SESSION_RETENTION_DAYS = intPreferencesKey("session_retention_days")
        val DEBUG_ENTER_WINDOWS_1 = booleanPreferencesKey("debug_enter_windows_1")
        val RECORDING_ENABLED = booleanPreferencesKey("recording_enabled")
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_START_MINUTES = intPreferencesKey("schedule_start_minutes")
        val SCHEDULE_END_MINUTES = intPreferencesKey("schedule_end_minutes")
        val SCHEDULE_SKIP_EPOCH_DAY = longPreferencesKey("schedule_skip_epoch_day")
        val START_DELAY_MINUTES = intPreferencesKey("start_delay_minutes")
    }

    val disclaimerAcceptedAt: Flow<Long?> = dataStore.data.map { it[Keys.DISCLAIMER_ACCEPTED_AT] }

    val retention: Flow<RetentionSettings> = dataStore.data.map { prefs ->
        RetentionSettings(
            clipsRetentionDays = prefs[Keys.CLIPS_RETENTION_DAYS] ?: RetentionSettings.DEFAULT_CLIPS_DAYS,
            sessionRetentionDays = prefs[Keys.SESSION_RETENTION_DAYS] ?: RetentionSettings.DEFAULT_SESSION_DAYS,
        )
    }

    suspend fun retentionNow(): RetentionSettings = retention.first()

    suspend fun acceptDisclaimer(at: Long = System.currentTimeMillis()) {
        dataStore.edit { it[Keys.DISCLAIMER_ACCEPTED_AT] = at }
    }

    suspend fun setRetention(clipsDays: Int? = null, sessionDays: Int? = null) {
        dataStore.edit { prefs ->
            if (clipsDays != null) prefs[Keys.CLIPS_RETENTION_DAYS] = PreferenceBounds.sanitizeRetentionDays(clipsDays)
            if (sessionDays != null) prefs[Keys.SESSION_RETENTION_DAYS] = PreferenceBounds.sanitizeRetentionDays(sessionDays)
        }
    }

    /**
     * Debug-only: relax event opening from 2 consecutive windows to 1 (docs/NEXT_STEPS.md P0
     * hardening follow-up). Takes effect on the NEXT recording; provenance is marked with a
     * "+dbg1" model-version suffix so affected sessions remain distinguishable.
     */
    val debugEnterWindows1: Flow<Boolean> = dataStore.data.map { it[Keys.DEBUG_ENTER_WINDOWS_1] ?: false }

    suspend fun debugEnterWindows1Now(): Boolean = debugEnterWindows1.first()

    suspend fun setDebugEnterWindows1(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBUG_ENTER_WINDOWS_1] = enabled }
    }

    /**
     * Recording controls (design: recording controls). [recordingEnabled] is the master
     * switch — when off, the app must not start a recording session (manual or scheduled);
     * it only gates starting, never force-stops an active night. The optional schedule is
     * off by default and stores start/end as minutes-of-day (hour*60+min).
     */
    val recordingEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.RECORDING_ENABLED] ?: true }

    val scheduleEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.SCHEDULE_ENABLED] ?: false }

    val scheduleStartMinutes: Flow<Int> =
        dataStore.data.map { it[Keys.SCHEDULE_START_MINUTES] ?: DEFAULT_SCHEDULE_START_MINUTES }

    val scheduleEndMinutes: Flow<Int> =
        dataStore.data.map { it[Keys.SCHEDULE_END_MINUTES] ?: DEFAULT_SCHEDULE_END_MINUTES }

    /** Epoch day of a skipped start occurrence (0 = none); naturally expires. */
    val scheduleSkipEpochDay: Flow<Long> = dataStore.data.map { it[Keys.SCHEDULE_SKIP_EPOCH_DAY] ?: 0L }

    suspend fun recordingEnabledNow(): Boolean = recordingEnabled.first()

    suspend fun scheduleEnabledNow(): Boolean = scheduleEnabled.first()

    suspend fun scheduleStartMinutesNow(): Int = scheduleStartMinutes.first()

    suspend fun scheduleEndMinutesNow(): Int = scheduleEndMinutes.first()

    suspend fun scheduleSkipEpochDayNow(): Long = scheduleSkipEpochDay.first()

    suspend fun setRecordingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RECORDING_ENABLED] = enabled }
    }

    suspend fun setScheduleEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SCHEDULE_ENABLED] = enabled }
    }

    suspend fun setScheduleStartMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SCHEDULE_START_MINUTES] = PreferenceBounds.sanitizeMinuteOfDay(minutes) }
    }

    suspend fun setScheduleEndMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SCHEDULE_END_MINUTES] = PreferenceBounds.sanitizeMinuteOfDay(minutes) }
    }

    suspend fun setScheduleSkipEpochDay(day: Long) {
        dataStore.edit { it[Keys.SCHEDULE_SKIP_EPOCH_DAY] = day }
    }

    /**
     * Capture start delay in minutes (0 = off, night-2 dogfood idea): after the user taps start,
     * audio capture begins this many minutes later so falling-asleep noise stays out of events
     * and clips. The session row and time-in-bed still start at the tap; the wait happens inside
     * the already-started foreground service (see StartDelayPolicy). Applies to the NEXT start.
     */
    val startDelayMinutes: Flow<Int> =
        dataStore.data.map { it[Keys.START_DELAY_MINUTES] ?: StartDelayPolicy.DEFAULT_DELAY_MINUTES }

    suspend fun startDelayMinutesNow(): Int = StartDelayPolicy.sanitizeDelayMinutes(startDelayMinutes.first())

    suspend fun setStartDelayMinutes(minutes: Int) {
        dataStore.edit { it[Keys.START_DELAY_MINUTES] = StartDelayPolicy.sanitizeDelayMinutes(minutes) }
    }

    companion object {
        const val DEFAULT_SCHEDULE_START_MINUTES = 22 * 60 + 30 // 22:30
        const val DEFAULT_SCHEDULE_END_MINUTES = 6 * 60 + 30 // 06:30
    }
}

/**
 * Pure bounds for the preference setters, mirroring StartDelayPolicy.sanitizeDelayMinutes:
 * values are clamped at the setter boundary. A non-positive retention would make the retention
 * cutoff future-dated (mass deletion on the next RetentionWorker run), and schedule minutes
 * are minutes-of-day, so they must stay on the 00:00–23:59 clock.
 */
object PreferenceBounds {
    const val MIN_RETENTION_DAYS = 1
    const val MAX_RETENTION_DAYS = 365
    const val MIN_MINUTE_OF_DAY = 0
    const val MAX_MINUTE_OF_DAY = 24 * 60 - 1

    fun sanitizeRetentionDays(raw: Int): Int = raw.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

    fun sanitizeMinuteOfDay(raw: Int): Int = raw.coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY)
}
