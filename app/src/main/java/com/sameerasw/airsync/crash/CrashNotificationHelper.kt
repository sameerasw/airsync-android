package com.sameerasw.airsync.crash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sameerasw.airsync.R

object CrashNotificationHelper {
    private const val CHANNEL_ID = "crash_report"
    const val NOTIFICATION_ID = 9999

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.crash_notification_title)
            val descriptionText = context.getString(R.string.crash_notification_body)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun postNotification(context: Context) {
        // Broadcast pending intent for Dismiss
        val dismissIntent = Intent(context, CrashNotificationReceiver::class.java).apply {
            action = "com.sameerasw.airsync.action.DISMISS_CRASH_NOTIFICATION"
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Activity pending intent for Report
        val reportIntent = Intent(context, CrashReportActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("show_report", true)
        }
        val reportPendingIntent = PendingIntent.getActivity(
            context,
            2,
            reportIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.rounded_bug_report_24)
            .setContentTitle(context.getString(R.string.crash_notification_title))
            .setContentText(context.getString(R.string.crash_notification_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(reportPendingIntent)
            .addAction(
                0,
                context.getString(R.string.crash_action_dismiss),
                dismissPendingIntent
            )
            .addAction(
                0,
                context.getString(R.string.crash_action_report),
                reportPendingIntent
            )

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
