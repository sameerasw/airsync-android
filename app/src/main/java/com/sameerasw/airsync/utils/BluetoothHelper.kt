package com.sameerasw.airsync.utils

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced Bluetooth Low Energy helper for AirSync
 * Provides:
 * - BLE advertising for device discovery
 * - BLE scanning to find nearby AirSync devices
 * - GATT server/client for bidirectional data exchange
 * - Connection management with auto-reconnect
 * - Chunked data transfer for large payloads
 * 
 * Inspired by LocalSend's discovery protocol
 */
class BluetoothHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothHelper"
        
        // AirSync service UUID - must match Mac side
        val AIRSYNC_SERVICE_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567890")
        
        // Characteristics
        val DEVICE_INFO_CHAR_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567891")
        val DATA_TRANSFER_CHAR_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567892")
        val COMMAND_CHAR_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567893")
        val NOTIFICATION_CHAR_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567894")
        
        // Client Characteristic Configuration Descriptor
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
        // Data transfer constants
        const val MAX_MTU = 512
        const val DEFAULT_MTU = 23
        const val CHUNK_SIZE = 500 // Leave room for headers
        const val SCAN_TIMEOUT_MS = 30000L
        const val RECONNECT_DELAY_MS = 5000L
        const val MAX_RECONNECT_ATTEMPTS = 3
    }
    
    // State
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false
    private var isScanning = false
    private var currentMtu = DEFAULT_MTU
    
    // Connection management
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val deviceGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private var reconnectJob: Job? = null
    
    // Discovered devices
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices
    
    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // Data transfer state
    private val pendingTransfers = ConcurrentHashMap<String, DataTransfer>()
    private val receivedChunks = ConcurrentHashMap<String, MutableList<ByteArray>>()
    
    // Callbacks
    var onDeviceDiscovered: ((DiscoveredDevice) -> Unit)? = null
    var onDeviceConnected: ((BluetoothDevice) -> Unit)? = null
    var onDeviceDisconnected: ((BluetoothDevice) -> Unit)? = null
    var onServicesDiscovered: ((BluetoothDevice) -> Unit)? = null  // Called when GATT services are ready
    var onDataReceived: ((String, ByteArray) -> Unit)? = null
    var onCommandReceived: ((String, JSONObject) -> Unit)? = null
    var onTransferProgress: ((String, Int, Int) -> Unit)? = null
    var onTransferComplete: ((String, ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Data classes
     */
    data class DiscoveredDevice(
        val device: BluetoothDevice,
        val name: String,
        val rssi: Int,
        val deviceInfo: DeviceInfo?,
        val lastSeen: Long = System.currentTimeMillis()
    )
    
    data class DeviceInfo(
        val alias: String,
        val version: String,
        val deviceModel: String?,
        val deviceType: String,
        val port: Int,
        val protocol: String
    )
    
    data class DataTransfer(
        val id: String,
        val totalSize: Int,
        val totalChunks: Int,
        var receivedChunks: Int = 0,
        val data: MutableList<ByteArray> = mutableListOf()
    )
    
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    /**
     * Initialize Bluetooth
     */
    fun initialize(): Boolean {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device")
            return false
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Bluetooth permissions not granted")
            return false
        }
        
        if (!bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        
        if (bluetoothLeAdvertiser == null) {
            Log.w(TAG, "BLE advertising not supported")
        }
        
        if (bluetoothLeScanner == null) {
            Log.w(TAG, "BLE scanning not supported")
        }
        
        Log.d(TAG, "✅ Bluetooth initialized successfully")
        return true
    }
    
    /**
     * Check if Bluetooth permissions are granted
     */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get required permissions based on Android version
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    
    // ==================== ADVERTISING ====================
    
    /**
     * Start BLE advertising so other devices can discover this device
     * Note: BLE advertising has a strict 31-byte limit per packet
     */
    fun startAdvertising(deviceInfo: DeviceInfo? = null) {
        if (isAdvertising) {
            Log.d(TAG, "Already advertising")
            return
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Cannot advertise - permissions not granted")
            onError?.invoke("Bluetooth permissions not granted")
            return
        }
        
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BLE advertising not supported")
            onError?.invoke("BLE advertising not supported on this device")
            return
        }
        
        try {
            // Set up GATT server first
            setupGattServer(deviceInfo)
            
            // Configure advertising settings for optimal discovery
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0) // Advertise indefinitely
                .build()
            
            // Configure advertising data - MINIMAL to stay under 31 bytes
            // Service UUID (16 bytes) + flags (3 bytes) = 19 bytes, leaving room for overhead
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false) // Don't include name - it's too long
                .setIncludeTxPowerLevel(false) // Save space
                .addServiceUuid(ParcelUuid(AIRSYNC_SERVICE_UUID))
                .build()
            
            // Scan response can include device name (separate 31-byte packet)
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true) // Name goes in scan response
                .build()
            
            // Start advertising
            bluetoothLeAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            
            Log.d(TAG, "📡 Starting BLE advertising...")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting advertising", e)
            onError?.invoke("Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting advertising", e)
            onError?.invoke("Error starting advertising: ${e.message}")
        }
    }
    
    /**
     * Stop BLE advertising
     */
    fun stopAdvertising() {
        if (!isAdvertising) return
        
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Log.d(TAG, "🛑 Stopped BLE advertising")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping advertising", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        }
    }
    
    // ==================== SCANNING ====================
    
    /**
     * Start scanning for nearby AirSync devices
     */
    fun startScanning(timeoutMs: Long = SCAN_TIMEOUT_MS) {
        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Cannot scan - permissions not granted")
            onError?.invoke("Bluetooth permissions not granted")
            return
        }
        
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BLE scanning not supported")
            onError?.invoke("BLE scanning not supported on this device")
            return
        }
        
        try {
            // Clear previous discoveries
            _discoveredDevices.value = emptyList()
            
            // Configure scan settings for aggressive discovery
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            
            // Scan with AirSync service filter ONLY
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(AIRSYNC_SERVICE_UUID))
                    .build()
            )
            
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
            _connectionState.value = ConnectionState.Disconnected
            
            Log.d(TAG, "🔍 Started BLE scanning for AirSync devices only...")
            
            // Auto-stop after timeout
            mainHandler.postDelayed({
                if (isScanning) {
                    stopScanning()
                    Log.d(TAG, "⏱️ Scan timeout reached")
                }
            }, timeoutMs)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting scan", e)
            onError?.invoke("Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            onError?.invoke("Error starting scan: ${e.message}")
        }
    }
    
    /**
     * Start scanning without any filters (finds all BLE devices) - for debugging only
     */
    fun startScanningAll(timeoutMs: Long = SCAN_TIMEOUT_MS) {
        // Just call the regular scan - it now filters in the callback
        startScanning(timeoutMs)
    }
    
    /**
     * Stop scanning
     */
    fun stopScanning() {
        if (!isScanning) return
        
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "🛑 Stopped BLE scanning")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping scan", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }
    
    // ==================== CONNECTION ====================
    
    /**
     * Connect to a discovered device
     */
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Cannot connect - permissions not granted")
            onError?.invoke("Bluetooth permissions not granted")
            return
        }
        
        val address = device.address
        if (connectedDevices.containsKey(address)) {
            Log.d(TAG, "Already connected to ${device.name}")
            return
        }
        
        try {
            _connectionState.value = ConnectionState.Connecting
            Log.d(TAG, "🔗 Connecting to ${device.name}...")
            
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            deviceGatts[address] = gatt
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting to device", e)
            _connectionState.value = ConnectionState.Error("Security exception: ${e.message}")
            onError?.invoke("Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            _connectionState.value = ConnectionState.Error("Error: ${e.message}")
            onError?.invoke("Error connecting: ${e.message}")
        }
    }
    
    /**
     * Disconnect from a device
     */
    fun disconnectFromDevice(device: BluetoothDevice) {
        val address = device.address
        try {
            deviceGatts[address]?.let { gatt ->
                gatt.disconnect()
                gatt.close()
            }
            deviceGatts.remove(address)
            connectedDevices.remove(address)
            reconnectAttempts.remove(address)
            
            Log.d(TAG, "🔌 Disconnected from ${device.name}")
            
            if (connectedDevices.isEmpty()) {
                _connectionState.value = ConnectionState.Disconnected
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception disconnecting", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }
    
    /**
     * Disconnect from all devices
     */
    fun disconnectAll() {
        connectedDevices.values.forEach { device ->
            disconnectFromDevice(device)
        }
        reconnectJob?.cancel()
    }

    
    // ==================== DATA TRANSFER ====================
    
    /**
     * Send data to a connected device
     * Automatically chunks large data
     */
    fun sendData(device: BluetoothDevice, data: ByteArray, transferId: String = UUID.randomUUID().toString()) {
        val address = device.address
        val gatt = deviceGatts[address]
        
        if (gatt == null) {
            Log.e(TAG, "Not connected to ${device.name}")
            onError?.invoke("Not connected to device")
            return
        }
        
        try {
            val service = gatt.getService(AIRSYNC_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(DATA_TRANSFER_CHAR_UUID)
            
            if (characteristic == null) {
                Log.e(TAG, "Data transfer characteristic not found")
                onError?.invoke("Data transfer characteristic not found")
                return
            }
            
            // Calculate chunks
            val chunkSize = minOf(CHUNK_SIZE, currentMtu - 3)
            val totalChunks = (data.size + chunkSize - 1) / chunkSize
            
            Log.d(TAG, "📤 Sending ${data.size} bytes in $totalChunks chunks (MTU: $currentMtu)")
            
            // Send header first
            val header = JSONObject().apply {
                put("type", "transfer_start")
                put("id", transferId)
                put("size", data.size)
                put("chunks", totalChunks)
            }.toString().toByteArray()
            
            characteristic.value = header
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(characteristic)
            
            // Send chunks with delay to prevent buffer overflow
            CoroutineScope(Dispatchers.IO).launch {
                for (i in 0 until totalChunks) {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize, data.size)
                    val chunk = data.copyOfRange(start, end)
                    
                    // Create chunk packet with header
                    val chunkPacket = ByteArray(chunk.size + 8)
                    // Header: transferId hash (4 bytes) + chunk index (4 bytes)
                    val idHash = transferId.hashCode()
                    chunkPacket[0] = (idHash shr 24).toByte()
                    chunkPacket[1] = (idHash shr 16).toByte()
                    chunkPacket[2] = (idHash shr 8).toByte()
                    chunkPacket[3] = idHash.toByte()
                    chunkPacket[4] = (i shr 24).toByte()
                    chunkPacket[5] = (i shr 16).toByte()
                    chunkPacket[6] = (i shr 8).toByte()
                    chunkPacket[7] = i.toByte()
                    System.arraycopy(chunk, 0, chunkPacket, 8, chunk.size)
                    
                    mainHandler.post {
                        try {
                            characteristic.value = chunkPacket
                            gatt.writeCharacteristic(characteristic)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing chunk $i", e)
                        }
                    }
                    
                    onTransferProgress?.invoke(transferId, i + 1, totalChunks)
                    delay(20) // Small delay between chunks
                }
                
                // Send completion marker
                delay(50)
                val footer = JSONObject().apply {
                    put("type", "transfer_end")
                    put("id", transferId)
                }.toString().toByteArray()
                
                mainHandler.post {
                    characteristic.value = footer
                    gatt.writeCharacteristic(characteristic)
                }
                
                Log.d(TAG, "✅ Transfer $transferId complete")
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception sending data", e)
            onError?.invoke("Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data", e)
            onError?.invoke("Error sending data: ${e.message}")
        }
    }
    
    /**
     * Send a command to a connected device
     */
    fun sendCommand(device: BluetoothDevice, command: String, params: JSONObject = JSONObject()) {
        val address = device.address
        val gatt = deviceGatts[address]
        val deviceName = try { device.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" }
        
        Log.d(TAG, "📤 sendCommand: $command to $deviceName ($address)")
        Log.d(TAG, "📤 deviceGatts has entry: ${gatt != null}, connectedDevices has entry: ${connectedDevices.containsKey(address)}")
        
        if (gatt == null) {
            // Check if connected as server (Peripheral mode)
            if (connectedDevices.containsKey(address)) {
                Log.d(TAG, "📡 Sending command via Server Notification (Peripheral mode)")
                val commandJson = JSONObject().apply {
                    put("command", command)
                    put("params", params)
                    put("timestamp", System.currentTimeMillis())
                }
                sendDataViaServer(device, commandJson.toString().toByteArray(StandardCharsets.UTF_8))
                return
            }
            
            Log.e(TAG, "❌ Not connected to $deviceName - cannot send command")
            return
        }
        
        try {
            val service = gatt.getService(AIRSYNC_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(COMMAND_CHAR_UUID)
            
            if (characteristic == null) {
                Log.e(TAG, "❌ Command characteristic not found on GATT client")
                return
            }
            
            val commandJson = JSONObject().apply {
                put("command", command)
                put("params", params)
                put("timestamp", System.currentTimeMillis())
            }
            
            characteristic.value = commandJson.toString().toByteArray(StandardCharsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(characteristic)
            
            Log.d(TAG, "📤 Sent command via GATT Client: $command")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command", e)
        }
    }
    
    /**
     * Send data to connected device via GATT server (for server-initiated transfers)
     */
    fun sendDataViaServer(device: BluetoothDevice, data: ByteArray) {
        try {
            val deviceName = try { device.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" }
            Log.d(TAG, "📡 sendDataViaServer to $deviceName: ${data.size} bytes")
            
            val service = gattServer?.getService(AIRSYNC_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "❌ GATT Server service not found - gattServer: ${gattServer != null}")
                return
            }
            
            val characteristic = service.getCharacteristic(NOTIFICATION_CHAR_UUID)
            if (characteristic == null) {
                Log.e(TAG, "❌ Notification characteristic not found in service")
                return
            }
            
            characteristic.value = data
            val result = gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            Log.d(TAG, "📤 notifyCharacteristicChanged result: $result, sent ${data.size} bytes")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception sending via server", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending via server", e)
        }
    }
    
    // ==================== GATT SERVER SETUP ====================
    
    private fun setupGattServer(deviceInfo: DeviceInfo?) {
        try {
            gattServer?.close()
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            
            // Create AirSync service
            val service = BluetoothGattService(
                AIRSYNC_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            
            // Device info characteristic (read-only)
            val deviceInfoChar = BluetoothGattCharacteristic(
                DEVICE_INFO_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            // Set device info value
            val info = deviceInfo ?: DeviceInfo(
                alias = Build.MODEL,
                version = "2.0",
                deviceModel = Build.MODEL,
                deviceType = "mobile",
                port = 6996,
                protocol = "ws"
            )
            deviceInfoChar.value = JSONObject().apply {
                put("alias", info.alias)
                put("version", info.version)
                put("deviceModel", info.deviceModel)
                put("deviceType", info.deviceType)
                put("port", info.port)
                put("protocol", info.protocol)
            }.toString().toByteArray(StandardCharsets.UTF_8)
            
            // Data transfer characteristic (read/write with notifications)
            val dataTransferChar = BluetoothGattCharacteristic(
                DATA_TRANSFER_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            dataTransferChar.addDescriptor(BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
            
            // Command characteristic (write-only)
            val commandChar = BluetoothGattCharacteristic(
                COMMAND_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            // Notification characteristic (notify-only for server-initiated messages)
            val notificationChar = BluetoothGattCharacteristic(
                NOTIFICATION_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            notificationChar.addDescriptor(BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
            
            service.addCharacteristic(deviceInfoChar)
            service.addCharacteristic(dataTransferChar)
            service.addCharacteristic(commandChar)
            service.addCharacteristic(notificationChar)
            
            gattServer?.addService(service)
            
            Log.d(TAG, "✅ GATT server set up with 4 characteristics")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception setting up GATT server", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up GATT server", e)
        }
    }

    
    // ==================== CALLBACKS ====================
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "✅ BLE advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "❌ BLE advertising failed: $errorMsg")
            onError?.invoke("Advertising failed: $errorMsg")
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                try {
                    val device = scanResult.device
                    val rssi = scanResult.rssi
                    
                    // Get device name - try multiple sources
                    val name = try {
                        device.name?.takeIf { it.isNotBlank() }
                            ?: scanResult.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                            ?: ""
                    } catch (e: SecurityException) {
                        ""
                    }
                    
                    // Check if this is an AirSync device by service UUID
                    val serviceUuids = scanResult.scanRecord?.serviceUuids ?: emptyList()
                    val isAirSyncDevice = serviceUuids.any { it.uuid == AIRSYNC_SERVICE_UUID }
                    
                    // ONLY show AirSync devices - skip everything else
                    if (!isAirSyncDevice) {
                        return@let
                    }
                    
                    Log.d(TAG, "🔍 Found AirSync device: $name (RSSI: $rssi)")
                    
                    // Parse device info from scan record if available
                    var deviceInfo: DeviceInfo? = null
                    scanResult.scanRecord?.serviceData?.get(ParcelUuid(AIRSYNC_SERVICE_UUID))?.let { data ->
                        try {
                            val json = JSONObject(String(data, StandardCharsets.UTF_8))
                            deviceInfo = DeviceInfo(
                                alias = json.optString("alias", name),
                                version = json.optString("version", "2.0"),
                                deviceModel = json.optString("deviceModel"),
                                deviceType = json.optString("deviceType", "desktop"),
                                port = json.optInt("port", 6996),
                                protocol = json.optString("protocol", "ws")
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse device info from scan data")
                        }
                    }
                    
                    // Create device info if not parsed from service data
                    if (deviceInfo == null) {
                        deviceInfo = DeviceInfo(
                            alias = name.ifEmpty { "AirSync Mac" },
                            version = "2.0",
                            deviceModel = name.ifEmpty { "Mac" },
                            deviceType = "desktop",
                            port = 6996,
                            protocol = "ws"
                        )
                    }
                    
                    val discovered = DiscoveredDevice(
                        device = device,
                        name = name.ifEmpty { "AirSync Mac" },
                        rssi = rssi,
                        deviceInfo = deviceInfo
                    )
                    
                    // Update discovered devices list
                    val currentList = _discoveredDevices.value.toMutableList()
                    val existingIndex = currentList.indexOfFirst { it.device.address == device.address }
                    if (existingIndex >= 0) {
                        currentList[existingIndex] = discovered
                    } else {
                        currentList.add(discovered)
                        Log.d(TAG, "✅ Added AirSync device: ${discovered.name}")
                    }
                    
                    // Sort by signal strength
                    currentList.sortByDescending { it.rssi }
                    
                    _discoveredDevices.value = currentList
                    onDeviceDiscovered?.invoke(discovered)
                    
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception in scan result", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing scan result", e)
                }
            }
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result ->
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "❌ BLE scan failed: $errorMsg")
            onError?.invoke("Scan failed: $errorMsg")
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            try {
                val device = gatt?.device ?: return
                val address = device.address
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "✅ Connected to ${device.name}")
                        connectedDevices[address] = device
                        reconnectAttempts.remove(address)
                        _connectionState.value = ConnectionState.Connected
                        
                        // Remove from discovered list
                        val currentList = _discoveredDevices.value.toMutableList()
                        currentList.removeAll { it.device.address == address }
                        _discoveredDevices.value = currentList
                        
                        // Request higher MTU for better throughput
                        gatt.requestMtu(MAX_MTU)
                        
                        mainHandler.post {
                            onDeviceConnected?.invoke(device)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "🔌 Disconnected from ${device.name}")
                        connectedDevices.remove(address)
                        
                        mainHandler.post {
                            onDeviceDisconnected?.invoke(device)
                        }
                        
                        // Attempt reconnect if not intentional
                        val attempts = reconnectAttempts.getOrDefault(address, 0)
                        if (attempts < MAX_RECONNECT_ATTEMPTS) {
                            reconnectAttempts[address] = attempts + 1
                            scheduleReconnect(device)
                        } else {
                            deviceGatts.remove(address)?.close()
                            reconnectAttempts.remove(address)
                            if (connectedDevices.isEmpty()) {
                                _connectionState.value = ConnectionState.Disconnected
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in connection state change", e)
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                Log.d(TAG, "📏 MTU changed to $mtu")
            }
            
            // Discover services after MTU negotiation
            try {
                gatt?.discoverServices()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception discovering services", e)
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ Services discovered")
                
                // Enable notifications on data transfer characteristic
                try {
                    val service = gatt?.getService(AIRSYNC_SERVICE_UUID)
                    val dataChar = service?.getCharacteristic(DATA_TRANSFER_CHAR_UUID)
                    val commandChar = service?.getCharacteristic(COMMAND_CHAR_UUID)
                    
                    Log.d(TAG, "📋 Service found: ${service != null}")
                    Log.d(TAG, "📋 Data char found: ${dataChar != null}")
                    Log.d(TAG, "📋 Command char found: ${commandChar != null}")
                    
                    if (dataChar != null) {
                        gatt.setCharacteristicNotification(dataChar, true)
                        val descriptor = dataChar.getDescriptor(CCCD_UUID)
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                    
                    // Notify that services are ready - this is when we can send commands
                    gatt?.device?.let { device ->
                        mainHandler.post {
                            onServicesDiscovered?.invoke(device)
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception enabling notifications", e)
                }
            } else {
                Log.e(TAG, "❌ Service discovery failed: $status")
            }
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                val data = characteristic.value
                Log.d(TAG, "📥 Read ${data?.size ?: 0} bytes from ${characteristic.uuid}")
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.value?.let { data ->
                handleReceivedData(gatt?.device?.address ?: "", data)
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "❌ Write failed: $status")
            }
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ Descriptor written for ${descriptor?.characteristic?.uuid}")
                
                // Chain subscriptions: Data -> Command
                if (descriptor?.characteristic?.uuid == DATA_TRANSFER_CHAR_UUID) {
                    try {
                        val service = gatt?.getService(AIRSYNC_SERVICE_UUID)
                        val commandChar = service?.getCharacteristic(COMMAND_CHAR_UUID)
                        
                        if (commandChar != null) {
                            gatt.setCharacteristicNotification(commandChar, true)
                            val desc = commandChar.getDescriptor(CCCD_UUID)
                            if (desc != null) {
                                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(desc)
                                Log.d(TAG, "🔔 Subscribing to Command Char...")
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception subscribing to command char", e)
                    }
                }
            } else {
                Log.e(TAG, "❌ Descriptor write failed: $status")
            }
        }
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            try {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "✅ Client connected: ${device?.name}")
                        device?.let { 
                            connectedDevices[it.address] = it
                            _connectionState.value = ConnectionState.Connected
                            mainHandler.post { onDeviceConnected?.invoke(it) }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "🔌 Client disconnected: ${device?.name}")
                        device?.let {
                            connectedDevices.remove(it.address)
                            mainHandler.post { onDeviceDisconnected?.invoke(it) }
                        }
                        // Update connection state if no more connected devices
                        if (connectedDevices.isEmpty()) {
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in server connection state", e)
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            try {
                val value = characteristic?.value ?: ByteArray(0)
                val response = if (offset < value.size) {
                    value.copyOfRange(offset, value.size)
                } else {
                    ByteArray(0)
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in read request", e)
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            try {
                value?.let { data ->
                    when (characteristic?.uuid) {
                        DATA_TRANSFER_CHAR_UUID -> handleReceivedData(device?.address ?: "", data)
                        COMMAND_CHAR_UUID -> handleReceivedCommand(device?.address ?: "", data)
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in write request", e)
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            try {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in descriptor write", e)
            }
        }
        
        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            currentMtu = mtu
            Log.d(TAG, "📏 Server MTU changed to $mtu")
        }
    }
    
    // ==================== HELPERS ====================
    
    private fun handleReceivedData(address: String, data: ByteArray) {
        try {
            // Check if it's a JSON control message
            val str = String(data, StandardCharsets.UTF_8)
            if (str.startsWith("{")) {
                val json = JSONObject(str)
                when (json.optString("type")) {
                    "transfer_start" -> {
                        val id = json.getString("id")
                        val size = json.getInt("size")
                        val chunks = json.getInt("chunks")
                        pendingTransfers[id] = DataTransfer(id, size, chunks)
                        receivedChunks[id] = mutableListOf()
                        Log.d(TAG, "📥 Starting transfer $id: $size bytes in $chunks chunks")
                    }
                    "transfer_end" -> {
                        val id = json.getString("id")
                        val transfer = pendingTransfers.remove(id)
                        val chunks = receivedChunks.remove(id)
                        
                        if (transfer != null && chunks != null) {
                            // Reassemble data
                            val totalSize = chunks.sumOf { it.size }
                            val result = ByteArray(totalSize)
                            var offset = 0
                            chunks.forEach { chunk ->
                                System.arraycopy(chunk, 0, result, offset, chunk.size)
                                offset += chunk.size
                            }
                            Log.d(TAG, "✅ Transfer $id complete: ${result.size} bytes")
                            mainHandler.post { onTransferComplete?.invoke(id, result) }
                        }
                    }
                    else -> {
                        mainHandler.post { onDataReceived?.invoke(address, data) }
                    }
                }
            } else if (data.size > 8) {
                // Chunk data: first 8 bytes are header
                val idHash = ((data[0].toInt() and 0xFF) shl 24) or
                            ((data[1].toInt() and 0xFF) shl 16) or
                            ((data[2].toInt() and 0xFF) shl 8) or
                            (data[3].toInt() and 0xFF)
                val chunkIndex = ((data[4].toInt() and 0xFF) shl 24) or
                                ((data[5].toInt() and 0xFF) shl 16) or
                                ((data[6].toInt() and 0xFF) shl 8) or
                                (data[7].toInt() and 0xFF)
                
                val chunkData = data.copyOfRange(8, data.size)
                
                // Find matching transfer by hash
                pendingTransfers.entries.find { it.key.hashCode() == idHash }?.let { entry ->
                    receivedChunks[entry.key]?.add(chunkData)
                    entry.value.receivedChunks++
                    mainHandler.post {
                        onTransferProgress?.invoke(entry.key, entry.value.receivedChunks, entry.value.totalChunks)
                    }
                }
            } else {
                mainHandler.post { onDataReceived?.invoke(address, data) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling received data", e)
            mainHandler.post { onDataReceived?.invoke(address, data) }
        }
    }
    
    private fun handleReceivedCommand(address: String, data: ByteArray) {
        try {
            val str = String(data, StandardCharsets.UTF_8)
            Log.d(TAG, "📥 Received command data: $str")
            
            val json = JSONObject(str)
            
            // Handle command format: { "command": "...", "params": {...} }
            val command = json.optString("command")
            if (command.isNotEmpty()) {
                val params = json.optJSONObject("params") ?: JSONObject()
                Log.d(TAG, "📥 Parsed command: $command")
                mainHandler.post { onCommandReceived?.invoke(command, params) }
                return
            }
            
            // Handle legacy format: { "type": "...", "data": {...} }
            val type = json.optString("type")
            if (type.isNotEmpty()) {
                val params = json.optJSONObject("data") ?: JSONObject()
                Log.d(TAG, "📥 Parsed legacy command type: $type")
                mainHandler.post { onCommandReceived?.invoke(type, params) }
                return
            }
            
            Log.w(TAG, "Unknown command format: $str")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing command: ${e.message}")
        }
    }
    
    private fun scheduleReconnect(device: BluetoothDevice) {
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(RECONNECT_DELAY_MS)
            Log.d(TAG, "🔄 Attempting reconnect to ${device.name}...")
            mainHandler.post { connectToDevice(device) }
        }
    }
    
    /**
     * Clean up all resources
     */
    fun cleanup() {
        stopScanning()
        stopAdvertising()
        disconnectAll()
        gattServer?.close()
        gattServer = null
        bluetoothLeAdvertiser = null
        bluetoothLeScanner = null
        bluetoothAdapter = null
        bluetoothManager = null
        pendingTransfers.clear()
        receivedChunks.clear()
        reconnectJob?.cancel()
        Log.d(TAG, "🧹 Bluetooth helper cleaned up")
    }
    
    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
    
    /**
     * Check if currently advertising
     */
    fun isAdvertising(): Boolean = isAdvertising
    
    /**
     * Check if currently scanning
     */
    fun isScanning(): Boolean = isScanning
    
    /**
     * Get list of connected devices
     */
    fun getConnectedDevices(): List<BluetoothDevice> = connectedDevices.values.toList()
}
