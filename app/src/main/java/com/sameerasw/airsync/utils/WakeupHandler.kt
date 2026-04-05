package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.domain.model.ConnectedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shared logic for processing wake-up requests from Mac clients.
 * This can be triggered via HTTP (WakeupService) or UDP (UDPDiscoveryManager).
 */
object WakeupHandler {
    private const val TAG = "WakeupHandler"

    suspend fun processWakeupRequest(
        context: Context,
        macIp: String,
        macPort: Int,
        macName: String,
        isManual: Boolean = false
    ) {
        try {
            Log.i(TAG, "Processing wake-up request from $macName at $macIp:$macPort")

            if (macIp.isEmpty()) {
                Log.w(TAG, "Wake-up request missing Mac IP address")
                return
            }

            val dataStoreManager = DataStoreManager.getInstance(context)

            if (WebSocketUtil.isConnected() || WebSocketUtil.isConnecting()) {
                Log.d(TAG, "Already connected or connecting, ignoring wake-up request")
                return
            }

            // Check if the user previously manually disconnected
            val isManuallyDisconnected = dataStoreManager.getUserManuallyDisconnected().first() || WebSocketUtil.isManualDisconnectPending.get()
            if (isManuallyDisconnected && !isManual) {
                Log.d(TAG, "Ignoring wake-up request because user manually disconnected")
                return
            }

            // Clear manual disconnect flag since this is an external wake-up request
            dataStoreManager.setUserManuallyDisconnected(false)

            // Reset in-memory flag so the auto-reconnect loop doesn't block this
            if (isManual) {
                WebSocketUtil.isManualDisconnectPending.set(false)
            }

            // Look up stored encryption key
            val encryptionKey =
                findStoredEncryptionKey(context, dataStoreManager, macIp, macPort, macName)

            if (encryptionKey == null) {
                Log.w(TAG, "No stored encryption key found for $macName at $macIp:$macPort")
                return
            }

            Log.d(TAG, "Found stored encryption key for $macName")

            // Update device information
            val ourIp = DeviceInfoUtil.getWifiIpAddress(context)
            if (ourIp != null) {
                dataStoreManager.saveNetworkDeviceConnection(
                    deviceName = macName,
                    ourIp = ourIp,
                    clientIp = macIp,
                    port = macPort.toString(),
                    isPlus = true,
                    symmetricKey = encryptionKey,
                    model = "Mac",
                    deviceType = "desktop"
                )

                val connectedDevice = ConnectedDevice(
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

            Log.d(TAG, "Attempting to connect to Mac at $macIp:$macPort (isManual=$isManual)")
            WebSocketUtil.connect(
                context = context,
                ipAddress = macIp,
                port = macPort,
                symmetricKey = encryptionKey,
                manualAttempt = isManual, // forward manual flag so Mac's "Connect" button overrides guards
                onConnectionStatus = { connected ->
                    if (connected) {
                        Log.i(TAG, "Successfully connected after wake-up")
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                dataStoreManager.updateNetworkDeviceLastConnected(
                                    macName,
                                    System.currentTimeMillis()
                                )
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing wake-up request", e)
        }
    }

    private suspend fun findStoredEncryptionKey(
        context: Context,
        dataStoreManager: DataStoreManager,
        macIp: String,
        macPort: Int,
        macName: String
    ): String? {
        try {
            val networkDevices = dataStoreManager.getAllNetworkDeviceConnections().first()
            val ourIp = DeviceInfoUtil.getWifiIpAddress(context)

            // 1. Exact match with current IP & Mac name (case-insensitive)
            if (ourIp != null) {
                val networkDevice = networkDevices.firstOrNull { device ->
                    device.deviceName.equals(macName, ignoreCase = true) && device.getClientIpForNetwork(ourIp) == macIp
                }
                if (networkDevice?.symmetricKey != null) return networkDevice.symmetricKey
            }

            // 2. Match Mac name from last connected device (case-insensitive)
            val lastConnectedDevice = dataStoreManager.getLastConnectedDevice().first()
            if (lastConnectedDevice?.name.equals(macName, ignoreCase = true) && lastConnectedDevice?.symmetricKey != null) {
                return lastConnectedDevice.symmetricKey
            }

            // 3. Match Mac name from any stored device (case-insensitive)
            val nameMatchDevice = networkDevices.firstOrNull { it.deviceName.equals(macName, ignoreCase = true) }
            if (nameMatchDevice?.symmetricKey != null) {
                return nameMatchDevice.symmetricKey
            }

            // 4. Fallback: if we only have one paired device total, use that key
            if (networkDevices.size == 1) {
                val singleDevice = networkDevices.first()
                if (singleDevice.symmetricKey != null) {
                    Log.d(TAG, "Fallback: using key from the single paired device: ${singleDevice.deviceName}")
                    return singleDevice.symmetricKey
                }
            }

            // 5. Fallback: if we have a last connected device key, try it regardless of name match
            if (lastConnectedDevice?.symmetricKey != null) {
                Log.d(TAG, "Fallback: using key from last connected device: ${lastConnectedDevice.name}")
                return lastConnectedDevice.symmetricKey
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding stored encryption key", e)
            return null
        }
    }
}
