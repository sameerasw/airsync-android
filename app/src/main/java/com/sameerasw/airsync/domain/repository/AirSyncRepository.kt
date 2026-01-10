package com.sameerasw.airsync.domain.repository

import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.domain.model.NotificationApp
import com.sameerasw.airsync.domain.model.NetworkDeviceConnection
import kotlinx.coroutines.flow.Flow

interface AirSyncRepository {
    suspend fun saveIpAddress(ipAddress: String)
    fun getIpAddress(): Flow<String>

    suspend fun savePort(port: String)
    fun getPort(): Flow<String>

    suspend fun saveDeviceName(deviceName: String)
    fun getDeviceName(): Flow<String>

    suspend fun setFirstRun(isFirstRun: Boolean)
    fun getFirstRun(): Flow<Boolean>

    suspend fun setPermissionsChecked(checked: Boolean)
    fun getPermissionsChecked(): Flow<Boolean>

    suspend fun setNotificationSyncEnabled(enabled: Boolean)
    fun getNotificationSyncEnabled(): Flow<Boolean>

    suspend fun setDeveloperMode(enabled: Boolean)
    fun getDeveloperMode(): Flow<Boolean>

    suspend fun saveLastConnectedDevice(device: ConnectedDevice)
    fun getLastConnectedDevice(): Flow<ConnectedDevice?>

    // Network-aware device connections
    suspend fun saveNetworkDeviceConnection(deviceName: String, ourIp: String, clientIp: String, port: String, isPlus: Boolean, symmetricKey: String?, model: String? = null, deviceType: String? = null)
    fun getNetworkDeviceConnection(deviceName: String): Flow<NetworkDeviceConnection?>
    fun getAllNetworkDeviceConnections(): Flow<List<NetworkDeviceConnection>>
    suspend fun updateNetworkDeviceLastConnected(deviceName: String, timestamp: Long)

    // App notification preferences
    suspend fun saveNotificationApps(apps: List<NotificationApp>)
    fun getNotificationApps(): Flow<List<NotificationApp>>

    // Last sync time tracking
    suspend fun updateLastSyncTime(timestamp: Long)
    fun getLastSyncTime(): Flow<Long?>

    // Clipboard sync settings
    suspend fun setClipboardSyncEnabled(enabled: Boolean)
    fun getClipboardSyncEnabled(): Flow<Boolean>

    suspend fun setClipboardHistoryEnabled(enabled: Boolean)
    fun getClipboardHistoryEnabled(): Flow<Boolean>

    // Auto reconnect settings
    suspend fun setAutoReconnectEnabled(enabled: Boolean)
    fun getAutoReconnectEnabled(): Flow<Boolean>

    // Continue Browsing settings
    suspend fun setContinueBrowsingEnabled(enabled: Boolean)
    fun getContinueBrowsingEnabled(): Flow<Boolean>

    // Send now playing settings
    suspend fun setSendNowPlayingEnabled(enabled: Boolean)
    fun getSendNowPlayingEnabled(): Flow<Boolean>

    // Keep previous link settings
    suspend fun setKeepPreviousLinkEnabled(enabled: Boolean)
    fun getKeepPreviousLinkEnabled(): Flow<Boolean>

    // Smartspacer settings
    suspend fun setSmartspacerShowWhenDisconnected(enabled: Boolean)
    fun getSmartspacerShowWhenDisconnected(): Flow<Boolean>

    // User manual disconnect tracking
    suspend fun setUserManuallyDisconnected(disconnected: Boolean)
    fun getUserManuallyDisconnected(): Flow<Boolean>

    // Mac Media controls
    suspend fun setMacMediaControlsEnabled(enabled: Boolean)
    fun getMacMediaControlsEnabled(): Flow<Boolean>
}