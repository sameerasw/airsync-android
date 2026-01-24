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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.selects.select

object WebSocketUtil {
    private const val TAG = "WebSocketUtil"
    private const val HANDSHAKE_TIMEOUT_MS = 7_000L
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var currentIpAddress: String? = null
    private var currentPort: Int? = null
    private var currentSymmetricKey: javax.crypto.SecretKey? = null
    private var isConnected = AtomicBoolean(false)
    private var isConnecting = AtomicBoolean(false)
    // Transport state: true after OkHttp onOpen, false after closing/failure/disconnect
    private var isSocketOpen = AtomicBoolean(false)
    private var handshakeCompleted = AtomicBoolean(false)
    private var handshakeTimeoutJob: Job? = null
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

    // Message Queues
    private val highPriorityQueue = Channel<String>(Channel.UNLIMITED)
    private val lowPriorityQueue = Channel<String>(10, BufferOverflow.DROP_OLDEST)
    private var messageProcessorJob: Job? = null

    private fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Keep connection alive
            .pingInterval(30, TimeUnit.SECONDS) // Send ping every 30 seconds
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

    fun connect(
        context: Context,
        ipAddress: String,
        port: Int,
        symmetricKey: String?,
        onConnectionStatus: ((Boolean) -> Unit)? = null,
        onMessage: ((String) -> Unit)? = null,
        // Distinguish between manual user triggered connections and auto reconnect attempts
        manualAttempt: Boolean = true,
        // Called if we don't receive an initial message from Mac within timeout (likely auth failure)
        onHandshakeTimeout: (() -> Unit)? = null
    ) {
        // Cache application context for future cleanup even if callers don't pass context on disconnect
        appContext = context.applicationContext
        
        // Initialize Bluetooth sync manager for fallback/parallel sync
        try {
            BluetoothSyncManager.initialize(context.applicationContext)
            BluetoothSyncManager.onMessageReceived = { message ->
                // Forward Bluetooth messages to the same handler as WebSocket
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        WebSocketMessageHandler.handleIncomingMessage(context.applicationContext, message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling Bluetooth message: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize Bluetooth sync: ${e.message}")
        }

        // If user initiates a manual attempt, force reset connection state and stop auto-reconnect
        if (manualAttempt) {
            // Force reset connection state for manual attempts to override stuck states
            isConnecting.set(false)
            isConnected.set(false)
            isSocketOpen.set(false)
            handshakeCompleted.set(false)
            cancelAutoReconnect()
        } else if (isConnecting.get() || isConnected.get()) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        // Validate local network IP
        CoroutineScope(Dispatchers.IO).launch {
            if (!isLocalNetwork(context, ipAddress)) {
                Log.e(TAG, "Invalid IP address: $ipAddress. Only local network addresses are allowed.")
                onConnectionStatus?.invoke(false)
                return@launch
            }

            isConnecting.set(true)
            handshakeCompleted.set(false)
        // Update widgets to show "Connecting…" immediately
        try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}

            // Notify listeners that a manual connection attempt has begun so they can cancel auto-reconnect loops
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

                // Always use ws:// for local network
                val url = "ws://$ipAddress:$port/socket"

                Log.d(TAG, "Connecting to $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                startMessageProcessor()

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "WebSocket connected to $url")
                        // Transport is open now
                        isSocketOpen.set(true)
                        // Defer marking as connected until we get macInfo (handshake)
                        isConnected.set(false)
                        isConnecting.set(true)

                        // Trigger initial sync so Mac responds
                        try { SyncManager.performInitialSync(context) } catch (_: Exception) {}

                        // Start handshake timeout
                        handshakeTimeoutJob?.cancel()
                        handshakeTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
                            try {
                                delay(HANDSHAKE_TIMEOUT_MS)
                                if (!handshakeCompleted.get()) {
                                    Log.w(TAG, "Handshake timed out; treating as authentication failure")
                                    isConnected.set(false)
                                    isConnecting.set(false)
                                    try { webSocket.close(4001, "Handshake timeout") } catch (_: Exception) {}
                                    // Treat as manual disconnect if this was a manual attempt
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
                        Log.d(TAG, "Received: $text")

                        val decryptedMessage = currentSymmetricKey?.let { key ->
                            CryptoUtil.decryptMessage(text, key)
                        } ?: text

                        // On first macInfo message, complete handshake and now report connected
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
                                // Clear manual-disconnect flag on successful connect so future non-manual disconnects can auto-reconnect
                                try {
                                    val ds = com.sameerasw.airsync.data.local.DataStoreManager(context)
                                    kotlinx.coroutines.runBlocking { ds.setUserManuallyDisconnected(false) }
                                } catch (_: Exception) { }
                                try { SyncManager.startPeriodicSync(context) } catch (_: Exception) {}

                                // Start AirSync service on successful connection
                                try {
                                    val ds = com.sameerasw.airsync.data.local.DataStoreManager(context)
                                    val lastDevice = kotlinx.coroutines.runBlocking { ds.getLastConnectedDevice().first() }
                                    com.sameerasw.airsync.service.AirSyncService.start(context, lastDevice?.name)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error starting AirSyncService on connection: ${e.message}")
                                }

                                onConnectionStatusChanged?.invoke(true)
                                notifyConnectionStatusListeners(true)
                                try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
                            }
                        }

                        // Handle incoming commands
                        WebSocketMessageHandler.handleIncomingMessage(context, decryptedMessage)

                        // Update last sync time on successful response
                        updateLastSyncTime(context)

                        // Notify listeners
                        onMessageReceived?.invoke(decryptedMessage)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "WebSocket closing: $code / $reason")
                        isConnected.set(false)
                        isSocketOpen.set(false)
                        isConnecting.set(false)
                        handshakeCompleted.set(false)
                        handshakeTimeoutJob?.cancel()

                        // Cancel all ongoing file transfers
                        try {
                            FileReceiver.cancelAllTransfers(context)
                            Log.d(TAG, "Cancelled all file transfers on connection close")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error cancelling file transfers: ${e.message}")
                        }

                        // Stop AirSync service on disconnect
                        try {
                            com.sameerasw.airsync.service.AirSyncService.stop(context)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping AirSyncService on close: ${e.message}")
                        }
                        
                        // Stop screen mirroring on disconnect
                        try {
                            MirrorRequestHelper.stopMirroring(context)
                            Log.d(TAG, "Screen mirroring stopped on disconnect")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping mirroring on close: ${e.message}")
                        }

                        onConnectionStatusChanged?.invoke(false)
                        // Clear continue browsing notifs on disconnect
                        try { NotificationUtil.clearContinueBrowsingNotifications(context) } catch (_: Exception) {}
                        // Ensure media player is removed when connection closes
                        try { com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(context) } catch (_: Exception) {}

                        // Notify listeners about the connection status
                        notifyConnectionStatusListeners(false)
                        // Attempt auto-reconnect if allowed
                        tryStartAutoReconnect(context)
                        try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "WebSocket connection failed: ${t.message}")
                        isConnected.set(false)
                        isConnecting.set(false)
                        isSocketOpen.set(false)
                        handshakeCompleted.set(false)
                        handshakeTimeoutJob?.cancel()

                        // Cancel all ongoing file transfers
                        try {
                            FileReceiver.cancelAllTransfers(context)
                            Log.d(TAG, "Cancelled all file transfers on connection failure")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error cancelling file transfers: ${e.message}")
                        }

                        // Stop AirSync service on failure
                        try {
                            com.sameerasw.airsync.service.AirSyncService.stop(context)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping AirSyncService on failure: ${e.message}")
                        }
                        
                        // Stop screen mirroring on failure
                        try {
                            MirrorRequestHelper.stopMirroring(context)
                            Log.d(TAG, "Screen mirroring stopped on connection failure")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping mirroring on failure: ${e.message}")
                        }

                        // Update connection status
                        onConnectionStatusChanged?.invoke(false)
                        // Clear continue browsing notifs on failure
                        try { NotificationUtil.clearContinueBrowsingNotifications(context) } catch (_: Exception) {}
                        // Ensure media player is removed when connection fails
                        try { com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(context) } catch (_: Exception) {}

                        // Notify listeners about the connection status
                        notifyConnectionStatusListeners(false)
                        // Attempt auto-reconnect if allowed
                        tryStartAutoReconnect(context)
                        try { AirSyncWidgetProvider.updateAllWidgets(context) } catch (_: Exception) {}
                    }
                }

                webSocket = client!!.newWebSocket(request, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create WebSocket: ${e.message}")
                isConnecting.set(false)
                handshakeCompleted.set(false)
                handshakeTimeoutJob?.cancel()
                onConnectionStatusChanged?.invoke(false)
                try { NotificationUtil.clearContinueBrowsingNotifications(context) } catch (_: Exception) {}
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

    fun sendMessage(message: String): Boolean {
        // Try WebSocket first (primary connection)
        if (isSocketOpen.get() && webSocket != null) {
            // Determine priority
            if (message.contains("\"type\":\"mirrorFrame\"") || message.contains("\"type\":\"fileChunk\"") || message.contains("\"type\":\"audioFrame\"")) {
                val result = lowPriorityQueue.trySend(message)
                if (result.isFailure) {
                    // Log only occasionally to avoid spam
                    // Log.v(TAG, "Dropped low priority message")
                }
                return true
            } else {
                highPriorityQueue.trySend(message)
                return true
            }
        }
        
        // Fallback to Bluetooth if WebSocket not available
        if (BluetoothSyncManager.isAvailable()) {
            if (!message.contains("\"type\":\"mirrorFrame\"")) {
                Log.d(TAG, "Sending message via Bluetooth: ${message.take(100)}...")
            }
            return BluetoothSyncManager.sendMessage(message)
        }
        
        Log.w(TAG, "No connection available (WebSocket or Bluetooth), cannot send message")
        return false
    }
    
    /**
     * Check if any connection (WebSocket or Bluetooth) is available
     */
    fun isAnyConnectionAvailable(): Boolean {
        return (isSocketOpen.get() && webSocket != null) || BluetoothSyncManager.isAvailable()
    }

    fun disconnect(context: Context? = null) {
        Log.d(TAG, "Disconnecting WebSocket")
        isConnected.set(false)
        isConnecting.set(false)
        isSocketOpen.set(false)
        handshakeCompleted.set(false)
        handshakeTimeoutJob?.cancel()

        // Stop periodic sync when disconnecting
        SyncManager.stopPeriodicSync()

        webSocket?.close(1000, "Manual disconnection")
        webSocket = null

        // Resolve a context for side-effects (try provided one, fall back to appContext)
        val ctx = context ?: appContext
        
        // Cancel all ongoing file transfers on disconnect
        ctx?.let { c ->
            try {
                FileReceiver.cancelAllTransfers(c)
                Log.d(TAG, "Cancelled all file transfers on disconnect")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling file transfers: ${e.message}")
            }
        }

        // Stop AirSync service on disconnect
        ctx?.let { c ->
            try { com.sameerasw.airsync.service.AirSyncService.stop(c) } catch (e: Exception) {
                Log.e(TAG, "Error stopping AirSyncService on disconnect: ${e.message}")
            }
        }

        onConnectionStatusChanged?.invoke(false)

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

    private fun updateLastSyncTime(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dataStoreManager = com.sameerasw.airsync.data.local.DataStoreManager(context)
                dataStoreManager.updateLastSyncTime(System.currentTimeMillis())
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

    private fun tryStartAutoReconnect(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ds = com.sameerasw.airsync.data.local.DataStoreManager(context)
                val manual = ds.getUserManuallyDisconnected().first()
                val autoEnabled = ds.getAutoReconnectEnabled().first()
                // Only start when toggle is on and disconnect wasn't manual
                if (manual || !autoEnabled) return@launch

                // Need a reconnect target
                val last = ds.getLastConnectedDevice().first()
                val ourIp = com.sameerasw.airsync.utils.DeviceInfoUtil.getWifiIpAddress(context)
                val all = ds.getAllNetworkDeviceConnections().first()
                val target = if (ourIp != null && last != null) {
                    all.firstOrNull { it.deviceName == last.name && it.getClientIpForNetwork(ourIp) != null }
                } else null
                if (target == null || ourIp == null) return@launch

                val ip = target.getClientIpForNetwork(ourIp) ?: return@launch
                val port = target.port.toIntOrNull() ?: 6996

                if (autoReconnectActive.get()) return@launch // already running
                autoReconnectActive.set(true)
                autoReconnectAttempts = 0
                autoReconnectStartTime = System.currentTimeMillis()

                autoReconnectJob?.cancel()
                autoReconnectJob = CoroutineScope(Dispatchers.IO).launch {
                    val maxDurationMs = 10 * 60 * 1000L // 10 minutes
                    while (autoReconnectActive.get()) {
                        val elapsed = System.currentTimeMillis() - autoReconnectStartTime
                        if (elapsed > maxDurationMs) {
                            Log.d(TAG, "Auto-reconnect time window exceeded, stopping")
                            cancelAutoReconnect()
                            break
                        }

                        autoReconnectAttempts++
                        val delayMs = if (autoReconnectAttempts <= 6) 10_000L else 60_000L

                        // Attempt connection
                        Log.d(TAG, "Auto-reconnect attempt #$autoReconnectAttempts ...")
                        connect(
                            context = context,
                            ipAddress = ip,
                            port = port,
                            symmetricKey = target.symmetricKey,
                            manualAttempt = false,
                            onConnectionStatus = { connected ->
                                if (connected) {
                                    // success: stop auto reconnect
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try { ds.updateNetworkDeviceLastConnected(target.deviceName, System.currentTimeMillis()) } catch (_: Exception) {}
                                        cancelAutoReconnect()
                                    }
                                } else {
                                    // keep going
                                }
                            }
                        )

                        // Wait for next attempt unless canceled or connected
                        var waited = 0L
                        val step = 500L
                        while (autoReconnectActive.get() && !isConnected.get() && waited < delayMs) {
                            delay(step)
                            waited += step
                        }
                        if (!autoReconnectActive.get() || isConnected.get()) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting auto-reconnect: ${e.message}")
            }
        }
    }

    // Public wrapper to request auto-reconnect from app logic (e.g., network changes)
    fun requestAutoReconnect(context: Context) {
        // Only if not already connected or connecting
        if (isConnected.get() || isConnecting.get()) return
        tryStartAutoReconnect(context)
    }
    private fun startMessageProcessor() {
        messageProcessorJob?.cancel()
        messageProcessorJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    // Prioritize high priority queue
                    val message = select<String> {
                        highPriorityQueue.onReceive { it }
                        lowPriorityQueue.onReceive { it }
                    }

                    if (isSocketOpen.get() && webSocket != null) {
                        if (!message.contains("\"type\":\"mirrorFrame\"")) {
                            Log.d(TAG, "Sending message via WebSocket: ${message.take(100)}...")
                        }
                        
                        val messageToSend = currentSymmetricKey?.let { key ->
                            CryptoUtil.encryptMessage(message, key)
                        } ?: message
                        
                        webSocket?.send(messageToSend)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in message processor: ${e.message}")
                    delay(1000)
                }
            }
        }
    }
}
