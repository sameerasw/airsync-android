package com.sameerasw.airsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.models.CallState
import com.sameerasw.airsync.models.OngoingCall
import com.sameerasw.airsync.utils.WebSocketUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing live notifications (calls, timers, etc.)
 * Supports Android's live notification updates API
 */
class LiveNotificationService : Service() {

    companion object {
        private const val TAG = "LiveNotificationService"
        
        // Notification channels
        private const val CHANNEL_CALL = "live_call_channel"
        private const val CHANNEL_TIMER = "live_timer_channel"
        private const val CHANNEL_STOPWATCH = "live_stopwatch_channel"
        
        // Actions
        const val ACTION_SHOW_CALL = "SHOW_CALL"
        const val ACTION_UPDATE_CALL = "UPDATE_CALL"
        const val ACTION_DISMISS_CALL = "DISMISS_CALL"
        const val ACTION_ANSWER_CALL = "ANSWER_CALL"
        const val ACTION_REJECT_CALL = "REJECT_CALL"
        const val ACTION_END_CALL = "END_CALL"
        
        // Extras
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_STATE = "state"
        const val EXTRA_START_TIME = "start_time"
        const val EXTRA_IS_INCOMING = "is_incoming"
        const val EXTRA_ALBUM_ART = "album_art"
        
        private var instance: LiveNotificationService? = null
        
        // Active notifications
        private val activeNotifications = ConcurrentHashMap<String, Int>()
        private var nextNotificationId = 2000
        
        fun showCallNotification(
            context: Context,
            call: OngoingCall,
            albumArt: Bitmap? = null
        ) {
            val intent = Intent(context, LiveNotificationService::class.java).apply {
                action = ACTION_SHOW_CALL
                putExtra(EXTRA_CALL_ID, call.id)
                putExtra(EXTRA_NUMBER, call.number)
                putExtra(EXTRA_CONTACT_NAME, call.contactName)
                putExtra(EXTRA_STATE, call.state.name)
                putExtra(EXTRA_START_TIME, call.startTime)
                putExtra(EXTRA_IS_INCOMING, call.isIncoming)
            }
            context.startService(intent)
            
            // Store album art separately if provided
            instance?.storeAlbumArt(call.id, albumArt)
        }
        
        fun updateCallNotification(
            context: Context,
            call: OngoingCall,
            albumArt: Bitmap? = null
        ) {
            val intent = Intent(context, LiveNotificationService::class.java).apply {
                action = ACTION_UPDATE_CALL
                putExtra(EXTRA_CALL_ID, call.id)
                putExtra(EXTRA_NUMBER, call.number)
                putExtra(EXTRA_CONTACT_NAME, call.contactName)
                putExtra(EXTRA_STATE, call.state.name)
                putExtra(EXTRA_START_TIME, call.startTime)
                putExtra(EXTRA_IS_INCOMING, call.isIncoming)
            }
            context.startService(intent)
            
            // Update album art if provided
            albumArt?.let {
                instance?.storeAlbumArt(call.id, it)
            }
        }
        
        fun dismissCallNotification(context: Context, callId: String) {
            val intent = Intent(context, LiveNotificationService::class.java).apply {
                action = ACTION_DISMISS_CALL
                putExtra(EXTRA_CALL_ID, callId)
            }
            context.startService(intent)
        }
    }
    
    private val albumArtCache = ConcurrentHashMap<String, Bitmap>()
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        Log.d(TAG, "LiveNotificationService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_CALL -> handleShowCall(intent)
            ACTION_UPDATE_CALL -> handleUpdateCall(intent)
            ACTION_DISMISS_CALL -> handleDismissCall(intent)
            ACTION_ANSWER_CALL -> handleAnswerCall(intent)
            ACTION_REJECT_CALL -> handleRejectCall(intent)
            ACTION_END_CALL -> handleEndCall(intent)
        }
        
        // Stop service if no active notifications
        if (activeNotifications.isEmpty()) {
            stopSelf()
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Call notification channel
        val callChannel = NotificationChannel(
            CHANNEL_CALL,
            "Live Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ongoing call notifications"
            setSound(null, null)
            enableVibration(true)
            enableLights(true)
        }
        
        // Timer channel
        val timerChannel = NotificationChannel(
            CHANNEL_TIMER,
            "Timers",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Active timer notifications"
            setSound(null, null)
        }
        
        // Stopwatch channel
        val stopwatchChannel = NotificationChannel(
            CHANNEL_STOPWATCH,
            "Stopwatch",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active stopwatch notifications"
            setSound(null, null)
        }
        
        notificationManager.createNotificationChannels(
            listOf(callChannel, timerChannel, stopwatchChannel)
        )
    }
    
    private fun handleShowCall(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
        val stateString = intent.getStringExtra(EXTRA_STATE) ?: return
        val startTime = intent.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis())
        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, true)
        
        val state = try {
            CallState.valueOf(stateString)
        } catch (e: Exception) {
            CallState.RINGING
        }
        
        val call = OngoingCall(
            id = callId,
            number = number,
            contactName = contactName,
            state = state,
            startTime = startTime,
            isIncoming = isIncoming
        )
        
        val notificationId = activeNotifications.getOrPut(callId) {
            nextNotificationId++
        }
        
        val notification = createCallNotification(call)
        
        // CallStyle notifications require foreground service
        try {
            startForeground(notificationId, notification)
            Log.d(TAG, "Started foreground service with call notification for $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service, falling back to regular notification", e)
            // Fallback: create a regular notification without CallStyle
            val fallbackNotification = createFallbackCallNotification(call)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(notificationId, fallbackNotification)
        }
    }
    
    private fun handleUpdateCall(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        val notificationId = activeNotifications[callId] ?: return
        
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
        val stateString = intent.getStringExtra(EXTRA_STATE) ?: return
        val startTime = intent.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis())
        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, true)
        
        val state = try {
            CallState.valueOf(stateString)
        } catch (e: Exception) {
            CallState.ACTIVE
        }
        
        val call = OngoingCall(
            id = callId,
            number = number,
            contactName = contactName,
            state = state,
            startTime = startTime,
            isIncoming = isIncoming
        )
        
        val notification = createCallNotification(call)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
        
        Log.d(TAG, "Updated call notification for $callId")
    }
    
    private fun handleDismissCall(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        val notificationId = activeNotifications.remove(callId) ?: return
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(notificationId)
        
        // Remove cached album art
        albumArtCache.remove(callId)
        
        // Stop foreground service if no more active calls
        if (activeNotifications.isEmpty()) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                Log.d(TAG, "Stopped foreground service - no more active calls")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop foreground service", e)
            }
        }
        
        Log.d(TAG, "Dismissed call notification for $callId")
    }
    
    private fun handleAnswerCall(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        
        // Send answer command to Mac
        val json = """{"type":"callAction","data":{"action":"answer","callId":"$callId"}}"""
        WebSocketUtil.sendMessage(json)
        
        Log.d(TAG, "Answer call action for $callId")
    }
    
    private fun handleRejectCall(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        
        // Send reject command to Mac
        val json = """{"type":"callAction","data":{"action":"reject","callId":"$callId"}}"""
        WebSocketUtil.sendMessage(json)
        
        // Dismiss notification
        handleDismissCall(intent)
        
        Log.d(TAG, "Reject call action for $callId")
    }
    
    private fun handleEndCall(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        
        // Send end command to Mac
        val json = """{"type":"callAction","data":{"action":"end","callId":"$callId"}}"""
        WebSocketUtil.sendMessage(json)
        
        // Dismiss notification
        handleDismissCall(intent)
        
        Log.d(TAG, "End call action for $callId")
    }
    
    private fun createCallNotification(call: OngoingCall): Notification {
        val displayName = call.contactName ?: call.number
        
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(displayName)
            .setContentIntent(contentPendingIntent)
            .setOngoing(call.state != CallState.DISCONNECTED)
            .setAutoCancel(call.state == CallState.DISCONNECTED)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        
        // Add album art if available
        albumArtCache[call.id]?.let { bitmap ->
            builder.setLargeIcon(bitmap)
        }
        
        // Set content text based on state
        val contentText = when (call.state) {
            CallState.RINGING -> if (call.isIncoming) "Incoming call" else "Outgoing call"
            CallState.ACTIVE -> {
                val duration = (System.currentTimeMillis() - call.startTime) / 1000
                val minutes = duration / 60
                val seconds = duration % 60
                String.format("Call in progress - %d:%02d", minutes, seconds)
            }
            CallState.HELD -> "Call on hold"
            CallState.DISCONNECTED -> "Call ended"
        }
        builder.setContentText(contentText)
        
        // Add actions based on state
        when (call.state) {
            CallState.RINGING -> {
                if (call.isIncoming) {
                    // Answer and Reject buttons for incoming calls
                    val answerIntent = Intent(this, LiveNotificationService::class.java).apply {
                        action = ACTION_ANSWER_CALL
                        putExtra(EXTRA_CALL_ID, call.id)
                    }
                    val answerPendingIntent = PendingIntent.getService(
                        this, call.id.hashCode(), answerIntent, PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    val rejectIntent = Intent(this, LiveNotificationService::class.java).apply {
                        action = ACTION_REJECT_CALL
                        putExtra(EXTRA_CALL_ID, call.id)
                    }
                    val rejectPendingIntent = PendingIntent.getService(
                        this, call.id.hashCode() + 1, rejectIntent, PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    builder.addAction(
                        R.drawable.outline_call_24,
                        "Answer",
                        answerPendingIntent
                    )
                    builder.addAction(
                        R.drawable.outline_call_end_24,
                        "Reject",
                        rejectPendingIntent
                    )
                    
                    // Use full screen intent for incoming calls
                    builder.setFullScreenIntent(contentPendingIntent, true)
                }
            }
            CallState.ACTIVE, CallState.HELD -> {
                // End call button for active calls
                val endIntent = Intent(this, LiveNotificationService::class.java).apply {
                    action = ACTION_END_CALL
                    putExtra(EXTRA_CALL_ID, call.id)
                }
                val endPendingIntent = PendingIntent.getService(
                    this, call.id.hashCode() + 2, endIntent, PendingIntent.FLAG_IMMUTABLE
                )
                
                builder.addAction(
                    R.drawable.outline_call_end_24,
                    "End Call",
                    endPendingIntent
                )
            }
            CallState.DISCONNECTED -> {
                // No actions for disconnected calls
            }
        }
        
        // Use CallStyle for Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder()
                .setName(displayName)
                .setImportant(true)
                .build()
            
            val callStyle = when (call.state) {
                CallState.RINGING -> {
                    if (call.isIncoming) {
                        NotificationCompat.CallStyle.forIncomingCall(
                            person,
                            PendingIntent.getService(
                                this,
                                call.id.hashCode() + 1,
                                Intent(this, LiveNotificationService::class.java).apply {
                                    action = ACTION_REJECT_CALL
                                    putExtra(EXTRA_CALL_ID, call.id)
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            ),
                            PendingIntent.getService(
                                this,
                                call.id.hashCode(),
                                Intent(this, LiveNotificationService::class.java).apply {
                                    action = ACTION_ANSWER_CALL
                                    putExtra(EXTRA_CALL_ID, call.id)
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    } else {
                        NotificationCompat.CallStyle.forOngoingCall(
                            person,
                            PendingIntent.getService(
                                this,
                                call.id.hashCode() + 2,
                                Intent(this, LiveNotificationService::class.java).apply {
                                    action = ACTION_END_CALL
                                    putExtra(EXTRA_CALL_ID, call.id)
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }
                }
                CallState.ACTIVE, CallState.HELD -> {
                    NotificationCompat.CallStyle.forOngoingCall(
                        person,
                        PendingIntent.getService(
                            this,
                            call.id.hashCode() + 2,
                            Intent(this, LiveNotificationService::class.java).apply {
                                action = ACTION_END_CALL
                                putExtra(EXTRA_CALL_ID, call.id)
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                }
                CallState.DISCONNECTED -> {
                    NotificationCompat.CallStyle.forOngoingCall(person, contentPendingIntent)
                }
            }
            
            builder.setStyle(callStyle)
        }
        
        return builder.build()
    }
    
    private fun createFallbackCallNotification(call: OngoingCall): Notification {
        val displayName = call.contactName ?: call.number
        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Call - $displayName")
            .setContentText(when (call.state) {
                CallState.RINGING -> if (call.isIncoming) "Incoming call" else "Outgoing call"
                CallState.ACTIVE -> "Call in progress"
                CallState.HELD -> "Call on hold"
                CallState.DISCONNECTED -> "Call ended"
            })
            .setContentIntent(contentPendingIntent)
            .setOngoing(call.state != CallState.DISCONNECTED)
            .setAutoCancel(call.state == CallState.DISCONNECTED)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
        
        return builder.build()
    }
    
    private fun storeAlbumArt(callId: String, albumArt: Bitmap?) {
        if (albumArt != null) {
            albumArtCache[callId] = albumArt
        } else {
            albumArtCache.remove(callId)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        albumArtCache.clear()
        activeNotifications.clear()
        Log.d(TAG, "LiveNotificationService destroyed")
    }
}
