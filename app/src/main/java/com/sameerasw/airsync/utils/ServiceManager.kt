package com.sameerasw.airsync.utils

import android.content.Context
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.service.AirSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manages the lifecycle of background services based on connection status
 * and user preferences for background features.
 */
object ServiceManager {
    
    /**
     * Determines if any background service should be running based on settings.
     */
    suspend fun shouldServiceRun(context: Context): Boolean {
        val dataStore = DataStoreManager.getInstance(context)
        val isConnected = WebSocketUtil.isConnected()
        val isAutoReconnectEnabled = dataStore.getAutoReconnectEnabled().first()
        val isDiscoveryEnabled = dataStore.getDeviceDiscoveryEnabled().first()
        
        // Service needs to run if:
        // 1. We are currently connected
        // 2. We need to auto-reconnect in the background
        // 3. Device discovery is enabled
        return isConnected || isAutoReconnectEnabled || isDiscoveryEnabled
    }

    /**
     * Updates the status of the AirSyncService based on current conditions.
     */
    fun updateServiceState(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            if (shouldServiceRun(context)) {
                AirSyncService.startScanning(context)
            } else {
                AirSyncService.stop(context)
            }
        }
    }
}
