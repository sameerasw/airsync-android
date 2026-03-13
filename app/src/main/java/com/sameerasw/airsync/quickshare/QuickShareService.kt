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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        const val ACTION_START_DISCOVERY = "com.sameerasw.airsync.quickshare.START_DISCOVERY"
        const val ACTION_CANCEL_TRANSFER = "com.sameerasw.airsync.quickshare.CANCEL_TRANSFER"
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_TRANSFER_ID = "transfer_id"
    }

    private lateinit var server: QuickShareServer
    private lateinit var advertiser: QuickShareAdvertiser
    private lateinit var dataStoreManager: DataStoreManager
    private val activeConnections = mutableMapOf<String, InboundQuickShareConnection>()
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var discoveryJob: kotlinx.coroutines.Job? = null
    
    private data class SpeedState(
        var lastBytes: Long = 0,
        var lastTime: Long = System.currentTimeMillis(),
        var smoothedSpeed: Double? = null
    )
    private val speedStates = mutableMapOf<String, SpeedState>()

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
                discoveryJob?.cancel() // Transfer started, abort timeout
                val pin = conn.ukey2Context?.authString ?: ""
                Log.d(TAG, "Connection ready, PIN: $pin")
                updateForegroundNotification("PIN: $pin - Waiting for files...")
                
                var lastUpdate = 0L
                conn.onFileProgress = { fileName, percent, bytesTransferred, totalSize, transferId ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 800) { // Throttle updates
                        val state = speedStates.getOrPut(transferId) { SpeedState(bytesTransferred, now) }
                        val timeDiff = (now - state.lastTime) / 1000.0
                        
                        var etaString: String? = null
                        if (timeDiff >= 1.0) {
                            val bytesDiff = bytesTransferred - state.lastBytes
                            val intervalSpeed = bytesDiff / timeDiff
                            
                            val alpha = 0.4
                            val newSpeed = if (state.smoothedSpeed != null) {
                                (alpha * intervalSpeed) + ((1.0 - alpha) * state.smoothedSpeed!!)
                            } else {
                                intervalSpeed
                            }
                            state.smoothedSpeed = newSpeed
                            state.lastBytes = bytesTransferred
                            state.lastTime = now

                            if (newSpeed > 0) {
                                val remainingBytes = (totalSize - bytesTransferred).coerceAtLeast(0)
                                val secondsRemaining = (remainingBytes / newSpeed).toLong()
                                etaString = if (secondsRemaining < 60) {
                                    "$secondsRemaining sec remaining"
                                } else {
                                    "${secondsRemaining / 60} min remaining"
                                }
                            }
                        }

                        lastUpdate = now
                        com.sameerasw.airsync.utils.NotificationUtil.showFileProgress(
                            this@QuickShareService,
                            transferId.hashCode(),
                            fileName,
                            percent,
                            transferId,
                            isSending = false,
                            etaString = etaString ?: "Updating..."
                        )
                    }
                }
                
                conn.onFileComplete = { fileName, transferId, success, uri ->
                    speedStates.remove(transferId)
                    com.sameerasw.airsync.utils.NotificationUtil.showFileComplete(
                        this@QuickShareService,
                        transferId.hashCode(),
                        fileName,
                        success,
                        isSending = false,
                        contentUri = uri
                    )
                }
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
                
                if (activeConnections.isEmpty()) {
                    Log.d(TAG, "All transfers finished, stopping discovery")
                    stopDiscovery()
                }
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
            ACTION_START_DISCOVERY -> {
                serviceScope.launch {
                    val enabled = dataStoreManager.isQuickShareEnabled().first()
                    if (enabled) {
                        startDiscoveryWithTimeout()
                    } else {
                        stopDiscovery()
                    }
                }
            }
            ACTION_CANCEL_TRANSFER -> {
                val transferIdStr = intent.getStringExtra(EXTRA_TRANSFER_ID)
                val transferId = transferIdStr?.toLongOrNull()
                Log.d(TAG, "Notification cancel requested for $transferIdStr")
                if (transferId != null) {
                    activeConnections.values.forEach { conn ->
                        if (conn.transferredFiles.containsKey(transferId)) {
                            Log.d(TAG, "Found connection for $transferId, closing")
                            conn.closeConnection()
                        }
                    }
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.cancel(transferIdStr?.hashCode() ?: 0)
            }
            else -> {
                // Remove the startForeground/createNotification call from here
                server.start()
            }
        }
        return START_STICKY
    }

    private fun startDiscoveryWithTimeout() {
        discoveryJob?.cancel()
        
        // Ensure service is in foreground with a notification while active
        startForeground(NOTIFICATION_ID, createNotification("Searching for files..."))
        
        server.start()
        val port = server.port
        if (port == -1) {
            Log.e(TAG, "Failed to get server port")
            return
        }

        discoveryJob = serviceScope.launch {
            val persistedName = dataStoreManager.getDeviceName().first().ifBlank { null }
            val deviceName = persistedName ?: Build.MODEL
            Log.d(TAG, "Starting discovery with name: $deviceName")
            advertiser.startAdvertising(deviceName, port)
            updateForegroundNotification("Quick Share is visible for 60s...")

            kotlinx.coroutines.delay(60_000) // 1 minute timeout
            
            if (activeConnections.isEmpty()) {
                Log.d(TAG, "Discovery timed out, stopping")
                stopDiscovery()
            } else {
                Log.d(TAG, "Discovery timed out but connections are active, keeping discovery on")
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        advertiser.stopAdvertising()
        
        if (activeConnections.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateForegroundNotification("Active transfer in progress...")
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
        stopDiscovery()
        server.stop()
        super.onDestroy()
    }
}
