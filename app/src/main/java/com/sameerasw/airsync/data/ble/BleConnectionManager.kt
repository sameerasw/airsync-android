package com.sameerasw.airsync.data.ble

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class BleConnectionManager(private val context: Context) {
    companion object {
        private const val TAG = "BleConnectionManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dataStoreManager = DataStoreManager(context)
    private var bleServer: BleGattServer? = null

    private var isBleEnabled = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _serverFlow = kotlinx.coroutines.flow.MutableStateFlow<BleGattServer?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState = _serverFlow.flatMapLatest { server ->
        server?.connectionState
            ?: kotlinx.coroutines.flow.MutableStateFlow(BleGattServer.BleConnectionState.DISCONNECTED)
    }

    fun start() {
        if (bleServer == null) {
            bleServer = BleGattServer(context)
            _serverFlow.value = bleServer
            BleTransportBridge.initialize(bleServer!!)
        }

        scope.launch {
            combine(
                dataStoreManager.getBleSyncEnabled(),
                dataStoreManager.getUserManuallyDisconnected(),
                WebSocketUtil.connectionState
            ) { enabled, manuallyDisconnected, wsConnected ->
                Triple(enabled, manuallyDisconnected, wsConnected)
            }.collectLatest { (enabled, manuallyDisconnected, wsConnected) ->
                isBleEnabled = enabled
                updateBleState(regularConnectionActive = wsConnected, manuallyDisconnected = manuallyDisconnected)
            }
        }
    }

    private fun updateBleState(regularConnectionActive: Boolean, manuallyDisconnected: Boolean) {
        if (!isBleEnabled) {
            Log.d(TAG, "BLE disabled in settings, stopping/pausing server")
            bleServer?.pauseAdvertising()
            return
        }

        if (regularConnectionActive) {
            // Regular Wi-Fi/USB connection is up — pause advertising to save power.
            Log.d(TAG, "Regular connection active — pausing BLE advertising")
            bleServer?.pauseAdvertising()
        } else {
            // No regular connection — ensure server is started and advertising so devices can discover and connect
            Log.d(TAG, "No regular connection — resuming BLE advertising")
            bleServer?.start()
            bleServer?.resumeAdvertising()
        }
    }

    fun stop() {
        scope.cancel()
        bleServer?.stop()
    }

    val isAuthenticated: Boolean
        get() = bleServer?.isAuthenticated ?: false

    fun sendChunkedNotification(characteristicUuid: java.util.UUID, payload: String) {
        bleServer?.sendChunkedNotification(characteristicUuid, payload)
    }

    fun sendNotification(characteristicUuid: java.util.UUID, data: ByteArray) {
        bleServer?.sendNotification(characteristicUuid, data)
    }

    fun disconnectAllConnectedDevices() {
        bleServer?.disconnectAllConnectedDevices()
    }

    fun restartServer() {
        Log.d(TAG, "Restarting BLE GATT server...")
        bleServer?.stop()
        bleServer = BleGattServer(context)
        _serverFlow.value = bleServer
        BleTransportBridge.initialize(bleServer!!)
        if (isBleEnabled) {
            bleServer?.start()
        }
    }
}
