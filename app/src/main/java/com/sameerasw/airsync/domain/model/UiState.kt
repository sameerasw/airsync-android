package com.sameerasw.airsync.domain.model

data class UiState(
    val ipAddress: String = "",
    val port: String = "",
    val deviceNameInput: String = "",
    val isLoading: Boolean = false,
    val response: String = "",
    val isDialogVisible: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    val isNotificationEnabled: Boolean = false,
    val lastConnectedDevice: ConnectedDevice? = null,
    val isNotificationSyncEnabled: Boolean = true,
    val isDeveloperMode: Boolean = false,
    val isDeveloperModeVisible: Boolean = false,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isClipboardSyncEnabled: Boolean = true,
    val isAutoReconnectEnabled: Boolean = true,
    val isIconSyncLoading: Boolean = false,
    val iconSyncMessage: String = "",
    val symmetricKey: String? = null,
    val manualPcName: String = "",
    val manualIsPlus: Boolean = false,
    val isContinueBrowsingEnabled: Boolean = true,
    val isSendNowPlayingEnabled: Boolean = true,
    val isKeepPreviousLinkEnabled: Boolean = true,
    val isSmartspacerShowWhenDisconnected: Boolean = false,
    val isMacMediaControlsEnabled: Boolean = true,
    // Mac device status
    val macDeviceStatus: MacDeviceStatus? = null,
    // Auth failure dialog
    val showAuthFailureDialog: Boolean = false,
    val authFailureMessage: String = "",
    // Clipboard history
    val isClipboardHistoryEnabled: Boolean = true,
    val clipboardHistory: List<ClipboardEntry> = emptyList(),
    val defaultTab: String = "dynamic",
    val isEssentialsConnectionEnabled: Boolean = false,
    val activeIp: String? = null,
    val connectingDeviceId: String? = null,
    val isDeviceDiscoveryEnabled: Boolean = true
)