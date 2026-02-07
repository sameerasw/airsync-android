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
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

object UDPDiscoveryManager {
    private const val TAG = "UDPDiscoveryManager"
    private const val BROADCAST_PORT = 8889
    private const val PRUNE_INTERVAL_MS = 10000L
    private const val DEVICE_TIMEOUT_MS = 25000L

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var socket: DatagramSocket? = null
    private var listeningJob: Job? = null
    private var broadcastJob: Job? = null
    private var pruningJob: Job? = null
    @Volatile private var isRunning = false
    @Volatile private var isDiscoveryEnabled = true

    fun start(context: Context, discoveryEnabled: Boolean = true) {
        isDiscoveryEnabled = discoveryEnabled
        if (isRunning) {
            updateBroadcastingState(context)
            return
        }
        
        isRunning = true
        Log.d(TAG, "Starting UDP Discovery Manager (Discovery: $isDiscoveryEnabled)")

        startListening(context)
        updateBroadcastingState(context)
        startPruning()
    }

    private fun updateBroadcastingState(context: Context) {
        broadcastJob?.cancel()
        if (isDiscoveryEnabled) {
            startBroadcasting(context)
        } else {
            Log.d(TAG, "Discovery broadcasting disabled")
            _discoveredDevices.value = emptyList()
        }
    }

    fun stop(context: Context? = null) {
        Log.d(TAG, "Stopping UDP Discovery Manager")
        if (context != null && isRunning && isDiscoveryEnabled) {
            broadcastGoodbye(context)
        }
        isRunning = false
        isDiscoveryEnabled = false
        listeningJob?.cancel()
        broadcastJob?.cancel()
        pruningJob?.cancel()
        
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
        
        if (enabled) {
            // If discovery is being enabled, start broadcasting
            startBroadcasting(context)
        } else {
            // If discovery is being disabled
            broadcastGoodbye(context)
            broadcastJob?.cancel()
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
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            
            when (type) {
                "presence" -> {
                    val deviceType = json.optString("deviceType")
                    if (deviceType == "mac") {
                        handlePresenceMessage(context, json, sourceIp)
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
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        WakeupHandler.processWakeupRequest(context, macIp, macPort, macName)
                    }
                }
            }
        } catch (e: Exception) {
            
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
            while (isRunning) {
                broadcastPresence(context)
                delay(10000)
            }
        }
    }

    private fun broadcastPresence(context: Context) {
        val allIps = getAllIpAddresses()
        if (allIps.isEmpty()) {
            return
        }

        // Fetch User-Configured Device Name
        val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
        val customName = try {
            runBlocking { ds.getDeviceName().first() }
        } catch (e: Exception) { "" }
        
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
        } catch (e: Exception) { true }

        // Filter out Tailscale IPs if Expanded Networking is disabled
        val filteredLocalIps = if (expandNetworkingEnabled) allIps else allIps.filter { !it.startsWith("100.") }
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
        val allIps = getAllIpAddresses()
        if (allIps.isEmpty()) return

        val ds = com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context)
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
                    } catch (e: Exception) {}
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
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp") || name.contains("ppp")) {
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

    private fun startPruning() {
        pruningJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                delay(PRUNE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val active = _discoveredDevices.value.filter { now - it.lastSeen < DEVICE_TIMEOUT_MS }
                if (active.size != _discoveredDevices.value.size) {
                    _discoveredDevices.value = active
                }
            }
        }
    }
}
