package com.sameerasw.airsync.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.DeviceInfoUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.*
import java.net.*

/**
 * Service that runs a lightweight HTTP server and UDP listener
 * to receive wake-up requests from Mac clients for initiating reconnection
 */
class WakeupService : Service() {
    companion object {
        private const val TAG = "WakeupService"
        private const val HTTP_PORT = 8888 // HTTP server port
        private const val UDP_PORT = 8889   // UDP listener port
        private const val WAKEUP_ENDPOINT = "/wakeup"
        
        fun startService(context: Context) {
            val intent = Intent(context, WakeupService::class.java)
            context.startService(intent)
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, WakeupService::class.java)
            context.stopService(intent)
        }
    }

    private var httpServerSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WakeupService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            startWakeupListeners()
        }
        return START_STICKY // Restart if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWakeupListeners()
        serviceScope.cancel()
        Log.d(TAG, "WakeupService destroyed")
    }

    private fun startWakeupListeners() {
        serviceScope.launch {
            try {
                isRunning = true
                
                // Start HTTP server
                startHttpServer()
                
                // Start UDP listener
                startUdpListener()
                
                Log.i(TAG, "Wake-up listeners started (HTTP: $HTTP_PORT, UDP: $UDP_PORT)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start wake-up listeners", e)
            }
        }
    }

    private fun stopWakeupListeners() {
        isRunning = false
        
        // Stop HTTP server
        try {
            httpServerSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing HTTP server socket", e)
        }
        httpServerSocket = null
        
        // Stop UDP listener
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP socket", e)
        }
        udpSocket = null
        
        Log.i(TAG, "Wake-up listeners stopped")
    }

    private suspend fun startHttpServer() {
        withContext(Dispatchers.IO) {
            try {
                httpServerSocket = ServerSocket(HTTP_PORT)
                
                serviceScope.launch {
                    while (isRunning && httpServerSocket?.isClosed == false) {
                        try {
                            val clientSocket = httpServerSocket?.accept()
                            if (clientSocket != null) {
                                // Handle client connection in a separate coroutine
                                launch {
                                    handleHttpRequest(clientSocket)
                                }
                            }
                        } catch (e: Exception) {
                            if (isRunning) {
                                Log.e(TAG, "Error accepting HTTP connection", e)
                            }
                        }
                    }
                }
                
                Log.d(TAG, "HTTP server started on port $HTTP_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
            }
        }
    }

    private suspend fun handleHttpRequest(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                clientSocket.use { socket ->
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val output = PrintWriter(socket.getOutputStream(), true)

                    // Read HTTP request line
                    val requestLine = input.readLine()
                    if (requestLine == null) return@withContext

                    val parts = requestLine.split(" ")
                    if (parts.size < 3) return@withContext

                    val method = parts[0]
                    val path = parts[1]

                    // Read headers to find Content-Length
                    var contentLength = 0
                    var line: String?
                    while (input.readLine().also { line = it } != null) {
                        if (line!!.isEmpty()) break // End of headers
                        if (line!!.lowercase().startsWith("content-length:")) {
                            contentLength = line!!.substring(15).trim().toIntOrNull() ?: 0
                        }
                    }

                    // Handle wake-up request
                    if (method == "POST" && path == WAKEUP_ENDPOINT) {
                        // Read request body
                        val body = if (contentLength > 0) {
                            val bodyChars = CharArray(contentLength)
                            input.read(bodyChars, 0, contentLength)
                            String(bodyChars)
                        } else {
                            ""
                        }

                        Log.d(TAG, "Received HTTP wake-up request: $body")

                        // Parse the wake-up request
                        try {
                            val jsonRequest = JSONObject(body)
                            
                            // Handle nested JSON structure from Mac
                            val macIp: String
                            val macPort: Int
                            val macName: String
                            
                            if (jsonRequest.has("data")) {
                                // Mac sends nested format: {"type": "wakeUpRequest", "data": {...}}
                                val data = jsonRequest.getJSONObject("data")
                                macIp = data.optString("macIP", "") // Note: Mac uses "macIP" not "macIp"
                                macPort = data.optInt("macPort", 6996)
                                macName = data.optString("macName", "Mac")
                            } else {
                                // Fallback to flat structure
                                macIp = jsonRequest.optString("macIp", "")
                                macPort = jsonRequest.optInt("macPort", 6996)
                                macName = jsonRequest.optString("macName", "Mac")
                            }

                            // Send success response
                            val response = """{"status": "success", "message": "Wake-up request received"}"""
                            sendHttpResponse(output, 200, "OK", response)

                            // Process the wake-up request
                            processWakeupRequest(macIp, macPort, macName)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing wake-up request", e)
                            val response = """{"status": "error", "message": "Invalid JSON"}"""
                            sendHttpResponse(output, 400, "Bad Request", response)
                        }
                    } else if (method == "OPTIONS") {
                        // Handle CORS preflight
                        sendCorsResponse(output)
                    } else {
                        // Method not allowed or path not found
                        val response = """{"status": "error", "message": "Method not allowed or path not found"}"""
                        sendHttpResponse(output, 405, "Method Not Allowed", response)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling HTTP request", e)
            }
        }
    }

    private fun sendHttpResponse(output: PrintWriter, statusCode: Int, statusText: String, body: String) {
        output.println("HTTP/1.1 $statusCode $statusText")
        output.println("Content-Type: application/json")
        output.println("Access-Control-Allow-Origin: *")
        output.println("Access-Control-Allow-Methods: POST, OPTIONS")
        output.println("Access-Control-Allow-Headers: Content-Type")
        output.println("Content-Length: ${body.length}")
        output.println() // Empty line to end headers
        output.print(body)
        output.flush()
    }

    private fun sendCorsResponse(output: PrintWriter) {
        output.println("HTTP/1.1 200 OK")
        output.println("Access-Control-Allow-Origin: *")
        output.println("Access-Control-Allow-Methods: POST, OPTIONS")
        output.println("Access-Control-Allow-Headers: Content-Type")
        output.println("Content-Length: 0")
        output.println() // Empty line to end headers
        output.flush()
    }

    private suspend fun startUdpListener() {
        withContext(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(UDP_PORT)
                
                serviceScope.launch {
                    val buffer = ByteArray(1024)
                    
                    while (isRunning && udpSocket?.isClosed == false) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            udpSocket?.receive(packet)
                            
                            val message = String(packet.data, 0, packet.length)
                            Log.d(TAG, "Received UDP wake-up message: $message")
                            
                            // Parse UDP message (expecting JSON similar to HTTP)
                            try {
                                val jsonRequest = JSONObject(message)
                                
                                // Handle nested JSON structure from Mac
                                val macIp: String
                                val macPort: Int
                                val macName: String
                                
                                if (jsonRequest.has("data")) {
                                    // Mac sends nested format: {"type": "wakeUpRequest", "data": {...}}
                                    val data = jsonRequest.getJSONObject("data")
                                    macIp = data.optString("macIP", "") // Note: Mac uses "macIP" not "macIp"
                                    macPort = data.optInt("macPort", 6996)
                                    macName = data.optString("macName", "Mac")
                                } else {
                                    // Fallback to flat structure
                                    macIp = jsonRequest.optString("macIp", "")
                                    macPort = jsonRequest.optInt("macPort", 6996)
                                    macName = jsonRequest.optString("macName", "Mac")
                                }
                                
                                processWakeupRequest(macIp, macPort, macName)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse UDP wake-up message", e)
                            }
                        } catch (e: Exception) {
                            if (isRunning) {
                                Log.e(TAG, "Error in UDP listener", e)
                            }
                        }
                    }
                }
                
                Log.d(TAG, "UDP listener started on port $UDP_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start UDP listener", e)
            }
        }
    }

    private suspend fun processWakeupRequest(macIp: String, macPort: Int, macName: String) {
        try {
            Log.i(TAG, "📞 Received wake-up request from $macName at $macIp:$macPort")

            // Validate that we have the necessary information
            if (macIp.isEmpty()) {
                Log.w(TAG, "Wake-up request missing Mac IP address")
                return
            }

            val dataStoreManager = DataStoreManager(this@WakeupService)

            // Check if we already have a connection
            if (WebSocketUtil.isConnected()) {
                Log.d(TAG, "Already connected, sending acknowledgment")
                return
            }

            // Clear manual disconnect flag since this is an external wake-up request
            dataStoreManager.setUserManuallyDisconnected(false)

            // Look up stored encryption key from previous connections
            val encryptionKey = findStoredEncryptionKey(dataStoreManager, macIp, macPort, macName)
            
            if (encryptionKey == null) {
                Log.w(TAG, "No stored encryption key found for $macName at $macIp:$macPort")
                return
            }
            
            Log.d(TAG, "Found stored encryption key for $macName")

            // Update device information and last connected timestamp
            val ourIp = DeviceInfoUtil.getWifiIpAddress(this@WakeupService)
            if (ourIp != null) {
                // Update the network device connection with current timestamp
                dataStoreManager.saveNetworkDeviceConnection(
                    deviceName = macName,
                    ourIp = ourIp,
                    clientIp = macIp,
                    port = macPort.toString(),
                    isPlus = true, // Assume Plus features for wake-up capability
                    symmetricKey = encryptionKey,
                    model = "Mac",
                    deviceType = "desktop"
                )
                
                // Update last connected device
                val connectedDevice = com.sameerasw.airsync.domain.model.ConnectedDevice(
                    name = macName,
                    ipAddress = macIp,
                    port = macPort.toString(),
                    lastConnected = System.currentTimeMillis(),
                    isPlus = true,
                    symmetricKey = encryptionKey,
                    model = "Mac",
                    deviceType = "desktop"
                )
                dataStoreManager.saveLastConnectedDevice(connectedDevice)
            }

            // Attempt to connect to the Mac - NO CHECKING, JUST CONNECT
            Log.d(TAG, "🚀 Connecting to Mac at $macIp:$macPort (no pre-check)")
            
            WebSocketUtil.connect(
                context = this@WakeupService,
                ipAddress = macIp,
                port = macPort,
                symmetricKey = encryptionKey,
                manualAttempt = false, // This is an automated response to wake-up
                onConnectionStatus = { connected ->
                    if (connected) {
                        Log.i(TAG, "✅ Successfully connected to Mac after wake-up request")
                        // Update last connected timestamp
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                dataStoreManager.updateNetworkDeviceLastConnected(macName, System.currentTimeMillis())
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to update last connected timestamp", e)
                            }
                        }
                    } else {
                        Log.w(TAG, "❌ Failed to connect to Mac after wake-up request")
                    }
                },
                onHandshakeTimeout = {
                    Log.w(TAG, "⏱️ Handshake timeout during wake-up connection attempt")
                    WebSocketUtil.disconnect(this@WakeupService)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error processing wake-up request", e)
        }
    }

    /**
     * Find stored encryption key for the given Mac device
     * Searches both network device connections and last connected device
     */
    private suspend fun findStoredEncryptionKey(
        dataStoreManager: DataStoreManager,
        macIp: String,
        macPort: Int,
        macName: String
    ): String? {
        try {
            // First, try to find by exact device name match
            val networkDevices = dataStoreManager.getAllNetworkDeviceConnections().first()
            val ourIp = DeviceInfoUtil.getWifiIpAddress(this@WakeupService)
            
            if (ourIp != null) {
                // Look for network-aware device connection
                val networkDevice = networkDevices.firstOrNull { device ->
                    device.deviceName == macName && device.getClientIpForNetwork(ourIp) == macIp
                }
                
                if (networkDevice?.symmetricKey != null) {
                    Log.d(TAG, "Found encryption key from network device connection")
                    return networkDevice.symmetricKey
                }
            }
            
            // Fallback: Check last connected device
            val lastConnectedDevice = dataStoreManager.getLastConnectedDevice().first()
            if (lastConnectedDevice?.name == macName && 
                lastConnectedDevice.ipAddress == macIp &&
                lastConnectedDevice.symmetricKey != null) {
                Log.d(TAG, "Found encryption key from last connected device")
                return lastConnectedDevice.symmetricKey
            }
            
            // Additional fallback: Look for any device with matching name (in case IP changed)
            val deviceByName = networkDevices.firstOrNull { it.deviceName == macName }
            if (deviceByName?.symmetricKey != null) {
                Log.d(TAG, "Found encryption key by device name (IP may have changed)")
                return deviceByName.symmetricKey
            }
            
            Log.w(TAG, "No stored encryption key found for $macName")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error finding stored encryption key", e)
            return null
        }
    }
}