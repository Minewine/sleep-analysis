package com.sleepanalysis.monitoring.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sleepanalysis.data.prefs.UserPreferences
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.monitoring.service.MonitorState
import com.sleepanalysis.monitoring.service.MonitorStateHolder
import dagger.hilt.android.AndroidEntryPoint
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires at the configured bedtime (and handles the notification's Skip action).
 *
 * NEVER starts MonitorService directly: on Android 11+ a background-started microphone
 * foreground service gets no while-in-use mic access. Instead the high-priority bedtime
 * notification is posted — the user tap is the user-interaction exemption (see
 * [BedtimeNotification]). The notification is suppressed while an interrupted night is open
 * (P2-1). Always re-arms the schedule at the end of the run.
 */
@AndroidEntryPoint
class ScheduledStartReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: UserPreferences
    @Inject lateinit var stateHolder: MonitorStateHolder
    @Inject lateinit var scheduler: RecordingScheduler
    @Inject lateinit var sessionRepository: SessionRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (intent.action == ACTION_SKIP) {
                    val occurrenceDay = intent.getLongExtra(EXTRA_OCCURRENCE_EPOCH_DAY, -1L).takeIf { it >= 0 }
                        ?: fallbackOccurrenceDay()
                    // P2-6: store the skip as the night's INTENDED WAKE date (the wake date a
                    // post-midnight start shares with its occurrence day) — the banner, trends
                    // and the scheduler's own filter all key that date.
                    val startOccurrence = java.time.LocalDate.ofEpochDay(occurrenceDay)
                        .atTime(java.time.LocalTime.ofSecondOfDay(prefs.scheduleStartMinutesNow() * 60L))
                        .atZone(java.time.ZoneId.systemDefault())
                    prefs.setScheduleSkipEpochDay(
                        ScheduleTimes.intendedWakeDay(startOccurrence, prefs.scheduleEndMinutesNow()),
                    )
                    BedtimeNotification.cancel(context)
                } else {
                    val occurrenceDay = intent.getLongExtra(EXTRA_OCCURRENCE_EPOCH_DAY, -1L).takeIf { it >= 0 }
                        ?: fallbackOccurrenceDay()
                    val state = stateHolder.state.value
                    // P2-6: the skip marker keys the intended WAKE date of tonight's start.
                    val startOccurrence = java.time.LocalDate.ofEpochDay(occurrenceDay)
                        .atTime(java.time.LocalTime.ofSecondOfDay(prefs.scheduleStartMinutesNow() * 60L))
                        .atZone(java.time.ZoneId.systemDefault())
                    val post = SchedulePolicy.shouldPostBedtimeNotification(
                        recordingEnabled = prefs.recordingEnabledNow(),
                        scheduleEnabled = prefs.scheduleEnabledNow(),
                        skipSet = ScheduleTimes.intendedWakeDay(startOccurrence, prefs.scheduleEndMinutesNow()) ==
                            prefs.scheduleSkipEpochDayNow(),
                        alreadyRecording = state is MonitorState.Recording ||
                            state is MonitorState.Starting ||
                            state is MonitorState.Delayed,
                        // P2-1: an open night means the Resume/Finalize decision is pending —
                        // its interrupted notification stays the re-entry point, and a bedtime
                        // Start must never silently finalize it behind a new session.
                        hasOpenSession = sessionRepository.openSession() != null,
                    )
                    if (post) BedtimeNotification.show(context, occurrenceDay)
                }
            } catch (t: Throwable) {
                // A receiver must never crash the process; re-arming below still runs.
                Log.w(TAG, "bedtime-start handling failed", t)
            }
            runCatching { scheduler.arm() }
                .onFailure { Log.w(TAG, "re-arming after bedtime start failed", it) }
            pendingResult.finish()
        }
    }

    private suspend fun fallbackOccurrenceDay(): Long =
        ScheduleTimes.nextOccurrence(ZonedDateTime.now(), prefs.scheduleStartMinutesNow())
            .toLocalDate().toEpochDay()

    companion object {
        private const val TAG = "SchedStartReceiver"
        const val ACTION_SKIP = "com.sleepanalysis.action.SCHEDULE_SKIP"
        const val EXTRA_OCCURRENCE_EPOCH_DAY = "occurrence_epoch_day"
    }
}
