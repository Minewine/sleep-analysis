package com.sleepanalysis.monitoring.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sleepanalysis.MainActivity
import com.sleepanalysis.R

/**
 * High-priority "recording interrupted" notification, posted when START_STICKY resurrects the
 * service with an open session. Capture is deliberately NOT auto-resumed there (see
 * [startCommandFor]): a background-started microphone foreground service gets no while-in-use
 * mic access on Android 11+ and would record silence all night without any error. The tap —
 * content intent (opens the app, whose Home shows the interrupted-night banner) or the Resume
 * action — is the user-interaction path that grants mic access (same exemption the bedtime
 * notification relies on).
 */
object InterruptedNotification {

    private const val CHANNEL_ID = "interrupted_recording"
    const val NOTIFICATION_ID = 1043 // deliberately NOT the FGS notification id (41)
    private const val RESUME_REQUEST_CODE = 1005

    fun show(context: Context) {
        createChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resumeIntent = PendingIntent.getService(
            context, RESUME_REQUEST_CODE,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.notif_interrupted_title))
            .setContentText(context.getString(R.string.notif_interrupted_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notif_interrupted_resume), resumeIntent)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_interrupted),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
