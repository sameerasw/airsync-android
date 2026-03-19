package com.sameerasw.airsync.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
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
import com.sameerasw.airsync.smartspacer.AirSyncDeviceTarget
import com.sameerasw.airsync.utils.DeviceInfoUtil
import com.sameerasw.airsync.utils.DiscoveredDevice
import com.sameerasw.airsync.utils.AirBridgeClient
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.NetworkMonitor
import com.sameerasw.airsync.utils.PermissionUtil
import com.sameerasw.airsync.utils.SyncManager
import com.sameerasw.airsync.utils.UDPDiscoveryManager
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    // Discovered devices from UDP
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = UDPDiscoveryManager.discoveredDevices

    // Notes Role state
    private val _stylusMode = MutableStateFlow(false)
    val stylusMode: StateFlow<Boolean> = _stylusMode.asStateFlow()

    private val _launchedFromLockScreen = MutableStateFlow(true)
    val launchedFromLockScreen: StateFlow<Boolean> = _launchedFromLockScreen.asStateFlow()

    private val _isFloatingWindow = MutableStateFlow(true)
    val isFloatingWindow: StateFlow<Boolean> = _isFloatingWindow.asStateFlow()

    private val _isNotesRoleHeld = MutableStateFlow(false)
    val isNotesRoleHeld: StateFlow<Boolean> = _isNotesRoleHeld.asStateFlow()

    // Network monitoring
    private var isNetworkMonitoringActive = false
    private var previousNetworkIp: String? = null
    private var lastWebSocketConnected = false
    private var lastRelayState: AirBridgeClient.State = AirBridgeClient.State.DISCONNECTED
    private var lastUnifiedConnected = false

    private var appContext: Context? = null

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                context?.let { updateBlurState(it) }
            }
        }
    }

    // Manual connect canceller reference (set in init) for unregistering
    private val manualConnectCanceler: () -> Unit = {
        // Cancel any active auto-reconnect when user starts manual connection
        try {
            WebSocketUtil.cancelAutoReconnect()
        } catch (_: Exception) {
        }
    }

    // Connection status listener for WebSocket updates
    private val connectionStatusListener: (Boolean) -> Unit = { isConnected ->
        lastWebSocketConnected = isConnected
        updateUnifiedConnectionState()
    }

    private fun updateUnifiedConnectionState() {
        viewModelScope.launch {
            val relayConnected = lastRelayState == AirBridgeClient.State.RELAY_ACTIVE
            val relayConnecting = lastRelayState == AirBridgeClient.State.CONNECTING ||
                lastRelayState == AirBridgeClient.State.REGISTERING ||
                lastRelayState == AirBridgeClient.State.WAITING_FOR_PEER
            val unifiedConnected = lastWebSocketConnected || relayConnected
            val unifiedConnecting = !unifiedConnected && (WebSocketUtil.isConnecting() || relayConnecting)

            _uiState.value = _uiState.value.copy(
                isConnected = unifiedConnected,
                isConnecting = unifiedConnecting,
                isRelayConnection = relayConnected && !lastWebSocketConnected,
                response = when {
                    unifiedConnected -> "Connected successfully!"
                    unifiedConnecting -> "Connecting..."
                    else -> "Disconnected"
                },
                activeIp = if (lastWebSocketConnected) WebSocketUtil.currentIpAddress else null,
                macDeviceStatus = if (unifiedConnected) _uiState.value.macDeviceStatus else null
            )

            if (unifiedConnected && !lastUnifiedConnected) {
                repository.setFirstMacConnectionTime(System.currentTimeMillis())
                updateRatingPromptDisplay()
            }
            lastUnifiedConnected = unifiedConnected

            // Notify Smartspacer of connection status change
            appContext?.let { context ->
                try {
                    AirSyncDeviceTarget.notifyChange(context)
                } catch (_: Exception) {
                    // Smartspacer might not be installed, ignore
                }
            }
        }
    }

    init {
        // Register for WebSocket connection status updates
        WebSocketUtil.registerConnectionStatusListener(connectionStatusListener)
        try {
            WebSocketUtil.registerManualConnectListener(manualConnectCanceler)
        } catch (_: Exception) {
        }
        viewModelScope.launch {
            AirBridgeClient.connectionState.collect { relayState ->
                lastRelayState = relayState
                updateUnifiedConnectionState()
            }
        }

        // Observe Mac device status updates
        viewModelScope.launch {
            MacDeviceStatusManager.macDeviceStatus.collect { macStatus ->
                _uiState.value = _uiState.value.copy(macDeviceStatus = macStatus)
            }
        }
        // Observe manual disconnect flag to immediately cancel any running auto-reconnect
        viewModelScope.launch {
            repository.getUserManuallyDisconnected().collect { _ ->
                // No device status notification to update
            }
        }

        // Observe pitch black theme preference
        viewModelScope.launch {
            repository.getPitchBlackThemeEnabled().collect { enabled ->
                _uiState.value = _uiState.value.copy(isPitchBlackThemeEnabled = enabled)
            }
        }

        // Observe sentry reporting preference
        viewModelScope.launch {
            repository.getSentryReportingEnabled().collect { enabled ->
                _uiState.value = _uiState.value.copy(isSentryReportingEnabled = enabled)
            }
        }

        // Observe widget transparency preference
        viewModelScope.launch {
            repository.getWidgetTransparency().collect { trans ->
                _uiState.value = _uiState.value.copy(widgetTransparency = trans)
            }
        }

        // Observe first run preference for onboarding status
        viewModelScope.launch {
            repository.getFirstRun().collect { firstRun ->
                _uiState.value = _uiState.value.copy(isOnboardingCompleted = !firstRun)
            }
        }

        // Observe Quick Share preference
        viewModelScope.launch {
            repository.isQuickShareEnabled().collect { enabled ->
                _uiState.value = _uiState.value.copy(isQuickShareEnabled = enabled)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister listeners
        WebSocketUtil.unregisterConnectionStatusListener(connectionStatusListener)
        try {
            WebSocketUtil.unregisterManualConnectListener(manualConnectCanceler)
        } catch (_: Exception) {
        }
        try {
            appContext?.unregisterReceiver(powerSaveReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered
        }
    }

    private fun startObservingDeviceChanges(context: Context) {
        val dataStoreManager = DataStoreManager(context)

        // Observe both last connected device and network devices for real-time updates
        viewModelScope.launch {
            dataStoreManager.getLastConnectedDevice()
                .distinctUntilChanged()
                .collect { device ->
                    Log.d(
                        "AirSyncViewModel",
                        "Last connected device changed: ${device?.name}, isPlus: ${device?.isPlus}"
                    )
                    updateDisplayedDevice(context)
                }
        }

        viewModelScope.launch {
            dataStoreManager.getAllNetworkDeviceConnections()
                .distinctUntilChanged()
                .collect { networkDevices ->
                    Log.d(
                        "AirSyncViewModel",
                        "Network devices changed: ${networkDevices.size} devices"
                    )
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

            // Only update if changed
            if (_uiState.value.lastConnectedDevice != deviceToShow) {
                Log.d(
                    "AirSyncViewModel",
                    "Updating displayed device: ${deviceToShow?.name}, isPlus: ${deviceToShow?.isPlus}, model: ${deviceToShow?.model}"
                )
                _uiState.value = _uiState.value.copy(lastConnectedDevice = deviceToShow)
            }
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
            val isAutoReconnectEnabled = repository.getAutoReconnectEnabled().first()
            val lastConnectedSymmetricKey = lastConnected?.symmetricKey
            val isContinueBrowsingEnabled = repository.getContinueBrowsingEnabled().first()
            val isSendNowPlayingEnabled = repository.getSendNowPlayingEnabled().first()
            val isKeepPreviousLinkEnabled = repository.getKeepPreviousLinkEnabled().first()
            val isMacMediaControlsEnabled = repository.getMacMediaControlsEnabled().first()
            val isClipboardHistoryEnabled = repository.getClipboardHistoryEnabled().first()
            repository.getDefaultTab().first()
            val isEssentialsConnectionEnabled = repository.getEssentialsConnectionEnabled().first()
            val isDeviceDiscoveryEnabled = repository.getDeviceDiscoveryEnabled().first()
            val isBlurEnabledSetting = repository.getUseBlurEnabled().first()
            val isPitchBlackThemeEnabled = repository.getPitchBlackThemeEnabled().first()
            val isSentryReportingEnabled = repository.getSentryReportingEnabled().first()
            val isFirstRun = repository.getFirstRun().first()
            val isPowerSaveMode = DeviceInfoUtil.isPowerSaveMode(context)
            val isBlurProblematic = DeviceInfoUtil.isBlurProblematicDevice()
            val isQuickShareEnabled = repository.isQuickShareEnabled().first()
            
            // Replicate Essentials logic for initial state
            val isBlurEnabled = isBlurEnabledSetting && !isPowerSaveMode && !isBlurProblematic

            // Rating tracking
            repository.getFirstMacConnectionTime().first()
            repository.getLastPromptDismissedVersion().first()
            repository.hasRatedApp().first()

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
            lastWebSocketConnected = WebSocketUtil.isConnected()
            lastRelayState = AirBridgeClient.connectionState.value
            val relayConnected = lastRelayState == AirBridgeClient.State.RELAY_ACTIVE
            val relayConnecting = lastRelayState == AirBridgeClient.State.CONNECTING ||
                lastRelayState == AirBridgeClient.State.REGISTERING ||
                lastRelayState == AirBridgeClient.State.WAITING_FOR_PEER
            val currentlyConnected = lastWebSocketConnected || relayConnected
            val currentlyConnecting = !currentlyConnected && (WebSocketUtil.isConnecting() || relayConnecting)

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
                isAutoReconnectEnabled = isAutoReconnectEnabled,
                isConnected = currentlyConnected,
                isConnecting = currentlyConnecting,
                symmetricKey = symmetricKey ?: lastConnectedSymmetricKey,
                isContinueBrowsingEnabled = isContinueBrowsingEnabled,
                isSendNowPlayingEnabled = isSendNowPlayingEnabled,
                isKeepPreviousLinkEnabled = isKeepPreviousLinkEnabled,
                isMacMediaControlsEnabled = isMacMediaControlsEnabled,
                isClipboardHistoryEnabled = isClipboardHistoryEnabled,
                isEssentialsConnectionEnabled = isEssentialsConnectionEnabled,
                isDeviceDiscoveryEnabled = isDeviceDiscoveryEnabled,
                isBlurSettingEnabled = isBlurEnabledSetting,
                isPowerSaveMode = isPowerSaveMode,
                isPitchBlackThemeEnabled = isPitchBlackThemeEnabled,
                isBlurEnabled = isBlurEnabled,
                isSentryReportingEnabled = isSentryReportingEnabled,
                isOnboardingCompleted = !isFirstRun,
                isQuickShareEnabled = isQuickShareEnabled
            )
            lastUnifiedConnected = currentlyConnected

            // If app was restarted and relay is already active, immediately bootstrap
            // LAN-first probing instead of waiting for a future network callback.
            if (relayConnected && !lastWebSocketConnected) {
                WebSocketUtil.startLanFirstRelayProbe(
                    context = context,
                    immediate = true,
                    source = "viewmodel_initialize_state",
                    resetBackoff = true
                )
            }

            updateRatingPromptDisplay()

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


            // Start observing device changes for real-time updates
            startObservingDeviceChanges(context)

            // Register power save receiver
            context.registerReceiver(
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            )

            // Start AirSync Service in scanning mode (which handles UDP Discovery and WakeupService)
            com.sameerasw.airsync.service.AirSyncService.startScanning(context)
            isNetworkMonitoringActive = true
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
                SyncManager.sendDeviceInfoNow(ctx, name)
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
            isConnecting = isConnecting,
            connectingDeviceId = if (!isConnecting) null else _uiState.value.connectingDeviceId
        )
    }

    fun setConnectingDeviceId(id: String?) {
        _uiState.value = _uiState.value.copy(connectingDeviceId = id)
    }

    fun refreshPermissions(context: Context) {
        val missingPermissions = PermissionUtil.getAllMissingPermissions(context)
        val isNotificationEnabled = PermissionUtil.isNotificationListenerEnabled(context)
        _uiState.value = _uiState.value.copy(
            missingPermissions = missingPermissions,
            isNotificationEnabled = isNotificationEnabled
        )
    }

    fun saveLastConnectedDevice(
        pcName: String? = null,
        isPlus: Boolean = false,
        symmetricKey: String? = null
    ) {
        viewModelScope.launch {
            val deviceName = pcName ?: "My Mac"
            val ourIp = _deviceInfo.value.localIp
            val clientIp = _uiState.value.ipAddress
            val port = _uiState.value.port

            // Save using network-aware storage
            repository.saveNetworkDeviceConnection(
                deviceName,
                ourIp,
                clientIp,
                port,
                isPlus,
                symmetricKey
            )

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

            // Notify Smartspacer of device update
            appContext?.let { context ->
                try {
                    AirSyncDeviceTarget.notifyChange(context)
                } catch (_: Exception) {
                    // Smartspacer might not be installed, ignore
                }
            }
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

    fun setClipboardHistoryEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isClipboardHistoryEnabled = enabled)
        viewModelScope.launch {
            repository.setClipboardHistoryEnabled(enabled)
            if (!enabled) {
                clearClipboardHistory()
            }
        }
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isAutoReconnectEnabled = enabled)
        viewModelScope.launch {
            repository.setAutoReconnectEnabled(enabled)
        }
    }

    fun setUseBlurEnabled(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            repository.setUseBlurEnabled(enabled)
            updateBlurState(context)
        }
    }

    private fun updateBlurState(context: Context) {
        viewModelScope.launch {
            val isBlurEnabledSetting = repository.getUseBlurEnabled().first()
            val isPowerSaveMode = DeviceInfoUtil.isPowerSaveMode(context)
            val isBlurProblematic = DeviceInfoUtil.isBlurProblematicDevice()

            // 1:1 Logic from Essentials: turned off if power saving is on or device is problematic
            val isBlurEnabled = isBlurEnabledSetting && !isPowerSaveMode && !isBlurProblematic

            _uiState.value = _uiState.value.copy(
                isBlurSettingEnabled = isBlurEnabledSetting,
                isPowerSaveMode = isPowerSaveMode,
                isBlurEnabled = isBlurEnabled
            )
        }
    }

    fun setSentryReportingEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isSentryReportingEnabled = enabled)
        viewModelScope.launch {
            repository.setSentryReportingEnabled(enabled)
            // Note: Changes typically take effect on next launch as Sentry is initialized in Application.onCreate
        }
    }

    fun setPitchBlackThemeEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isPitchBlackThemeEnabled = enabled)
        viewModelScope.launch {
            repository.setPitchBlackThemeEnabled(enabled)
            // Note: Currently theme changes check via MainActivity collection instead of restart
        }
    }

    fun setWidgetTransparency(alpha: Float) {
        _uiState.value = _uiState.value.copy(widgetTransparency = alpha)
        viewModelScope.launch {
            repository.setWidgetTransparency(alpha)
            appContext?.let { context ->
                com.sameerasw.airsync.widget.AirSyncWidgetProvider.updateAllWidgets(context)
            }
        }
    }

    fun setDeviceDiscoveryEnabled(context: Context, enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isDeviceDiscoveryEnabled = enabled)
        viewModelScope.launch {
            repository.setDeviceDiscoveryEnabled(enabled)
            if (enabled) {
                com.sameerasw.airsync.service.AirSyncService.startScanning(context)
            } else {
                // When disabling discovery, we should stop discovery broadcasts
                // If a connection exists, the service continues but discovery stops.
                // If no connection, the service should still run for WakeupService but maybe without scanning?
                // For now, let's just trigger a service update that checks the new flag.
                com.sameerasw.airsync.service.AirSyncService.startScanning(context)
            }
        }
    }

    fun setQuickShareEnabled(context: Context, enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isQuickShareEnabled = enabled)
        viewModelScope.launch {
            repository.setQuickShareEnabled(enabled)
            val intent = Intent(context, com.sameerasw.airsync.quickshare.QuickShareService::class.java)
            if (enabled) {
                // Start QuickShareService in foreground discovery mode so it can immediately call startForeground().
                intent.action = com.sameerasw.airsync.quickshare.QuickShareService.ACTION_START_DISCOVERY
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }
    }

    fun manualSyncAppIcons(context: Context) {
        _uiState.value = _uiState.value.copy(isIconSyncLoading = true, iconSyncMessage = "")

        SyncManager.manualSyncAppIcons(context) { _, message ->
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

    // Auth failure dialog controls
    fun showAuthFailure(message: String) {
        _uiState.value =
            _uiState.value.copy(showAuthFailureDialog = true, authFailureMessage = message)
    }

    fun dismissAuthFailure() {
        _uiState.value = _uiState.value.copy(showAuthFailureDialog = false, authFailureMessage = "")
    }

    fun setUserManuallyDisconnected(disconnected: Boolean) {
        viewModelScope.launch {
            repository.setUserManuallyDisconnected(disconnected)
        }
    }

    // Awaitable variant used when ordering matters (e.g., ensure flag is persisted before disconnect)
    suspend fun setUserManuallyDisconnectedAwait(disconnected: Boolean) {
        repository.setUserManuallyDisconnected(disconnected)
    }

    private fun hasNetworkAwareMappingForLastDevice(): ConnectedDevice? {
        val ourIp = _deviceInfo.value.localIp
        val last = _uiState.value.lastConnectedDevice ?: return null
        if (ourIp.isEmpty() || ourIp == "Unknown" || ourIp == "No Wi-Fi") return null
        // Find matching device by name with mapping for our IP
        val networkDevice = _networkDevices.value.firstOrNull {
            it.deviceName == last.name && it.getClientIpForNetwork(ourIp) != null
        }
        return networkDevice?.toConnectedDevice(ourIp)
    }

    private suspend fun loadNetworkDevicesForNetworkChange() {
        // thin wrapper in case logic needs splitting
        loadNetworkDevices()
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

                        // Update local device info immediately
                        _deviceInfo.value = _deviceInfo.value.copy(localIp = currentIp)

                        // Always refresh network-aware device mappings on network change
                        loadNetworkDevicesForNetworkChange()

                        // Cancel any ongoing auto-reconnect loop; we'll restart with the new network context if needed
                        try {
                            WebSocketUtil.cancelAutoReconnect()
                        } catch (_: Exception) {
                        }

                        val manual = repository.getUserManuallyDisconnected().first()
                        val autoOn = repository.getAutoReconnectEnabled().first()

                        // Determine if we have a mapping for the last connected device on this network
                        val target = hasNetworkAwareMappingForLastDevice()

                        if (currentIp == "No Wi-Fi" || currentIp == "Unknown") {
                            // No usable Wi‑Fi. If relay is active, keep service/relay alive.
                            if (AirBridgeClient.isRelayConnectedOrConnecting()) {
                                Log.d(
                                    "AirSyncViewModel",
                                    "Wi-Fi unavailable but relay is active; keeping relay path alive"
                                )
                                _uiState.value = _uiState.value.copy(isConnecting = false)
                            } else {
                                // No LAN and no relay: stop active LAN path and service.
                                try {
                                    WebSocketUtil.disconnect(context)
                                } catch (_: Exception) {
                                }
                                try {
                                    com.sameerasw.airsync.service.AirSyncService.stop(context)
                                } catch (_: Exception) {
                                }
                                _uiState.value =
                                    _uiState.value.copy(isConnected = false, isConnecting = false)
                            }
                            return@collect
                        } else {
                            // Ensure service is running when WiFi is available
                            try {
                                com.sameerasw.airsync.service.AirSyncService.startScanning(context)
                            } catch (_: Exception) {
                            }
                        }

                        if (target != null) {
                            // We have a specific device mapping for this network. Switch immediately.
                            // Update UI fields so the user sees the correct endpoint.
                            updateIpAddress(target.ipAddress)
                            updatePort(target.port)
                            updateSymmetricKey(target.symmetricKey)

                            // If connected/connecting to old network, disconnect first to force a clean switch
                            if (WebSocketUtil.isConnected() || WebSocketUtil.isConnecting()) {
                                try {
                                    WebSocketUtil.disconnect(context)
                                } catch (_: Exception) {
                                }
                            }

                            // Auto-connect if auto-reconnect is enabled and the user hasn't manually disconnected.
                            if (autoOn && !manual) {
                                // Mark as connecting in UI and kick off a non-manual connection (so it won't flip manual flags)
                                _uiState.value = _uiState.value.copy(isConnecting = true)
                                try {
                                    WebSocketUtil.connect(
                                        context = context,
                                        ipAddress = target.ipAddress,
                                        port = target.port.toIntOrNull() ?: 6996,
                                        symmetricKey = target.symmetricKey,
                                        manualAttempt = false,
                                        onConnectionStatus = { connected ->
                                            viewModelScope.launch {
                                                _uiState.value = _uiState.value.copy(
                                                    isConnected = connected,
                                                    isConnecting = false,
                                                    response = if (connected) "Connected successfully!" else "Reconnection failed"
                                                )
                                                if (connected) {
                                                    // Update last connected record timestamp for this device
                                                    try {
                                                        // Persist as the last connected device and refresh network-aware mapping timestamps
                                                        saveLastConnectedDevice(
                                                            pcName = target.name,
                                                            isPlus = target.isPlus,
                                                            symmetricKey = target.symmetricKey
                                                        )
                                                    } catch (_: Exception) {
                                                    }
                                                } else if (autoOn && !manual) {
                                                    // If the immediate connect failed, restart the auto-reconnect loop for this network
                                                    try {
                                                        WebSocketUtil.requestAutoReconnect(context)
                                                    } catch (_: Exception) {
                                                    }
                                                }
                                            }
                                        }
                                    )
                                } catch (_: Exception) {
                                    // Fall back to auto-reconnect loop
                                    try {
                                        WebSocketUtil.requestAutoReconnect(context)
                                    } catch (_: Exception) {
                                    }
                                }
                            } else {
                                // User has disabled auto connect, just update the displayed device/IP
                                _uiState.value = _uiState.value.copy(isConnecting = false)
                            }
                        } else {
                            // No mapping for this network: disconnect if connected and, if allowed, start generic auto-reconnect
                            if (WebSocketUtil.isConnected() || WebSocketUtil.isConnecting()) {
                                try {
                                    WebSocketUtil.disconnect(context)
                                } catch (_: Exception) {
                                }
                            }
                            if (autoOn && !manual) {
                                try {
                                    if (AirBridgeClient.isRelayConnectedOrConnecting()) {
                                        WebSocketUtil.requestLanReconnectFromRelay(context)
                                    } else {
                                        WebSocketUtil.requestAutoReconnect(context)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
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
                com.sameerasw.airsync.service.MediaNotificationListener.setNowPlayingEnabled(
                    ctx,
                    enabled
                )
            }
        }
    }

    fun setKeepPreviousLinkEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isKeepPreviousLinkEnabled = enabled)
        viewModelScope.launch {
            repository.setKeepPreviousLinkEnabled(enabled)
        }
    }

    fun setSmartspacerShowWhenDisconnected(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isSmartspacerShowWhenDisconnected = enabled)
        viewModelScope.launch {
            repository.setSmartspacerShowWhenDisconnected(enabled)
        }
        // Notify Smartspacer to update immediately
        appContext?.let { context ->
            try {
                AirSyncDeviceTarget.notifyChange(context)
            } catch (_: Exception) {
            }
        }
    }

    fun setMacMediaControlsEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isMacMediaControlsEnabled = enabled)
        viewModelScope.launch {
            repository.setMacMediaControlsEnabled(enabled)
            // If disabled, stop the service immediately
            if (!enabled) {
                appContext?.let { ctx ->
                    com.sameerasw.airsync.service.MacMediaPlayerService.stopMacMedia(ctx)
                }
            }
        }
    }

    // Expose DataStore export/import helpers
    suspend fun exportAllDataToJson(context: Context): String? {
        return try {
            val manager = DataStoreManager(context)
            manager.exportAllDataToJson()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun importDataFromJson(context: Context, json: String): Boolean {
        return try {
            val manager = DataStoreManager(context)
            manager.importAllDataFromJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Clipboard history management
    fun addClipboardEntry(text: String, isFromPc: Boolean) {
        if (!_uiState.value.isClipboardHistoryEnabled) return

        val entry = com.sameerasw.airsync.domain.model.ClipboardEntry(
            id = java.util.UUID.randomUUID().toString(),
            text = text,
            timestamp = System.currentTimeMillis(),
            isFromPc = isFromPc
        )
        val updatedHistory =
            (listOf(entry) + _uiState.value.clipboardHistory).take(100) // Keep last 100 entries
        _uiState.value = _uiState.value.copy(clipboardHistory = updatedHistory)
    }

    fun clearClipboardHistory() {
        _uiState.value = _uiState.value.copy(clipboardHistory = emptyList())
    }

    fun clearDisconnectionClipboardHistory() {
        // Clear clipboard history when disconnected
        _uiState.value = _uiState.value.copy(clipboardHistory = emptyList())
    }

    // Notes Role state setters
    fun setStylusMode(enabled: Boolean) {
        _stylusMode.value = enabled
    }

    fun setLaunchedFromLockScreen(isLockScreen: Boolean) {
        _launchedFromLockScreen.value = isLockScreen
    }

    fun setIsFloatingWindow(isFloating: Boolean) {
        _isFloatingWindow.value = isFloating
    }

    fun setIsNotesRoleHeld(held: Boolean) {
        _isNotesRoleHeld.value = held
    }

    fun setDefaultTab(tab: String) {
        _uiState.value = _uiState.value.copy(defaultTab = tab)
        viewModelScope.launch {
            repository.setDefaultTab(tab)
        }
    }

    fun setEssentialsConnectionEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isEssentialsConnectionEnabled = enabled)
        viewModelScope.launch {
            repository.setEssentialsConnectionEnabled(enabled)
            // wait for next up[date to sync
            if (enabled) {
                // later: Trigger broadcast update immediately
            }
        }
    }

    private fun updateRatingPromptDisplay() {
        viewModelScope.launch {
            val isConnected = WebSocketUtil.isConnected()
            val hasRated = repository.hasRatedApp().first()
            if (hasRated) {
                _uiState.value = _uiState.value.copy(shouldShowRatingPrompt = false)
                return@launch
            }

            val firstConnectionTime = repository.getFirstMacConnectionTime().first()
            if (firstConnectionTime == 0L) {
                _uiState.value = _uiState.value.copy(shouldShowRatingPrompt = false)
                return@launch
            }

            // Must have passed 24 hours
            val oneDayInMillis = 24 * 60 * 60 * 1000L
            val isEnoughTimePassed =
                System.currentTimeMillis() - firstConnectionTime >= oneDayInMillis

            if (!isEnoughTimePassed) {
                _uiState.value = _uiState.value.copy(shouldShowRatingPrompt = false)
                return@launch
            }

            // Check if dismissed for current version
            val lastDismissedVersion = repository.getLastPromptDismissedVersion().first()
            val currentVersion = getAppVersionCode()

            val isDismissedForCurrentVersion = lastDismissedVersion == currentVersion

            _uiState.value = _uiState.value.copy(
                shouldShowRatingPrompt = isConnected && !isDismissedForCurrentVersion
            )
        }
    }

    private fun getAppVersionCode(): Int {
        return try {
            val context = appContext ?: return -1
            val packageInfo =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (_: Exception) {
            -1
        }
    }

    fun setRatingCardDismissed() {
        viewModelScope.launch {
            val currentVersion = getAppVersionCode()
            repository.setLastPromptDismissedVersion(currentVersion)
            updateRatingPromptDisplay()
        }
    }

    fun setAppRated() {
        viewModelScope.launch {
            repository.setHasRatedApp(true)
            updateRatingPromptDisplay()
        }
    }

    fun setUseBlurEnabled(enabled: Boolean) {
        val finalEnabled = if (DeviceInfoUtil.isBlurProblematicDevice()) false else enabled
        _uiState.value = _uiState.value.copy(isBlurEnabled = finalEnabled)
        viewModelScope.launch {
            repository.setUseBlurEnabled(enabled)
        }
    }

    fun resetOnboarding() {
        viewModelScope.launch {
            repository.setFirstRun(true)
            repository.setFirstMacConnectionTime(0L)
            repository.setLastPromptDismissedVersion(-1)
            repository.setHasRatedApp(false)
            updateRatingPromptDisplay()
        }
    }

    fun setOnboardingCompleted(completed: Boolean) {
        _uiState.value = _uiState.value.copy(isOnboardingCompleted = completed)
        viewModelScope.launch {
            repository.setFirstRun(!completed)
        }
    }

}
