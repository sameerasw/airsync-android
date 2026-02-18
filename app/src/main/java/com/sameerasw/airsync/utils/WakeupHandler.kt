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
        macName: String
    ) {
        try {
            Log.i(TAG, "Processing wake-up request from $macName at $macIp:$macPort")

            if (macIp.isEmpty()) {
                Log.w(TAG, "Wake-up request missing Mac IP address")
                return
            }

            val dataStoreManager = DataStoreManager.getInstance(context)

            if (WebSocketUtil.isConnected()) {
                Log.d(TAG, "Already connected, ignoring wake-up request")
                return
            }

            // Clear manual disconnect flag since this is an external wake-up request
            dataStoreManager.setUserManuallyDisconnected(false)

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

            Log.d(TAG, "Attempting to connect to Mac at $macIp:$macPort")
            WebSocketUtil.connect(
                context = context,
                ipAddress = macIp,
                port = macPort,
                symmetricKey = encryptionKey,
                manualAttempt = false,
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

            if (ourIp != null) {
                val networkDevice = networkDevices.firstOrNull { device ->
                    device.deviceName == macName && device.getClientIpForNetwork(ourIp) == macIp
                }
                if (networkDevice?.symmetricKey != null) return networkDevice.symmetricKey
            }

            val lastConnectedDevice = dataStoreManager.getLastConnectedDevice().first()
            if (lastConnectedDevice?.name == macName && lastConnectedDevice.symmetricKey != null) {
                return lastConnectedDevice.symmetricKey
            }

            return networkDevices.firstOrNull { it.deviceName == macName }?.symmetricKey
        } catch (e: Exception) {
            return null
        }
    }
}
