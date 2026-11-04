package com.sleepanalysis.monitoring.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sleepanalysis.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms/cancels the two nightly alarms (bedtime start, end time). Start and end are computed
 * independently from "now" so that after the start fires, tonight's end and tomorrow's start
 * both still get armed. Exact when the exact-alarm permission is granted, otherwise an inexact
 * ~10-minute window (surface the fallback in Settings). Re-armed on boot, on every settings
 * change, and at the end of every start/stop receiver run.
 */
@Singleton
class RecordingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
) {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    suspend fun arm() {
        if (!prefs.recordingEnabledNow() || !prefs.scheduleEnabledNow()) {
            // Either switch off: no alarms AND no showing bedtime notification.
            BedtimeNotification.cancel(context)
            cancel()
            return
        }
        val now = ZonedDateTime.now()
        val start = ScheduleTimes.nextStart(now, prefs.scheduleStartMinutesNow(), prefs.scheduleSkipEpochDayNow())
        val end = ScheduleTimes.nextOccurrence(now, prefs.scheduleEndMinutesNow())
        val startPi = PendingIntent.getBroadcast(
            context, START_REQUEST_CODE,
            Intent(context, ScheduledStartReceiver::class.java)
                .putExtra(ScheduledStartReceiver.EXTRA_OCCURRENCE_EPOCH_DAY, start.toLocalDate().toEpochDay()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endPi = PendingIntent.getBroadcast(
            context, END_REQUEST_CODE,
            Intent(context, ScheduledStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val startMillis = start.toInstant().toEpochMilli()
        val endMillis = end.toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, startPi)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, endPi)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, startMillis, INEXACT_WINDOW_MS, startPi)
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, endMillis, INEXACT_WINDOW_MS, endPi)
        }
    }

    fun cancel() {
        // FLAG_NO_CREATE: only cancel alarms actually still registered.
        PendingIntent.getBroadcast(
            context, START_REQUEST_CODE, Intent(context, ScheduledStartReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.cancel()
        PendingIntent.getBroadcast(
            context, END_REQUEST_CODE, Intent(context, ScheduledStopReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.cancel()
    }

    /** Null when the master switch or the schedule is off — for UI notes. */
    suspend fun nextStartOrNull(now: ZonedDateTime): ZonedDateTime? {
        if (!prefs.recordingEnabledNow() || !prefs.scheduleEnabledNow()) return null
        return ScheduleTimes.nextStart(now, prefs.scheduleStartMinutesNow(), prefs.scheduleSkipEpochDayNow())
    }

    /** Marks the next scheduled start as skipped for one night and re-arms. */
    suspend fun skipNextStart(): ZonedDateTime? {
        val next = nextStartOrNull(ZonedDateTime.now()) ?: return null
        prefs.setScheduleSkipEpochDay(next.toLocalDate().toEpochDay())
        arm()
        return next
    }

    private companion object {
        const val START_REQUEST_CODE = 1001
        const val END_REQUEST_CODE = 1002
        const val INEXACT_WINDOW_MS = 10L * 60_000
    }
}
