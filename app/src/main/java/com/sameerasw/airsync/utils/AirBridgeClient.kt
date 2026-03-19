package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

/**
 * Singleton that manages the WebSocket connection to the AirBridge relay server.
 * Runs alongside the local WebSocket connection as a fallback for remote communication.
 */
object AirBridgeClient {
    private const val TAG = "AirBridgeClient"

    // Connection state
    enum class State {
        DISCONNECTED,
        CONNECTING,
        REGISTERING,
        WAITING_FOR_PEER,
        RELAY_ACTIVE,
        FAILED
    }

    // Connection state mutable state flow
    private val _connectionState = MutableStateFlow(State.DISCONNECTED)

    val connectionState: StateFlow<State> = _connectionState

    // WebSocket connection
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    // Symmetric key for relay encryption/decryption
    private var symmetricKey: SecretKey? = null
    private var appContext: Context? = null

    // Manual disconnect flag
    private val isManuallyDisconnected = AtomicBoolean(false)

    // Reconnect backoff
    private var reconnectJob: Job? = null
    private var statusQueryJob: Job? = null

    // Number of consecutive reconnect attempts, used for exponential backoff calculation.
    private var reconnectAttempt = 0

    // Maximum backoff delay of 30 seconds to prevent excessively long waits.
    private val maxReconnectDelay = 30_000L // 30 seconds

    // Prevent concurrent connect attempts to
    private val connectInProgress = AtomicBoolean(false)
    private val activeConnectionGeneration = AtomicLong(0L)

    // Cached view of last status_reply from relay
    @Volatile
    private var lastStatusBothConnected: Boolean = false
    private val _peerReallyActive = MutableStateFlow(false)
    val peerReallyActive: StateFlow<Boolean> = _peerReallyActive

    // Message callback — routes relayed messages to the existing handler
    private var onMessageReceived: ((Context, String) -> Unit)? = null

    // Updates the connection state and logs the transition reason.
    private fun setState(newState: State, reason: String) {
        val oldState = _connectionState.value
        if (oldState != newState) {
            Log.i(TAG, "State: $oldState -> $newState | $reason")
        } else {
            Log.d(TAG, "State unchanged: $newState | $reason")
        }
        _connectionState.value = newState
        if (newState != State.RELAY_ACTIVE) {
            _peerReallyActive.value = false
        }
    }

    /**
     * Connects to the AirBridge relay server.
     * Reads configuration from DataStore.
     */
    fun connect(context: Context) {
        appContext = context.applicationContext
        isManuallyDisconnected.set(false)

        // Guard against duplicate connect attempts while a usable relay connection is already active/in-flight.
        if (isRelayConnectedOrConnecting()) {
            return
        }

        if (!connectInProgress.compareAndSet(false, true)) {
            return
        }

        // Read config and connect in background to avoid blocking the caller and allow suspend functions.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // First check if AirBridge is enabled and config is present before attempting connection.
                val ds = DataStoreManager.getInstance(context)
                // If disabled, set state and exit early to avoid unnecessary connection attempts and log spam.
                val enabled = ds.getAirBridgeEnabled().first()
                // If AirBridge is disabled, set state to DISCONNECTED and return early to prevent connection attempts and log spam.
                if (!enabled) {
                    setState(State.DISCONNECTED, "AirBridge disabled in settings")
                    return@launch
                }
                val relayUrl = ds.getAirBridgeRelayUrl().first()
                val pairingId = ds.getAirBridgePairingId().first()
                val secret = ds.getAirBridgeSecret().first()

                // Validate config values before connecting to prevent futile attempts and log spam.
                if (relayUrl.isBlank()) {
                    Log.w(TAG, "Relay URL is empty, skipping connection")
                    setState(State.DISCONNECTED, "Missing relay URL")
                    return@launch
                }

                // Pairing ID and secret are required for registration, so treat missing values as failed state to prompt user action.
                if (pairingId.isBlank() || secret.isBlank()) {
                    Log.w(TAG, "Pairing ID or secret is empty, skipping connection")
                    setState(State.FAILED, "Missing pairing credentials")
                    return@launch
                }

                // Load/refresh symmetric key for relay encryption/decryption.
                // Fallback to network-aware records if lastConnected is empty/stale.
                if (symmetricKey == null) {
                    symmetricKey = resolveSymmetricKey(ds)
                }

                if (symmetricKey == null) {
                    Log.e(TAG, "SECURITY: No symmetric key resolved — refusing relay connection to prevent plaintext transport")
                    setState(State.FAILED, "No encryption key available")
                    return@launch
                } else {
                    Log.d(TAG, "Symmetric key resolved for relay transport")
                }

                connectInternal(relayUrl, pairingId, hashSecret(secret))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read AirBridge config: ${e.message}")
                setState(State.FAILED, "Failed reading persisted config")
            } finally {
                connectInProgress.set(false)
            }
        }
    }

    /**
     * Allows LAN flow to explicitly refresh the relay key, so transport switching is seamless.
     */
    fun updateSymmetricKey(base64Key: String?) {
        symmetricKey = base64Key?.let { CryptoUtil.decodeKey(it) }
        if (symmetricKey != null) {
            Log.d(TAG, "Relay symmetric key updated from active session")
        }
    }

    // Resolves the symmetric key for relay encryption/decryption.
    private suspend fun resolveSymmetricKey(ds: DataStoreManager): SecretKey? {
        // First try to get the most recently connected device's key, which is the most likely to be valid.
        val fromLast = ds.getLastConnectedDevice().first()?.symmetricKey
            ?.let { CryptoUtil.decodeKey(it) }
        // If that fails, look through all known devices for the most recently connected one with a valid key.
        if (fromLast != null) return fromLast

        // Only consider devices that have connected at least once (lastConnected > 0) to avoid using stale keys from old records that were never active.
        val fromNetwork = ds.getAllNetworkDeviceConnections().first()
            .sortedByDescending { it.lastConnected }
            .firstNotNullOfOrNull { conn ->
                conn.symmetricKey?.let { CryptoUtil.decodeKey(it) }
            }
        return fromNetwork
    }

    /**
     * Ensures relay connection is active when enabled.
     * If immediate=true, cancels pending backoff reconnect and retries now.
     */
    fun ensureConnected(context: Context, immediate: Boolean = false) {
        // Create a new coroutine scope for this operation to avoid blocking the caller and allow suspend functions.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ds = DataStoreManager.getInstance(context)
                val enabled = ds.getAirBridgeEnabled().first()
                if (!enabled) {
                    Log.d(TAG, "ensureConnected skipped: AirBridge disabled")
                    return@launch
                }
                // If already connected or in the process of connecting, do nothing. Otherwise, attempt to connect.
                when (_connectionState.value) {
                    State.RELAY_ACTIVE,
                    State.WAITING_FOR_PEER,
                    State.REGISTERING,
                    State.CONNECTING -> {
                        // Already connected/connecting
                        return@launch
                    }
                    else -> {
                        if (immediate) {
                            reconnectJob?.cancel()
                            reconnectJob = null
                            reconnectAttempt = 0
                            Log.i(TAG, "ensureConnected: forcing immediate reconnect")
                        }
                        connect(context)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureConnected failed: ${e.message}")
            }
        }
    }

    /**
     * Disconnects from the relay server. Disables auto-reconnect.
     */
    fun disconnect() {
        isManuallyDisconnected.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        WebSocketUtil.stopLanFirstRelayProbe("relay_manual_disconnect")
        statusQueryJob?.cancel()
        statusQueryJob = null
        reconnectAttempt = 0
        lastStatusBothConnected = false
        _peerReallyActive.value = false

        // Close WebSocket connection gracefully.
        try {
            webSocket?.close(1000, "Manual disconnect")
        } catch (_: Exception) {}
        webSocket = null
        activeConnectionGeneration.incrementAndGet()

        client?.dispatcher?.executorService?.shutdown()
        client = null

        setState(State.DISCONNECTED, "Manual disconnect")
        Log.d(TAG, "Disconnected manually")
    }

    /**
     * Sends a pre-encrypted message through the relay.
     * @return true if the message was enqueued successfully.
     */
    fun sendMessage(message: String): Boolean {
        // Only allow sending if we have an active WebSocket connection and are in a state that should allow relay messages.
        val ws = webSocket ?: return false
        // Only allow sending relay messages if we're in a state where the relay should be active or soon-to-be-active.
        if (_connectionState.value != State.RELAY_ACTIVE &&
            _connectionState.value != State.WAITING_FOR_PEER) {
            return false
        }

        // Encrypt with the same symmetric key used for local connections.
        val key = symmetricKey
        if (key == null) {
            Log.e(TAG, "SECURITY: Cannot send relay message — no symmetric key available. Dropping message to prevent plaintext leak.")
            return false
        }

        // Encrypt the message for relay transport. If encryption fails, drop the message to prevent plaintext leak.
        val messageToSend = CryptoUtil.encryptMessage(message, key)
        if (messageToSend == null) {
            Log.e(TAG, "SECURITY: Encryption failed, dropping message to prevent plaintext leak.")
            return false
        }

        val type = try {
            JSONObject(message).optString("type", "unknown")
        } catch (_: Exception) {
            "non_json"
        }

        return try {
            Log.d(TAG, "Relay TX type=$type bytes=${messageToSend.length}")
            ws.send(messageToSend)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send relay message: ${e.message}")
            false
        }
    }

    /**
     * Returns true if the relay is active and ready to forward messages.
     */
    fun isRelayActive(): Boolean = _connectionState.value == State.RELAY_ACTIVE

    /**
     * Returns true if the relay reports that both Mac and Android are currently attached
     * to the same pairing session (based on the latest status_reply snapshot).
     */
    fun isPeerReallyActive(): Boolean = isRelayActive() && lastStatusBothConnected

    /**
     * Returns true if relay transport is already usable or being established.
     * Useful to suppress noisy LAN reconnect loops while relay failover is in progress.
     */
    fun isRelayConnectedOrConnecting(): Boolean {
        return when (_connectionState.value) {
            State.RELAY_ACTIVE,
            State.WAITING_FOR_PEER,
            State.REGISTERING,
            State.CONNECTING -> true
            else -> false
        }
    }

    /**
     * Sets the message handler. Called once during app initialization.
     */
    fun setMessageHandler(handler: (Context, String) -> Unit) {
        onMessageReceived = handler
    }

    /**
     * Internal function to establish WebSocket connection and handle relay protocol.
     */
    private fun connectInternal(relayUrl: String, pairingId: String, secret: String) {
        // Allocate a fresh generation so callbacks from older sockets are ignored.
        val generation = activeConnectionGeneration.incrementAndGet()

        // Set state early to prevent concurrent connect attempts and log spam while connection is in progress.
        setState(State.CONNECTING, "Opening websocket to relay")

        // Normalize the relay URL to ensure it has the correct scheme and path. This also enforces ws:// for private hosts and wss:// for public hosts to prevent user misconfiguration that could lead to plaintext transport over the internet.
        val normalizedUrl = normalizeRelayUrl(relayUrl)
        Log.d(TAG, "Connecting to relay: $normalizedUrl")

        // Lazily initialize OkHttpClient with timeouts suitable for a long-lived relay connection.
        if (client == null) {
            client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS) // Disable client-side pings to prevent protocol conflicts
                .build()
        }

        // Build the WebSocket request
        val request = Request.Builder()
            .url(normalizedUrl)
            .build()

        // Create a new WebSocket connection with a listener to handle the relay protocol.
        val listener = object : WebSocketListener() {
            fun isStale(): Boolean = generation != activeConnectionGeneration.get()

            // On open, send the registration message with the pairing ID and secret hash.
            override fun onOpen(ws: WebSocket, response: Response) {
                if (isStale()) {
                    try {
                        ws.close(1000, "Stale relay socket")
                    } catch (_: Exception) {}
                    return
                }
                Log.d(TAG, "WebSocket opened to relay")
                webSocket = ws
                setState(State.REGISTERING, "Socket open, sending register")
                reconnectAttempt = 0

                // Send registration
                val regMsg = JSONObject().apply {
                    put("action", "register")
                    put("role", "android")
                    put("pairingId", pairingId)
                    put("secret", secret)
                    put("localIp", DeviceInfoUtil.getWifiIpAddress(appContext!!) ?: "unknown")
                    put("port", 0) // Android doesn't run a server it's the client
                }

                if (ws.send(regMsg.toString())) {
                    Log.d(TAG, "Registration sent for pairingId: $pairingId")
                    setState(State.WAITING_FOR_PEER, "Registration accepted, waiting peer")
                    // Reset status cache on fresh registration
                    lastStatusBothConnected = false
                    _peerReallyActive.value = false
                } else {
                    Log.e(TAG, "Failed to send registration")
                    setState(State.FAILED, "Registration send failed")
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (isStale()) return
                handleTextMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                Log.d(TAG, "Relay closing: $code $reason")
                ws.close(1000, null)
                setState(State.DISCONNECTED, "Socket closing code=$code")
                WebSocketUtil.stopLanFirstRelayProbe("relay_onClosing")
                statusQueryJob?.cancel()
                statusQueryJob = null
                lastStatusBothConnected = false
                _peerReallyActive.value = false
                if (webSocket == ws) {
                    webSocket = null
                }
                scheduleReconnect(relayUrl, pairingId, secret, generation)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) return
                val msg = if (t is java.io.EOFException) "Server closed connection (EOF)" else (t.message ?: "Unknown error ($t)")
                Log.e(TAG, "Relay connection failed: $msg")
                setState(State.FAILED, "Socket failure: $msg")
                WebSocketUtil.stopLanFirstRelayProbe("relay_onFailure")
                statusQueryJob?.cancel()
                statusQueryJob = null
                lastStatusBothConnected = false
                _peerReallyActive.value = false
                if (webSocket == ws) {
                    webSocket = null
                }
                scheduleReconnect(relayUrl, pairingId, secret, generation)
            }
        }

        client!!.newWebSocket(request, listener)
    }

    private fun handleTextMessage(text: String) {
        // First, try to parse as an AirBridge control message
        try {
            val json = JSONObject(text)
            val action = json.optString("action", "")

            when (action) {
                "relay_started" -> {
                    Log.i(TAG, "Relay tunnel established!")
                    setState(State.RELAY_ACTIVE, "Server confirmed relay tunnel")
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reconnectAttempt = 0
                    startStatusPolling()
                    // Advertise effective transport immediately so desktop UI can switch
                    // icon/actions without waiting for stale local session cleanup.
                    val transport = if (WebSocketUtil.isConnected()) "wifi" else "relay"
                    WebSocketUtil.notifyPeerTransportChanged(transport, force = true)
                    appContext?.let { ctx ->
                        if (!WebSocketUtil.isConnected()) {
                            val generation = WebSocketUtil.nextTransportGeneration()
                            WebSocketUtil.sendTransportOffer(
                                context = ctx,
                                reason = "relay_started",
                                generation = generation
                            )
                            WebSocketUtil.startLanFirstRelayProbe(
                                context = ctx,
                                immediate = true,
                                source = "relay_started",
                                resetBackoff = true
                            )
                        }
                    }

                    // Trigger initial sync via relay now that the tunnel is active
                    appContext?.let { ctx ->
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                delay(1000) // Stabilize connection before sending data
                                SyncManager.performInitialSync(ctx)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to perform initial sync via relay: ${e.message}")
                            }
                        }
                    }
                    return
                }
                "status_reply" -> {
                    // Health snapshot from relay: record whether both peers are currently attached.
                    val both = json.optBoolean("bothConnected", false)
                    val macActive = json.optBoolean("macActive", false)
                    val androidActive = json.optBoolean("androidActive", false)
                    lastStatusBothConnected = both && macActive && androidActive
                    _peerReallyActive.value = isRelayActive() && lastStatusBothConnected
                    Log.d(TAG, "Relay status_reply: macActive=$macActive androidActive=$androidActive bothConnected=$both")
                    return
                }
                "mac_info" -> {
                    // Server echoing Mac's info — we can ignore this at the relay level
                    // but let the message handler process it for device discovery
                    Log.d(TAG, "Received mac_info from relay")
                }
                "error" -> {
                    val msg = json.optString("message", "Unknown error")
                    Log.e(TAG, "Relay server error: $msg")
                    setState(State.FAILED, "Server error action: $msg")
                    return
                }
            }
        } catch (_: Exception) {
            // Not a JSON control message, treat as relayed payload
        }

        // Decrypt and forward to the existing message handler.
        // Try to decrypt first, but allow plaintext fallback for resilience
        val key = symmetricKey
        var processedMessage: String? = null

        if (key != null) {
            processedMessage = CryptoUtil.decryptMessage(text, key)
        }

        if (processedMessage == null) {
            // Decryption failed or no key available.
            // Check if the raw message looks like valid JSON (plaintext fallback).
            if (text.trim().startsWith("{")) {
                Log.w(TAG, "Decryption failed (or no key), falling back to plaintext processing")
                processedMessage = text
            } else {
                Log.e(TAG, "SECURITY: Decryption failed and message is not JSON. Dropping.")
                return
            }
        }

        appContext?.let { ctx ->
            onMessageReceived?.invoke(ctx, processedMessage!!)
        }
    }

    // Schedules a reconnect attempt with exponential backoff. Resets the backoff if the connection is successful. Does nothing if the disconnect was manual.
    private fun scheduleReconnect(relayUrl: String, pairingId: String, secret: String, sourceGeneration: Long) {
        if (isManuallyDisconnected.get()) return
        if (sourceGeneration != activeConnectionGeneration.get()) return

        val delayMs = minOf(
            (1L shl minOf(reconnectAttempt, 10)) * 1000L,
            maxReconnectDelay
        )
        reconnectAttempt++

        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
        setState(State.CONNECTING, "Backoff reconnect scheduled in ${delayMs}ms")

        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            if (!isManuallyDisconnected.get() && sourceGeneration == activeConnectionGeneration.get()) {
                connectInternal(relayUrl, pairingId, secret)
            }
        }
    }

    // Periodically asks relay for session health so UI can detect relay-zombie situations.
    private fun startStatusPolling() {
        statusQueryJob?.cancel()
        statusQueryJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRelayActive() && !isManuallyDisconnected.get()) {
                sendStatusQuery()
                delay(5_000)
            }
        }
    }

    private fun sendStatusQuery() {
        val ws = webSocket ?: return
        val msg = JSONObject().apply {
            put("action", "query_status")
        }.toString()
        try {
            ws.send(msg)
        } catch (e: Exception) {
            Log.d(TAG, "query_status send failed: ${e.message}")
        }
    }

    /**
     * SHA-256 hashes the raw secret so the plaintext never leaves the device.
     * The relay server only ever sees (and stores) this hash.
     */
    private fun hashSecret(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(raw.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Normalizes the relay URL: adds ws:// or wss:// prefix and /ws suffix.
     * Uses ws:// for localhost/private IPs, wss:// for remote domains.
     */
    private fun normalizeRelayUrl(raw: String): String {
        var url = raw.trim()

        // Extract host for private-IP detection
        val host: String = run {
            var h = url
            if (h.startsWith("wss://")) h = h.removePrefix("wss://")
            else if (h.startsWith("ws://")) h = h.removePrefix("ws://")
            h.split(":").firstOrNull()?.split("/")?.firstOrNull() ?: ""
        }

        val isPrivate = isPrivateHost(host)

        // If user explicitly provided ws://, only allow it for private/localhost hosts.
        // Upgrade to wss:// for public hosts to prevent cleartext transport over the internet.
        if (url.startsWith("ws://") && !url.startsWith("wss://") && !isPrivate) {
            Log.w(TAG, "SECURITY: Upgrading ws:// to wss:// for public host: $host")
            url = "wss://" + url.removePrefix("ws://")
        }

        // Add scheme if missing
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = if (isPrivate) "ws://$url" else "wss://$url"
        }

        // Add /ws path if missing
        if (!url.endsWith("/ws")) {
            url = if (url.endsWith("/")) "${url}ws" else "$url/ws"
        }

        return url
    }

    /** Returns true if the host is loopback or RFC 1918 private address. */
    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        // RFC 1918: only 172.16.0.0 – 172.31.255.255 (NOT all of 172.*)
        if (host.startsWith("172.")) {
            val second = host.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}
