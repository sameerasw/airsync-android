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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.DiscoveryMode
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.ShortcutUtil
import com.sameerasw.airsync.utils.UDPDiscoveryManager
import com.sameerasw.airsync.utils.WebDavServer
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    private var httpServerSocket: ServerSocket? = null
    private var isHttpServerRunning = false

    // Network state tracking
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val connectionStatusListener: (Boolean) -> Unit = { _ ->
        scope.launch {
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AirSyncService created")
        createNotificationChannel()
        MacDeviceStatusManager.startMonitoring(this)
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
        startForeground(NOTIFICATION_ID, buildNotification())
        val dataStoreManager = DataStoreManager.getInstance(applicationContext)
        
        // Start with default of true to avoid blocking the thread during boot
        UDPDiscoveryManager.start(this, true)
        UDPDiscoveryManager.setDiscoveryMode(this, DiscoveryMode.ACTIVE)
        UDPDiscoveryManager.burstBroadcast(this)
        
        // Update asynchronously
        scope.launch {
            try {
                val isDiscoveryEnabled = dataStoreManager.getDeviceDiscoveryEnabled().first()
                if (!isDiscoveryEnabled) {
                    UDPDiscoveryManager.setDiscoveryEnabled(this@AirSyncService, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read discovery preference", e)
            }
        }

        scope.launch {
            delay(60_000L)
            if (isScanning) {
                Log.d(TAG, "Switching from ACTIVE to PASSIVE discovery after 60s")
                UDPDiscoveryManager.setDiscoveryMode(applicationContext, DiscoveryMode.PASSIVE)
            }
        }

        startHttpServer()

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
        if (!isScanning && connectedDeviceName != null) {
            Log.d(TAG, "AirSync foreground service already in sync state, ignoring")
            return
        }
        Log.d(TAG, "Starting AirSync foreground service (connected)")
        isScanning = false
        startForeground(NOTIFICATION_ID, buildNotification())

        val dataStoreManager = DataStoreManager.getInstance(applicationContext)

        // Stop discovery completely while connected — it restarts in ACTIVE mode when startScanning() is called
        UDPDiscoveryManager.stop(this)

        monitorWebDavRequirements()
        startHttpServer()
    }

    private fun stopSync() {
        Log.d(TAG, "Stopping AirSync foreground service")
        webDavJob?.cancel()
        webDavJob = null
        stopWebDavServer()
        ShortcutUtil.refreshShortcuts(this, false)
        UDPDiscoveryManager.stop(this)
        // NOTE: HTTP server intentionally kept running so the Mac's /wakeup POST
        // can still be received after a disconnect. It is stopped in onDestroy().
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
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    val networkType = when {
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN/Tailscale"
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                        else -> "Other/Cellular"
                    }
                    Log.d(TAG, "Network available: $networkType, triggering burst broadcast and refreshing socket")
                    // Refresh UDP socket to bind to new network interface
                    UDPDiscoveryManager.refreshSocket()
                    // Burst broadcast to announce presence
                    UDPDiscoveryManager.burstBroadcast(applicationContext)
                    // Trigger auto-reconnect for any known peers
                    WebSocketUtil.requestAutoReconnect(applicationContext)
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost, triggering peer exchange")
                    // Trigger peer exchange to find peers via alternative routes
                    com.sameerasw.airsync.utils.UDPDiscoveryManager.triggerPeerExchange(this@AirSyncService)
                }
                
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val hasVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    Log.d(TAG, "Network capabilities changed - WiFi: $hasWifi, VPN: $hasVpn")
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
        stopHttpServer()
        MacDeviceStatusManager.stopMonitoring()
        MacDeviceStatusManager.cleanup(this)
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private fun startHttpServer() {
        if (isHttpServerRunning) return
        scope.launch(Dispatchers.IO) {
            try {
                isHttpServerRunning = true
                httpServerSocket = ServerSocket(HTTP_SERVER_PORT)
                Log.i(TAG, "Wake-up HTTP server started on port $HTTP_SERVER_PORT")

                while (isHttpServerRunning && httpServerSocket?.isClosed == false) {
                    try {
                        val clientSocket = httpServerSocket?.accept()
                        if (clientSocket != null) {
                            launch(Dispatchers.IO) {
                                handleHttpRequest(clientSocket)
                            }
                        }
                    } catch (e: Exception) {
                        if (isHttpServerRunning) {
                            Log.e(TAG, "Error accepting HTTP connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
            }
        }
    }

    private fun stopHttpServer() {
        isHttpServerRunning = false
        try {
            httpServerSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing HTTP server socket", e)
        }
        httpServerSocket = null
        Log.i(TAG, "Wake-up HTTP server stopped")
    }

    private suspend fun handleHttpRequest(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                clientSocket.use { socket ->
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val output = PrintWriter(socket.getOutputStream(), true)

                    val requestLine = input.readLine()
                    if (requestLine == null) return@withContext

                    val parts = requestLine.split(" ")
                    if (parts.size < 3) return@withContext

                    val method = parts[0]
                    val path = parts[1]

                    var contentLength = 0
                    var line: String?
                    while (input.readLine().also { line = it } != null) {
                        if (line!!.isEmpty()) break
                        if (line.lowercase().startsWith("content-length:")) {
                            contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                        }
                    }

                    if (method == "POST" && path == WAKEUP_ENDPOINT) {
                        val body = if (contentLength > 0) {
                            val bodyChars = CharArray(contentLength)
                            input.read(bodyChars, 0, contentLength)
                            String(bodyChars)
                        } else {
                            ""
                        }

                        Log.d(TAG, "Received HTTP wake-up request: $body")

                        try {
                            val jsonRequest = JSONObject(body)

                            val macIp: String
                            val macPort: Int
                            val macName: String
                            val isManual: Boolean

                            if (jsonRequest.has("data")) {
                                val data = jsonRequest.getJSONObject("data")
                                macIp = data.optString("macIP", "")
                                macPort = data.optInt("macPort", 6996)
                                macName = data.optString("macName", "Mac")
                                isManual = data.optBoolean("isManual", false)
                            } else {
                                macIp = jsonRequest.optString("macIp", "")
                                macPort = jsonRequest.optInt("macPort", 6996)
                                macName = jsonRequest.optString("macName", "Mac")
                                isManual = jsonRequest.optBoolean("isManual", false)
                            }

                            val response =
                                """{"status": "success", "message": "Wake-up request received"}"""
                            sendHttpResponse(output, 200, "OK", response)

                            com.sameerasw.airsync.utils.WakeupHandler.processWakeupRequest(
                                this@AirSyncService,
                                macIp,
                                macPort,
                                macName,
                                isManual
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing wake-up request", e)
                            val response = """{"status": "error", "message": "Invalid JSON"}"""
                            sendHttpResponse(output, 400, "Bad Request", response)
                        }
                    } else if (method == "OPTIONS") {
                        sendCorsResponse(output)
                    } else {
                        val response =
                            """{"status": "error", "message": "Method not allowed or path not found"}"""
                        sendHttpResponse(output, 405, "Method Not Allowed", response)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling HTTP request", e)
            }
        }
    }

    private fun sendHttpResponse(
        output: PrintWriter,
        statusCode: Int,
        statusText: String,
        body: String
    ) {
        output.println("HTTP/1.1 $statusCode $statusText")
        output.println("Content-Type: application/json")
        output.println("Access-Control-Allow-Origin: *")
        output.println("Access-Control-Allow-Methods: POST, OPTIONS")
        output.println("Access-Control-Allow-Headers: Content-Type")
        output.println("Content-Length: ${body.length}")
        output.println()
        output.print(body)
        output.flush()
    }

    private fun sendCorsResponse(output: PrintWriter) {
        output.println("HTTP/1.1 200 OK")
        output.println("Access-Control-Allow-Origin: *")
        output.println("Access-Control-Allow-Methods: POST, OPTIONS")
        output.println("Access-Control-Allow-Headers: Content-Type")
        output.println("Content-Length: 0")
        output.println()
        output.flush()
    }

    companion object {
        private const val TAG = "AirSyncService"
        private const val CHANNEL_ID = "airsync_connection_channel"
        private const val NOTIFICATION_ID = 4001
        private const val HTTP_SERVER_PORT = 8888
        private const val WAKEUP_ENDPOINT = "/wakeup"

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
