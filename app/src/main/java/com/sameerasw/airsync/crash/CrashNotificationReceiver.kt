package com.sameerasw.airsync.crash

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CrashNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null && intent?.action == "com.sameerasw.airsync.action.DISMISS_CRASH_NOTIFICATION") {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(CrashNotificationHelper.NOTIFICATION_ID)
        }
    }
}
