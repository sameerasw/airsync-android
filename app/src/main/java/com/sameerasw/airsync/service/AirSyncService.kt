package com.sameerasw.airsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.DiscoveryMode
import com.sameerasw.airsync.utils.UDPDiscoveryManager
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that maintains the airsync connection and handles discovery.
 * 
 * Uses connectedDevice foreground service type as per Google Play Store requirements.
 */
class AirSyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var connectedDeviceName: String? = null
    private var isScanning = false
    
    // Network state tracking
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AirSyncService created")
        createNotificationChannel()
        com.sameerasw.airsync.utils.MacDeviceStatusManager.startMonitoring(this)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AirSyncService started with action: ${intent?.action}")

        val action = intent?.action
        when (action) {
            ACTION_START_SCANNING -> startScanning()
            ACTION_START_SYNC -> {
                connectedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Mac"
                startSync()
            }
            ACTION_STOP_SYNC -> stopSync()
            ACTION_APP_FOREGROUND -> handleAppForeground()
            ACTION_APP_BACKGROUND -> handleAppBackground()
            else -> {
                if (connectedDeviceName != null) {
                    startSync()
                } else {
                    startScanning()
                }
            }
        }

        return START_STICKY
    }

    private fun startScanning() {
        Log.d(TAG, "Starting AirSync scanning mode")
        isScanning = true
        connectedDeviceName = null
        startForeground(NOTIFICATION_ID, buildNotification())
        
        val dataStoreManager = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(applicationContext)
        val isDiscoveryEnabled = runBlocking {
            dataStoreManager.getDeviceDiscoveryEnabled().first() 
        }
        
        // Default to PASSIVE mode to save battery
        // But do a burst to check for devices immediately
        UDPDiscoveryManager.start(this, isDiscoveryEnabled)
        UDPDiscoveryManager.setDiscoveryMode(this, DiscoveryMode.PASSIVE)
        UDPDiscoveryManager.burstBroadcast(this)
        
        // Start WakeupService for HTTP wakeups
        com.sameerasw.airsync.service.WakeupService.startService(this)
        
        // Also trigger auto-reconnect logic to check if we already have a candidate
        WebSocketUtil.requestAutoReconnect(this)
    }

    private fun handleAppForeground() {
        if (isScanning) {
            Log.d(TAG, "App in foreground, switching to ACTIVE discovery")
            UDPDiscoveryManager.setDiscoveryMode(this, DiscoveryMode.ACTIVE)
            startForeground(NOTIFICATION_ID, buildNotification()) // Update notification if needed
        }
    }

    private fun handleAppBackground() {
        if (isScanning) {
            Log.d(TAG, "App in background, switching to PASSIVE discovery")
            UDPDiscoveryManager.setDiscoveryMode(this, DiscoveryMode.PASSIVE)
            startForeground(NOTIFICATION_ID, buildNotification()) // Update notification if needed
        }
    }

    private fun startSync() {
        Log.d(TAG, "Starting AirSync foreground service (connected)")
        isScanning = false
        startForeground(NOTIFICATION_ID, buildNotification())
        
        val dataStoreManager = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(applicationContext)
        val isDiscoveryEnabled = runBlocking {
            dataStoreManager.getDeviceDiscoveryEnabled().first() 
        }
        
        // Keep discovery manager running for wake-ups even when connected
        // But stay in Passive mode mostly
        UDPDiscoveryManager.start(this, isDiscoveryEnabled)
        UDPDiscoveryManager.setDiscoveryMode(this, DiscoveryMode.PASSIVE)
        
        com.sameerasw.airsync.service.WakeupService.startService(this)
    }

    private fun stopSync() {
        Log.d(TAG, "Stopping AirSync foreground service")
        UDPDiscoveryManager.stop(this)
        com.sameerasw.airsync.service.WakeupService.stopService(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val builder = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available, triggering burst broadcast")
                    // When network becomes available, do a burst to announce ourselves
                    if (isScanning) {
                        UDPDiscoveryManager.burstBroadcast(applicationContext)
                        WebSocketUtil.requestAutoReconnect(applicationContext)
                    }
                }
            }
            
            connectivityManager.registerNetworkCallback(builder.build(), networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirSync Status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Shows AirSync connection and discovery status"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val disconnectIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_laptop_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (isScanning && connectedDeviceName == null ) {
            builder.setContentTitle(getString(R.string.app_name))
            builder.setContentText(getString(R.string.no_device_connected))
        } else {
            val name = connectedDeviceName ?: "Mac"
            builder.setContentTitle(getString(R.string.app_name))
            builder.setContentText(getString(R.string.connected_to_device, name))
            builder.addAction(R.drawable.rounded_link_off_24, getString(R.string.disconnect), disconnectPendingIntent)
        }
        
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "AirSyncService destroyed")
        
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        
        com.sameerasw.airsync.utils.MacDeviceStatusManager.stopMonitoring()
        com.sameerasw.airsync.utils.MacDeviceStatusManager.cleanup(this)
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AirSyncService"
        private const val CHANNEL_ID = "airsync_connection_channel"
        private const val NOTIFICATION_ID = 4001

        const val ACTION_START_SCANNING = "com.sameerasw.airsync.START_SCANNING"
        const val ACTION_START_SYNC = "com.sameerasw.airsync.START_SYNC"
        const val ACTION_STOP_SYNC = "com.sameerasw.airsync.STOP_SYNC"
        const val ACTION_DISCONNECT = "com.sameerasw.airsync.DISCONNECT_FROM_NOTIFICATION"
        const val ACTION_APP_FOREGROUND = "com.sameerasw.airsync.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.sameerasw.airsync.APP_BACKGROUND"
        
        const val EXTRA_DEVICE_NAME = "device_name"

        fun startScanning(context: Context) {
            val intent = Intent(context, AirSyncService::class.java).apply {
                action = ACTION_START_SCANNING
            }
            startAction(context, intent)
        }

        fun start(context: Context, deviceName: String?) {
            val intent = Intent(context, AirSyncService::class.java).apply {
                action = ACTION_START_SYNC
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            startAction(context, intent)
        }
        
        fun notifyAppForeground(context: Context) {
            val intent = Intent(context, AirSyncService::class.java).apply {
                action = ACTION_APP_FOREGROUND
            }
            startAction(context, intent)
        }
        
        fun notifyAppBackground(context: Context) {
            val intent = Intent(context, AirSyncService::class.java).apply {
                action = ACTION_APP_BACKGROUND
            }
            startAction(context, intent)
        }

        private fun startAction(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirSyncService::class.java).apply {
                action = ACTION_STOP_SYNC
            }
            context.startService(intent)
        }
    }
}
