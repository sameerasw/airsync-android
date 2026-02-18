package com.sameerasw.airsync.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.sameerasw.airsync.R
import com.sameerasw.airsync.service.NotificationActionReceiver

object NotificationUtil {
    private const val FILE_CHANNEL_ID = "airsync_file_transfer"

    // New: Continue Browsing channel
    private const val CONTINUE_CHANNEL_ID = "airsync_continue_browsing"


    private fun createContinueBrowsingChannel(context: Context) {
        val name = "Continue browsing"
        val descriptionText = "Quick open links received from desktop"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CONTINUE_CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    // File transfer notifications remain unchanged
    fun createFileChannel(context: Context) {
        val name = "File transfers"
        val descriptionText = "Notifications for file transfers"
        // Use IMPORTANCE_HIGH for Top Priority visibility
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(FILE_CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
        }

        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun showFileProgress(
        context: Context,
        notifId: Int,
        fileName: String,
        percent: Int,
        transferId: String,
        isSending: Boolean = false,
        etaString: String? = null
    ) {
        createFileChannel(context)
        val manager = NotificationManagerCompat.from(context)

        // Cancel intent
        val cancelIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CANCEL_TRANSFER
            putExtra("transfer_id", transferId)
        }
        val cancelPending = PendingIntent.getBroadcast(
            context,
            notifId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeTitle = if (isSending) "Sending" else "Receiving"
        val title = "$modeTitle: $fileName"
        val icon =
            if (isSending) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download

        val chipText = "$percent%"
        val bodyText = if (etaString != null) "$percent% • $etaString" else "$percent%"

        val builder = NotificationCompat.Builder(context, FILE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setSubText(chipText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setSmallIcon(icon)
            .setProgress(100, percent, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setColorized(false)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)

        try {
            builder.javaClass.getMethod(
                "setRequestPromotedOngoing",
                Boolean::class.javaPrimitiveType
            )
                .invoke(builder, true)
            builder.javaClass.getMethod("setShortCriticalText", CharSequence::class.java)
                .invoke(builder, chipText)
        } catch (_: Throwable) {
        }

        // Extras for Promoted Ongoing
        val extras = android.os.Bundle()
        extras.putBoolean("android.requestPromotedOngoing", true)
        extras.putString("android.shortCriticalText", chipText)
        builder.addExtras(extras)

        manager.notify(notifId, builder.build())
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun showFileComplete(
        context: Context,
        notifId: Int,
        fileName: String,
        success: Boolean,
        isSending: Boolean = false,
        contentUri: Uri? = null
    ) {
        createFileChannel(context)
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(notifId)

        val title: String
        val text: String
        val icon: Int

        if (isSending) {
            title = if (success) "Sent: $fileName" else "Sending failed: $fileName"
            text = if (success) "Transfer complete" else "Could not send file"
            icon =
                if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error
        } else {
            title = if (success) "Received: $fileName" else "Download failed: $fileName"
            text = if (success) "Saved to Downloads" else "Verification failed"
            icon =
                if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error
        }

        val builder = NotificationCompat.Builder(context, FILE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, false)

        if (contentUri != null && success && !isSending) {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, context.contentResolver.getType(contentUri))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pending = PendingIntent.getActivity(
                context,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pending)
            builder.addAction(android.R.drawable.ic_menu_view, "Open", pending)
        }

        val notif = builder.build()
        manager.notify(notifId, notif)
    }

    // New: Continue Browsing notifications
    fun showContinueBrowsingLink(context: Context, url: String, keepPrevious: Boolean = true) {
        createContinueBrowsingChannel(context)
        val manager = NotificationManagerCompat.from(context)
        val notifId = (url.hashCode() and 0x7fffffff) // stable positive ID per URL

        // If keepPrevious is false, clear all existing continue browsing notifications first
        if (!keepPrevious) {
            clearContinueBrowsingNotifications(context)
        }

        // Normalize only for the open intent (keep text as-is)
        val trimmed = url.trim()
        val normalized =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"

        val openIntent = Intent(Intent.ACTION_VIEW, normalized.toUri())
        val openPending = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CONTINUE_BROWSING_DISMISS
            putExtra("notif_id", notifId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CONTINUE_CHANNEL_ID)
            .setSmallIcon(R.drawable.outline_open_in_browser_24)
            .setContentTitle("Continue browsing")
            .setContentText(trimmed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(trimmed))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_view, "Open", openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPending)

        try {
            manager.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            android.util.Log.w(
                "NotificationUtil",
                "Failed to show continue-browsing notification: ${e.message}"
            )
        }
    }

    fun clearContinueBrowsingNotifications(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.activeNotifications?.forEach { sbn ->
                if (sbn.notification.channelId == CONTINUE_CHANNEL_ID) {
                    nm.cancel(sbn.id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "NotificationUtil",
                "Failed to clear continue-browsing notifications: ${e.message}"
            )
        }
    }
}
