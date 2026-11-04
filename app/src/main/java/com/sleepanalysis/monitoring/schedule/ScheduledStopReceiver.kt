package com.sleepanalysis.monitoring.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sleepanalysis.monitoring.service.MonitorService
import com.sleepanalysis.monitoring.service.MonitorState
import com.sleepanalysis.monitoring.service.MonitorStateHolder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires at the scheduled end time. If a recording is active, stops it via a plain
 * startService on the already-running foreground service (no mic access is needed to stop).
 * Otherwise cancels a stale bedtime notification. Always re-arms at the end of the run.
 */
@AndroidEntryPoint
class ScheduledStopReceiver : BroadcastReceiver() {

    @Inject lateinit var stateHolder: MonitorStateHolder
    @Inject lateinit var scheduler: RecordingScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val state = stateHolder.state.value
        // Delayed counts as active: if the scheduled end lands during the start delay, the
        // session finalizes with zero capture instead of leaking an open row.
        val recording = state is MonitorState.Recording ||
            state is MonitorState.Starting ||
            state is MonitorState.Delayed
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (recording) {
                    try {
                        MonitorService.stop(context, MonitorService.SOURCE_SCHEDULE)
                    } catch (t: IllegalStateException) {
                        // Background service-start restriction — nothing we can do at bedtime.
                        Log.w(TAG, "scheduled stop could not start the service", t)
                    }
                } else {
                    BedtimeNotification.cancel(context)
                }
            } catch (t: Throwable) {
                // A receiver must never crash the process; re-arming below still runs.
                Log.w(TAG, "scheduled-stop handling failed", t)
            }
            runCatching { scheduler.arm() }
                .onFailure { Log.w(TAG, "re-arming after scheduled stop failed", it) }
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "SchedStopReceiver"
    }
}
