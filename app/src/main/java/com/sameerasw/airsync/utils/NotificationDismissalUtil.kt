package com.sameerasw.airsync.utils

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sameerasw.airsync.service.MediaNotificationListener
import java.util.concurrent.ConcurrentHashMap

object NotificationDismissalUtil {
    private const val TAG = "NotificationDismissalUtil"
    
    // Store active notifications with their IDs for dismissal or actions
    private val activeNotifications = ConcurrentHashMap<String, StatusBarNotification>()
    // Reverse map from system notification key to our generated ID
    private val keyToId = ConcurrentHashMap<String, String>()
    // IDs suppressed from Android->Mac dismissal sync (because dismissal originated from Mac)
    private val suppressedIds = ConcurrentHashMap.newKeySet<String>()
    // Test notification IDs for mock dismissal support
    private val testNotificationIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Generate a unique notification ID
     */
    fun generateNotificationId(packageName: String, title: String, timestamp: Long): String {
        return "${packageName}_${title.hashCode()}_$timestamp"
    }
    
    /**
     * Store a notification for dismissal
     */
    fun storeNotification(id: String, notification: StatusBarNotification) {
        activeNotifications[id] = notification
        // Keep reverse lookup so we can map sbn.key -> id on removal
        try {
            keyToId[notification.key] = id
        } catch (_: Exception) { }
        Log.d(TAG, "Stored notification with ID: $id")
        
        // Clean up old notifications
        if (activeNotifications.size > 200) {
            val oldestKeys = activeNotifications.keys.take(activeNotifications.size - 200)
            oldestKeys.forEach { oldId ->
                activeNotifications.remove(oldId)?.let { sbn ->
                    keyToId.remove(sbn.key)
                }
            }
        }
    }
    
    /**
     * Store a test notification ID for mock dismissal
     */
    fun storeTestNotificationId(id: String) {
        testNotificationIds.add(id)
        Log.d(TAG, "Stored test notification ID: $id")
    }
    
    /**
     * Dismiss a notification by its ID
     */
    fun dismissNotification(notificationId: String): Boolean {
        return try {
            val notification = activeNotifications[notificationId]
            if (notification != null) {
                val service = getNotificationListenerService()
                if (service != null) {
                    // Mark suppressed to avoid echo back to Mac when onNotificationRemoved triggers
                    markSuppressed(notificationId)
                    service.cancelNotification(notification.key)
                    // Cleanup maps after cancel is requested (onNotificationRemoved may also do this)
                    activeNotifications.remove(notificationId)
                    keyToId.remove(notification.key)
                    Log.d(TAG, "Successfully dismissed notification: $notificationId")
                    true
                } else {
                    Log.w(TAG, "Notification listener service not available")
                    false
                }
            } else if (testNotificationIds.contains(notificationId)) {
                // Handle test notification dismissal as a success (mock)
                testNotificationIds.remove(notificationId)
                Log.d(TAG, "Mock dismissed test notification: $notificationId")
                true
            } else {
                Log.w(TAG, "Notification with ID $notificationId not found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification $notificationId: ${e.message}")
            false
        }
    }

    /**
     * Perform an action on the notification by title. If replyText is provided and the action supports inline reply, send it.
     */
    fun performNotificationAction(notificationId: String, actionName: String, replyText: String? = null): Boolean {
        return try {
            val service = getNotificationListenerService()
            if (service == null) {
                Log.w(TAG, "Notification listener service not available")
                return false
            }
            val sbn = activeNotifications[notificationId]
            if (sbn == null) {
                Log.w(TAG, "Notification with ID $notificationId not found for action $actionName")
                return false
            }

            val actions = sbn.notification.actions
            if (actions == null || actions.isEmpty()) {
                Log.w(TAG, "No actions available on notification $notificationId")
                return false
            }

            val target = actions.firstOrNull { it.title?.toString()?.equals(actionName, ignoreCase = true) == true }
            if (target == null) {
                Log.w(TAG, "Action '$actionName' not found on notification $notificationId")
                return false
            }

            val pendingIntent = target.actionIntent
            if (replyText != null) {
                // Inline reply path
                val remoteInputs = target.remoteInputs
                if (remoteInputs.isNullOrEmpty()) {
                    Log.w(TAG, "Action '$actionName' does not support reply on $notificationId")
                    return false
                }
                val intent = Intent()
                val results = Bundle()
                remoteInputs.forEach { ri ->
                    results.putCharSequence(ri.resultKey, replyText)
                }
                // Attach results
                RemoteInput.addResultsToIntent(remoteInputs, intent, results)
                return try {
                    pendingIntent.send(service, 0, intent)
                    Log.d(TAG, "Sent inline reply for action '$actionName' on $notificationId")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending inline reply: ${e.message}")
                    false
                }
            } else {
                // Simple button action
                return try {
                    pendingIntent.send()
                    Log.d(TAG, "Invoked action '$actionName' on $notificationId")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking action '$actionName': ${e.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing action: ${e.message}")
            false
        }
    }

    /**
     * Lookup generated ID by a system notification key.
     */
    fun getIdBySystemKey(systemKey: String): String? = try { keyToId[systemKey] } catch (_: Exception) { null }

    /**
     * Lookup generated ID by StatusBarNotification
     */
    fun getIdForSbn(sbn: StatusBarNotification?): String? {
        if (sbn == null) return null
        return getIdBySystemKey(sbn.key)
    }

    /** Mark an ID as suppressed for outgoing dismissal sync. */
    fun markSuppressed(notificationId: String) {
        suppressedIds.add(notificationId)
    }

    /** Returns true if the ID was suppressed and clears the suppression flag. */
    fun consumeSuppressed(notificationId: String): Boolean {
        return suppressedIds.remove(notificationId)
    }

    /** Remove from caches when we it's gone. */
    fun removeFromCaches(id: String) {
        activeNotifications.remove(id)?.let { sbn ->
            keyToId.remove(sbn.key)
        }
        testNotificationIds.remove(id)
    }

    /**
     * Get the notification listener service instance
     */
    private fun getNotificationListenerService(): MediaNotificationListener? {
        return MediaNotificationListener.getInstance()
    }
}
