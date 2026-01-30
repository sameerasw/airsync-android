package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.widget.AirSyncWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.*
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
    private var autoReconnectStartTime: Long = 0L
    private var autoReconnectAttempts: Int = 0

    // Callback for connection status changes
    private var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    private var onMessageReceived: ((String) -> Unit)? = null
    // Application context for side-effects (notifications/services) when explicit context isn't provided
    private var appContext: Context? = null

    // Global connection status listeners for UI updates
    private val connectionStatusListeners = mutableSetOf<(Boolean) -> Unit>()

    private fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Keep connection alive
            .pingInterval(5, TimeUnit.SECONDS) // Send ping every 5 seconds for fast disconnect detection
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

        if (isConnecting.get() || isConnected.get()) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        // If user initiates a manual attempt, stop any auto-reconnect loop
        if (manualAttempt) {
            cancelAutoReconnect()
        }

        // Validate local network IP
        CoroutineScope(Dispatchers.IO).launch {
            // Handle multiple IPs if provided (comma-separated)
            val ipList = ipAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            
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
            // Update widgets to show "Connecting…" immediately
            try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}

            // Notify listeners that a manual connection attempt has begun
            if (manualAttempt) {
                manualConnectListeners.forEach { listener ->
                    try { listener() } catch (e: Exception) { Log.w(TAG, "ManualConnectListener error: ${e.message}") }
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
                        try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
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
                            .readTimeout(0, TimeUnit.MILLISECONDS) // websockets should have no read timeout
                            .build()

                        val listener = object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                if (isConnected.get() || !connectionStarted.compareAndSet(false, true)) {
                                    webSocket.close(1000, "Already connected elsewhere")
                                    return
                                }
                                Log.d(TAG, "WebSocket connected to $url")

                                connectionAttemptJob?.cancel()
                                WebSocketUtil.webSocket = webSocket
                                WebSocketUtil.currentIpAddress = ip // Store the successful IP
                                isSocketOpen.set(true)
                                isConnected.set(false)
                                isConnecting.set(true)

                                try { SyncManager.performInitialSync(context) } catch (_: Exception) {}

                                handshakeTimeoutJob?.cancel()
                                handshakeTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        delay(HANDSHAKE_TIMEOUT_MS)
                                        if (!handshakeCompleted.get()) {
                                            Log.w(TAG, "Handshake timed out")
                                            isConnected.set(false)
                                            isConnecting.set(false)
                                            try { webSocket.close(4001, "Handshake timeout") } catch (_: Exception) {}
                                            if (manualAttempt) {
                                                try {
                                                    val ds = com.sameerasw.airsync.data.local.DataStoreManager(context)
                                                    ds.setUserManuallyDisconnected(true)
                                                } catch (_: Exception) {}
                                            }
                                            onConnectionStatusChanged?.invoke(false)
                                            notifyConnectionStatusListeners(false)
                                            onHandshakeTimeout?.invoke()
                                            try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
                                        }
                                    } catch (_: Exception) {}
                                }
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val decryptedMessage = currentSymmetricKey?.let { key ->
                                    CryptoUtil.decryptMessage(text, key)
                                } ?: text

                                if (!handshakeCompleted.get()) {
                                    val handshakeOk = try {
                                        val json = org.json.JSONObject(decryptedMessage)
                                        json.optString("type") == "macInfo"
                                    } catch (_: Exception) { false }
                                    if (handshakeOk) {
                                        handshakeCompleted.set(true)
                                        try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
                                        isConnected.set(true)
                                        isConnecting.set(false)
                                        handshakeTimeoutJob?.cancel()
                                        try {
                                            val ds = com.sameerasw.airsync.data.local.DataStoreManager(context)
                                            kotlinx.coroutines.runBlocking { ds.setUserManuallyDisconnected(false) }
                                        } catch (_: Exception) { }
                                        try { SyncManager.startPeriodicSync(context) } catch (_: Exception) {}

                                        try {
                                            val ds = com.sameerasw.airsync.data.local.DataStoreManager(context)
                                            val lastDevice = kotlinx.coroutines.runBlocking { ds.getLastConnectedDevice().first() }
                                            com.sameerasw.airsync.service.AirSyncService.start(context, lastDevice?.name)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error starting AirSyncService: ${e.message}")
                                        }

                                        onConnectionStatusChanged?.invoke(true)
                                        notifyConnectionStatusListeners(true)
                                        try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
                                    }
                                }

                                WebSocketMessageHandler.handleIncomingMessage(context, decryptedMessage)
                                updateLastSyncTime(context)
                                onMessageReceived?.invoke(decryptedMessage)
                            }

                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                if (webSocket == WebSocketUtil.webSocket) {
                                    isConnected.set(false)
                                    isSocketOpen.set(false)
                                    isConnecting.set(false)
                                    handshakeCompleted.set(false)
                                    handshakeTimeoutJob?.cancel()
                                    currentIpAddress = null
                                    try { com.sameerasw.airsync.service.AirSyncService.startScanning(context) } catch (_: Exception) {}
                                    onConnectionStatusChanged?.invoke(false)
                                    notifyConnectionStatusListeners(false)
                                    tryStartAutoReconnect(context)
                                    try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
                                }
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                val totalToTry = ipList.size
                                val failedCount = failedAttempts.incrementAndGet()

                                if (webSocket == WebSocketUtil.webSocket || (!connectionStarted.get() && failedCount >= totalToTry)) {
                                    isConnected.set(false)
                                    isConnecting.set(false)
                                    isSocketOpen.set(false)
                                    handshakeCompleted.set(false)
                                    handshakeTimeoutJob?.cancel()
                                    connectionAttemptJob?.cancel()
                                    currentIpAddress = null
                                    try { com.sameerasw.airsync.service.AirSyncService.startScanning(context) } catch (_: Exception) {}
                                    onConnectionStatusChanged?.invoke(false)
                                    notifyConnectionStatusListeners(false)
                                    tryStartAutoReconnect(context)
                                    try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
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

        // Check standard private IP ranges (RFC 1918) and Carrier-Grade NAT (Tailscale/VPNs)
        if (ipAddress.startsWith("192.168.") || ipAddress.startsWith("10.") || ipAddress.startsWith("100.")) {
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
        return if (isSocketOpen.get() && webSocket != null) {
            Log.d(TAG, "Sending message: $message")
            val messageToSend = currentSymmetricKey?.let { key ->
                CryptoUtil.encryptMessage(message, key)
            } ?: message

            webSocket!!.send(messageToSend)
        } else {
            Log.w(TAG, "WebSocket not connected, cannot send message")
            false
        }
    }

    /**
     * Disconnects the WebSocket and cleans up resources.
     * Stops related services (AirSyncService, periodic sync) and updates UI state.
     */
    fun disconnect(context: Context? = null) {
        Log.d(TAG, "Disconnecting WebSocket")
        isConnected.set(false)
        isConnecting.set(false)
        isSocketOpen.set(false)
        handshakeCompleted.set(false)
        handshakeTimeoutJob?.cancel()
        currentIpAddress = null

        // Stop periodic sync when disconnecting
        SyncManager.stopPeriodicSync()

        webSocket?.close(1000, "Manual disconnection")
        webSocket = null

        // Transition back to scanning on disconnect
        val ctx = context ?: appContext
        ctx?.let { c ->
            try { com.sameerasw.airsync.service.AirSyncService.startScanning(c) } catch (e: Exception) {
                Log.e(TAG, "Error starting scanning on disconnect: ${e.message}")
            }
        }

        onConnectionStatusChanged?.invoke(false)

        // Resolve a context for side-effects (try provided one, fall back to appContext)
        // Clear continue browsing notifications if possible
        ctx?.let { c ->
            try { NotificationUtil.clearContinueBrowsingNotifications(c) } catch (_: Exception) {}
        }

        // Notify listeners about the disconnection
        notifyConnectionStatusListeners(false)
        // Stop any auto-reconnect in progress
        cancelAutoReconnect()
        // Stop media player if running
        ctx?.let { c ->
            try { com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(c) } catch (_: Exception) {}
        }

        // Update widgets to reflect new state
        ctx?.let { c ->
            try { AirSyncWidgetProvider.updateAllWidgets(c) } catch (_: Exception) {}
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
        return isConnected.get()
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

    /**
     * Internal logic to attempt auto-reconnection to the last known device.
     * Uses discovery-triggered strategy.
     */
    private fun tryStartAutoReconnect(context: Context) {
        if (autoReconnectActive.get()) return // already running
        autoReconnectActive.set(true)
        autoReconnectStartTime = System.currentTimeMillis()

        autoReconnectJob?.cancel()
        autoReconnectJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
                
                // Monitor discovered devices
                UDPDiscoveryManager.discoveredDevices.collect { discoveredList ->
                    if (!autoReconnectActive.get() || isConnected.get() || isConnecting.get()) return@collect

                    val manual = ds.getUserManuallyDisconnected().first()
                    val autoEnabled = ds.getAutoReconnectEnabled().first()
                    if (manual || !autoEnabled) {
                        cancelAutoReconnect()
                        return@collect
                    }

                    val last = ds.getLastConnectedDevice().first() ?: return@collect
                    val ourIp = com.sameerasw.airsync.utils.DeviceInfoUtil.getWifiIpAddress(context) ?: return@collect
                    
                    // Match by name within the discovery list
                    val discoveryMatch = discoveredList.find { it.name == last.name }
                    if (discoveryMatch != null) {
                        Log.d(TAG, "Discovery found target device: ${discoveryMatch.name} at ${discoveryMatch.getBestIp()}")
                        
                        val all = ds.getAllNetworkDeviceConnections().first()
                        val targetConnection = all.firstOrNull { it.deviceName == last.name && it.getClientIpForNetwork(ourIp) != null }
                        
                        if (targetConnection != null) {
                            val ip = discoveryMatch.getBestIp()
                            val port = targetConnection.port.toIntOrNull() ?: 6996
                            
                            Log.d(TAG, "Smart Auto-reconnect attempting connection to $ip:$port")
                            connect(
                                context = context,
                                ipAddress = ip,
                                port = port,
                                symmetricKey = targetConnection.symmetricKey,
                                manualAttempt = false,
                                onConnectionStatus = { connected ->
                                    if (connected) {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            try { ds.updateNetworkDeviceLastConnected(targetConnection.deviceName, System.currentTimeMillis()) } catch (_: Exception) {}
                                            cancelAutoReconnect()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in discovery auto-reconnect: ${e.message}")
            }
        }
    }

    // Public wrapper to request auto-reconnect from app logic (e.g., network changes)
    fun requestAutoReconnect(context: Context) {
        // Only if not already connected or connecting
        if (isConnected.get() || isConnecting.get()) return
        tryStartAutoReconnect(context)
    }
}
