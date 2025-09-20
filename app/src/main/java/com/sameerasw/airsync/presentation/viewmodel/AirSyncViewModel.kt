package com.sameerasw.airsync.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.data.repository.AirSyncRepositoryImpl
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.domain.model.DeviceInfo
import com.sameerasw.airsync.domain.model.NetworkDeviceConnection
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.domain.repository.AirSyncRepository
import com.sameerasw.airsync.utils.DeviceInfoUtil
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.NetworkMonitor
import com.sameerasw.airsync.utils.NotificationUtil
import com.sameerasw.airsync.utils.PermissionUtil
import com.sameerasw.airsync.utils.SyncManager
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class AirSyncViewModel(
    private val repository: AirSyncRepository
) : ViewModel() {

    companion object {
        fun create(context: Context): AirSyncViewModel {
            val dataStoreManager = DataStoreManager(context)
            val repository = AirSyncRepositoryImpl(dataStoreManager)
            return AirSyncViewModel(repository)
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _deviceInfo = MutableStateFlow(DeviceInfo())
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    // Network-aware device connections state
    private val _networkDevices = MutableStateFlow<List<NetworkDeviceConnection>>(emptyList())

    // Network monitoring
    private var isNetworkMonitoringActive = false
    private var previousNetworkIp: String? = null

    // Auto-reconnect
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var autoReconnectStart: Long = 0L
    private var appContext: Context? = null

    // Connection status listener for WebSocket updates
    private val connectionStatusListener: (Boolean) -> Unit = { isConnected ->
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnected = isConnected,
                isConnecting = false,
                response = if (isConnected) "Connected successfully!" else "Disconnected"
            )

            // Cancel auto-reconnect when connected; schedule when disconnected (if allowed)
            if (isConnected) {
                cancelAutoReconnect()
            } else {
                appContext?.let { ctx ->
                    maybeScheduleAutoReconnect(ctx)
                }
            }

            // Update persistent status notification to reflect real-time state
            appContext?.let { pushStatusNotification(it) }
        }
    }

    init {
        // Register for WebSocket connection status updates
        WebSocketUtil.registerConnectionStatusListener(connectionStatusListener)

        // Observe Mac device status updates
        viewModelScope.launch {
            MacDeviceStatusManager.macDeviceStatus.collect { macStatus ->
                _uiState.value = _uiState.value.copy(macDeviceStatus = macStatus)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister the connection status listener when ViewModel is cleared
        WebSocketUtil.unregisterConnectionStatusListener(connectionStatusListener)

        // Clean up Mac media session
        MacDeviceStatusManager.cleanup()
    }

    private fun startObservingDeviceChanges(context: Context) {
        val dataStoreManager = DataStoreManager(context)
        
        // Observe both last connected device and network devices for real-time updates
        viewModelScope.launch {
            dataStoreManager.getLastConnectedDevice().collect { device ->
                Log.d("AirSyncViewModel", "Last connected device changed: ${device?.name}, isPlus: ${device?.isPlus}")
                updateDisplayedDevice(context)
            }
        }
        
        viewModelScope.launch {
            dataStoreManager.getAllNetworkDeviceConnections().collect { networkDevices ->
                Log.d("AirSyncViewModel", "Network devices changed: ${networkDevices.size} devices")
                _networkDevices.value = networkDevices
                updateDisplayedDevice(context)
            }
        }
    }

    private fun updateDisplayedDevice(context: Context) {
        viewModelScope.launch {
            // Get current network IP for network-aware device lookup
            val currentIp = DeviceInfoUtil.getWifiIpAddress(context) ?: "Unknown"
            _deviceInfo.value = _deviceInfo.value.copy(localIp = currentIp)
            
            // Use network-aware device if available for current network, otherwise use the stored device
            val networkAwareDevice = getNetworkAwareLastConnectedDevice()
            val storedDevice = repository.getLastConnectedDevice().first()
            val deviceToShow = networkAwareDevice ?: storedDevice
            
            Log.d("AirSyncViewModel", "Updating displayed device: ${deviceToShow?.name}, isPlus: ${deviceToShow?.isPlus}, model: ${deviceToShow?.model}")
            _uiState.value = _uiState.value.copy(lastConnectedDevice = deviceToShow)
        }
    }

    fun initializeState(
        context: Context,
        initialIp: String? = null,
        initialPort: String? = null,
        showConnectionDialog: Boolean = false,
        pcName: String? = null,
        isPlus: Boolean = false,
        symmetricKey: String? = null
    ) {
        appContext = context.applicationContext
        viewModelScope.launch {
            // Load saved values
            val savedIp = initialIp ?: repository.getIpAddress().first()
            val savedPort = initialPort ?: repository.getPort().first()
            val savedDeviceName = repository.getDeviceName().first()
            val lastConnected = repository.getLastConnectedDevice().first()
            val isNotificationSyncEnabled = repository.getNotificationSyncEnabled().first()
            val isDeveloperMode = repository.getDeveloperMode().first()
            val isClipboardSyncEnabled = repository.getClipboardSyncEnabled().first()
            val lastConnectedSymmetricKey = lastConnected?.symmetricKey
            val isAutoReconnectEnabled = repository.getAutoReconnectEnabled().first()
            val isContinueBrowsingEnabled = repository.getContinueBrowsingEnabled().first()
            val isSendNowPlayingEnabled = repository.getSendNowPlayingEnabled().first()

            // Get device info
            val deviceName = savedDeviceName.ifEmpty {
                val autoName = DeviceInfoUtil.getDeviceName(context)
                repository.saveDeviceName(autoName)
                autoName
            }

            val localIp = DeviceInfoUtil.getWifiIpAddress(context) ?: "Unknown"

            _deviceInfo.value = DeviceInfo(name = deviceName, localIp = localIp)

            // Load network-aware device connections
            loadNetworkDevices()

            // Check for network-aware device for current network
            val networkAwareDevice = getNetworkAwareLastConnectedDevice()
            val deviceToShow = networkAwareDevice ?: lastConnected

            // Check permissions
            val missingPermissions = PermissionUtil.getAllMissingPermissions(context)
            val isNotificationEnabled = PermissionUtil.isNotificationListenerEnabled(context)

            // Check current WebSocket connection status
            val currentlyConnected = WebSocketUtil.isConnected()

            _uiState.value = _uiState.value.copy(
                ipAddress = savedIp,
                port = savedPort,
                deviceNameInput = deviceName,
                // Only show dialog if not already connected and showConnectionDialog is true
                isDialogVisible = showConnectionDialog && !currentlyConnected,
                missingPermissions = missingPermissions,
                isNotificationEnabled = isNotificationEnabled,
                lastConnectedDevice = deviceToShow,
                isNotificationSyncEnabled = isNotificationSyncEnabled,
                isDeveloperMode = isDeveloperMode,
                isClipboardSyncEnabled = isClipboardSyncEnabled,
                isConnected = currentlyConnected,
                symmetricKey = symmetricKey ?: lastConnectedSymmetricKey,
                isAutoReconnectEnabled = isAutoReconnectEnabled,
                isContinueBrowsingEnabled = isContinueBrowsingEnabled,
                isSendNowPlayingEnabled = isSendNowPlayingEnabled
            )

            // If we have PC name from QR code and not already connected, store it temporarily for the dialog
            if (pcName != null && showConnectionDialog && !currentlyConnected) {
                _uiState.value = _uiState.value.copy(
                    lastConnectedDevice = ConnectedDevice(
                        name = pcName,
                        ipAddress = savedIp,
                        port = savedPort,
                        lastConnected = System.currentTimeMillis(),
                        isPlus = isPlus,
                        symmetricKey = symmetricKey
                    )
                )
            }

            // If not connected and conditions allow, schedule auto-reconnect
            if (!currentlyConnected) {
                maybeScheduleAutoReconnect(context)
            }

            // Push initial status notification
            pushStatusNotification(context)

            // Start observing device changes for real-time updates
            startObservingDeviceChanges(context)
        }
    }

    fun updateIpAddress(ipAddress: String) {
        _uiState.value = _uiState.value.copy(ipAddress = ipAddress)
        viewModelScope.launch {
            repository.saveIpAddress(ipAddress)
        }
    }

    fun updatePort(port: String) {
        _uiState.value = _uiState.value.copy(port = port)
        viewModelScope.launch {
            repository.savePort(port)
        }
    }

    fun updateSymmetricKey(symmetricKey: String?) {
        _uiState.value = _uiState.value.copy(symmetricKey = symmetricKey)
    }

    fun updateManualPcName(name: String) {
        _uiState.value = _uiState.value.copy(manualPcName = name)
    }

    fun updateManualIsPlus(isPlus: Boolean) {
        _uiState.value = _uiState.value.copy(manualIsPlus = isPlus)
    }

    fun prepareForManualConnection() {
        val manualDevice = ConnectedDevice(
            name = _uiState.value.manualPcName.ifEmpty { "My Mac/PC" },
            ipAddress = _uiState.value.ipAddress,
            port = _uiState.value.port,
            lastConnected = System.currentTimeMillis(),
            isPlus = _uiState.value.manualIsPlus,
            symmetricKey = _uiState.value.symmetricKey
        )
        _uiState.value = _uiState.value.copy(
            lastConnectedDevice = manualDevice,
            isDialogVisible = true
        )
    }

    fun updateDeviceName(name: String) {
        _uiState.value = _uiState.value.copy(deviceNameInput = name)
        _deviceInfo.value = _deviceInfo.value.copy(name = name)
        viewModelScope.launch {
            repository.saveDeviceName(name)
        }

        val ctx = appContext
        if (ctx != null) {
            // Send updated device info immediately so desktop sees the new name
            try {
                com.sameerasw.airsync.utils.SyncManager.sendDeviceInfoNow(ctx, name)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = loading)
    }

    fun setResponse(response: String) {
        _uiState.value = _uiState.value.copy(response = response)
    }

    fun setDialogVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(isDialogVisible = visible)
    }

    fun setPermissionDialogVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showPermissionDialog = visible)
    }

    fun setConnectionStatus(isConnected: Boolean, isConnecting: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            isConnected = isConnected,
            isConnecting = isConnecting
        )
        appContext?.let { ctx ->
            viewModelScope.launch { pushStatusNotification(ctx) }
        }
    }

    fun refreshPermissions(context: Context) {
        val missingPermissions = PermissionUtil.getAllMissingPermissions(context)
        val isNotificationEnabled = PermissionUtil.isNotificationListenerEnabled(context)
        _uiState.value = _uiState.value.copy(
            missingPermissions = missingPermissions,
            isNotificationEnabled = isNotificationEnabled
        )
    }

    fun saveLastConnectedDevice(pcName: String? = null, isPlus: Boolean = false, symmetricKey: String? = null) {
        viewModelScope.launch {
            val deviceName = pcName ?: "My Mac"
            val ourIp = _deviceInfo.value.localIp
            val clientIp = _uiState.value.ipAddress
            val port = _uiState.value.port

            // Save using network-aware storage
            repository.saveNetworkDeviceConnection(deviceName, ourIp, clientIp, port, isPlus, symmetricKey)

            // Also save to legacy storage for backwards compatibility
            val connectedDevice = ConnectedDevice(
                name = deviceName,
                ipAddress = clientIp,
                port = port,
                lastConnected = System.currentTimeMillis(),
                isPlus = isPlus,
                symmetricKey = symmetricKey
            )
            repository.saveLastConnectedDevice(connectedDevice)
            _uiState.value = _uiState.value.copy(lastConnectedDevice = connectedDevice)

            // Refresh network devices list
            loadNetworkDevices()

            // Update status notification with new device name
            appContext?.let { pushStatusNotification(it) }
        }
    }

    private suspend fun loadNetworkDevices() {
        repository.getAllNetworkDeviceConnections().first().let { devices ->
            _networkDevices.value = devices
        }
    }

    fun getNetworkAwareLastConnectedDevice(): ConnectedDevice? {
        val ourIp = _deviceInfo.value.localIp
        if (ourIp.isEmpty() || ourIp == "Unknown") return null

        // Find the most recent device that has a connection for our current network
        val networkDevice = _networkDevices.value
            .filter { it.getClientIpForNetwork(ourIp) != null }
            .maxByOrNull { it.lastConnected }

        return networkDevice?.toConnectedDevice(ourIp)
    }

    fun setNotificationSyncEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isNotificationSyncEnabled = enabled)
        viewModelScope.launch {
            repository.setNotificationSyncEnabled(enabled)
        }
    }

    fun setDeveloperMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isDeveloperMode = enabled)
        viewModelScope.launch {
            repository.setDeveloperMode(enabled)
        }
    }

    fun toggleDeveloperModeVisibility() {
        _uiState.value = _uiState.value.copy(
            isDeveloperModeVisible = !_uiState.value.isDeveloperModeVisible
        )
    }

    fun setClipboardSyncEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isClipboardSyncEnabled = enabled)
        viewModelScope.launch {
            repository.setClipboardSyncEnabled(enabled)
        }
    }

    fun manualSyncAppIcons(context: Context) {
        _uiState.value = _uiState.value.copy(isIconSyncLoading = true, iconSyncMessage = "")

        SyncManager.manualSyncAppIcons(context) { success, message ->
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    isIconSyncLoading = false,
                    iconSyncMessage = message
                )
            }
        }
    }

    fun clearIconSyncMessage() {
        _uiState.value = _uiState.value.copy(iconSyncMessage = "")
    }

    fun setUserManuallyDisconnected(disconnected: Boolean) {
        viewModelScope.launch {
            repository.setUserManuallyDisconnected(disconnected)
            if (disconnected) {
                cancelAutoReconnect()
            }
            appContext?.let { pushStatusNotification(it) }
        }
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isAutoReconnectEnabled = enabled)
        viewModelScope.launch {
            repository.setAutoReconnectEnabled(enabled)
            if (!enabled) {
                cancelAutoReconnect()
            } else {
                appContext?.let { ctx ->
                    if (!_uiState.value.isConnected) {
                        maybeScheduleAutoReconnect(ctx)
                    }
                }
            }
            appContext?.let { pushStatusNotification(it) }
        }
    }

    private fun cancelAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        autoReconnectStart = 0L
    }

    private fun hasNetworkAwareMappingForLastDevice(): ConnectedDevice? {
        val ourIp = _deviceInfo.value.localIp
        val last = _uiState.value.lastConnectedDevice ?: return null
        if (ourIp.isEmpty() || ourIp == "Unknown" || ourIp == "No Wi-Fi") return null
        // Find matching device by name with mapping for our IP
        val networkDevice = _networkDevices.value.firstOrNull { it.deviceName == last.name && it.getClientIpForNetwork(ourIp) != null }
        return networkDevice?.toConnectedDevice(ourIp)
    }

    fun maybeScheduleAutoReconnect(context: Context) {
        viewModelScope.launch {
            try {
                val autoEnabled = repository.getAutoReconnectEnabled().first()
                val manuallyDisconnected = repository.getUserManuallyDisconnected().first()
                if (!autoEnabled || manuallyDisconnected || WebSocketUtil.isConnected()) {
                    return@launch
                }

                // Ensure we have latest network devices
                loadNetworkDevices()

                val targetDevice = hasNetworkAwareMappingForLastDevice() ?: return@launch

                if (autoReconnectJob?.isActive == true) return@launch

                autoReconnectStart = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(response = "Will auto reconnect to ${targetDevice.name} if possible")

                // Reflect auto-reconnect waiting state
                pushStatusNotification(context)

                autoReconnectJob = viewModelScope.launch {
                    while (coroutineContext.isActive) {
                        // Stop conditions
                        val autoStillEnabled = repository.getAutoReconnectEnabled().first()
                        val stillManual = repository.getUserManuallyDisconnected().first()
                        if (WebSocketUtil.isConnected() || !autoStillEnabled || stillManual) {
                            break
                        }

                        // Re-resolve mapping in case IP changed
                        val currentTarget = hasNetworkAwareMappingForLastDevice()
                        if (currentTarget == null) {
                            // No longer on a known network for last device; stop trying
                            break
                        }

                        // Delay according to elapsed time window
                        val elapsed = System.currentTimeMillis() - autoReconnectStart
                        val delayMs = if (elapsed <= 60_000L) 10_000L else 60_000L
                        delay(delayMs)

                        if (WebSocketUtil.isConnected()) continue

                        // Attempt connection
                        _uiState.value = _uiState.value.copy(isConnecting = true, response = "Auto reconnecting to ${currentTarget.name}...")
                        // Notification will show Connecting via WebSocketUtil.connect
                        WebSocketUtil.connect(
                            context = context,
                            ipAddress = currentTarget.ipAddress,
                            port = currentTarget.port.toIntOrNull() ?: 6996,
                            symmetricKey = currentTarget.symmetricKey,
                            onConnectionStatus = { connected ->
                                viewModelScope.launch {
                                    _uiState.value = _uiState.value.copy(isConnecting = false)
                                    if (connected) {
                                        _uiState.value = _uiState.value.copy(response = "Auto-reconnected to ${currentTarget.name}")
                                        repository.updateNetworkDeviceLastConnected(currentTarget.name, System.currentTimeMillis())
                                        cancelAutoReconnect()
                                    } else {
                                        _uiState.value = _uiState.value.copy(response = "Auto-reconnect attempt failed")
                                    }

                                    // Push status notification after attempt
                                    appContext?.let { pushStatusNotification(it) }
                                }
                            }
                        )
                    }
                }
            } catch (_: Exception) {
                // no-op
            }
        }
    }

    private suspend fun loadNetworkDevicesForNetworkChange() {
        // thin wrapper in case logic needs splitting
        loadNetworkDevices()
    }

    private suspend fun attemptAutoReconnection(context: Context, device: ConnectedDevice) {
        // Add a small delay to ensure network is stable
        delay(2000)

        // Check if we're still on the same network and not manually disconnected
        val currentIp = DeviceInfoUtil.getWifiIpAddress(context)
        val userManuallyDisconnected = repository.getUserManuallyDisconnected().first()

        if (currentIp != null && !userManuallyDisconnected && !WebSocketUtil.isConnected()) {
            setConnectionStatus(isConnected = false, isConnecting = true)
            setResponse("Auto-reconnecting to ${device.name}...")

            WebSocketUtil.connect(
                context = context,
                ipAddress = device.ipAddress,
                port = device.port.toIntOrNull() ?: 6996,
                symmetricKey = device.symmetricKey,
                onConnectionStatus = { connected ->
                    viewModelScope.launch {
                        setConnectionStatus(isConnected = connected, isConnecting = false)
                        if (connected) {
                            setResponse("Auto-reconnected to ${device.name}")
                            // Update last connected timestamp
                            repository.updateNetworkDeviceLastConnected(device.name, System.currentTimeMillis())
                        } else {
                            setResponse("Auto-reconnection failed")
                        }
                    }
                },
                onMessage = { response ->
                    viewModelScope.launch {
                        setResponse("Received: $response")
                        // Handle clipboard updates and other messages as usual
                        try {
                            val json = org.json.JSONObject(response)
                            if (json.optString("type") == "clipboardUpdate") {
                                val data = json.optJSONObject("data")
                                val text = data?.optString("text")
                                if (!text.isNullOrEmpty()) {
                                    com.sameerasw.airsync.utils.ClipboardSyncManager.handleClipboardUpdate(context, text)
                                }
                            }
                        } catch (_: Exception) {
                            // Not a clipboard update, ignore
                        }
                    }
                }
            )
        }
    }

    // Build and push the latest status notification
    private suspend fun pushStatusNotification(context: Context) {
        try {
            val last = repository.getLastConnectedDevice().first()
            val deviceName = last?.name
            val ourIp = DeviceInfoUtil.getWifiIpAddress(context)
            val all = repository.getAllNetworkDeviceConnections().first()
            val hasReconnectTarget = if (ourIp != null && last != null) {
                all.firstOrNull { it.deviceName == last.name && it.getClientIpForNetwork(ourIp) != null } != null
            } else false
            val autoEnabled = repository.getAutoReconnectEnabled().first()
            val manual = repository.getUserManuallyDisconnected().first()
            val isConnected = _uiState.value.isConnected
            val isConnecting = _uiState.value.isConnecting

            val shouldShow = !isConnected && autoEnabled && !manual && hasReconnectTarget
            if (shouldShow) {
                val isAutoReconnecting = true // show notification during both waiting and connecting
                NotificationUtil.showConnectionStatusNotification(
                    context = context,
                    deviceName = deviceName,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    isAutoReconnecting = isAutoReconnecting,
                    hasReconnectTarget = hasReconnectTarget
                )
            } else {
                NotificationUtil.hideConnectionStatusNotification(context)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    // Start monitoring network changes (Wi-Fi IP) to update mappings and trigger auto-reconnect attempts
    fun startNetworkMonitoring(context: Context) {
        if (isNetworkMonitoringActive) return
        isNetworkMonitoringActive = true
        previousNetworkIp = DeviceInfoUtil.getWifiIpAddress(context) ?: "Unknown"

        viewModelScope.launch {
            try {
                NetworkMonitor.observeNetworkChanges(context).collect { networkInfo ->
                    val currentIp = networkInfo.ipAddress ?: "No Wi-Fi"

                    if (currentIp != previousNetworkIp) {
                        previousNetworkIp = currentIp

                        // Update local device info
                        _deviceInfo.value = _deviceInfo.value.copy(localIp = currentIp)

                        // Reload network-aware device mappings
                        loadNetworkDevicesForNetworkChange()

                        // If we have a mapping for the last connected device, try auto-reconnect
                        val target = getNetworkAwareLastConnectedDevice()
                        if (target != null && !_uiState.value.isConnected) {
                            // Attempt auto-reconnect asynchronously
                            try {
                                attemptAutoReconnection(context, target)
                            } catch (_: Exception) { /* ignore */ }
                        }

                        // Update persistent status notification
                        pushStatusNotification(context)
                    }
                }
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    fun setContinueBrowsingEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isContinueBrowsingEnabled = enabled)
        viewModelScope.launch {
            repository.setContinueBrowsingEnabled(enabled)
        }
    }

    fun setSendNowPlayingEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isSendNowPlayingEnabled = enabled)
        viewModelScope.launch {
            repository.setSendNowPlayingEnabled(enabled)
            appContext?.let { ctx ->
                // Update media listener immediate behavior and sync status
                com.sameerasw.airsync.service.MediaNotificationListener.setNowPlayingEnabled(ctx, enabled)
            }
        }
    }

    // Wallpaper selection functions
    fun showWallpaperSelectionDialog(message: String = "") {
        _uiState.value = _uiState.value.copy(
            showWallpaperSelectionDialog = true,
            wallpaperSelectionMessage = message
        )
    }

    fun hideWallpaperSelectionDialog() {
        _uiState.value = _uiState.value.copy(
            showWallpaperSelectionDialog = false,
            wallpaperSelectionMessage = ""
        )
    }

    fun handleWallpaperSelected(context: Context, uri: Uri?) {
        if (uri != null) {
            viewModelScope.launch {
                try {
                    // Convert URI to bitmap and then to base64
                    val bitmap = com.sameerasw.airsync.utils.WallpaperUtil.uriToBitmap(context, uri)
                    if (bitmap != null) {
                        val base64 = com.sameerasw.airsync.utils.WallpaperUtil.bitmapToBase64(
                            bitmap,
                            android.graphics.Bitmap.CompressFormat.JPEG,
                            85
                        )
                        if (base64 != null) {
                            // Send wallpaper to connected device
                            com.sameerasw.airsync.utils.SyncManager.sendWallpaper(context, base64)
                            _uiState.value = _uiState.value.copy(
                                response = "Wallpaper synced successfully!"
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                response = "Failed to process wallpaper image"
                            )
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            response = "Failed to load wallpaper image"
                        )
                    }
                } catch (e: Exception) {
                    Log.e("AirSyncViewModel", "Error processing selected wallpaper: ${e.message}")
                    _uiState.value = _uiState.value.copy(
                        response = "Error processing wallpaper: ${e.message}"
                    )
                }
            }
        }
        hideWallpaperSelectionDialog()
    }

}
