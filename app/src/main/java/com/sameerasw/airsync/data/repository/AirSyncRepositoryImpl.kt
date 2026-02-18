package com.sameerasw.airsync.data.repository

import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.domain.model.NetworkDeviceConnection
import com.sameerasw.airsync.domain.model.NotificationApp
import com.sameerasw.airsync.domain.repository.AirSyncRepository
import kotlinx.coroutines.flow.Flow

class AirSyncRepositoryImpl(
    private val dataStoreManager: DataStoreManager
) : AirSyncRepository {

    override suspend fun saveIpAddress(ipAddress: String) {
        dataStoreManager.saveIpAddress(ipAddress)
    }

    override fun getIpAddress(): Flow<String> {
        return dataStoreManager.getIpAddress()
    }

    override suspend fun savePort(port: String) {
        dataStoreManager.savePort(port)
    }

    override fun getPort(): Flow<String> {
        return dataStoreManager.getPort()
    }

    override suspend fun saveDeviceName(deviceName: String) {
        dataStoreManager.saveDeviceName(deviceName)
    }

    override fun getDeviceName(): Flow<String> {
        return dataStoreManager.getDeviceName()
    }

    override suspend fun setFirstRun(isFirstRun: Boolean) {
        dataStoreManager.setFirstRun(isFirstRun)
    }

    override fun getFirstRun(): Flow<Boolean> {
        return dataStoreManager.getFirstRun()
    }

    override suspend fun setPermissionsChecked(checked: Boolean) {
        dataStoreManager.setPermissionsChecked(checked)
    }

    override fun getPermissionsChecked(): Flow<Boolean> {
        return dataStoreManager.getPermissionsChecked()
    }

    override suspend fun setNotificationSyncEnabled(enabled: Boolean) {
        dataStoreManager.setNotificationSyncEnabled(enabled)
    }

    override fun getNotificationSyncEnabled(): Flow<Boolean> {
        return dataStoreManager.getNotificationSyncEnabled()
    }

    override suspend fun setDeveloperMode(enabled: Boolean) {
        dataStoreManager.setDeveloperMode(enabled)
    }

    override fun getDeveloperMode(): Flow<Boolean> {
        return dataStoreManager.getDeveloperMode()
    }

    override suspend fun saveLastConnectedDevice(device: ConnectedDevice) {
        dataStoreManager.saveLastConnectedDevice(device)
    }

    override fun getLastConnectedDevice(): Flow<ConnectedDevice?> {
        return dataStoreManager.getLastConnectedDevice()
    }

    // Network-aware device connections
    override suspend fun saveNetworkDeviceConnection(
        deviceName: String,
        ourIp: String,
        clientIp: String,
        port: String,
        isPlus: Boolean,
        symmetricKey: String?,
        model: String?,
        deviceType: String?
    ) {
        dataStoreManager.saveNetworkDeviceConnection(
            deviceName,
            ourIp,
            clientIp,
            port,
            isPlus,
            symmetricKey,
            model,
            deviceType
        )
    }

    override fun getNetworkDeviceConnection(deviceName: String): Flow<NetworkDeviceConnection?> {
        return dataStoreManager.getNetworkDeviceConnection(deviceName)
    }

    override fun getAllNetworkDeviceConnections(): Flow<List<NetworkDeviceConnection>> {
        return dataStoreManager.getAllNetworkDeviceConnections()
    }

    override suspend fun updateNetworkDeviceLastConnected(deviceName: String, timestamp: Long) {
        dataStoreManager.updateNetworkDeviceLastConnected(deviceName, timestamp)
    }

    override suspend fun saveNotificationApps(apps: List<NotificationApp>) {
        dataStoreManager.saveNotificationApps(apps)
    }

    override fun getNotificationApps(): Flow<List<NotificationApp>> {
        return dataStoreManager.getNotificationApps()
    }

    override suspend fun updateLastSyncTime(timestamp: Long) {
        dataStoreManager.updateLastSyncTime(timestamp)
    }

    override fun getLastSyncTime(): Flow<Long?> {
        return dataStoreManager.getLastSyncTime()
    }

    override suspend fun setClipboardSyncEnabled(enabled: Boolean) {
        dataStoreManager.setClipboardSyncEnabled(enabled)
    }

    override fun getClipboardSyncEnabled(): Flow<Boolean> {
        return dataStoreManager.getClipboardSyncEnabled()
    }

    override suspend fun setClipboardHistoryEnabled(enabled: Boolean) {
        dataStoreManager.setClipboardHistoryEnabled(enabled)
    }

    override fun getClipboardHistoryEnabled(): Flow<Boolean> {
        return dataStoreManager.getClipboardHistoryEnabled()
    }

    override suspend fun setAutoReconnectEnabled(enabled: Boolean) {
        dataStoreManager.setAutoReconnectEnabled(enabled)
    }

    override fun getAutoReconnectEnabled(): Flow<Boolean> {
        return dataStoreManager.getAutoReconnectEnabled()
    }

    override suspend fun setContinueBrowsingEnabled(enabled: Boolean) {
        dataStoreManager.setContinueBrowsingEnabled(enabled)
    }

    override fun getContinueBrowsingEnabled(): Flow<Boolean> {
        return dataStoreManager.getContinueBrowsingEnabled()
    }

    // New: Send now playing setting
    override suspend fun setSendNowPlayingEnabled(enabled: Boolean) {
        dataStoreManager.setSendNowPlayingEnabled(enabled)
    }

    override fun getSendNowPlayingEnabled(): Flow<Boolean> {
        return dataStoreManager.getSendNowPlayingEnabled()
    }

    // New: Keep previous link setting
    override suspend fun setKeepPreviousLinkEnabled(enabled: Boolean) {
        dataStoreManager.setKeepPreviousLinkEnabled(enabled)
    }

    override fun getKeepPreviousLinkEnabled(): Flow<Boolean> {
        return dataStoreManager.getKeepPreviousLinkEnabled()
    }

    override suspend fun setSmartspacerShowWhenDisconnected(enabled: Boolean) {
        dataStoreManager.setSmartspacerShowWhenDisconnected(enabled)
    }

    override fun getSmartspacerShowWhenDisconnected(): Flow<Boolean> {
        return dataStoreManager.getSmartspacerShowWhenDisconnected()
    }

    override suspend fun setUserManuallyDisconnected(disconnected: Boolean) {
        dataStoreManager.setUserManuallyDisconnected(disconnected)
    }

    override fun getUserManuallyDisconnected(): Flow<Boolean> {
        return dataStoreManager.getUserManuallyDisconnected()
    }

    override suspend fun setMacMediaControlsEnabled(enabled: Boolean) {
        dataStoreManager.setMacMediaControlsEnabled(enabled)
    }

    override fun getMacMediaControlsEnabled(): Flow<Boolean> {
        return dataStoreManager.getMacMediaControlsEnabled()
    }

    override suspend fun setDefaultTab(tab: String) {
        dataStoreManager.setDefaultTab(tab)
    }

    override fun getDefaultTab(): Flow<String> {
        return dataStoreManager.getDefaultTab()
    }

    override suspend fun setEssentialsConnectionEnabled(enabled: Boolean) {
        dataStoreManager.setEssentialsConnectionEnabled(enabled)
    }

    override fun getEssentialsConnectionEnabled(): Flow<Boolean> {
        return dataStoreManager.getEssentialsConnectionEnabled()
    }

    override suspend fun setExpandNetworkingEnabled(enabled: Boolean) {
        dataStoreManager.setExpandNetworkingEnabled(enabled)
    }

    override fun getExpandNetworkingEnabled(): Flow<Boolean> {
        return dataStoreManager.getExpandNetworkingEnabled()
    }

    override suspend fun setDeviceDiscoveryEnabled(enabled: Boolean) {
        dataStoreManager.setDeviceDiscoveryEnabled(enabled)
    }

    override fun getDeviceDiscoveryEnabled(): Flow<Boolean> {
        return dataStoreManager.getDeviceDiscoveryEnabled()
    }

    override suspend fun setFirstMacConnectionTime(time: Long) {
        dataStoreManager.setFirstMacConnectionTime(time)
    }

    override fun getFirstMacConnectionTime(): Flow<Long> {
        return dataStoreManager.getFirstMacConnectionTime()
    }

    override suspend fun setLastPromptDismissedVersion(version: Int) {
        dataStoreManager.setLastPromptDismissedVersion(version)
    }

    override fun getLastPromptDismissedVersion(): Flow<Int> {
        return dataStoreManager.getLastPromptDismissedVersion()
    }

    override suspend fun setHasRatedApp(hasRated: Boolean) {
        dataStoreManager.setHasRatedApp(hasRated)
    }

    override fun hasRatedApp(): Flow<Boolean> {
        return dataStoreManager.hasRatedApp()
    }
}