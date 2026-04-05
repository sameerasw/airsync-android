package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.ble.BleConstants
import com.sameerasw.airsync.data.ble.BleTransportBridge
import com.sameerasw.airsync.domain.model.AudioInfo
import com.sameerasw.airsync.widget.AirSyncWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton utility for managing the WebSocket connection to the AirSync Mac server.
 * Handles connection lifecycle, handshake, auto-reconnection, and message transport.
 */
object WebSocketUtil {
    private const val TAG = "WebSocketUtil"
    private const val HANDSHAKE_TIMEOUT_MS = 10_000L
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    var currentIpAddress: String? = null
    private var currentPort: Int? = null
    private var currentSymmetricKey: javax.crypto.SecretKey? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    private fun updateConnectedStatus(status: Boolean) {
        isConnected.set(status)
        _connectionStateFlow.value = status
    }

    // Transport state: true after OkHttp onOpen, false after closing/failure/disconnect
    private val isSocketOpen = AtomicBoolean(false)
    private val handshakeCompleted = AtomicBoolean(false)
    private val connectionStarted = AtomicBoolean(false)
    private val failedAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    private var handshakeTimeoutJob: Job? = null
    private var connectionAttemptJob: Job? = null

    // Auto-reconnect machinery
    private var autoReconnectJob: Job? = null
    private var autoReconnectActive = AtomicBoolean(false)
    val isManualDisconnectPending = AtomicBoolean(false)
    private var autoReconnectStartTime: Long = 0L
    private var autoReconnectAttempts: Int = 0

    // Callback for connection status changes
    private var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    private var onMessageReceived: ((String) -> Unit)? = null

    // Application context for side-effects (notifications/services) when explicit context isn't provided
    private var appContext: Context? = null

    // Global connection status listeners for UI updates
    private val connectionStatusListeners = mutableSetOf<(Boolean) -> Unit>()

    private val _connectionStateFlow = MutableStateFlow(false)
    val connectionState = _connectionStateFlow.asStateFlow()

    // Watchdog to monitor connection health
    private val lastActivityTime = java.util.concurrent.atomic.AtomicLong(0L)
    private var watchdogJob: Job? = null

    private fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Keep connection alive
            .pingInterval(10, TimeUnit.SECONDS) // Auto ping every 10 seconds to detect dead connection
            .build()
    }

    // Manual connect listeners are invoked when a user-initiated connection starts (not auto reconnect)
    private val manualConnectListeners = mutableSetOf<() -> Unit>()

    fun registerManualConnectListener(listener: () -> Unit) {
        manualConnectListeners.add(listener)
    }

    fun unregisterManualConnectListener(listener: () -> Unit) {
        manualConnectListeners.remove(listener)
    }

    /**
     * Initiates a WebSocket connection to the specified IP and port.
     *
     * @param context Context for widget updates and service management.
     * @param ipAddress Target IP address(es), comma-separated if multiple.
     * @param port Target port.
     * @param symmetricKey Encryption key for secure communication.
     * @param onConnectionStatus Callback for connection success/failure.
     * @param onMessage Callback for received messages.
     * @param manualAttempt True if triggered by user interaction, false if auto-reconnect.
     * @param onHandshakeTimeout Callback invoked if handshake fails (e.g., auth error).
     */
    fun connect(
        context: Context,
        ipAddress: String,
        port: Int,
        symmetricKey: String?,
        onConnectionStatus: ((Boolean) -> Unit)? = null,
        onMessage: ((String) -> Unit)? = null,
        manualAttempt: Boolean = true,
        onHandshakeTimeout: (() -> Unit)? = null
    ) {
        // Cache application context for future cleanup even if callers don't pass context on disconnect
        appContext = context.applicationContext

        if (isConnecting.get()) {
            if (manualAttempt) {
                Log.d(TAG, "Canceling ongoing connection attempt for new manual attempt")
                connectionAttemptJob?.cancel()
                client?.dispatcher?.cancelAll()
                isConnecting.set(false)
            } else {
                Log.d(TAG, "Already connecting")
                return
            }
        }

        if (isConnected.get()) {
            Log.d(TAG, "Already connected")
            return
        }

        // If user initiates a manual attempt, stop any auto-reconnect loop
        if (manualAttempt) {
            cancelAutoReconnect()
        }

        // Validate local network IP
        CoroutineScope(Dispatchers.IO).launch {
            // Handle multiple IPs if provided (comma-separated)
            val ipList =
                ipAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

            if (ipList.isEmpty()) {
                Log.e(TAG, "No valid IP addresses provided")
                onConnectionStatus?.invoke(false)
                return@launch
            }

            // Validate at least one local network IP
            val anyLocal = ipList.any { isLocalNetwork(context, it) }
            if (!anyLocal) {
                Log.e(TAG, "None of the provided IP addresses are in the local network: $ipAddress")
                onConnectionStatus?.invoke(false)
                return@launch
            }

            isConnecting.set(true)
            handshakeCompleted.set(false)

            // Reset manual disconnect flag on manual attempt
            isManualDisconnectPending.set(false)
            if (manualAttempt) {
                try {
                    val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
                    ds.setUserManuallyDisconnected(false)
                } catch (_: Exception) {
                }
            }

            // Update widgets to show "Connecting…" immediately
            try {
                AirSyncWidgetProvider.updateAllWidgets(context)
            } catch (_: Exception) {
            }

            // Notify listeners that a manual connection attempt has begun
            if (manualAttempt) {
                manualConnectListeners.forEach { listener ->
                    try {
                        listener()
                    } catch (e: Exception) {
                        Log.w(TAG, "ManualConnectListener error: ${e.message}")
                    }
                }
            }
            currentIpAddress = ipAddress
            currentPort = port
            currentSymmetricKey = symmetricKey?.let { CryptoUtil.decodeKey(it) }
            onConnectionStatusChanged = onConnectionStatus
            onMessageReceived = onMessage

            try {
                if (client == null) {
                    client = createClient()
                }

                connectionAttemptJob?.cancel()
                connectionStarted.set(false)
                failedAttempts.set(0)

                // Overall timeout for all parallel connection attempts
                connectionAttemptJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(15000) // 15 seconds global timeout
                    if (isConnecting.get() && !isSocketOpen.get()) {
                        Log.w(TAG, "All connection attempts timed out")
                        isConnecting.set(false)
                        onConnectionStatusChanged?.invoke(false)
                        notifyConnectionStatusListeners(false)
                        tryStartAutoReconnect(context)
                        try {
                            AirSyncWidgetProvider.updateAllWidgets(context)
                        } catch (_: Exception) {
                        }
                    }
                }

                // Try each IP in parallel
                ipList.forEach { ip ->
                    val url = "ws://$ip:$port/socket"
                    Log.d(TAG, "Attempting connection to $url")

                    CoroutineScope(Dispatchers.IO).launch {
                        if (isConnected.get() || connectionStarted.get()) return@launch

                        val request = Request.Builder()
                            .url(url)
                            .build()

                        val perAttemptClient = client!!.newBuilder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(
                                0,
                                TimeUnit.MILLISECONDS
                            ) // websockets should have no read timeout
                            .build()

                        val listener = object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                if (isConnected.get() || !connectionStarted.compareAndSet(
                                        false,
                                        true
                                    )
                                ) {
                                    webSocket.close(1000, "Already connected elsewhere")
                                    return
                                }
                                Log.d(TAG, "WebSocket connected to $url")

                                connectionAttemptJob?.cancel()
                                WebSocketUtil.webSocket = webSocket
                                currentIpAddress = ip // Store the successful IP
                                isSocketOpen.set(true)
                                updateConnectedStatus(false)
                                isConnecting.set(true)

                                lastActivityTime.set(System.currentTimeMillis())
                                startWatchdog(context)

                                try {
                                    SyncManager.performInitialSync(context)
                                } catch (_: Exception) {
                                }

                                handshakeTimeoutJob?.cancel()
                                handshakeTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        delay(HANDSHAKE_TIMEOUT_MS)
                                        if (!handshakeCompleted.get()) {
                                            Log.w(TAG, "Handshake timed out")
                                            updateConnectedStatus(false)
                                            isConnecting.set(false)
                                            try {
                                                webSocket.close(4001, "Handshake timeout")
                                            } catch (_: Exception) {
                                            }
                                            if (manualAttempt) {
                                                try {
                                                    val ds =
                                                        com.sameerasw.airsync.data.local.DataStoreManager(
                                                            context
                                                        )
                                                    ds.setUserManuallyDisconnected(true)
                                                } catch (_: Exception) {
                                                }
                                            }
                                            onConnectionStatusChanged?.invoke(false)
                                            notifyConnectionStatusListeners(false)
                                            onHandshakeTimeout?.invoke()
                                            try {
                                                AirSyncWidgetProvider.updateAllWidgets(context)
                                            } catch (_: Exception) {
                                            }
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                lastActivityTime.set(System.currentTimeMillis())
                                Log.d(TAG, "RAW WebSocket message received: ${text}...")
                                val decryptedMessage = currentSymmetricKey?.let { key ->
                                    val decrypted = CryptoUtil.decryptMessage(text, key)
                                    if (decrypted == null) Log.e(TAG, "FAILED TO DECRYPT WebSocket message!")
                                    decrypted
                                } ?: text

                                if (!handshakeCompleted.get()) {
                                    val handshakeOk = try {
                                        val json = org.json.JSONObject(decryptedMessage)
                                        json.optString("type") == "macInfo"
                                    } catch (_: Exception) {
                                        false
                                    }
                                    if (handshakeOk) {
                                        handshakeCompleted.set(true)
                                        try {
                                            AirSyncWidgetProvider.updateAllWidgets(context)
                                        } catch (_: Exception) {
                                        }
                                        updateConnectedStatus(true)
                                        isConnecting.set(false)
                                        handshakeTimeoutJob?.cancel()
                                        try {
                                            val ds =
                                                com.sameerasw.airsync.data.local.DataStoreManager(
                                                    context
                                                )
                                            kotlinx.coroutines.runBlocking {
                                                ds.setUserManuallyDisconnected(
                                                    false
                                                )
                                            }
                                        } catch (_: Exception) {
                                        }
                                        try {
                                            SyncManager.startPeriodicSync(context)
                                        } catch (_: Exception) {
                                        }

                                        try {
                                            val ds =
                                                com.sameerasw.airsync.data.local.DataStoreManager(
                                                    context
                                                )
                                            val lastDevice = kotlinx.coroutines.runBlocking {
                                                ds.getLastConnectedDevice().first()
                                            }
                                            com.sameerasw.airsync.service.AirSyncService.start(
                                                context,
                                                lastDevice?.name
                                            )
                                        } catch (e: Exception) {
                                            Log.e(
                                                TAG,
                                                "Error starting AirSyncService: ${e.message}"
                                            )
                                        }

                                        onConnectionStatusChanged?.invoke(true)
                                        notifyConnectionStatusListeners(true)
                                        cancelAutoReconnect()
                                        try {
                                            AirSyncWidgetProvider.updateAllWidgets(context)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }

                                WebSocketMessageHandler.handleIncomingMessage(
                                    context,
                                    decryptedMessage
                                )
                                updateLastSyncTime(context)
                                onMessageReceived?.invoke(decryptedMessage)
                            }

                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String
                            ) {
                                if (webSocket == WebSocketUtil.webSocket) {
                                    stopWatchdog()
                                    if (code != 1000 && !isManualDisconnectPending.get()) {
                                        if (com.sameerasw.airsync.AirSyncApp.isAppForeground()) {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                val msg = reason.ifEmpty { "Unknown Server Disconnect" }
                                                android.widget.Toast.makeText(context.applicationContext, "Disconnected: $msg", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    updateConnectedStatus(false)
                                    isSocketOpen.set(false)
                                    isConnecting.set(false)
                                    handshakeCompleted.set(false)
                                    handshakeTimeoutJob?.cancel()
                                    currentIpAddress = null
                                    try {
                                        ServiceManager.updateServiceState(context)
                                    } catch (_: Exception) {
                                    }
                                    try {
                                        com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(context)
                                        com.sameerasw.airsync.utils.MacDeviceStatusManager.cleanup(context)
                                    } catch (_: Exception) {
                                    }
                                    onConnectionStatusChanged?.invoke(false)
                                    notifyConnectionStatusListeners(false)
                                    
                                    // Only auto-reconnect if it wasn't a manual close (1000)
                                    if (code != 1000) {
                                        tryStartAutoReconnect(context)
                                    }
                                    
                                    try {
                                        AirSyncWidgetProvider.updateAllWidgets(context)
                                    } catch (_: Exception) {
                                    }
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?
                            ) {
                                val totalToTry = ipList.size
                                val failedCount = failedAttempts.incrementAndGet()
                                val wasActive = webSocket == WebSocketUtil.webSocket
                                val isFinalAttempt = !connectionStarted.get() && failedCount >= totalToTry

                                if (wasActive || isFinalAttempt) {
                                    stopWatchdog()
                                    if (manualAttempt && (!isSocketOpen.get() || wasActive) && !isManualDisconnectPending.get()) {
                                        if (com.sameerasw.airsync.AirSyncApp.isAppForeground()) {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                val msg = when (t) {
                                                    is java.net.ConnectException -> "Connection Refused (Is AirSync Mac running?)"
                                                    is java.net.SocketTimeoutException -> "Could not discover your mac"
                                                    is java.net.UnknownHostException -> "Could not reach your mac"
                                                    is java.io.EOFException, is java.net.SocketException -> "Lost connection to your mac"
                                                    else -> t.message ?: "Unknown connection error"
                                                }
                                                android.widget.Toast.makeText(context.applicationContext, "AirSync: $msg", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    updateConnectedStatus(false)
                                    isConnecting.set(false)
                                    isSocketOpen.set(false)
                                    handshakeCompleted.set(false)
                                    handshakeTimeoutJob?.cancel()
                                    connectionAttemptJob?.cancel()
                                    currentIpAddress = null
                                    try {
                                        ServiceManager.updateServiceState(context)
                                    } catch (_: Exception) {
                                    }
                                    try {
                                        com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(context)
                                        com.sameerasw.airsync.utils.MacDeviceStatusManager.cleanup(context)
                                    } catch (_: Exception) {
                                    }
                                    onConnectionStatusChanged?.invoke(false)
                                    notifyConnectionStatusListeners(false)
                                    // Check manual disconnect flag before auto-reconnecting on failure
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
                                            val manual = ds.getUserManuallyDisconnected().first()
                                            if (!manual) {
                                                tryStartAutoReconnect(context)
                                            }
                                        } catch (_: Exception) {
                                            tryStartAutoReconnect(context)
                                        }
                                    }

                                    try {
                                        AirSyncWidgetProvider.updateAllWidgets(context)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        perAttemptClient.newWebSocket(request, listener)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create WebSocket: ${e.message}")
                isConnecting.set(false)
                handshakeCompleted.set(false)
                onConnectionStatusChanged?.invoke(false)
            }
        }
    }

    private suspend fun isLocalNetwork(context: Context, ipAddress: String): Boolean {
        // Check if expand networking is enabled - if so, allow all IPs
        val ds = com.sameerasw.airsync.data.local.DataStoreManager(context)
        val expandNetworkingEnabled = ds.getExpandNetworkingEnabled().first()
        if (expandNetworkingEnabled) {
            return true
        }

        // Check standard private IP ranges (RFC 1918)
        if (ipAddress.startsWith("192.168.") || ipAddress.startsWith("10.")) {
            return true
        }
        
        // Carrier-Grade NAT (Tailscale/VPNs) - only allowed if expand networking is enabled
        if (ipAddress.startsWith("100.") && expandNetworkingEnabled) {
            return true
        }
        // Check 172.16.0.0 to 172.31.255.255 range
        if (ipAddress.startsWith("172.")) {
            val parts = ipAddress.split(".")
            if (parts.size >= 2) {
                val secondOctet = parts[1].toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return true
                }
            }
        }
        // Check localhost
        if (ipAddress == "127.0.0.1" || ipAddress == "localhost") {
            return true
        }
        return false
    }

    /**
     * Sends a text message over the WebSocket connection.
     * Encrypts the message if a symmetric key is active.
     *
     * @param message The raw JSON message string to send.
     * @return True if the message was enqueued, false if not connected.
     */
    fun sendMessage(message: String): Boolean {
        // Allow sending as soon as the socket is open (even before handshake completes)
        if (isSocketOpen.get() && webSocket != null) {
            Log.d(TAG, "Sending message via WebSocket: $message")
            val messageToSend = currentSymmetricKey?.let { key ->
                CryptoUtil.encryptMessage(message, key)
            } ?: message

            return webSocket!!.send(messageToSend)
        } else {
            // Fallback to BLE if authenticated
            val ble = com.sameerasw.airsync.AirSyncApp.getBleConnectionManager()
            if (ble != null && ble.isAuthenticated) {
                Log.d(TAG, "WebSocket not connected, falling back to BLE: $message")
                return sendOverBLE(message)
            }
            
            Log.w(TAG, "Neither WebSocket nor BLE connected, cannot send message")
            return false
        }
    }

    private fun sendOverBLE(message: String): Boolean {
        val ble = com.sameerasw.airsync.AirSyncApp.getBleConnectionManager() ?: return false
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val data = json.optJSONObject("data") ?: JSONObject()

            when (type) {
                "notificationAction" -> {
                    val pkg = data.optString("package")
                    val actionId = data.optString("actionId")
                    val payload = "$pkg|$actionId"
                    ble.sendChunkedNotification(BleConstants.CHAR_NOTIFICATION_ACTION, payload)
                    return true
                }
                "mediaControl" -> {
                    val action = data.optString("action")
                    // Protocol: type|action
                    val payload = "media|$action"
                    ble.sendChunkedNotification(BleConstants.CHAR_MAC_CONTROL, payload)
                    return true
                }
                "volumeControl" -> {
                    val action = data.optString("action")
                    // Protocol: type|action
                    val payload = "volume|$action"
                    ble.sendChunkedNotification(BleConstants.CHAR_MAC_CONTROL, payload)
                    return true
                }
                "clipboard", "clipboardUpdate" -> {
                    val content = data.optString("text", data.optString("content"))
                    ble.sendChunkedNotification(BleConstants.CHAR_CLIPBOARD_DATA_NOTIFY, content)
                    return true
                }
                "dismissNotification" -> {
                    val id = data.optString("id")
                    ble.sendChunkedNotification(BleConstants.CHAR_NOTIFICATION_DISMISS_NOTIFY, id)
                    return true
                }
                "remoteControl" -> {
                    val action = data.optString("action")
                    // Filter out high-frequency cursor controls over BLE
                    if (action == "mouse_move" || action == "mouse_click" || action == "mouse_scroll") {
                        return false
                    }
                    // Include value if present (e.g. vol_set needs level)
                    val value = if (data.has("value")) data.opt("value")?.toString() else null
                    val payload = if (value != null) "remote|$action|$value" else "remote|$action"
                    ble.sendChunkedNotification(BleConstants.CHAR_MAC_CONTROL, payload)
                    return true
                }
                "notification" -> {
                    val pkg = data.optString("package")
                    val appName = data.optString("app")
                    val title = data.optString("title")
                    val body = data.optString("body")
                    BleTransportBridge.sendNotification(pkg, appName, title, body)
                    return true
                }
                "status" -> {
                    val battery = data.optJSONObject("battery")
                    if (battery != null) {
                        val level = battery.optInt("level")
                        ble.sendNotification(BleConstants.CHAR_BATTERY_LEVEL, byteArrayOf(level.toByte()))
                    }
                    val music = data.optJSONObject("music")
                    if (music != null) {
                        val audio = AudioInfo(
                            isPlaying = music.optBoolean("isPlaying"),
                            title = music.optString("title"),
                            artist = music.optString("artist"),
                            volume = music.optInt("volume"),
                            isMuted = music.optBoolean("isMuted"),
                            likeStatus = music.optString("likeStatus"),
                            albumArtLite = music.optString("albumArtLite")
                        )
                        BleTransportBridge.sendMediaState(audio)
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending over BLE fallback: ${e.message}")
        }
        return false
    }

    /**
     * Disconnects the WebSocket and cleans up resources.
     * Stops related services (AirSyncService, periodic sync) and updates UI state.
     */
    fun disconnect(context: Context? = null, isManual: Boolean = true) {
        Log.d(TAG, "Disconnecting WebSocket (isManual=$isManual)")
        updateConnectedStatus(false)
        isConnecting.set(false)
        isSocketOpen.set(false)
        handshakeCompleted.set(false)
        handshakeTimeoutJob?.cancel()
        stopWatchdog()
        currentIpAddress = null

        val ctx = context ?: appContext

        // Set manual disconnect flag if applicable
        if (isManual) {
            isManualDisconnectPending.set(true)
            ctx?.let { c ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(c)
                        ds.setUserManuallyDisconnected(true)
                    } catch (_: Exception) {
                    }
                }
            }
            
            // Send manual disconnect signal over BLE before disconnecting BLE client
            try {
                val ble = com.sameerasw.airsync.AirSyncApp.getBleConnectionManager()
                if (ble != null && ble.isAuthenticated) {
                    Log.d(TAG, "Sending manual disconnect signal over BLE before disconnecting")
                    ble.sendChunkedNotification(BleConstants.CHAR_MAC_CONTROL, "remote|manual_disconnect")
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(300)
                        ble.disconnectAllConnectedDevices()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending manual disconnect signal over BLE: ${e.message}")
            }
        }

        webSocket?.close(1000, "Manual disconnection")
        webSocket = null

        // Transition back to scanning on disconnect
        ctx?.let { c ->
            try {
                ServiceManager.updateServiceState(c)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating service state on disconnect: ${e.message}")
            }
        }

        onConnectionStatusChanged?.invoke(false)

        // Resolve a context for side-effects (try provided one, fall back to appContext)
        // Clear continue browsing notifications if possible
        ctx?.let { c ->
            try {
                NotificationUtil.clearContinueBrowsingNotifications(c)
            } catch (_: Exception) {
            }
        }

        // Notify listeners about the disconnection
        notifyConnectionStatusListeners(false)
        // Stop any auto-reconnect in progress
        cancelAutoReconnect()
        // Stop media player if running
        ctx?.let { c ->
            try {
                com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(c)
            } catch (_: Exception) {
            }
        }

        // Update widgets to reflect new state
        ctx?.let { c ->
            try {
                AirSyncWidgetProvider.updateAllWidgets(c)
            } catch (_: Exception) {
            }
        }
    }

    fun cleanup() {
        disconnect()

        // Reset sync manager state
        SyncManager.reset()

        client?.dispatcher?.executorService?.shutdown()
        client = null
        currentIpAddress = null
        currentPort = null
        currentSymmetricKey = null
        onConnectionStatusChanged = null
        onMessageReceived = null
        handshakeCompleted.set(false)
        handshakeTimeoutJob?.cancel()
        appContext = null
    }

    fun isConnected(): Boolean {
        return isConnected.get() || com.sameerasw.airsync.data.ble.BleGattServer.isAnyAuthenticated()
    }

    fun isConnecting(): Boolean {
        return isConnecting.get()
    }

    private val lastSyncTimeCache = java.util.concurrent.atomic.AtomicLong(0L)

    private fun updateLastSyncTime(context: Context) {
        val now = System.currentTimeMillis()
        // Only update if at least 60 seconds have passed since last write
        if (now - lastSyncTimeCache.get() < 60_000L) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                lastSyncTimeCache.set(now)
                val dataStoreManager = com.sameerasw.airsync.data.local.DataStoreManager(context)
                dataStoreManager.updateLastSyncTime(now)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating last sync time: ${e.message}")
            }
        }
    }

    // Register a global connection status listener
    fun registerConnectionStatusListener(listener: (Boolean) -> Unit) {
        connectionStatusListeners.add(listener)
    }

    // Unregister a global connection status listener
    fun unregisterConnectionStatusListener(listener: (Boolean) -> Unit) {
        connectionStatusListeners.remove(listener)
    }

    // Notify listeners about the connection status
    private fun notifyConnectionStatusListeners(isConnected: Boolean) {
        connectionStatusListeners.forEach { listener ->
            listener(isConnected)
        }
    }

    private fun startWatchdog(context: Context) {
        watchdogJob?.cancel()
        lastActivityTime.set(System.currentTimeMillis())
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected.get() || isSocketOpen.get()) {
                delay(10000) // check every 10 seconds
                val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime.get()
                if (timeSinceLastActivity > 35000L) { // 35 seconds (heartbeat is 12.5s)
                    Log.w(TAG, "No activity from Mac for ${timeSinceLastActivity / 1000}s. Connection is stale. Disconnecting.")
                    CoroutineScope(Dispatchers.Main).launch {
                        disconnect(context, isManual = false)
                    }
                    break
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // Public API to cancel auto reconnect (from Stop action)
    fun cancelAutoReconnect() {
        autoReconnectActive.set(false)
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        autoReconnectAttempts = 0
        autoReconnectStartTime = 0L
    }

    fun isAutoReconnecting(): Boolean = autoReconnectActive.get()

    fun stopAutoReconnect(context: Context) {
        cancelAutoReconnect()
    }

    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireWifiLock(context: Context) {
        try {
            if (wifiLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirSync:ReconnectLock")
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.d(TAG, "WifiLock acquired for reconnection")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "WifiLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WifiLock: ${e.message}")
        }
    }

    /**
     * Internal logic to attempt auto-reconnection to the last known device.
     * Uses a dual strategy: proactive exponential backoff AND discovery-triggered.
     */
    private fun tryStartAutoReconnect(context: Context) {
        if (autoReconnectActive.get()) return // already running

        // Check manual disconnect / auto-enabled status asynchronously before starting log and job
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
                val manual = ds.getUserManuallyDisconnected().first()
                val autoEnabled = ds.getAutoReconnectEnabled().first()

                if (manual || !autoEnabled) {
                    // Suppress starting auto-reconnect if manually disconnected or disabled
                    return@launch
                }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (autoReconnectActive.get()) return@withContext
                    autoReconnectActive.set(true)
                    autoReconnectStartTime = System.currentTimeMillis()
                    Log.d(TAG, "Starting Smart Auto-Reconnect strategy")

                    autoReconnectJob?.cancel()
                    autoReconnectJob = CoroutineScope(Dispatchers.IO).launch {
                        try {
                            acquireWifiLock(context)

                            // 1. Retry Loop (Try last known IPs with exponential backoff)
                            launch {
                                var backoffMs = 2000L
                                while (autoReconnectActive.get() && !isConnected.get()) {
                                    val currentManual = ds.getUserManuallyDisconnected().first()
                                    val currentAutoEnabled = ds.getAutoReconnectEnabled().first()

                                    if (currentManual || !currentAutoEnabled) {
                                        Log.d(TAG, "Auto-reconnect cancelled: manual=$currentManual, enabled=$currentAutoEnabled")
                                        cancelAutoReconnect()
                                        break
                                    }

                                    if (!isConnecting.get()) {
                                        val last = ds.getLastConnectedDevice().first()
                                        if (last != null) {
                                            val ourIp = DeviceInfoUtil.getWifiIpAddress(context) ?: "Unknown"
                                            val all = ds.getAllNetworkDeviceConnections().first()
                                            val targetConnection = all.firstOrNull { it.deviceName == last.name }

                                            val targetIps = if (targetConnection != null && ourIp != "Unknown") {
                                                targetConnection.getClientIpForNetwork(ourIp) ?: last.ipAddress
                                            } else {
                                                targetConnection?.networkConnections?.values?.joinToString(",") ?: last.ipAddress
                                            }

                                            if (targetIps.isNotEmpty()) {
                                                Log.d(TAG, "Proactive retry to $targetIps (backoff: ${backoffMs}ms)")
                                                connect(
                                                    context = context,
                                                    ipAddress = targetIps,
                                                    port = targetConnection?.port?.toIntOrNull() ?: last.port.toIntOrNull() ?: 6996,
                                                    symmetricKey = targetConnection?.symmetricKey ?: last.symmetricKey,
                                                    manualAttempt = false,
                                                    onConnectionStatus = { connected ->
                                                        if (connected) {
                                                            CoroutineScope(Dispatchers.IO).launch {
                                                                try {
                                                                    if (targetConnection != null) {
                                                                        ds.updateNetworkDeviceLastConnected(
                                                                            targetConnection.deviceName,
                                                                            System.currentTimeMillis()
                                                                        )
                                                                    }
                                                                } catch (_: Exception) {}
                                                                releaseWifiLock()
                                                                cancelAutoReconnect()
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    delay(backoffMs)
                                    // Exponential backoff capped at 1 minute
                                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                                }
                            }

                            // 2. Discovery Listener (Listen for presence packets in case IP changed)
                            suspend fun tryConnectIfAvailable(discoveredList: List<DiscoveredDevice>) {
                                if (!autoReconnectActive.get() || isConnected.get() || isConnecting.get()) return

                                val currentManual = ds.getUserManuallyDisconnected().first()
                                val currentAutoEnabled = ds.getAutoReconnectEnabled().first()
                                if (currentManual || !currentAutoEnabled) {
                                    cancelAutoReconnect()
                                    return
                                }

                                val last = ds.getLastConnectedDevice().first() ?: return

                                // Check if we have ANY network available (WiFi, VPN, or Cellular)
                                val networkStatus = DeviceInfoUtil.getNetworkStatus(context)
                                val hasNetwork = networkStatus.isConnected

                                if (!hasNetwork) {
                                    Log.d(TAG, "No network available, skipping auto-reconnect")
                                    return
                                }

                                // Try discovered devices first
                                val discoveryMatch = discoveredList.find { it.name == last.name }
                                if (discoveryMatch != null) {
                                    val all = ds.getAllNetworkDeviceConnections().first()
                                    val targetConnection = all.firstOrNull { it.deviceName == last.name }

                                    if (targetConnection != null) {
                                        val ips = discoveryMatch.ips.joinToString(",")
                                        val port = targetConnection.port.toIntOrNull() ?: 6996

                                        Log.d(TAG, "Discovery-triggered reconnect for: ${discoveryMatch.name} to $ips:$port")
                                        connect(
                                            context = context,
                                            ipAddress = ips,
                                            port = port,
                                            symmetricKey = targetConnection.symmetricKey,
                                            manualAttempt = false,
                                            onConnectionStatus = { connected ->
                                                if (connected) {
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        try {
                                                            ds.updateNetworkDeviceLastConnected(
                                                                targetConnection.deviceName,
                                                                System.currentTimeMillis()
                                                            )
                                                        } catch (_: Exception) {}
                                                        releaseWifiLock()
                                                        cancelAutoReconnect()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            tryConnectIfAvailable(UDPDiscoveryManager.discoveredDevices.value)
                            UDPDiscoveryManager.discoveredDevices.collect { discoveredList ->
                                if (!autoReconnectActive.get()) return@collect
                                tryConnectIfAvailable(discoveredList)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Coroutine was cancelled - this is normal, not an error
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in smart auto-reconnect: ${e.message}")
                            releaseWifiLock()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed pre-checking auto-reconnect: ${e.message}")
            }
        }
    }

    fun requestAutoReconnect(context: Context) {
        if (isConnected.get() || isConnecting.get()) return
        tryStartAutoReconnect(context)
    }
}
