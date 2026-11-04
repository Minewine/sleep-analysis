package com.sleepanalysis.monitoring.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sleepanalysis.MainActivity
import com.sleepanalysis.R
import com.sleepanalysis.monitoring.service.MonitorService

/**
 * High-priority "bedtime" notification. The user tap (or its Start-recording action) is the
 * documented user-interaction exemption granting while-in-use microphone access on Android
 * 11+ — starting the foreground service from the alarm receiver would get NO mic access.
 * This is why the receiver posts a notification instead of starting the service itself.
 */
object BedtimeNotification {

    private const val CHANNEL_ID = "scheduled_recording"
    const val NOTIFICATION_ID = 1042 // deliberately NOT the FGS notification id (41)
    private const val START_REQUEST_CODE = 1003
    private const val SKIP_REQUEST_CODE = 1004

    fun show(context: Context, occurrenceEpochDay: Long) {
        createChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val startIntent = PendingIntent.getService(
            context, START_REQUEST_CODE,
            Intent(context, MonitorService::class.java)
                .setAction(MonitorService.ACTION_START)
                .putExtra(MonitorService.EXTRA_START_SOURCE, MonitorService.SOURCE_SCHEDULE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipIntent = PendingIntent.getBroadcast(
            context, SKIP_REQUEST_CODE,
            Intent(context, ScheduledStartReceiver::class.java)
                .setAction(ScheduledStartReceiver.ACTION_SKIP)
                .putExtra(ScheduledStartReceiver.EXTRA_OCCURRENCE_EPOCH_DAY, occurrenceEpochDay),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.notif_scheduled_title))
            .setContentText(context.getString(R.string.notif_scheduled_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notif_scheduled_start), startIntent)
            .addAction(0, context.getString(R.string.notif_scheduled_skip), skipIntent)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_scheduled),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
