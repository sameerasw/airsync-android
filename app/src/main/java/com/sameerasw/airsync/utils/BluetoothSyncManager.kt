package com.sameerasw.airsync.utils

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

/**
 * Manages Bluetooth data synchronization as an alternative/supplement to WebSocket
 * 
 * Features:
 * - 3-option pairing code verification
 * - Bidirectional pairing initiation
 * - Persistent paired device storage
 * - Auto-connect on app launch
 */
object BluetoothSyncManager {
    private const val TAG = "BluetoothSyncManager"
    private const val PREFS_NAME = "bluetooth_pairing_prefs"
    private const val KEY_PAIRED_DEVICE_ADDRESS = "paired_device_address"
    private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"
    private const val KEY_AUTO_CONNECT_ENABLED = "auto_connect_enabled"
    
    private var bluetoothHelper: BluetoothHelper? = null
    private var isInitialized = false
    private var prefs: SharedPreferences? = null
    
    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // Pairing state
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState
    
    private var pendingPairingDevice: BluetoothDevice? = null
    
    // Paired device info
    private val _pairedDevice = MutableStateFlow<PairedDeviceInfo?>(null)
    val pairedDevice: StateFlow<PairedDeviceInfo?> = _pairedDevice
    
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Scanning : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    sealed class PairingState {
        object Idle : PairingState()
        data class ConfirmationRequired(val code: String) : PairingState() // We received a request, user needs to confirm
        data class WaitingForConfirmation(val code: String) : PairingState() // We sent a request, waiting for peer to confirm
        object Success : PairingState()
        data class Failed(val reason: String) : PairingState()
    }
    
    data class PairedDeviceInfo(
        val address: String,
        val name: String,
        val pairedAt: Long = System.currentTimeMillis()
    )
    
    // Pairing timeout job
    private var pairingTimeoutJob: kotlinx.coroutines.Job? = null
    private const val PAIRING_TIMEOUT_MS = 30000L // 30 seconds
    
    // Track if we're waiting for services to be discovered before sending pairing request
    private var pendingPairingRequestDevice: BluetoothDevice? = null
    private var pendingPairingCode: String? = null
    
    var onMessageReceived: ((String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String?) -> Unit)? = null
    
    fun initialize(context: Context) {
        if (isInitialized) return
        
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPairedDevice()
        
        bluetoothHelper = BluetoothHelper(context).apply {
            if (!initialize()) {
                Log.e(TAG, "Failed to initialize Bluetooth")
                return@apply
            }
            
            onDeviceConnected = { device ->
                val deviceName = try { device.name ?: "Unknown Device" } catch (e: SecurityException) { "Unknown Device" }
                Log.d(TAG, "✅ Bluetooth connected to: $deviceName")
                _isConnected.value = true
                _connectedDeviceName.value = deviceName
                _connectionState.value = ConnectionState.Connected(deviceName)
                notifyConnectionChange(true, deviceName)
            }
            
            onDeviceDisconnected = { device ->
                val deviceName = try { device?.name } catch (e: SecurityException) { null }
                val deviceAddress = try { device?.address } catch (e: SecurityException) { null }
                Log.d(TAG, "🔌 Bluetooth disconnected from: $deviceName")
                
                // Only update UI state if the PAIRED device disconnected
                val pairedAddress = _pairedDevice.value?.address
                if (pairedAddress != null && deviceAddress == pairedAddress) {
                    Log.d(TAG, "🔌 Paired device disconnected - updating UI state")
                    _isConnected.value = false
                    _connectedDeviceName.value = null
                    _connectionState.value = ConnectionState.Disconnected
                    pendingPairingRequestDevice = null
                    notifyConnectionChange(false, null)
                } else {
                    Log.d(TAG, "🔌 Non-paired device disconnected - ignoring for UI")
                }
            }
            
            // This is called when GATT services are discovered and ready
            onServicesDiscovered = { device ->
                Log.d(TAG, "📋 Services discovered for: ${device.name}")
                // If we have a pending pairing request, send it now
                if (pendingPairingRequestDevice?.address == device.address) {
                    val code = pendingPairingCode
                    if (code != null) {
                        Log.d(TAG, "📤 Sending pending pairing request now that services are ready")
                        sendPairingRequest(code)
                    }
                    pendingPairingRequestDevice = null
                    pendingPairingCode = null
                }
            }
            
            onDataReceived = { _, data -> handleReceivedData(data) }
            onCommandReceived = { command, params -> handleReceivedCommand(command, params) }
            onError = { error ->
                Log.e(TAG, "Bluetooth error: $error")
                _connectionState.value = ConnectionState.Error(error)
            }
        }
        
        isInitialized = true
        Log.d(TAG, "✅ BluetoothSyncManager initialized")
    }
    
    // Advertising state
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising
    
    fun startAdvertising() { 
        bluetoothHelper?.startAdvertising()
        _isAdvertising.value = true
    }
    
    fun stopAdvertising() { 
        bluetoothHelper?.stopAdvertising() 
        _isAdvertising.value = false
    }
    
    fun startScanning() {
        _connectionState.value = ConnectionState.Scanning
        bluetoothHelper?.startScanning()
    }
    
    fun stopScanning() {
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
        bluetoothHelper?.stopScanning()
    }
    
    /**
     * Refresh the connection state by checking if the paired device is connected.
     * Only considers the paired device, not other Bluetooth devices like smartwatches.
     */
    fun refreshConnectionState() {
        val pairedAddress = _pairedDevice.value?.address ?: return
        val connectedDevices = bluetoothHelper?.getConnectedDevices() ?: emptyList()
        
        // Only check if the PAIRED device is connected, ignore other devices
        val pairedDeviceConnected = connectedDevices.any { 
            try { it.address == pairedAddress } catch (e: SecurityException) { false }
        }
        
        Log.d(TAG, "🔄 Refreshing connection state: paired=$pairedAddress, connected=$pairedDeviceConnected")
        
        if (pairedDeviceConnected) {
            val deviceName = _pairedDevice.value?.name ?: "Unknown Device"
            _isConnected.value = true
            _connectedDeviceName.value = deviceName
            _connectionState.value = ConnectionState.Connected(deviceName)
        } else {
            _isConnected.value = false
            _connectedDeviceName.value = null
            if (_connectionState.value is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }
    
    fun initiatePairing(device: BluetoothDevice) {
        pendingPairingDevice = device
        
        // Generate a 6-digit code
        val code = String.format("%06d", Random.nextInt(1000000))
        
        _pairingState.value = PairingState.WaitingForConfirmation(code)
        _connectionState.value = ConnectionState.Connecting
        
        Log.d(TAG, "🔐 Pairing initiated - generated code: $code")
        
        // Start pairing timeout
        startPairingTimeout()
        
        // Check if already connected
        val connectedDevices = bluetoothHelper?.getConnectedDevices() ?: emptyList()
        if (connectedDevices.any { it.address == device.address }) {
            Log.d(TAG, "🔐 Device already connected, sending pairing request immediately")
            sendPairingRequest(code)
        } else {
            // Mark this device as pending pairing request - will be sent when services are discovered
            pendingPairingRequestDevice = device
            pendingPairingCode = code
            
            bluetoothHelper?.connectToDevice(device)
            Log.d(TAG, "🔐 Connecting to device to send pairing request...")
        }
    }
    
    fun acceptPairing() {
        Log.d(TAG, "✅ User accepted pairing")
        sendPairingAccepted()
        pendingPairingDevice?.let { completePairing(it) }
    }
    
    fun rejectPairing() {
        Log.d(TAG, "❌ User rejected pairing")
        _pairingState.value = PairingState.Idle
        cancelPairing()
    }
    
    private fun handlePairingRequest(code: String) {
        Log.d(TAG, "📥 Received pairing request with code: $code")
        
        // We need to know which device sent this, but for now we assume it's the connected one
        // In a real scenario we'd map the device address
        val connectedDevices = bluetoothHelper?.getConnectedDevices() ?: emptyList()
        if (connectedDevices.isNotEmpty()) {
            pendingPairingDevice = connectedDevices.first()
        }
        
        _pairingState.value = PairingState.ConfirmationRequired(code)
        startPairingTimeout()
    }
    
    private fun startPairingTimeout() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(PAIRING_TIMEOUT_MS)
            if (_pairingState.value !is PairingState.Success && _pairingState.value !is PairingState.Idle) {
                Log.d(TAG, "⏱️ Pairing timeout")
                _pairingState.value = PairingState.Failed("Pairing timed out")
                cancelPairing()
            }
        }
    }
    
    private fun completePairing(device: BluetoothDevice) {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        
        val deviceName = try { device.name ?: "Unknown Device" } catch (e: SecurityException) { "Unknown Device" }
        val pairedInfo = PairedDeviceInfo(address = device.address, name = deviceName)
        
        _pairedDevice.value = pairedInfo
        savePairedDevice(pairedInfo)
        
        _pairingState.value = PairingState.Success
        _connectionState.value = ConnectionState.Connected(deviceName)
        _isConnected.value = true
        _connectedDeviceName.value = deviceName
        
        Log.d(TAG, "✅ Pairing completed and saved: $deviceName")
        
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(2000)
            _pairingState.value = PairingState.Idle
        }
    }
    
    fun cancelPairing() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pendingPairingDevice = null
        pendingPairingRequestDevice = null
        pendingPairingCode = null
        _pairingState.value = PairingState.Idle
        _connectionState.value = ConnectionState.Disconnected
        bluetoothHelper?.disconnectAll()
    }
    
    fun connect(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        bluetoothHelper?.connectToDevice(device)
    }
    
    fun tryAutoConnect() {
        val paired = _pairedDevice.value ?: return
        if (!isAutoConnectEnabled()) return
        
        Log.d(TAG, "🔄 Attempting auto-connect to ${paired.name}")
        _connectionState.value = ConnectionState.Scanning
        bluetoothHelper?.startScanning()
        
        CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            while (attempts < 10 && _connectionState.value is ConnectionState.Scanning) {
                kotlinx.coroutines.delay(1000)
                val discovered = bluetoothHelper?.discoveredDevices?.value ?: emptyList()
                val found = discovered.find { it.device.address == paired.address }
                if (found != null) {
                    Log.d(TAG, "✅ Found paired device, connecting...")
                    _connectionState.value = ConnectionState.Connecting
                    bluetoothHelper?.connectToDevice(found.device)
                    return@launch
                }
                attempts++
            }
            if (_connectionState.value is ConnectionState.Scanning) {
                Log.d(TAG, "⏱️ Auto-connect timeout")
                _connectionState.value = ConnectionState.Disconnected
                bluetoothHelper?.stopScanning()
            }
        }
    }
    
    fun disconnect() {
        bluetoothHelper?.disconnectAll()
        _isConnected.value = false
        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.Disconnected
    }
    
    fun forgetPairedDevice() {
        disconnect()
        _pairedDevice.value = null
        prefs?.edit()?.apply {
            remove(KEY_PAIRED_DEVICE_ADDRESS)
            remove(KEY_PAIRED_DEVICE_NAME)
            apply()
        }
        Log.d(TAG, "🗑️ Paired device forgotten")
    }
    
    fun setAutoConnectEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AUTO_CONNECT_ENABLED, enabled)?.apply()
    }
    
    fun isAutoConnectEnabled(): Boolean = prefs?.getBoolean(KEY_AUTO_CONNECT_ENABLED, true) ?: true
    
    fun sendMessage(message: String): Boolean {
        if (!_isConnected.value) return false
        val connectedDevices = bluetoothHelper?.getConnectedDevices() ?: emptyList()
        if (connectedDevices.isEmpty()) return false
        
        return try {
            val data = message.toByteArray(Charsets.UTF_8)
            connectedDevices.forEach { bluetoothHelper?.sendData(it, data) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending via Bluetooth: ${e.message}")
            false
        }
    }
    
    fun sendCommand(command: String, params: JSONObject = JSONObject()): Boolean {
        if (!_isConnected.value) return false
        val connectedDevices = bluetoothHelper?.getConnectedDevices() ?: emptyList()
        if (connectedDevices.isEmpty()) return false
        
        return try {
            connectedDevices.forEach { bluetoothHelper?.sendCommand(it, command, params) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command: ${e.message}")
            false
        }
    }
    
    fun isAvailable(): Boolean = _isConnected.value
    fun getHelper(): BluetoothHelper? = bluetoothHelper
    
    private fun handleReceivedData(data: ByteArray) {
        try {
            val message = String(data, Charsets.UTF_8)
            if (message.contains("\"type\":\"pairing") || message.contains("\"command\":\"pairing")) {
                handlePairingMessage(message)
                return
            }
            onMessageReceived?.invoke(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling received data: ${e.message}")
        }
    }
    
    private fun handleReceivedCommand(command: String, params: JSONObject) {
        Log.d(TAG, "📥 Received command: $command with params: $params")
        when (command) {
            "pairingRequest" -> {
                val code = params.optString("code")
                if (code.isNotEmpty()) {
                    handlePairingRequest(code)
                }
            }
            "pairingAccepted" -> {
                Log.d(TAG, "✅ Peer accepted pairing!")
                pendingPairingDevice?.let { completePairing(it) }
            }
            else -> {
                val message = JSONObject().apply {
                    put("type", command)
                    put("data", params)
                }.toString()
                onMessageReceived?.invoke(message)
            }
        }
    }
    
    private fun handlePairingMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val command = json.optString("command")
            val data = json.optJSONObject("data") ?: json.optJSONObject("params") ?: JSONObject()
            
            when {
                type == "pairingRequest" || command == "pairingRequest" -> {
                    val code = data.optString("code")
                    if (code.isNotEmpty()) {
                        handlePairingRequest(code)
                    }
                }
                type == "pairingAccepted" || command == "pairingAccepted" -> {
                    Log.d(TAG, "✅ Peer accepted pairing!")
                    pendingPairingDevice?.let { completePairing(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing pairing message: ${e.message}")
        }
    }
    
    private fun sendPairingRequest(code: String) {
        val params = JSONObject().apply {
            put("code", code)
        }
        
        val connectedDevices = bluetoothHelper?.getConnectedDevices() ?: emptyList()
        connectedDevices.forEach { device ->
            bluetoothHelper?.sendCommand(device, "pairingRequest", params)
        }
        Log.d(TAG, "📤 Sent pairing request with code: $code")
    }
    
    private fun sendPairingAccepted() {
        val connectedDevices = bluetoothHelper?.getConnectedDevices() ?: emptyList()
        Log.d(TAG, "📤 Sending pairing accepted to ${connectedDevices.size} connected devices")
        
        if (connectedDevices.isEmpty()) {
            Log.w(TAG, "⚠️ No connected devices found to send pairingAccepted!")
            return
        }
        
        connectedDevices.forEach { device ->
            try {
                val deviceName = device.name ?: "Unknown"
                Log.d(TAG, "📤 Sending pairingAccepted to: $deviceName (${device.address})")
                bluetoothHelper?.sendCommand(device, "pairingAccepted")
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception sending pairingAccepted", e)
            }
        }
        Log.d(TAG, "📤 Sent pairing accepted")
    }
    
    private fun savePairedDevice(device: PairedDeviceInfo) {
        prefs?.edit()?.apply {
            putString(KEY_PAIRED_DEVICE_ADDRESS, device.address)
            putString(KEY_PAIRED_DEVICE_NAME, device.name)
            apply()
        }
    }
    
    private fun loadPairedDevice() {
        val address = prefs?.getString(KEY_PAIRED_DEVICE_ADDRESS, null)
        val name = prefs?.getString(KEY_PAIRED_DEVICE_NAME, null)
        if (address != null && name != null) {
            _pairedDevice.value = PairedDeviceInfo(address, name)
            Log.d(TAG, "📱 Loaded paired device: $name ($address)")
        }
    }
    
    private fun notifyConnectionChange(connected: Boolean, deviceName: String?) {
        CoroutineScope(Dispatchers.Main).launch {
            onConnectionStateChanged?.invoke(connected, deviceName)
        }
    }
    
    fun getCurrentConnectionState(): ConnectionState = _connectionState.value
    
    fun hasActiveConnection(): Boolean {
        val connectedDevices = bluetoothHelper?.getConnectedDevices() ?: emptyList()
        val hasConnection = connectedDevices.isNotEmpty()
        if (hasConnection != _isConnected.value) {
            _isConnected.value = hasConnection
            if (!hasConnection) {
                _connectedDeviceName.value = null
                _connectionState.value = ConnectionState.Disconnected
            }
        }
        return hasConnection
    }
    
    fun cleanup() {
        bluetoothHelper?.cleanup()
        bluetoothHelper = null
        isInitialized = false
        _isConnected.value = false
        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.Disconnected
        _pairingState.value = PairingState.Idle
    }
}
