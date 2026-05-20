package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val ips: Set<String>,
    val port: Int,
    val type: String, // "mac" or "android"
    val lastSeen: Long = System.currentTimeMillis()
) {
    //check if it has a local IP (non-Tailscale)
    fun hasLocalIp(): Boolean = ips.any { !it.startsWith("100.") }

    //check if it has a Tailscale IP
    fun hasTailscaleIp(): Boolean = ips.any { it.startsWith("100.") }

    // Best IP for connection
    fun getBestIp(): String = ips.find { !it.startsWith("100.") } ?: ips.firstOrNull() ?: ""
}

enum class DiscoveryMode {
    ACTIVE,  // Continuous broadcasting (Foreground)
    PASSIVE  // Listening only (Background)
}


object UDPDiscoveryManager {
    private const val TAG = "UDPDiscoveryManager"
    private const val BROADCAST_PORT = 8889
    private const val PRUNE_INTERVAL_MS = 10000L
    private const val DEVICE_TIMEOUT_MS = 25000L
    private const val PEER_EXCHANGE_INTERVAL_MS = 30000L  // Exchange peer info every 30s

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var socket: DatagramSocket? = null
    private var listeningJob: Job? = null
    private var broadcastJob: Job? = null
    private var pruningJob: Job? = null
    private var burstJob: Job? = null
    private var peerExchangeJob: Job? = null

    @Volatile
    private var isRunning = false
    @Volatile
    private var currentMode = DiscoveryMode.ACTIVE

    // We need to keep track if discovery was explicitly enabled/disabled by the user/system
    @Volatile
    private var isDiscoveryEnabled = true

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                multicastLock = wm.createMulticastLock("AirSync:DiscoveryLock")
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
                Log.d(TAG, "MulticastLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MulticastLock: ${e.message}")
        }
    }

    @Volatile
    private var lastKnownPeerIps: MutableMap<String, Set<String>> = mutableMapOf() // deviceId -> IPs

    fun start(context: Context, discoveryEnabled: Boolean = true) {
        isDiscoveryEnabled = discoveryEnabled
        if (isRunning) {
            updateBroadcastingState(context)
            return
        }

        isRunning = true
        
        Log.d(
            TAG,
            "Starting UDP Discovery Manager (Discovery: $isDiscoveryEnabled, Mode: $currentMode)"
        )

        acquireMulticastLock(context)
        startListening(context)
        updateBroadcastingState(context)
        startPruning(context)
        startPeerExchange(context)
    }

    fun setDiscoveryMode(context: Context, mode: DiscoveryMode) {
        if (currentMode == mode) return
        Log.d(TAG, "Changing discovery mode to: $mode")
        currentMode = mode
        updateBroadcastingState(context)
    }

    fun burstBroadcast(context: Context, durationMs: Long = 30000) {
        if (!isDiscoveryEnabled) {
            Log.d(TAG, "Discovery disabled, skipping burst broadcast")
            return
        }
        
        Log.d(TAG, "Starting burst broadcast for ${durationMs}ms")
        burstJob?.cancel()
        burstJob = CoroutineScope(Dispatchers.IO).launch {
            val endTime = System.currentTimeMillis() + durationMs
            while (isRunning && System.currentTimeMillis() < endTime) {
                broadcastPresence(context)
                delay(3000)
            }
            Log.d(TAG, "Burst broadcast finished")
        }
    }

    fun triggerPeerExchange(context: Context) {
        if (!isRunning) return
        Log.d(TAG, "Manually triggering peer exchange")
        CoroutineScope(Dispatchers.IO).launch {
            performPeerExchange(context)
        }
    }

    fun getLastKnownPeerIps(): Map<String, Set<String>> = lastKnownPeerIps.toMap()

    private fun updateBroadcastingState(context: Context) {
        broadcastJob?.cancel()

        if (!isDiscoveryEnabled) {
            Log.d(TAG, "Discovery broadcasting disabled completely")
            _discoveredDevices.value = emptyList()
            releaseMulticastLock()
            return
        }

        if (currentMode == DiscoveryMode.ACTIVE) {
            acquireMulticastLock(context)
            startBroadcasting(context)
        } else {
            Log.d(TAG, "Switched to PASSIVE discovery (listening only)")
            // In passive mode, we still need MulticastLock to hear others
            acquireMulticastLock(context)
        }
    }

    fun stop(context: Context? = null) {
        Log.d(TAG, "Stopping UDP Discovery Manager")
        if (context != null && isRunning && isDiscoveryEnabled && currentMode == DiscoveryMode.ACTIVE) {
            broadcastGoodbye(context)
        }
        isRunning = false
        isDiscoveryEnabled = false
        listeningJob?.cancel()
        broadcastJob?.cancel()
        pruningJob?.cancel()
        burstJob?.cancel()

        releaseMulticastLock()
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
        socket = null
        _discoveredDevices.value = emptyList()
    }

    fun setDiscoveryEnabled(context: Context, enabled: Boolean) {
        if (isDiscoveryEnabled == enabled) return
        Log.d(TAG, "Discovery enabled changed to: $enabled")
        isDiscoveryEnabled = enabled

        updateBroadcastingState(context)

        if (!enabled) {
            broadcastGoodbye(context)
            _discoveredDevices.value = emptyList()
        }
    }

    private fun startListening(context: Context) {
        val appContext = context.applicationContext
        listeningJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ensure socket is closed before creating new one
                socket?.close()
                socket = DatagramSocket(BROADCAST_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 0
                }

                val buffer = ByteArray(4096)
                while (isRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        val jsonString = String(packet.data, 0, packet.length)
                        handleIncomingTraffic(appContext, jsonString, packet.address.hostAddress)
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error receiving packet: ${e.message}")
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket creation failed: ${e.message}")
            }
        }
    }

    private fun handleIncomingTraffic(context: Context, message: String, sourceIp: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "RAW UDP MSG from $sourceIp: $message")
                var payload = message
                if (payload.startsWith("AIRSYNC_WAKEUP:")) {
                    payload = payload.substring("AIRSYNC_WAKEUP:".length)
                }
                val json = JSONObject(payload)
                val type = json.optString("type")

                when (type) {
                    "presence" -> {
                        val deviceType = json.optString("deviceType")
                        if (deviceType == "mac") {
                            handlePresenceMessage(context, json, sourceIp)

                            // Lazy-handshake: when in PASSIVE mode and we see a Mac presence beacon,
                            // respond with a single unicast so the Mac knows we're alive.
                            // This is battery-friendly (no loop, no timer) and is the primary
                            // mechanism that makes the Mac auto-discover the Android when the
                            // AirSync app is in the background.
                            if (currentMode == DiscoveryMode.PASSIVE && isDiscoveryEnabled) {
                                val macIp = sourceIp ?: run {
                                    val ipsArray = json.optJSONArray("ips")
                                    if (ipsArray != null && ipsArray.length() > 0) ipsArray.getString(0)
                                    else json.optString("ip").takeIf { it.isNotEmpty() }
                                }
                                if (macIp != null) {
                                    // Already in an IO coroutine, no need to launch another one for unicast
                                    sendPresenceUnicast(context, macIp)
                                    if (!WebSocketUtil.isConnected() && !WebSocketUtil.isConnecting()) {
                                        WebSocketUtil.requestAutoReconnect(context)
                                    }
                                }
                            }
                        }
                    }

                    "bye" -> {
                        val deviceType = json.optString("deviceType")
                        if (deviceType == "mac") {
                            val id = json.optString("id")
                            val currentList = _discoveredDevices.value.filter { it.id != id }
                            _discoveredDevices.value = currentList
                        }
                    }

                    "wakeUpRequest" -> {
                        // Handle wake-up logic shifted from WakeupService
                        val data = if (json.has("data")) json.getJSONObject("data") else json
                        val macIp = data.optString("macIP", data.optString("macIp", ""))
                        val macPort = data.optInt("macPort", 6996)
                        val macName = data.optString("macName", "Mac")

                        WakeupHandler.processWakeupRequest(context, macIp, macPort, macName)
                    }

                    "peerExchange" -> {
                        // Handle peer exchange for no-WiFi discovery
                        handlePeerExchange(context, json)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error handling incoming traffic: ${e.message}, message=$message")
            }
        }
    }

    private fun handlePresenceMessage(context: Context, json: JSONObject, sourceIp: String?) {
        try {
            val id = json.optString("id")
            val name = json.optString("name")
            val port = json.optInt("port", 6996)
            val deviceType = json.optString("deviceType")

            // Support both "ips" (array) and legacy "ip" (string)
            val incomingIps = mutableSetOf<String>()
            val ipsArray = json.optJSONArray("ips")
            if (ipsArray != null) {
                for (i in 0 until ipsArray.length()) {
                    incomingIps.add(ipsArray.getString(i))
                }
            } else {
                val singleIp = json.optString("ip")
                if (singleIp.isNotEmpty()) incomingIps.add(singleIp)
                else if (sourceIp != null) incomingIps.add(sourceIp)
            }

            // Fetch Expanded Networking Setting
            val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
            val expandNetworkingEnabled = runBlocking { ds.getExpandNetworkingEnabled().first() }

            val validIps = incomingIps.filter { ip ->
                if (ip.startsWith("100.")) {
                    if (expandNetworkingEnabled) return@filter true
                    val myIps = getAllIpAddresses()
                    myIps.any { it.startsWith("100.") }
                } else true
            }.toSet()

            if (validIps.isEmpty()) return

            val device = DiscoveredDevice(
                id = id,
                name = name,
                ips = validIps,
                port = port,
                type = deviceType,
                lastSeen = System.currentTimeMillis()
            )

            updateDeviceList(device, validIps)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing discovery message: ${e.message}")
        }
    }

    private fun updateDeviceList(device: DiscoveredDevice, newIps: Set<String>) {
        val currentList = _discoveredDevices.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == device.id }

        if (index != -1) {
            val existing = currentList[index]
            val updatedIps = existing.ips.toMutableSet()
            updatedIps.addAll(newIps)
            currentList[index] = existing.copy(
                ips = updatedIps,
                lastSeen = System.currentTimeMillis(),
                name = device.name
            )
        } else {
            currentList.add(device)
        }
        _discoveredDevices.value = currentList
    }

    private fun startBroadcasting(context: Context) {
        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Starting Active Broadcast Loop")
            while (isRunning && currentMode == DiscoveryMode.ACTIVE) {
                broadcastPresence(context)
                delay(10000)
            }
        }
    }

    private fun broadcastPresence(context: Context) {
        if (!isDiscoveryEnabled) return
        
        val allIps = getAllIpAddresses()
        if (allIps.isEmpty()) {
            return
        }

        // Fetch User-Configured Device Name
        val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
        val customName = try {
            runBlocking { ds.getDeviceName().first() }
        } catch (e: Exception) {
            ""
        }

        val deviceName = if (customName.isNotBlank()) customName else android.os.Build.MODEL

        val knownTargetIps = try {
            val connections = runBlocking {
                ds.getAllNetworkDeviceConnections().first()
            }
            // Extract all known IPs from all network connections
            connections.flatMap { connection ->
                connection.networkConnections.values
            }.toSet()
        } catch (e: Exception) {
            emptySet<String>()
        }

        val expandNetworkingEnabled = try {
            runBlocking { ds.getExpandNetworkingEnabled().first() }
        } catch (e: Exception) {
            true
        }

        // Filter out Tailscale IPs if Expanded Networking is disabled
        val filteredLocalIps =
            if (expandNetworkingEnabled) allIps else allIps.filter { !it.startsWith("100.") }
        if (filteredLocalIps.isEmpty()) return

        val deviceId = DeviceInfoUtil.getDeviceId(context)

        val json = JSONObject()
        json.put("type", "presence")
        json.put("deviceType", "android")
        json.put("id", deviceId)
        json.put("name", deviceName)
        json.put("ips", FilteredIpArray(filteredLocalIps)) // Send FILTERED IPs
        val payload = json.toString()
        val data = payload.toByteArray()

        for (bindIp in filteredLocalIps) {
            // 1. Send Broadcast (Local Network)
            try {
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName("255.255.255.255"),
                    BROADCAST_PORT
                )
                DatagramSocket(0, InetAddress.getByName(bindIp)).use { sender ->
                    sender.broadcast = true
                    sender.send(packet)
                }
            } catch (e: Exception) {
                // Log.e(TAG, "Failed broadcast from $bindIp: ${e.message}")
            }
        }

        // 2. Send Unicast (Remote/VPN)
        if (knownTargetIps.isNotEmpty()) {
            for (targetIp in knownTargetIps) {
                if (allIps.contains(targetIp)) continue

                // If Expanded Networking is disabled, don't ping Tailscale targets
                if (!expandNetworkingEnabled && targetIp.startsWith("100.")) continue

                sendUnicast(targetIp, payload)
            }
        }
    }

    private fun broadcastGoodbye(context: Context) {
        if (!isDiscoveryEnabled) return
        
        val allIps = getAllIpAddresses()
        if (allIps.isEmpty()) return

        com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
        val deviceId = DeviceInfoUtil.getDeviceId(context)

        val json = JSONObject()
        json.put("type", "bye")
        json.put("deviceType", "android")
        json.put("id", deviceId)
        val payload = json.toString()
        val data = payload.toByteArray()

        CoroutineScope(Dispatchers.IO).launch {
            repeat(3) {
                for (bindIp in allIps) {
                    try {
                        val packet = DatagramPacket(
                            data,
                            data.size,
                            InetAddress.getByName("255.255.255.255"),
                            BROADCAST_PORT
                        )
                        DatagramSocket(0, InetAddress.getByName(bindIp)).use { sender ->
                            sender.broadcast = true
                            sender.send(packet)
                        }
                    } catch (e: Exception) {
                    }
                }
                delay(100)
            }
        }
    }

    private fun sendUnicast(targetIp: String, message: String) {
        try {
            val data = message.toByteArray()
            val packet = DatagramPacket(
                data,
                data.size,
                InetAddress.getByName(targetIp),
                BROADCAST_PORT
            )

            // Let OS route the unicast packet
            DatagramSocket().use { sender ->
                sender.send(packet)
            }
        } catch (e: Exception) {
            // Log.d(TAG, "Unicast failed to $targetIp: ${e.message}")
        }
    }

    /**
     * Send a single unicast presence packet to a specific IP (e.g., the Mac that just pinged us).
     * Used in PASSIVE mode as a lazy-handshake so the Mac becomes aware we are reachable.
     */
    private fun sendPresenceUnicast(context: Context, targetIp: String) {
        try {
            val allIps = getAllIpAddresses()
            if (allIps.isEmpty()) return

            val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
            val customName = try {
                runBlocking { ds.getDeviceName().first() }
            } catch (e: Exception) { "" }

            val deviceName = if (customName.isNotBlank()) customName else android.os.Build.MODEL
            val expandNetworkingEnabled = try {
                runBlocking { ds.getExpandNetworkingEnabled().first() }
            } catch (e: Exception) { false }

            val filteredIps = if (expandNetworkingEnabled) allIps else allIps.filter { !it.startsWith("100.") }
            if (filteredIps.isEmpty()) return

            val deviceId = DeviceInfoUtil.getDeviceId(context)
            val json = JSONObject()
            json.put("type", "presence")
            json.put("deviceType", "android")
            json.put("id", deviceId)
            json.put("name", deviceName)
            json.put("ips", FilteredIpArray(filteredIps))
            val payload = json.toString()

            sendUnicast(targetIp, payload)
            Log.d(TAG, "Lazy-handshake: sent presence unicast to $targetIp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send presence unicast: ${e.message}")
        }
    }

    private fun startPeerExchange(context: Context) {
        peerExchangeJob?.cancel()
        peerExchangeJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                delay(PEER_EXCHANGE_INTERVAL_MS)
                performPeerExchange(context)
            }
        }
    }

    private fun performPeerExchange(context: Context) {
        val allIps = getAllIpAddresses()
        if (allIps.isEmpty()) return

        val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
        val expandNetworkingEnabled = try {
            runBlocking { ds.getExpandNetworkingEnabled().first() }
        } catch (e: Exception) { true }

        val customName = try {
            runBlocking { ds.getDeviceName().first() }
        } catch (e: Exception) { "" }
        val deviceName = if (customName.isNotBlank()) customName else android.os.Build.MODEL
        val deviceId = DeviceInfoUtil.getDeviceId(context)

        val json = JSONObject()
        json.put("type", "peerExchange")
        json.put("deviceType", "android")
        json.put("id", deviceId)
        json.put("name", deviceName)
        
        // Include all IPs based on settings
        val ipsToSend = if (expandNetworkingEnabled) allIps else allIps.filter { !it.startsWith("100.") }
        json.put("ips", FilteredIpArray(ipsToSend))
        
        // Include known peer IPs from stored connections (for no-WiFi scenarios)
        val knownPeers = mutableMapOf<String, List<String>>()
        try {
            val connections = runBlocking { ds.getAllNetworkDeviceConnections().first() }
            for (conn in connections) {
                val peerIps = conn.networkConnections.values.toList()
                if (peerIps.isNotEmpty()) {
                    knownPeers[conn.deviceName] = peerIps
                }
            }
        } catch (e: Exception) { }
        
        // Build JSON object for knownPeers manually
        val knownPeersJson = org.json.JSONObject()
        for ((name, ips) in knownPeers) {
            val ipsArray = org.json.JSONArray()
            for (ip in ips) {
                ipsArray.put(ip)
            }
            knownPeersJson.put(name, ipsArray)
        }
        json.put("knownPeers", knownPeersJson)

        val payload = json.toString()

        // Send to all known peer IPs (even without WiFi, we can reach Tailscale peers)
        val allKnownTargetIps = mutableSetOf<String>()
        try {
            val connections = runBlocking { ds.getAllNetworkDeviceConnections().first() }
            for (conn in connections) {
                allKnownTargetIps.addAll(conn.networkConnections.values)
            }
        } catch (e: Exception) { }

        // Store our IPs for peers to discover
        lastKnownPeerIps[deviceId] = ipsToSend.toSet()

        // Broadcast our presence to all known peers
        for (targetIp in allKnownTargetIps) {
            // Skip if it's our own IP
            if (ipsToSend.contains(targetIp)) continue
            
            // If Expanded Networking is disabled, don't ping Tailscale targets
            if (!expandNetworkingEnabled && targetIp.startsWith("100.")) continue
            
            sendUnicast(targetIp, payload)
        }

        Log.d(TAG, "Peer exchange completed, knownPeers=${knownPeers.size}")
    }

    private fun handlePeerExchange(context: Context, json: JSONObject) {
        try {
            val id = json.optString("id")
            val name = json.optString("name")
            val ipsArray = json.optJSONArray("ips")
            val port = json.optInt("port", 0)
            val deviceType = json.optString("deviceType")
            val knownPeers = json.optJSONObject("knownPeers")

            if (id.isEmpty() || name.isEmpty() || ipsArray == null) return

            val ips = mutableSetOf<String>()
            for (i in 0 until ipsArray.length()) {
                ips.add(ipsArray.getString(i))
            }

            val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
            val expandNetworkingEnabled = try {
                runBlocking { ds.getExpandNetworkingEnabled().first() }
            } catch (e: Exception) { true }

            // Validate IPs
            val validIps = ips.filter { ip ->
                if (ip.startsWith("100.")) {
                    expandNetworkingEnabled || DeviceInfoUtil.getNetworkStatus(context).hasVpn
                } else true
            }.toSet()

            if (validIps.isEmpty()) return

            // Update peer knowledge for future connections
            lastKnownPeerIps[id] = validIps

            // Also learn about peer's known peers (recursive discovery)
            if (knownPeers != null && expandNetworkingEnabled) {
                handleKnownPeersFromPeer(context, knownPeers)
            }

            val device = DiscoveredDevice(
                id = id,
                name = name,
                ips = validIps,
                port = port,
                type = deviceType,
                lastSeen = System.currentTimeMillis()
            )
            updateDeviceList(device, validIps)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling peer exchange: ${e.message}")
        }
    }

    private fun handleKnownPeersFromPeer(context: Context, knownPeers: org.json.JSONObject) {
        // When a peer sends us their known peers, store them for potential future connection
        // This helps discover devices even when we're not on the same network
        try {
            val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
            
            knownPeers.keys().forEach { deviceName ->
                val peerIps = knownPeers.getJSONArray(deviceName)
                val ips = mutableSetOf<String>()
                for (i in 0 until peerIps.length()) {
                    ips.add(peerIps.getString(i))
                }
                
                // Store these IPs as potential connection targets
                // This allows us to reach peers even if we can't broadcast
                Log.d(TAG, "Learned peer $deviceName with IPs: $ips from peer exchange")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling known peers: ${e.message}")
        }
    }

    private fun FilteredIpArray(ips: List<String>): org.json.JSONArray {
        val array = org.json.JSONArray()
        ips.forEach { array.put(it) }
        return array
    }

    fun getAllIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val name = networkInterface.name.lowercase()
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp") || name.contains(
                        "ppp"
                    )
                ) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address) {
                        ips.add(address.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting network interfaces: ${e.message}")
        }
        return ips
    }

    private fun startPruning(context: Context) {
        pruningJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                delay(PRUNE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val active =
                    _discoveredDevices.value.filter { now - it.lastSeen < DEVICE_TIMEOUT_MS }
                if (active.size != _discoveredDevices.value.size) {
                    _discoveredDevices.value = active
                }

                if (active.isNotEmpty() && !WebSocketUtil.isConnected() && !WebSocketUtil.isConnecting()) {
                    WebSocketUtil.requestAutoReconnect(context)
                }
            }
        }
    }
}
