package com.sleepanalysis.monitoring.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms the nightly alarms after a reboot or a timezone/clock change (RTC_WAKEUP bedtimes
 * must survive timezone travel). Re-arm ONLY — Android 14+ forbids starting a microphone
 * foreground service from BOOT_COMPLETED, so this receiver never records.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: RecordingScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {}
            else -> return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { scheduler.arm() }
                .onFailure { Log.w(TAG, "re-arming after boot/clock change failed", it) }
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
