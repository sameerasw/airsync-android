package com.sameerasw.airsync.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.NotificationDismissalUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleGattServer(private val context: Context) {
    companion object {
        private const val TAG = "BleGattServer"
        private var instance: BleGattServer? = null
        fun isAnyAuthenticated(): Boolean = instance?.isAuthenticated ?: false
    }

    init {
        instance = this
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStoreManager = DataStoreManager(context)

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val characteristicQueues = mutableMapOf<UUID, ConcurrentLinkedQueue<ByteArray>>()
    private val isSending = mutableMapOf<UUID, Boolean>()
    private val preparedWrites = java.util.concurrent.ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()

    var isAuthenticated = false
        private set
    private var negotiatedMtu = 23
    private var heartbeatJob: Job? = null
    private var isAdvertisingPaused = false

    enum class BleConnectionState {
        DISCONNECTED, ADVERTISING, CONNECTED, AUTHENTICATED
    }

    private val pendingServices = mutableListOf<BluetoothGattService>()

    /**
     * Start the GATT server and begin advertising
     */
    fun start() {
        if (_connectionState.value != BleConnectionState.DISCONNECTED) {
            Log.d(TAG, "BLE GATT Server already starting or started")
            return
        }

        if (!com.sameerasw.airsync.utils.PermissionUtil.isBluetoothPermissionsGranted(context)) {
            Log.e(TAG, "Missing Bluetooth permissions, cannot start BLE transport")
            return
        }

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter not available or disabled")
            return
        }

        val isEnabled = try {
            runBlocking { dataStoreManager.getBleSyncEnabled().first() }
        } catch (e: Exception) {
            false
        }
        if (!isEnabled) {
            Log.d(TAG, "BLE Sync is disabled in settings, skipping start")
            return
        }

        // Set Bluetooth adapter name dynamically based on configured device name to keep BLE matching precise
        val customName = try {
            runBlocking { dataStoreManager.getDeviceName().first() }
        } catch (e: Exception) {
            ""
        }
        val rawName =
            if (customName.isNotBlank()) customName else com.sameerasw.airsync.utils.DeviceInfoUtil.getDeviceName(
                context
            )
        val baseName = rawName
            .replace("AirSync-AirSync-", "")
            .replace("AirSync-", "")
            .replace("airsync-", "")
            .replace("airsync", "")
            .trim()

        val bleName = "AirSync-$baseName"
        try {
            if (adapter.name != bleName) {
                adapter.name = bleName
                Log.d(TAG, "Updated Bluetooth adapter name to: $bleName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set Bluetooth adapter name: ${e.message}")
        }

        setupGattServer()
    }

    /**
     * Stop the GATT server and advertising
     */
    fun stop() {
        stopAdvertising()
        stopHeartbeat()
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        pendingServices.clear()
        _connectionState.value = BleConnectionState.DISCONNECTED
        isAuthenticated = false
        isAdvertisingPaused = false
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        pendingServices.clear()

        // System Service
        val systemService = BluetoothGattService(
            BleConstants.SERVICE_SYSTEM,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        systemService.addCharacteristic(createReadCharacteristic(BleConstants.CHAR_PROTOCOL_VERSION))
        systemService.addCharacteristic(createWriteCharacteristic(BleConstants.CHAR_AUTH_TOKEN))
        systemService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_AUTH_RESULT))
        systemService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_BATTERY_LEVEL))
        systemService.addCharacteristic(createWriteCharacteristic(BleConstants.CHAR_MAC_BATTERY))
        systemService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_SYSTEM_STATE))
        systemService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_MAC_CONTROL))
        systemService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_DEVICE_NAME))
        pendingServices.add(systemService)

        // Notifications Service
        val notifService = BluetoothGattService(
            BleConstants.SERVICE_NOTIFICATIONS,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        notifService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_NOTIFICATION_DATA))
        notifService.addCharacteristic(createWriteCharacteristic(BleConstants.CHAR_NOTIFICATION_ACTION))
        notifService.addCharacteristic(createWriteCharacteristic(BleConstants.CHAR_NOTIFICATION_DISMISS))
        notifService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_NOTIFICATION_DISMISS_NOTIFY))
        pendingServices.add(notifService)

        // Media Service
        val mediaService = BluetoothGattService(
            BleConstants.SERVICE_MEDIA,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        mediaService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_MEDIA_STATE))
        mediaService.addCharacteristic(createWriteCharacteristic(BleConstants.CHAR_MEDIA_CONTROL))
        mediaService.addCharacteristic(createWriteCharacteristic(BleConstants.CHAR_MAC_MEDIA_STATE))
        pendingServices.add(mediaService)

        // Clipboard Service
        val clipService = BluetoothGattService(
            BleConstants.SERVICE_CLIPBOARD,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        clipService.addCharacteristic(createNotifyCharacteristic(BleConstants.CHAR_CLIPBOARD_DATA_NOTIFY))
        clipService.addCharacteristic(createWriteCharacteristic(BleConstants.CHAR_CLIPBOARD_DATA_WRITE))
        pendingServices.add(clipService)

        // Add first service with a small delay for stability
        scope.launch(Dispatchers.Main) {
            delay(300)
            if (pendingServices.isNotEmpty()) {
                val first = pendingServices.removeAt(0)
                gattServer?.addService(first)
            }
        }
    }

    private var currentAdvertiseCallback: AdvertiseCallback? = null

    private fun startAdvertising() {
        if (currentAdvertiseCallback != null) {
            stopAdvertising()
        }

        val advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_SYSTEM))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "BLE Advertising started successfully")
                _connectionState.value = BleConnectionState.ADVERTISING
            }

            override fun onStartFailure(errorCode: Int) {
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                    Log.d(TAG, "BLE Advertising already started, treating as success")
                    _connectionState.value = BleConnectionState.ADVERTISING
                } else {
                    Log.e(TAG, "BLE Advertising failed: $errorCode")
                    currentAdvertiseCallback = null
                    _connectionState.value = BleConnectionState.DISCONNECTED
                }
            }
        }

        currentAdvertiseCallback = callback
        advertiser.startAdvertising(settings, data, scanResponse, callback)
    }

    private fun stopAdvertising() {
        val callback = currentAdvertiseCallback ?: return
        adapter.bluetoothLeAdvertiser?.stopAdvertising(callback)
        currentAdvertiseCallback = null
    }

    /**
     * Stop advertising while keeping the GATT server alive.
     * Existing BLE connections remain active
     */
    fun pauseAdvertising() {
        if (isAdvertisingPaused) return
        Log.d(TAG, "BLE advertising paused (regular connection active)")
        isAdvertisingPaused = true
        stopAdvertising()
        if (_connectionState.value == BleConnectionState.ADVERTISING) {
            _connectionState.value = BleConnectionState.CONNECTED
        }
    }

    /**
     * Resume advertising after it was paused. No-op if already advertising.
     */
    fun resumeAdvertising() {
        if (!isAdvertisingPaused) return
        if (gattServer == null) return
        Log.d(TAG, "BLE advertising resumed")
        isAdvertisingPaused = false
        startAdvertising()
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "Service added: ${service.uuid}, status: $status")
            if (pendingServices.isNotEmpty()) {
                val next = pendingServices.removeAt(0)
                gattServer?.addService(next)
            } else {
                Log.d(TAG, "All services added, starting advertising")
                startAdvertising()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(
                TAG,
                "onConnectionStateChange: device=${device.address}, status=$status, newState=$newState, bond=${device.bondState}"
            )

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected: ${device.address}")
                connectedDevices.add(device)
                _connectionState.value = BleConnectionState.CONNECTED
                stopAdvertising()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: ${device.address}")
                connectedDevices.remove(device)
                if (connectedDevices.isEmpty()) {
                    stopHeartbeat()
                    _connectionState.value =
                        if (gattServer != null) BleConnectionState.ADVERTISING else BleConnectionState.DISCONNECTED
                    isAuthenticated = false
                    if (gattServer != null) {
                        val isEnabled = try {
                            runBlocking { dataStoreManager.getBleSyncEnabled().first() }
                        } catch (e: Exception) {
                            false
                        }
                        if (isEnabled) {
                            if (!isAdvertisingPaused) {
                                startAdvertising()
                            }
                        } else {
                            Log.d(TAG, "BLE Sync is disabled, stopping server")
                            stop()
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "MTU changed for ${device.address}: $mtu")
            negotiatedMtu = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BleConstants.CHAR_PROTOCOL_VERSION) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    byteArrayOf(BleConstants.PROTOCOL_VERSION.toByte())
                )
            } else {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_READ_NOT_PERMITTED,
                    0,
                    null
                )
            }
        }

        private val chunkBuffers = mutableMapOf<UUID, MutableMap<Int, ByteArray>>()

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Write request for characteristic=${characteristic.uuid}, fromDevice=${device.address}, requestId=$requestId, preparedWrite=$preparedWrite, responseNeeded=$responseNeeded, offset=$offset, valueLength=${value.size}")
            if (characteristic.uuid != BleConstants.CHAR_AUTH_TOKEN && !isAuthenticated) {
                Log.w(
                    TAG,
                    "Blocked unauthorized write request to ${characteristic.uuid} from ${device.address}"
                )
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                        offset,
                        null
                    )
                }
                return
            }

            if (preparedWrite) {
                val key = "${device.address}_${characteristic.uuid}"
                val bos = preparedWrites.getOrPut(key) { java.io.ByteArrayOutputStream() }
                bos.write(value)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                return
            }
            when (characteristic.uuid) {
                BleConstants.CHAR_AUTH_TOKEN -> handleAuthRequest(device, value)
                BleConstants.CHAR_MAC_BATTERY -> handleMacBattery(value)
                BleConstants.CHAR_NOTIFICATION_ACTION -> handleChunkedWrite(
                    characteristic.uuid,
                    value
                ) { handleNotificationAction(it.toByteArray(Charsets.UTF_8)) }

                BleConstants.CHAR_MEDIA_CONTROL -> handleChunkedWrite(
                    characteristic.uuid,
                    value
                ) { handleMediaControl(it.toByteArray(Charsets.UTF_8)) }

                BleConstants.CHAR_MAC_MEDIA_STATE -> handleChunkedWrite(
                    characteristic.uuid,
                    value
                ) { handleMacMediaState(it) }

                BleConstants.CHAR_CLIPBOARD_DATA_WRITE -> handleChunkedWrite(
                    characteristic.uuid,
                    value
                ) {
                    Log.d(TAG, "Received clipboard from Mac via BLE: ${it.take(50)}")
                    ClipboardSyncManager.handleClipboardUpdate(context, it)
                }

                BleConstants.CHAR_DEVICE_NAME -> handleChunkedWrite(characteristic.uuid, value) {
                    Log.d(TAG, "Received Mac Device Name: $it")
                    // Update Mac name in status manager
                    MacDeviceStatusManager.updateMacStatus(context, name = it)
                }

                BleConstants.CHAR_NOTIFICATION_DISMISS -> handleChunkedWrite(
                    characteristic.uuid,
                    value
                ) { handleNotificationDismiss(it) }

                else -> Log.w(TAG, "Unknown characteristic write: ${characteristic.uuid}")
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }

        private fun handleChunkedWrite(uuid: UUID, value: ByteArray, onComplete: (String) -> Unit) {
            val header = BleChunkUtil.parseHeader(value)
            if (header == null) {
                // Not chunked or invalid header - maybe small payload?
                // For now, assume everything to these characteristics is chunked.
                return
            }
            val (current, total) = header
            val payload = BleChunkUtil.getPayload(value)

            val buffer = chunkBuffers.getOrPut(uuid) { mutableMapOf() }
            buffer[current] = payload

            if (buffer.size == total) {
                val completePayload = BleChunkUtil.reassemble(buffer)
                chunkBuffers.remove(uuid)
                onComplete(completePayload)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            Log.d(TAG, "Descriptor read request: ${descriptor.uuid}")
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(
                TAG,
                "Descriptor write request: ${descriptor.uuid}, value: ${value.contentToString()}"
            )
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // This is crucial for sequential chunk sending
            processNextInQueues()
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            Log.d(TAG, "onExecuteWrite: device=${device.address}, requestId=$requestId, execute=$execute")
            if (execute) {
                val keys = preparedWrites.keys().toList()
                for (key in keys) {
                    if (key.startsWith(device.address)) {
                        val bos = preparedWrites.remove(key) ?: continue
                        val value = bos.toByteArray()
                        val uuidStr = key.substring(device.address.length + 1)
                        val uuid = UUID.fromString(uuidStr)
                        val characteristic = findCharacteristic(uuid)
                        if (characteristic != null) {
                            Log.d(TAG, "Executing prepared write for characteristic=$uuid, valueLength=${value.size}")
                            scope.launch {
                                when (uuid) {
                                    BleConstants.CHAR_AUTH_TOKEN -> handleAuthRequest(device, value)
                                    BleConstants.CHAR_MAC_BATTERY -> handleMacBattery(value)
                                    BleConstants.CHAR_NOTIFICATION_ACTION -> handleChunkedWrite(uuid, value) { handleNotificationAction(it.toByteArray(Charsets.UTF_8)) }
                                    BleConstants.CHAR_MEDIA_CONTROL -> handleChunkedWrite(uuid, value) { handleMediaControl(it.toByteArray(Charsets.UTF_8)) }
                                    BleConstants.CHAR_MAC_MEDIA_STATE -> handleChunkedWrite(uuid, value) { handleMacMediaState(it) }
                                    BleConstants.CHAR_CLIPBOARD_DATA_WRITE -> handleChunkedWrite(uuid, value) {
                                        Log.d(TAG, "Received clipboard from Mac via BLE: ${it.take(50)}")
                                        ClipboardSyncManager.handleClipboardUpdate(context, it)
                                    }
                                    BleConstants.CHAR_DEVICE_NAME -> handleChunkedWrite(uuid, value) { 
                                        Log.d(TAG, "Received Mac Device Name: $it")
                                        MacDeviceStatusManager.updateMacStatus(context, name = it)
                                    }
                                    BleConstants.CHAR_NOTIFICATION_DISMISS -> handleChunkedWrite(uuid, value) { handleNotificationDismiss(it) }
                                }
                            }
                        }
                    }
                }
            } else {
                val keys = preparedWrites.keys().toList()
                for (key in keys) {
                    if (key.startsWith(device.address)) {
                        preparedWrites.remove(key)
                    }
                }
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun handleAuthRequest(device: BluetoothDevice, token: ByteArray) {
        scope.launch {
            val deviceData = dataStoreManager.getLastConnectedDevice().first()
            val storedKey = deviceData?.symmetricKey
            Log.d(TAG, "Handling auth request from ${device.address}. DB device details: name=${deviceData?.name}, ip=${deviceData?.ipAddress}, port=${deviceData?.port}, storedKeyExists=${storedKey != null}")
            if (storedKey != null) {
                Log.d(TAG, "Stored symmetric key found: $storedKey")
                val expectedToken = BleTransportBridge.deriveAuthToken(storedKey)
                val receivedTokenStr = String(token, Charsets.UTF_8)
                val expectedTokenBytes = expectedToken.toByteArray(Charsets.UTF_8)
                val expectedTokenBase64 = android.util.Base64.encodeToString(expectedTokenBytes, android.util.Base64.NO_WRAP)
                val receivedTokenBase64 = android.util.Base64.encodeToString(token, android.util.Base64.NO_WRAP)
                
                Log.d(TAG, "Expected token string: '$expectedToken'")
                Log.d(TAG, "Expected token base64: $expectedTokenBase64")
                Log.d(TAG, "Received token string: '$receivedTokenStr'")
                Log.d(TAG, "Received token base64: $receivedTokenBase64")
                
                val isMatch = token.contentEquals(expectedTokenBytes)
                Log.d(TAG, "Performing byte-by-byte authentication token comparison. Match result: $isMatch")

                if (isMatch) {
                    Log.i(TAG, "BLE Auth Success!")
                    isAuthenticated = true
                    _connectionState.value = BleConnectionState.AUTHENTICATED
                    sendNotification(
                        BleConstants.CHAR_AUTH_RESULT,
                        byteArrayOf(BleConstants.AUTH_SUCCESS)
                    )
                    BleTransportBridge.sendDeviceName()
                    startHeartbeat()
                } else {
                    Log.w(TAG, "BLE Auth Failed! Token mismatch (byte-level mismatch).")
                    sendNotification(BleConstants.CHAR_AUTH_RESULT, byteArrayOf(BleConstants.AUTH_FAILED))
                }
            } else {
                Log.w(TAG, "BLE Auth Failed! No symmetric key found for last connected device.")
                sendNotification(
                    BleConstants.CHAR_AUTH_RESULT,
                    byteArrayOf(BleConstants.AUTH_FAILED)
                )
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive && isAuthenticated) {
                delay(5000)
                if (connectedDevices.isNotEmpty()) {
                    val level =
                        com.sameerasw.airsync.utils.DeviceInfoUtil.getBatteryInfo(context).level
                    sendNotification(BleConstants.CHAR_BATTERY_LEVEL, byteArrayOf(level.toByte()))
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleNotificationDismiss(id: String) {
        Log.d(TAG, "Handling notification dismissal from BLE: $id")
        NotificationDismissalUtil.dismissNotification(id)
    }

    private fun handleMacBattery(value: ByteArray) {
        if (!isAuthenticated) return
        val payload = String(value, Charsets.UTF_8)
        val parts = payload.split(BleConstants.DELIMITER)
        if (parts.size >= 2) {
            val level = parts[0].toIntOrNull() ?: -1
            val isCharging = parts[1] == "1"
            Log.d(TAG, "Received Mac battery level via BLE: $level%, charging: $isCharging")
            MacDeviceStatusManager.updateBatteryStatus(context, level, isCharging)
        } else if (value.size == 1) {
            // Legacy 1-byte fallback
            val level = value[0].toInt() and 0xFF
            MacDeviceStatusManager.updateBatteryStatus(context, level, false)
        }
    }

    private fun handleMacMediaState(payload: String) {
        if (!isAuthenticated) return
        val parts = payload.split(BleConstants.DELIMITER)
        if (parts.size >= 6) {
            val isPlaying = parts[0] == "1"
            val title = parts[1]
            val artist = parts[2]
            val volume = parts[3].toIntOrNull() ?: 0
            val isMuted = parts[4] == "1"
            val likeStatus = parts[5]
            val albumArt = if (parts.size >= 7) parts[6] else null

            Log.d(TAG, "Received Mac media state via BLE: $title by $artist (Playing: $isPlaying)")
            MacDeviceStatusManager.updateMusicStatus(
                context, isPlaying, title, artist, volume, isMuted, likeStatus, albumArt
            )
        }
    }

    private fun handleNotificationAction(value: ByteArray) {
        if (!isAuthenticated) return
        val data = String(value, Charsets.UTF_8)
        BleTransportBridge.handleNotificationAction(data, context)
    }

    private fun handleMediaControl(value: ByteArray) {
        if (!isAuthenticated) return
        val action = String(value, Charsets.UTF_8)
        BleTransportBridge.handleMediaControl(action, context)
    }

    /**
     * Send a notification to all connected devices (with chunking)
     */
    fun sendNotification(characteristicUuid: UUID, data: ByteArray) {
        if (connectedDevices.isEmpty()) return

        // Characteristic level queue to ensure order
        val queue = characteristicQueues.getOrPut(characteristicUuid) { ConcurrentLinkedQueue() }
        queue.add(data)

        if (isSending[characteristicUuid] != true) {
            processNextInQueue(characteristicUuid)
        }
    }

    fun sendChunkedNotification(characteristicUuid: UUID, payload: String) {
        if (connectedDevices.isEmpty()) return

        // Truncate notification text to conserve BLE bandwidth
        val truncatedPayload = if (characteristicUuid == BleConstants.CHAR_NOTIFICATION_DATA) {
            payload.take(500)
        } else payload

        val mtu = negotiatedMtu
        val chunks = BleChunkUtil.splitIntoChunks(truncatedPayload, mtu)

        val queue = characteristicQueues.getOrPut(characteristicUuid) { ConcurrentLinkedQueue() }
        chunks.forEach { queue.add(it) }

        if (isSending[characteristicUuid] != true) {
            processNextInQueue(characteristicUuid)
        }
    }

    private fun processNextInQueues() {
        characteristicQueues.keys.forEach { uuid ->
            if (isSending[uuid] == true) {
                isSending[uuid] = false
                processNextInQueue(uuid)
            }
        }
    }

    private fun processNextInQueue(uuid: UUID) {
        val queue = characteristicQueues[uuid] ?: return
        val data = queue.poll() ?: return

        isSending[uuid] = true

        val characteristic = findCharacteristic(uuid) ?: return
        characteristic.value = data

        connectedDevices.forEach { device ->
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        gattServer?.services?.forEach { service ->
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun createReadCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        return BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
    }

    private fun createWriteCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        return BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
    }

    private fun createNotifyCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val char = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Add CCCD for notification support
        val configDescriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        char.addDescriptor(configDescriptor)
        return char
    }

    fun disconnectAllConnectedDevices() {
        Log.d(TAG, "Disconnecting all connected BLE devices manually...")
        val devicesCopy = synchronized(connectedDevices) { connectedDevices.toList() }
        for (device in devicesCopy) {
            try {
                gattServer?.cancelConnection(device)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel connection for ${device.address}: ${e.message}")
            }
        }
        isAuthenticated = false
        _connectionState.value = BleConnectionState.DISCONNECTED
    }
}
