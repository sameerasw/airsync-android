package com.sameerasw.airsync.quickshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.sharing.ConnectionResponseFrame
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that manages Quick Share advertisement and connections.
 */
class QuickShareService : Service() {

    companion object {
        private const val TAG = "QuickShareService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "quick_share_channel"
        
        const val ACTION_ACCEPT = "com.sameerasw.airsync.quickshare.ACCEPT"
        const val ACTION_REJECT = "com.sameerasw.airsync.quickshare.REJECT"
        const val EXTRA_CONNECTION_ID = "connection_id"
    }

    private lateinit var server: QuickShareServer
    private lateinit var advertiser: QuickShareAdvertiser
    private lateinit var dataStoreManager: DataStoreManager
    private val activeConnections = mutableMapOf<String, InboundQuickShareConnection>()
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    inner class LocalBinder : Binder() {
        fun getService(): QuickShareService = this@QuickShareService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        server = QuickShareServer(this) { connection ->
            val id = java.util.UUID.randomUUID().toString()
            activeConnections[id] = connection
            
            connection.onConnectionReady = { conn ->
                val pin = conn.ukey2Context?.authString ?: ""
                Log.d(TAG, "Connection ready, PIN: $pin")
                updateForegroundNotification("PIN: $pin - Waiting for files...")
            }
            
            connection.onIntroductionReceived = { intro ->
                val deviceName = connection.endpointName ?: "Unknown Device"
                val firstFileName = intro.file_metadata.firstOrNull()?.name ?: "Unknown File"
                val fileCount = intro.file_metadata.size
                val displayText = if (fileCount > 1) "$firstFileName and ${fileCount - 1} more" else firstFileName
                
                serviceScope.launch {
                    val pairedDevice = dataStoreManager.getLastConnectedDevice().first()
                    val pairedName = pairedDevice?.name
                    
                    if (!pairedName.isNullOrBlank() && deviceName == pairedName) {
                        Log.d(TAG, "Auto-accepting transfer from paired Mac: $deviceName")
                        connection.sendSharingResponse(ConnectionResponseFrame.Status.ACCEPT)
                    } else {
                        showConsentNotification(id, deviceName, displayText)
                    }
                }
            }
            
            connection.onFinished = {
                activeConnections.remove(id)
                val manager = getSystemService(NotificationManager::class.java)
                manager.cancel(NOTIFICATION_ID + id.hashCode())
            }
        }
        advertiser = QuickShareAdvertiser(this)
        dataStoreManager = DataStoreManager.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACCEPT -> {
                val id = intent.getStringExtra(EXTRA_CONNECTION_ID)
                activeConnections[id]?.sendSharingResponse(ConnectionResponseFrame.Status.ACCEPT)
            }
            ACTION_REJECT -> {
                val id = intent.getStringExtra(EXTRA_CONNECTION_ID)
                activeConnections[id]?.sendSharingResponse(ConnectionResponseFrame.Status.REJECT)
                activeConnections.remove(id)
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification("Waiting for files..."))
                startQuickShare()
            }
        }
        return START_STICKY
    }

    private fun startQuickShare() {
        server.start()
        // Wait a bit for server to start and get port
        val port = server.port
        if (port != -1) {
            serviceScope.launch {
                val persistedName = dataStoreManager.getDeviceName().first().ifBlank { null }
                val deviceName = persistedName ?: Build.MODEL
                Log.d(TAG, "Starting advertisement with name: $deviceName")
                advertiser.startAdvertising(deviceName, port)
            }
        } else {
            // Retry or handle error
            Log.e(TAG, "Failed to get server port")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quick Share",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateForegroundNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quick Share")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_laptop_24)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showConsentNotification(connectionId: String, deviceName: String, fileName: String) {
        val acceptIntent = Intent(this, QuickShareService::class.java).apply {
            action = ACTION_ACCEPT
            putExtra(EXTRA_CONNECTION_ID, connectionId)
        }
        val acceptPendingIntent = PendingIntent.getService(this, 0, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val rejectIntent = Intent(this, QuickShareService::class.java).apply {
            action = ACTION_REJECT
            putExtra(EXTRA_CONNECTION_ID, connectionId)
        }
        val rejectPendingIntent = PendingIntent.getService(this, 1, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quick Share from $deviceName")
            .setContentText("Wants to send: $fileName")
            .setSmallIcon(R.drawable.ic_laptop_24)
            .addAction(0, "Accept", acceptPendingIntent)
            .addAction(0, "Reject", rejectPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + connectionId.hashCode(), notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        advertiser.stopAdvertising()
        server.stop()
        super.onDestroy()
    }
}
