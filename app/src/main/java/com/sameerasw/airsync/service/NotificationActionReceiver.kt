package com.sameerasw.airsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Receives notification actions and updates sync/connection state and notifications.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        // New: Continue Browsing dismiss action
        const val ACTION_CONTINUE_BROWSING_DISMISS =
            "com.sameerasw.airsync.CONTINUE_BROWSING_DISMISS"
        const val ACTION_CANCEL_TRANSFER = "com.sameerasw.airsync.CANCEL_TRANSFER"
        private const val TAG = "NotificationActionReceiver"
    }

    /** Coroutine scope for background operations. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONTINUE_BROWSING_DISMISS -> {
                val notifId = intent.getIntExtra("notif_id", -1)
                if (notifId != -1) {
                    try {
                        val nm =
                            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        nm.cancel(notifId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cancel continue-browsing notif: ${e.message}")
                    }
                }
            }

            AirSyncService.ACTION_DISCONNECT -> {
                Log.d(TAG, "Disconnecting from notification")
                WebSocketUtil.disconnect(context)
            }

            ACTION_CANCEL_TRANSFER -> {
                val transferId = intent.getStringExtra("transfer_id")
                if (!transferId.isNullOrEmpty()) {
                    Log.d(TAG, "Cancelling transfer $transferId from notification")
                    // Try cancelling both (one will be active)
                    com.sameerasw.airsync.utils.FileReceiver.cancelTransfer(context, transferId)
                    com.sameerasw.airsync.utils.FileSender.cancelTransfer(transferId)
                }
            }
        }
    }
}
