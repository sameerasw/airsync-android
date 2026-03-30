package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.widget.AirSyncWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

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
    private val lastRelayLanRetryMs = AtomicLong(0L)
    private val lastAdvertisedTransport = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private var relayLanProbeJob: Job? = null
    private val relayLanProbeStartedAtMs = AtomicLong(0L)
    private val consecutiveLanProbeFailures = AtomicInteger(0)
    private val lanProbeCooldownUntilMs = AtomicLong(0L)
    private val lastLanProbeDiscoveryBurstMs = AtomicLong(0L)
    private val transportGeneration = AtomicLong(0L)
    private val activeTransportGeneration = AtomicLong(0L)
    private val activeTransportGenerationStartedAtMs = AtomicLong(0L)
    private val validatedTransportGeneration = AtomicLong(0L)
    private val pendingTransportCheckGeneration = AtomicLong(0L)
    private val pendingTransportCheckToken = AtomicReference<String?>(null)
    private var transportCheckTimeoutJob: Job? = null

    private const val RELAY_LAN_PROBE_FAST_WINDOW_MS = 120_000L
    private const val RELAY_LAN_PROBE_MEDIUM_WINDOW_MS = 10 * 60_000L
    private const val RELAY_LAN_PROBE_FAST_INTERVAL_MS = 15_000L
    private const val RELAY_LAN_PROBE_MEDIUM_INTERVAL_MS = 30_000L
    private const val RELAY_LAN_PROBE_SLOW_INTERVAL_MS = 60_000L
    private const val RELAY_LAN_PROBE_MAX_CONSECUTIVE_FAILURES = 8
    private const val RELAY_LAN_PROBE_COOLDOWN_MS = 5 * 60_000L
    private const val RELAY_LAN_PROBE_DISCOVERY_MIN_INTERVAL_MS = 30_000L
    private const val TRANSPORT_CHECK_TIMEOUT_MS = 6_000L
    private const val TRANSPORT_GENERATION_TTL_MS = 120_000L

    // Callback for connection status changes
    private var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    private var onMessageReceived: ((String) -> Unit)? = null

    // Application context for side-effects (notifications/services) when explicit context isn't provided
    private var appContext: Context? = null

    // Global connection status listeners for UI updates
    private val connectionStatusListeners = mutableSetOf<(Boolean) -> Unit>()

    // Advertises the current Android transport to peer so desktop UI can switch immediately.
    fun notifyPeerTransportChanged(transport: String, force: Boolean = false): Boolean {
        val previous = lastAdvertisedTransport.get()
        if (!force && previous == transport) return true

        val payload = JSONObject().apply {
            put("type", "peerTransport")
            put("data", JSONObject().apply {
                put("source", "android")
                put("transport", transport) // "wifi" | "relay"
                put("ts", System.currentTimeMillis())
            })
        }.toString()

        val sent = if (transport == "relay") {
            if (AirBridgeClient.isRelayConnectedOrConnecting()) {
                AirBridgeClient.sendMessage(payload)
            } else {
                sendMessage(payload)
            }
        } else {
            sendMessage(payload)
        }

        if (sent) {
            lastAdvertisedTransport.set(transport)
        }
        return sent
    }

    /**
     * Starts a LAN-first probe loop while relay is active.
     * The loop keeps relay as fallback and periodically retries direct LAN recovery.
     */
    fun startLanFirstRelayProbe(
        context: Context,
        immediate: Boolean = true,
        source: String = "unknown",
        resetBackoff: Boolean = false
    ) {
        appContext = context.applicationContext

        if (!isLanNegotiationAllowed(context)) {
            stopLanFirstRelayProbe("no_lan_network")
            return
        }

        if (!AirBridgeClient.isRelayConnectedOrConnecting()) {
            stopLanFirstRelayProbe("relay_not_active")
            return
        }

        val now = System.currentTimeMillis()
        if (relayLanProbeJob?.isActive == true) {
            if (resetBackoff) {
                relayLanProbeStartedAtMs.set(now)
                resetLanProbeFailureState("reset_by:$source")
            }
            if (immediate) {
                CoroutineScope(Dispatchers.IO).launch {
                    requestLanReconnectFromRelay(context, source = "immediate:$source")
                }
            }
            return
        }

        relayLanProbeStartedAtMs.set(now)
        if (resetBackoff) {
            resetLanProbeFailureState("reset_by:$source")
        }
        relayLanProbeJob = CoroutineScope(Dispatchers.IO).launch {
            if (immediate) {
                requestLanReconnectFromRelay(context, source = "start:$source")
            }

            while (isActive) {
                val elapsed = (System.currentTimeMillis() - relayLanProbeStartedAtMs.get()).coerceAtLeast(0L)
                val intervalMs = computeAdaptiveLanProbeInterval(elapsed)
                val nowLoop = System.currentTimeMillis()
                val cooldownUntil = lanProbeCooldownUntilMs.get()
                if (cooldownUntil > nowLoop) {
                    val remaining = cooldownUntil - nowLoop
                    delay(minOf(remaining, intervalMs))
                    continue
                }
                delay(intervalMs)
                if (isConnected.get()) {
                    break
                }
                if (!AirBridgeClient.isRelayConnectedOrConnecting()) {
                    break
                }
                requestLanReconnectFromRelay(context, source = "periodic:$source")
            }
        }
    }

    private fun computeAdaptiveLanProbeInterval(elapsedMs: Long): Long {
        return when {
            elapsedMs < RELAY_LAN_PROBE_FAST_WINDOW_MS -> RELAY_LAN_PROBE_FAST_INTERVAL_MS
            elapsedMs < (RELAY_LAN_PROBE_FAST_WINDOW_MS + RELAY_LAN_PROBE_MEDIUM_WINDOW_MS) -> RELAY_LAN_PROBE_MEDIUM_INTERVAL_MS
            else -> RELAY_LAN_PROBE_SLOW_INTERVAL_MS
        }
    }

    fun stopLanFirstRelayProbe(_reason: String = "unspecified") {
        relayLanProbeJob?.cancel()
        relayLanProbeJob = null
        relayLanProbeStartedAtMs.set(0L)
    }


    private fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Keep connection alive
            .pingInterval(
                5,
                TimeUnit.SECONDS
            ) // Send ping every 5 seconds for fast disconnect detection
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
            currentSymmetricKey = try {
                symmetricKey?.let { CryptoUtil.decodeKey(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode symmetric key: ${e.message}")
                null
            }
            if (symmetricKey != null && currentSymmetricKey == null) {
                Log.e(TAG, "Invalid symmetric key format, aborting connection")
                isConnecting.set(false)
                onConnectionStatus?.invoke(false)
                notifyConnectionStatusListeners(false)
                try {
                    AirSyncWidgetProvider.updateAllWidgets(context)
                } catch (_: Exception) {
                }
                return@launch
            }
            // Keep relay key aligned with current active key for seamless LAN<->relay switching.
            AirBridgeClient.updateSymmetricKey(symmetricKey)
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
                                isConnected.set(false)
                                isConnecting.set(true)

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
                                            isConnected.set(false)
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
                                        isConnected.set(true)
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
                                        stopLanFirstRelayProbe("lan_handshake_completed")
                                        // Keep relay warm in background (if enabled) for instant failover.
                                        AirBridgeClient.ensureConnected(context, immediate = false)
                                        notifyPeerTransportChanged("wifi", force = true)
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
                                    if (code != 1000) {
                                        if (com.sameerasw.airsync.AirSyncApp.isAppForeground()) {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                val msg = reason.ifEmpty { "Unknown Server Disconnect" }
                                                android.widget.Toast.makeText(context, "Disconnected: $msg", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    isConnected.set(false)
                                    isSocketOpen.set(false)
                                    isConnecting.set(false)
                                    handshakeCompleted.set(false)
                                    handshakeTimeoutJob?.cancel()
                                    currentIpAddress = null
                                    try {
                                        com.sameerasw.airsync.service.AirSyncService.startScanning(
                                            context
                                        )
                                    } catch (_: Exception) {
                                    }
                                    try {
                                        com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(context)
                                        com.sameerasw.airsync.utils.MacDeviceStatusManager.cleanup(context)
                                    } catch (_: Exception) {
                                    }
                                    onConnectionStatusChanged?.invoke(false)
                                    notifyConnectionStatusListeners(false)
                                    // If relay is enabled, force immediate relay reconnect for seamless fallback.
                                    AirBridgeClient.ensureConnected(context, immediate = true)
                                    notifyPeerTransportChanged("relay", force = true)
                                    if (AirBridgeClient.isRelayConnectedOrConnecting()) {
                                        startLanFirstRelayProbe(context, immediate = true, source = "lan_onClosing", resetBackoff = true)
                                    }
                                    Log.w(TAG, "LAN socket closing, requested immediate relay fallback")
                                    if (!AirBridgeClient.isRelayConnectedOrConnecting()) {
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
                                val isFinalManualAttempt = manualAttempt && !connectionStarted.get() && failedCount >= totalToTry

                                if (wasActive || isFinalManualAttempt) {
                                    if (manualAttempt || isSocketOpen.get()) {
                                        // Avoid noisy LAN error toasts when relay failover is already available.
                                        if (!AirBridgeClient.isRelayConnectedOrConnecting() && com.sameerasw.airsync.AirSyncApp.isAppForeground()) {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                val msg = when (t) {
                                                    is java.net.ConnectException -> "Connection Refused (Is AirSync Mac running?)"
                                                    is java.net.SocketTimeoutException -> "Could not discover your mac"
                                                    is java.net.UnknownHostException -> "Could not reach your mac"
                                                    is java.io.EOFException, is java.net.SocketException -> "Lost connection to your mac"
                                                    else -> t.message ?: "Unknown connection error"
                                                }
                                                android.widget.Toast.makeText(context, "AirSync: $msg", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    isConnected.set(false)
                                    isConnecting.set(false)
                                    isSocketOpen.set(false)
                                    handshakeCompleted.set(false)
                                    handshakeTimeoutJob?.cancel()
                                    connectionAttemptJob?.cancel()
                                    currentIpAddress = null
                                    try {
                                        com.sameerasw.airsync.service.AirSyncService.startScanning(
                                            context
                                        )
                                    } catch (_: Exception) {
                                    }
                                    try {
                                        com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(context)
                                        com.sameerasw.airsync.utils.MacDeviceStatusManager.cleanup(context)
                                    } catch (_: Exception) {
                                    }
                                    onConnectionStatusChanged?.invoke(false)
                                    notifyConnectionStatusListeners(false)
                                    // If relay is enabled, force immediate relay reconnect for seamless fallback.
                                    AirBridgeClient.ensureConnected(context, immediate = true)
                                    notifyPeerTransportChanged("relay", force = true)
                                    if (AirBridgeClient.isRelayConnectedOrConnecting()) {
                                        startLanFirstRelayProbe(context, immediate = true, source = "lan_onFailure", resetBackoff = true)
                                    }
                                    Log.w(TAG, "LAN failure, requested immediate relay fallback")
                                    if (!AirBridgeClient.isRelayConnectedOrConnecting()) {
                                        tryStartAutoReconnect(context)
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

        // Check standard private IP ranges (RFC 1918) and Carrier-Grade NAT (Tailscale/VPNs)
        if (ipAddress.startsWith("192.168.") || ipAddress.startsWith("10.") || ipAddress.startsWith(
                "100."
            )
        ) {
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
        } else if (AirBridgeClient.isRelayActive()) {
            // Fallback: route through AirBridge relay if local connection is down
            AirBridgeClient.sendMessage(message)
        } else {
            Log.w(TAG, "Drop TX: no LAN/relay available")
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
        lastAdvertisedTransport.set(null)
        stopLanFirstRelayProbe("manual_disconnect")
        resetLanProbeFailureState("manual_disconnect")

        webSocket?.close(1000, "Manual disconnection")
        webSocket = null

        // Transition back to scanning on disconnect
        val ctx = context ?: appContext
        ctx?.let { c ->
            try {
                com.sameerasw.airsync.service.AirSyncService.startScanning(c)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting scanning on disconnect: ${e.message}")
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
        stopLanFirstRelayProbe("cleanup")
        resetLanProbeFailureState("cleanup")
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
                    if (AirBridgeClient.isRelayConnectedOrConnecting()) {
                        startLanFirstRelayProbe(context, immediate = false, source = "auto_reconnect_collect", resetBackoff = false)
                        return@collect
                    }

                    val manual = ds.getUserManuallyDisconnected().first()
                    val autoEnabled = ds.getAutoReconnectEnabled().first()
                    if (manual || !autoEnabled) {
                        cancelAutoReconnect()
                        return@collect
                    }

                    val last = ds.getLastConnectedDevice().first() ?: return@collect
                    DeviceInfoUtil.getWifiIpAddress(context)
                        ?: return@collect

                    // Match by name within the discovery list
                    val discoveryMatch = discoveredList.find { it.name == last.name }
                    if (discoveryMatch != null) {
                        Log.d(
                            TAG,
                            "Discovery found target device: ${discoveryMatch.name} with IPs: ${discoveryMatch.ips}"
                        )

                        val all = ds.getAllNetworkDeviceConnections().first()
                        val targetConnection = all.firstOrNull { it.deviceName == last.name }

                        if (targetConnection != null) {
                            val ips = discoveryMatch.ips.joinToString(",")
                            val port = targetConnection.port.toIntOrNull() ?: 6996

                            Log.d(
                                TAG,
                                "Smart Auto-reconnect attempting parallel connections to $ips:$port"
                            )
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
                                            } catch (_: Exception) {
                                            }
                                            cancelAutoReconnect()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                } else {
                    Log.e(TAG, "Error in discovery auto-reconnect: ${e.message}")
                }
            }
        }
    }

    // Public wrapper to request auto-reconnect from app logic (e.g., network changes)
    fun requestAutoReconnect(context: Context) {
        // Only if not already connected or connecting
        if (isConnected.get() || isConnecting.get()) return
        if (AirBridgeClient.isRelayConnectedOrConnecting()) {
            // Important for app cold-start/reopen after process kill:
            // there may be no immediate network callback/discovery emission,
            // so start LAN-first probe right away while relay is up.
            sendTransportOffer(context, reason = "requestAutoReconnect_relay_bootstrap")
            startLanFirstRelayProbe(
                context = context,
                immediate = true,
                source = "requestAutoReconnect_relay_bootstrap",
                resetBackoff = true
            )
        }
        tryStartAutoReconnect(context)
    }

    /**
     * Attempts to re-establish a direct LAN connection while relay is active.
     * Called when WiFi becomes available again after being lost.
     * On success the existing relay stays warm but message routing automatically
     * prefers LAN via sendMessage().
     */
    fun requestLanReconnectFromRelay(context: Context) {
        requestLanReconnectFromRelay(context, source = "default")
    }

    fun requestLanReconnectFromRelay(context: Context, source: String) {
        if (!isLanNegotiationAllowed(context)) {
            return
        }
        val now = System.currentTimeMillis()
        val cooldownUntil = lanProbeCooldownUntilMs.get()
        if (cooldownUntil > now) {
            return
        }
        if (isConnected.get() || isConnecting.get()) return
        val last = lastRelayLanRetryMs.get()
        if (now - last < 5_000L && source.startsWith("periodic:")) {
            return
        }
        lastRelayLanRetryMs.set(now)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
                val manual = ds.getUserManuallyDisconnected().first()
                val autoEnabled = ds.getAutoReconnectEnabled().first()
                if (manual || !autoEnabled) {
                    return@launch
                }

                val last = ds.getLastConnectedDevice().first() ?: return@launch
                val all = ds.getAllNetworkDeviceConnections().first()
                val targetConnection = all.firstOrNull { it.deviceName == last.name }

                if (targetConnection != null) {
                    // Discover fresh IPs via UDP burst first, but throttle to avoid battery drain.
                    val burstNow = System.currentTimeMillis()
                    val lastBurst = lastLanProbeDiscoveryBurstMs.get()
                    val shouldBurst = source.startsWith("start:") ||
                        source.startsWith("immediate:") ||
                        burstNow - lastBurst >= RELAY_LAN_PROBE_DISCOVERY_MIN_INTERVAL_MS
                    if (shouldBurst && lastLanProbeDiscoveryBurstMs.compareAndSet(lastBurst, burstNow)) {
                        UDPDiscoveryManager.burstBroadcast(context)
                    }
                    delay(2000) // Allow time for discovery responses

                    // Check discovered devices for the target
                    val discovered = UDPDiscoveryManager.discoveredDevices.value
                    val match = discovered.find { it.name == last.name }

                    val ips = match?.ips?.joinToString(",")
                        ?: targetConnection.getClientIpForNetwork(DeviceInfoUtil.getWifiIpAddress(context) ?: "")
                        ?: last.ipAddress
                    val port = targetConnection.port.toIntOrNull() ?: 6996

                    connect(
                        context = context,
                        ipAddress = ips,
                        port = port,
                        symmetricKey = targetConnection.symmetricKey,
                        manualAttempt = false,
                        onConnectionStatus = { connected ->
                            if (connected) {
                                resetLanProbeFailureState("lan_reconnect_success")
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        ds.updateNetworkDeviceLastConnected(
                                            targetConnection.deviceName,
                                            System.currentTimeMillis()
                                        )
                                    } catch (_: Exception) {}
                                }
                            } else {
                                markLanProbeFailure("lan_reconnect_failed:$source")
                            }
                        }
                    )
                } else {
                    markLanProbeFailure("missing_target_connection:$source")
                    // Fall back to generic auto-reconnect which monitors discovery
                    tryStartAutoReconnect(context)
                }
            } catch (e: Exception) {
                markLanProbeFailure("request_exception:$source")
                Log.e(TAG, "Error in requestLanReconnectFromRelay")
            }
        }
    }

    private fun resetLanProbeFailureState(reason: String) {
        consecutiveLanProbeFailures.set(0)
        lanProbeCooldownUntilMs.set(0L)
    }

    private fun markLanProbeFailure(reason: String) {
        val fails = consecutiveLanProbeFailures.incrementAndGet()
        if (fails >= RELAY_LAN_PROBE_MAX_CONSECUTIVE_FAILURES) {
            val until = System.currentTimeMillis() + RELAY_LAN_PROBE_COOLDOWN_MS
            lanProbeCooldownUntilMs.set(until)
            consecutiveLanProbeFailures.set(0)
            Log.w(TAG, "LAN-first probe entering cooldown after repeated failures")
        }
    }

    fun reportLanNegotiationFailure(reason: String) {
        markLanProbeFailure("negotiation:$reason")
    }

    fun reportLanNegotiationSuccess(reason: String) {
        resetLanProbeFailureState("negotiation:$reason")
    }

    fun nextTransportGeneration(): Long {
        val next = transportGeneration.incrementAndGet()
        beginTransportRound(next, "local_next_generation")
        return next
    }

    private fun beginTransportRound(generation: Long, reason: String) {
        if (generation <= 0L) return
        activeTransportGeneration.set(generation)
        activeTransportGenerationStartedAtMs.set(System.currentTimeMillis())
        validatedTransportGeneration.set(0L)
        pendingTransportCheckGeneration.set(0L)
        pendingTransportCheckToken.set(null)
        transportCheckTimeoutJob?.cancel()
        transportCheckTimeoutJob = null
    }

    fun acceptIncomingTransportGeneration(generation: Long, reason: String): Boolean {
        if (generation <= 0L) return false
        val current = activeTransportGeneration.get()
        if (current == 0L) {
            beginTransportRound(generation, "incoming_init:$reason")
            return true
        }
        if (generation == current) return true

        val age = System.currentTimeMillis() - activeTransportGenerationStartedAtMs.get()
        if (age > TRANSPORT_GENERATION_TTL_MS && generation > current) {
            beginTransportRound(generation, "incoming_rollover:$reason")
            return true
        }

        Log.w(TAG, "Dropping stale transport generation update")
        return false
    }

    fun isTransportGenerationActive(generation: Long): Boolean {
        if (generation <= 0L) return false
        val current = activeTransportGeneration.get()
        if (generation != current) return false
        val age = System.currentTimeMillis() - activeTransportGenerationStartedAtMs.get()
        return age <= TRANSPORT_GENERATION_TTL_MS
    }

    fun markTransportGenerationValidated(generation: Long, reason: String) {
        if (!isTransportGenerationActive(generation)) return
        validatedTransportGeneration.set(generation)
    }

    fun isTransportGenerationValidated(generation: Long): Boolean {
        return generation > 0L && validatedTransportGeneration.get() == generation
    }

    fun getActiveTransportGeneration(): Long {
        return activeTransportGeneration.get()
    }

    fun sendTransportOffer(context: Context, reason: String, generation: Long = nextTransportGeneration()): Boolean {
        if (!isLanNegotiationAllowed(context)) {
            return false
        }
        beginTransportRound(generation, "send_offer:$reason")
        val localIp = DeviceInfoUtil.getWifiIpAddress(context) ?: ""
        val candidates = JSONArray().apply {
            if (localIp.isNotBlank()) {
                put(JSONObject().apply {
                    put("ip", localIp)
                    put("port", 0)
                    put("type", "host")
                })
            }
        }
        val payload = JSONObject().apply {
            put("type", "transportOffer")
            put("data", JSONObject().apply {
                put("source", "android")
                put("generation", generation)
                put("candidates", candidates)
                put("ts", System.currentTimeMillis())
                put("reason", reason)
            })
        }.toString()

        return sendMessage(payload)
    }

    fun sendTransportAnswer(generation: Long, reason: String, context: Context? = appContext): Boolean {
        if (context == null || !isLanNegotiationAllowed(context)) {
            return false
        }
        if (!isTransportGenerationActive(generation)) {
            Log.w(TAG, "Dropping transport answer for inactive generation")
            return false
        }
        val localIp = DeviceInfoUtil.getWifiIpAddress(context) ?: ""
        val candidates = JSONArray().apply {
            if (localIp.isNotBlank()) {
                put(JSONObject().apply {
                    put("ip", localIp)
                    put("port", 0)
                    put("type", "host")
                })
            }
        }
        val payload = JSONObject().apply {
            put("type", "transportAnswer")
            put("data", JSONObject().apply {
                put("source", "android")
                put("generation", generation)
                put("candidates", candidates)
                put("ts", System.currentTimeMillis())
                put("reason", reason)
            })
        }.toString()
        return sendMessage(payload)
    }

    fun sendTransportCheck(generation: Long, reason: String): Boolean {
        if (!isTransportGenerationActive(generation)) {
            Log.w(TAG, "Dropping transport check for inactive generation")
            return false
        }
        val token = UUID.randomUUID().toString()
        pendingTransportCheckGeneration.set(generation)
        pendingTransportCheckToken.set(token)
        transportCheckTimeoutJob?.cancel()
        transportCheckTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
            delay(TRANSPORT_CHECK_TIMEOUT_MS)
            val pendingToken = pendingTransportCheckToken.get()
            if (pendingToken == token) {
                Log.w(TAG, "Transport check timed out")
                reportLanNegotiationFailure("check_timeout")
                sendTransportNominate("relay", generation, "check_timeout")
            }
        }

        val payload = JSONObject().apply {
            put("type", "transportCheck")
            put("data", JSONObject().apply {
                put("source", "android")
                put("generation", generation)
                put("token", token)
                put("ts", System.currentTimeMillis())
                put("reason", reason)
            })
        }.toString()
        return sendMessage(payload)
    }

    fun sendTransportCheckAck(generation: Long, token: String): Boolean {
        if (!isTransportGenerationActive(generation)) {
            Log.w(TAG, "Dropping transport check-ack for inactive generation")
            return false
        }
        val payload = JSONObject().apply {
            put("type", "transportCheckAck")
            put("data", JSONObject().apply {
                put("source", "android")
                put("generation", generation)
                put("token", token)
                put("ts", System.currentTimeMillis())
            })
        }.toString()
        return sendMessage(payload)
    }

    fun onTransportCheckAck(generation: Long, token: String) {
        if (!isTransportGenerationActive(generation)) {
            return
        }
        val pendingGeneration = pendingTransportCheckGeneration.get()
        val pendingToken = pendingTransportCheckToken.get()
        if (pendingGeneration != generation || pendingToken != token) {
            return
        }
        transportCheckTimeoutJob?.cancel()
        transportCheckTimeoutJob = null
        pendingTransportCheckToken.set(null)
        pendingTransportCheckGeneration.set(0L)
        reportLanNegotiationSuccess("check_ack")
        if (!isConnected()) {
            Log.w(TAG, "Dropping transport check-ack because LAN is not connected")
            return
        }
        markTransportGenerationValidated(generation, "check_ack")
        notifyPeerTransportChanged("wifi", force = true)
        sendTransportNominate("lan", generation, "check_ack")
    }

    fun sendTransportNominate(path: String, generation: Long, reason: String): Boolean {
        if (!isTransportGenerationActive(generation)) {
            Log.w(TAG, "Dropping transport nominate for inactive generation")
            return false
        }
        if (path == "lan" && !isTransportGenerationValidated(generation)) {
            Log.w(TAG, "Dropping LAN nominate because generation is not validated")
            return false
        }
        val payload = JSONObject().apply {
            put("type", "transportNominate")
            put("data", JSONObject().apply {
                put("source", "android")
                put("generation", generation)
                put("path", path)
                put("ts", System.currentTimeMillis())
                put("reason", reason)
            })
        }.toString()
        return sendMessage(payload)
    }

    private fun isPrivateLanIp(ip: String): Boolean {
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
        if (ip.startsWith("172.")) {
            val parts = ip.split(".")
            if (parts.size >= 2) {
                val secondOctet = parts[1].toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return true
                }
            }
        }
        return false
    }

    fun isLanNegotiationAllowed(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            val isLanTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLanTransport) return false
            val ip = DeviceInfoUtil.getWifiIpAddress(context) ?: return false
            isPrivateLanIp(ip)
        } catch (_: Exception) {
            false
        }
    }


}
