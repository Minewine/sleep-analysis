package com.sleepanalysis.monitoring.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.sleepanalysis.MainActivity

/**
 * Shared notification plumbing for the monitoring notifications (plan T4d): ONE open-app
 * content intent, ONE channel factory and ONE cancel — the bedtime, interrupted, delay and
 * recording notifications all used to repeat these blocks verbatim.
 */
object AppNotifications {

    /** Opens the app (MainActivity) — the content intent every notification shares. */
    fun openAppPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** High-importance channel factory shared by the bedtime and interrupted notifications. */
    fun ensureChannel(context: Context, channelId: String, @StringRes nameRes: Int) {
        val channel = NotificationChannel(
            channelId,
            context.getString(nameRes),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun cancel(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
    }
}
