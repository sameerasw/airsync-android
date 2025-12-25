package com.sameerasw.airsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.SyncManager
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background service that keeps the app connected and syncing data
 * - Maintains WebSocket connection
 * - Syncs notifications in real-time
 * - Syncs health data periodically
 * - Syncs calls and messages
 */
class BackgroundSyncService : Service() {
    
    companion object {
        private const val TAG = "BackgroundSyncService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "airsync_background_sync"
        
        private var isRunning = false
        
        fun start(context: Context) {
            if (isRunning) {
                Log.d(TAG, "Service already running")
                return
            }
            
            val intent = Intent(context, BackgroundSyncService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, BackgroundSyncService::class.java)
            context.stopService(intent)
        }
        
        fun isRunning(): Boolean = isRunning
    }
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var dataStoreManager: DataStoreManager
    
    private var syncJob: Job? = null
    private var reconnectJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Background sync service created")
        dataStoreManager = DataStoreManager(this)
        isRunning = true
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Background sync service started")
        
        // Start syncing
        startSyncing()
        
        // Keep service alive
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Background sync service destroyed")
        
        isRunning = false
        syncJob?.cancel()
        reconnectJob?.cancel()
        serviceJob.cancel()
        
        // Stop periodic sync
        SyncManager.stopPeriodicSync()
    }
    
    private fun startSyncing() {
        syncJob?.cancel()
        
        syncJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (WebSocketUtil.isConnected()) {
                        updateNotification("Connected - Syncing data")
                        
                        // Start periodic sync if not already running
                        SyncManager.startPeriodicSync(this@BackgroundSyncService)
                        
                        // Wait and check connection
                        delay(30_000) // Check every 30 seconds
                    } else {
                        updateNotification("Disconnected - Attempting to reconnect")
                        
                        // Try to reconnect
                        attemptReconnect()
                        
                        delay(10_000) // Wait 10 seconds before next attempt
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in sync loop: ${e.message}", e)
                    delay(10_000)
                }
            }
        }
    }
    
    private suspend fun attemptReconnect() {
        try {
            val ipAddress = dataStoreManager.getIpAddress().first()
            val port = dataStoreManager.getPort().first().toIntOrNull() ?: 6996
            val symmetricKey = dataStoreManager.getLastConnectedDevice().first()?.symmetricKey
            val autoReconnect = dataStoreManager.getAutoReconnectEnabled().first()
            
            if (ipAddress.isNotEmpty() && autoReconnect) {
                Log.d(TAG, "Attempting to reconnect to $ipAddress:$port")
                
                WebSocketUtil.connect(
                    context = this@BackgroundSyncService,
                    ipAddress = ipAddress,
                    port = port,
                    symmetricKey = symmetricKey,
                    manualAttempt = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attempting reconnect: ${e.message}", e)
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps AirSync connected and syncing data"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirSync")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
}
