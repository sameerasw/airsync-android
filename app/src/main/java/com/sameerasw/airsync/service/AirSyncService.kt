package com.sameerasw.airsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.DiscoveryMode
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.ShortcutUtil
import com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator
import com.sameerasw.airsync.utils.WebDavServer
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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

    private var webDavServer: WebDavServer? = null
    private var webDavJob: Job? = null

    // Network state tracking
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val connectionStatusListener: (Boolean) -> Unit = { _ ->
        scope.launch {
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        Log.d(TAG, "AirSyncService created")
        createNotificationChannel()
        MacDeviceStatusManager.startMonitoring(this)
        val dataStoreManager = com.sameerasw.airsync.data.local.DataStoreManager(this)
        scope.launch {
            combine(
                dataStoreManager.isCellularSyncEnabled,
                WebSocketUtil.connectionState
            ) { isEnabled, isConnected ->
                isEnabled && isConnected
            }.collect { shouldRun ->
                if (shouldRun) {
                    com.sameerasw.airsync.utils.CellularMonitor.start(this@AirSyncService)
                } else {
                    com.sameerasw.airsync.utils.CellularMonitor.stop(this@AirSyncService)
                }
            }
        }
        registerNetworkCallback()
        WebSocketUtil.registerConnectionStatusListener(connectionStatusListener)

        // Monitor connection status, auto-reconnect, and battery status to update notification live
        scope.launch {
            MacDeviceStatusManager.macDeviceStatus.collect {
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AirSyncService started with action: ${intent?.action}")

        val action = intent?.action
        when (action) {
            ACTION_START_SCANNING -> startScanning()
            ACTION_START_SYNC -> {
                connectedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Mac"
                startSync()
                ShortcutUtil.refreshShortcuts(this, true)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val dataStoreManager =
            DataStoreManager.getInstance(applicationContext)
        val isDiscoveryEnabled = runBlocking {
            dataStoreManager.getDeviceDiscoveryEnabled().first()
        }

        // Default to PASSIVE mode to save battery
        // But do a burst to check for devices immediately
        DiscoveryOrchestrator.start(this, isDiscoveryEnabled)
        DiscoveryOrchestrator.setDiscoveryMode(this, DiscoveryMode.PASSIVE)
        DiscoveryOrchestrator.burstBroadcast(this)

        // Start WakeupService for HTTP wakeups
        WakeupService.startService(this)

        // Also trigger auto-reconnect logic to check if we already have a candidate
        WebSocketUtil.requestAutoReconnect(this)
    }

    private fun startWebDavServer() {
        if (webDavServer == null) {
            webDavServer = WebDavServer(this)
        }
        webDavServer?.start()
    }

    private fun stopWebDavServer() {
        webDavServer?.stop()
        webDavServer = null
    }

    private fun monitorWebDavRequirements() {
        webDavJob?.cancel()
        webDavJob = scope.launch {
            val dataStoreManager = DataStoreManager.getInstance(applicationContext)
            combine(
                dataStoreManager.isFileAccessEnabled(),
                dataStoreManager.getLastConnectedDevice()
            ) { isEnabled, device ->
                Log.d(TAG, "WebDAV flow evaluation: isEnabled=$isEnabled, isPlus=${device?.isPlus}")
                isEnabled && device?.isPlus == true
            }.collect { shouldStart ->
                Log.d(TAG, "WebDAV requirement state updated: shouldStart = $shouldStart")
                if (shouldStart) {
                    startWebDavServer()
                } else {
                    stopWebDavServer()
                }
            }
        }
    }

    private fun handleAppForeground() {
        if (isScanning) {
            Log.d(TAG, "App in foreground, switching to ACTIVE discovery")
            DiscoveryOrchestrator.setDiscoveryMode(this, DiscoveryMode.ACTIVE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun handleAppBackground() {
        if (isScanning) {
            Log.d(TAG, "App in background, switching to PASSIVE discovery")
            DiscoveryOrchestrator.setDiscoveryMode(this, DiscoveryMode.PASSIVE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun startSync() {
        if (!isScanning && connectedDeviceName != null) {
            Log.d(TAG, "AirSync foreground service already in sync state, ignoring")
            return
        }
        Log.d(TAG, "Starting AirSync foreground service (connected)")
        isScanning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val dataStoreManager =
            DataStoreManager.getInstance(applicationContext)
        val isDiscoveryEnabled = runBlocking {
            dataStoreManager.getDeviceDiscoveryEnabled().first()
        }

        // Keep discovery manager running for wake-ups even when connected
        // But stay in Passive mode mostly
        DiscoveryOrchestrator.start(this, isDiscoveryEnabled)
        DiscoveryOrchestrator.setDiscoveryMode(this, DiscoveryMode.PASSIVE)

        WakeupService.startService(this)
        monitorWebDavRequirements()
    }

    private fun stopSync() {
        Log.d(TAG, "Stopping AirSync foreground service")
        webDavJob?.cancel()
        webDavJob = null
        stopWebDavServer()
        ShortcutUtil.refreshShortcuts(this, false)
        DiscoveryOrchestrator.stop(this)
        WakeupService.stopService(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager =
                getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val builder = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(
                        TAG,
                        "Network available, triggering burst broadcast and refreshing socket"
                    )
                    // Refresh UDP socket to bind to new network interface
                    DiscoveryOrchestrator.refreshSocket()
                    // When network becomes available, do a burst to announce ourselves
                    if (isScanning) {
                        DiscoveryOrchestrator.burstBroadcast(applicationContext)
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

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating foreground notification", e)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val disconnectIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent =
            PendingIntent.getBroadcast(this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_laptop_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val isConnected = WebSocketUtil.isConnected()
        val isAuto = WebSocketUtil.isAutoReconnecting()
        val isConnecting = WebSocketUtil.isConnecting()

        val dataStoreManager = DataStoreManager.getInstance(applicationContext)
        val lastDevice = runBlocking { dataStoreManager.getLastConnectedDevice().first() }
        val macStatus = MacDeviceStatusManager.macDeviceStatus.value

        if (isConnected && lastDevice != null) {
            val name = lastDevice.name
            builder.setContentTitle(getString(R.string.app_name))
            
            val batteryText = macStatus?.let { status ->
                val level = status.battery.level
                if (level >= 0) {
                    val pct = level.coerceIn(0, 100)
                    if (status.battery.isCharging) " ($pct% Charging)" else " ($pct%)"
                } else ""
            } ?: ""

            builder.setContentText(getString(R.string.connected_to_device, name) + batteryText)
            builder.addAction(
                R.drawable.rounded_link_off_24,
                getString(R.string.disconnect),
                disconnectPendingIntent
            )
        } else if (com.sameerasw.airsync.data.ble.BleGattServer.isAnyAuthenticated() && lastDevice != null) {
            builder.setContentTitle(getString(R.string.app_name))
            builder.setContentText("Connected to ${lastDevice.name} via Bluetooth")
            builder.addAction(
                R.drawable.rounded_link_off_24,
                getString(R.string.disconnect),
                disconnectPendingIntent
            )
        } else if (isAuto) {
            builder.setContentTitle("Reconnecting...")
            builder.setContentText(if (isConnecting) "Trying to connect to Mac..." else "Waiting to retry connection...")
        } else if (isConnecting) {
            builder.setContentTitle("Connecting...")
            builder.setContentText("Connecting to last device...")
        } else {
            builder.setContentTitle(getString(R.string.app_name))
            builder.setContentText(getString(R.string.no_device_connected))
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "AirSyncService destroyed")
        serviceInstance = null
        WebSocketUtil.unregisterConnectionStatusListener(connectionStatusListener)

        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }

        stopWebDavServer()
        MacDeviceStatusManager.stopMonitoring()
        MacDeviceStatusManager.cleanup(this)
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

        private var serviceInstance: AirSyncService? = null

        fun isRunning(): Boolean = serviceInstance != null

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
                if (isRunning()) {
                    context.startService(intent)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
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
