package com.sameerasw.airsync.presentation.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.Phonelink
import androidx.compose.material.icons.outlined.Settings
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.DeviceInfoUtil
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.TestNotificationUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.airsync.utils.WallpaperUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sameerasw.airsync.ui.theme.ExtraCornerRadius
import com.sameerasw.airsync.ui.theme.minCornerRadius
import com.sameerasw.airsync.presentation.ui.components.cards.ClipboardSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeveloperModeCard
import com.sameerasw.airsync.presentation.ui.components.cards.ConnectionStatusCard
import com.sameerasw.airsync.presentation.ui.components.cards.PermissionStatusCard
import com.sameerasw.airsync.presentation.ui.components.cards.LastConnectedDeviceCard
import com.sameerasw.airsync.presentation.ui.components.cards.ManualConnectionCard
// Removed QrScannerRow; functionality moved to FAB
import com.sameerasw.airsync.presentation.ui.components.cards.NotificationSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeviceInfoCard
import com.sameerasw.airsync.presentation.ui.components.cards.WallpaperSyncCard
import com.sameerasw.airsync.presentation.ui.components.dialogs.AboutDialog
import com.sameerasw.airsync.presentation.ui.components.dialogs.ConnectionDialog
import com.sameerasw.airsync.utils.PermissionUtil
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.PickVisualMediaRequest
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AirSyncMainScreen(
    modifier: Modifier = Modifier,
    initialIp: String? = null,
    initialPort: String? = null,
    showConnectionDialog: Boolean = false,
    pcName: String? = null,
    isPlus: Boolean = false,
    symmetricKey: String? = null,
    onNavigateToApps: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    showAboutDialog: Boolean = false,
    onDismissAbout: () -> Unit = {}
) {
    val context = LocalContext.current

    val versionName = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
    } catch (_: Exception) {
        "2.0.0"
    }
    val viewModel: AirSyncViewModel = viewModel { AirSyncViewModel.create(context) }
    val uiState by viewModel.uiState.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            val result = WallpaperUtil.getCustomWallpaperAsBase64(uri, context)
            viewModel.setCustomWallpaperBase64(result)
            viewModel.updateDeviceName(deviceInfo.name)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            imagePickerLauncher.launch(null)
        }
    }
    val connectScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()
    var fabVisible by remember { mutableStateOf(true) }
    var fabExpanded by remember { mutableStateOf(true) }

    // Track if we've already processed the QR code dialog to prevent re-showing
    var hasProcessedQrDialog by remember { mutableStateOf(false) }

    // Pager state for swipeable tabs (0 = Connect, 1 = Settings)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    LaunchedEffect(Unit) {
        viewModel.initializeState(context, initialIp, initialPort, showConnectionDialog && !hasProcessedQrDialog, pcName, isPlus, symmetricKey)

        // Start network monitoring for dynamic Wi-Fi changes
        viewModel.startNetworkMonitoring(context)
    }

    // Mark QR dialog as processed when it's shown or when already connected
    LaunchedEffect(showConnectionDialog, uiState.isConnected) {
        if (showConnectionDialog) {
            if (uiState.isConnected) {
                // If already connected, don't show dialog
                hasProcessedQrDialog = true
            } else if (uiState.isDialogVisible) {
                // Dialog is being shown, mark as processed
                hasProcessedQrDialog = true
            }
        }
    }

    // Refresh permissions when returning from settings
    LaunchedEffect(uiState.showPermissionDialog) {
        if (!uiState.showPermissionDialog) {
            viewModel.refreshPermissions(context)
        }
    }

    // Hide FAB on scroll down, show on scroll up for the active tab
    LaunchedEffect(pagerState.currentPage) {
        val state = if (pagerState.currentPage == 0) connectScrollState else settingsScrollState
        var last = state.value
        snapshotFlow { state.value }.collect { value ->
            val delta = value - last
            if (delta > 2) fabVisible = false
            else if (delta < -2) fabVisible = true
            last = value
        }
    }

    // Expand FAB on first launch and whenever variant changes (connect <-> disconnect), then collapse after 5s
    LaunchedEffect(uiState.isConnected) {
        fabExpanded = true
        // Give users a hint for a short period, then collapse to icon-only
        delay(5000)
        fabExpanded = false
    }

    // Start/stop clipboard sync based on connection status and settings
    LaunchedEffect(uiState.isConnected, uiState.isClipboardSyncEnabled) {
        if (uiState.isConnected && uiState.isClipboardSyncEnabled) {
            ClipboardSyncManager.startSync(context)
        } else {
            ClipboardSyncManager.stopSync(context)
        }
    }

    // Connection management functions
    fun connect() {
        viewModel.setConnectionStatus(isConnected = false, isConnecting = true)

        viewModel.setUserManuallyDisconnected(false)

        WebSocketUtil.connect(
            context = context,
            ipAddress = uiState.ipAddress,
            port = uiState.port.toIntOrNull() ?: 6996,
            symmetricKey = uiState.symmetricKey,
            manualAttempt = true,
            onHandshakeTimeout = {
                scope.launch(Dispatchers.Main) {
                    // Strong haptic feedback
                    try { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                    viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
                    WebSocketUtil.disconnect(context)
                    viewModel.showAuthFailure(
                        "Connection failed due to authentication failure. Please check the encryption key by re-scanning the QR code."
                    )
                }
            },
            onConnectionStatus = { connected ->
                scope.launch(Dispatchers.Main) {
                    viewModel.setConnectionStatus(isConnected = connected, isConnecting = false)
                    if (connected) {
                        viewModel.setResponse("Connected successfully!")
                        // Get plus status from current temporary device or use QR code value
                        val plusStatus = uiState.lastConnectedDevice?.isPlus ?: isPlus
                        viewModel.saveLastConnectedDevice(pcName, plusStatus, uiState.symmetricKey)
                    } else {
                        viewModel.setResponse("Failed to connect")
                    }
                }
            },
            onMessage = { response ->
                scope.launch(Dispatchers.Main) {
                    viewModel.setResponse("Received: $response")
                    // Handle clipboard updates from desktop
                    try {
                        val json = JSONObject(response)
                        if (json.optString("type") == "clipboardUpdate") {
                            val data = json.optJSONObject("data")
                            val text = data?.optString("text")
                            if (!text.isNullOrEmpty()) {
                                ClipboardSyncManager.handleClipboardUpdate(context, text)
                            }
                        }
                    } catch (_: Exception) {
                        // Not a clipboard update, ignore
                    }
                }
            }
        )
    }

    // Auth failure dialog
    if (uiState.showAuthFailureDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissAuthFailure() },
            title = { Text("Connection failed") },
            text = {
                Text(uiState.authFailureMessage.ifEmpty {
                    "Authentication failed. Please re-scan the QR code on your Mac to ensure the encryption key matches."
                })
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissAuthFailure() }) {
                    Text("OK")
                }
            }
        )
    }

    fun disconnect() {
        // Set manual disconnect flag BEFORE disconnecting to prevent auto-reconnect trigger
        // Use an awaited write to avoid a race where auto-reconnect reads the old value.
        scope.launch {
            viewModel.setUserManuallyDisconnectedAwait(true)
            WebSocketUtil.disconnect(context)
            viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
            viewModel.setResponse("Disconnected")
        }
    }

    fun launchScanner(context: Context) {
        val lensIntent = Intent("com.google.vr.apps.ornament.app.lens.LensLauncherActivity.MAIN")
        lensIntent.setPackage("com.google.ar.lens")

        try {
            context.startActivity(lensIntent)
        } catch (_: ActivityNotFoundException) {
            // Fallback to default camera app
            val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            try {
                context.startActivity(cameraIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No camera app found", Toast.LENGTH_SHORT).show()
            }
        }
    }


    fun sendMessage(message: String) {
        scope.launch {
            viewModel.setLoading(true)
            viewModel.setResponse("")

            if (!WebSocketUtil.isConnected()) {
                connect()
                delay(500)
            }

            val success = WebSocketUtil.sendMessage(message)
            if (success) {
                viewModel.setResponse("Message sent: $message")
            } else {
                viewModel.setResponse("Failed to send message")
            }
            viewModel.setLoading(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ClipboardSyncManager.stopSync(context)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            AnimatedVisibility(visible = fabVisible, enter = scaleIn(), exit = scaleOut()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (uiState.isConnected) {
                            disconnect()
                        } else {
                            launchScanner(context)
                        }
                    },
                    icon = {
                        if (uiState.isConnected) {
                            Icon(imageVector = Icons.Filled.LinkOff, contentDescription = "Disconnect")
                        } else {
                            Icon(imageVector = Icons.Filled.QrCodeScanner, contentDescription = "Scan QR")
                        }
                    },
                    text = { Text(text = if (uiState.isConnected) "Disconnect" else "Scan to connect") },
                    expanded = fabExpanded
                )
            }
        },
        bottomBar = {
            val items = listOf("Connect", "Settings")
            val selectedIcons = listOf(Icons.Filled.Phonelink, Icons.Filled.Settings)
            val unselectedIcons = listOf(Icons.Outlined.Phonelink, Icons.Outlined.Settings)
            NavigationBar(
                windowInsets = NavigationBarDefaults.windowInsets
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = {
                            val selected = pagerState.currentPage == index
                            val iconOffset by animateDpAsState(targetValue = if (selected) 0.dp else 2.dp, label = "NavIconOffset")
                            Icon(
                                imageVector = if (selected) selectedIcons[index] else unselectedIcons[index],
                                contentDescription = item,
                                modifier = Modifier.offset(y = iconOffset)
                            )
                        },
                        label = {
                            val selected = pagerState.currentPage == index
                            val alpha by animateFloatAsState(targetValue = if (selected) 1f else 0f, label = "NavLabelAlpha")
                            // Keep label space reserved (alwaysShowLabel=true) and fade it in/out to avoid icon jumps
                            Text(item, modifier = Modifier.alpha(alpha))
                        },
                        alwaysShowLabel = true,
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } }
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            modifier = Modifier.fillMaxSize(),
            state = pagerState
        ) { page ->
            when (page) {
                0 -> {
                    // Connect tab content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                            .verticalScroll(connectScrollState)
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {

                    // Permission Status Card
                    AnimatedVisibility(
                        visible = uiState.missingPermissions.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        PermissionStatusCard(
                            missingPermissions = uiState.missingPermissions,
                            onGrantPermissions = { viewModel.setPermissionDialogVisible(true) },
                            onRefreshPermissions = { viewModel.refreshPermissions(context) },
                            onRequestNotificationPermission = onRequestNotificationPermission
                        )
                    }

                    // Connection Status Card
                    ConnectionStatusCard(
                        isConnected = uiState.isConnected,
                        isConnecting = uiState.isConnecting,
                        onDisconnect = { disconnect() },
                        connectedDevice = uiState.lastConnectedDevice,
                        lastConnected = uiState.lastConnectedDevice != null,
                        uiState = uiState,
                    )

                    AnimatedVisibility(
                        visible = !uiState.isConnected,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            ManualConnectionCard(
                                isConnected = uiState.isConnected,
                                lastConnected = uiState.lastConnectedDevice != null,
                                uiState = uiState,
                                onIpChange = { viewModel.updateIpAddress(it) },
                                onPortChange = { viewModel.updatePort(it) },
                                onPcNameChange = { viewModel.updateManualPcName(it) },
                                onIsPlusChange = { viewModel.updateManualIsPlus(it) },
                                onSymmetricKeyChange = { viewModel.updateSymmetricKey(it) },
                                onConnect = { viewModel.prepareForManualConnection() }
                            )
                        }
                    }

                    // Last Connected Device Section
                    AnimatedVisibility(
                        visible = !uiState.isConnected && uiState.lastConnectedDevice != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        uiState.lastConnectedDevice?.let { device ->
                            LastConnectedDeviceCard(
                                device = device,
                                isAutoReconnectEnabled = uiState.isAutoReconnectEnabled,
                                onToggleAutoReconnect = { enabled -> viewModel.setAutoReconnectEnabled(enabled) },
                                onQuickConnect = {
                                    // Check if we can use network-aware connection first
                                    val networkAwareDevice = viewModel.getNetworkAwareLastConnectedDevice()
                                    if (networkAwareDevice != null) {
                                        // Use network-aware device IP for current network
                                        viewModel.updateIpAddress(networkAwareDevice.ipAddress)
                                        viewModel.updatePort(networkAwareDevice.port)
                                        connect()
                                    } else {
                                        // Fallback to legacy stored device
                                        viewModel.updateIpAddress(device.ipAddress)
                                        viewModel.updatePort(device.port)
                                        viewModel.updateSymmetricKey(device.symmetricKey)
                                        connect()
                                    }
                                }
                            )
                        }
                    }

                    WallpaperSyncCard(
                        onPickImage = {
                            if (PermissionUtil.hasReadMediaImagesPermission(context)) {
                                imagePickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            } else {
                                permissionLauncher.launch(PermissionUtil.getGalleryPermission())
                            }
                        },
                        onSync = { viewModel.updateDeviceName(deviceInfo.name) }
                    )

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                else -> {
                    // Settings tab content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                            .verticalScroll(settingsScrollState)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    // Notification Sync Settings Card
                    NotificationSyncCard(
                        isNotificationEnabled = uiState.isNotificationEnabled,
                        isNotificationSyncEnabled = uiState.isNotificationSyncEnabled,
                        onToggleSync = { enabled -> viewModel.setNotificationSyncEnabled(enabled) },
                        onGrantPermissions = { viewModel.setPermissionDialogVisible(true) }
                    )

                    // Clipboard Sync Card
                    ClipboardSyncCard(
                        isClipboardSyncEnabled = uiState.isClipboardSyncEnabled,
                        onToggleClipboardSync = { enabled ->
                            viewModel.setClipboardSyncEnabled(enabled)
                        },
                        isContinueBrowsingEnabled = uiState.isContinueBrowsingEnabled,
                        onToggleContinueBrowsing = { enabled -> viewModel.setContinueBrowsingEnabled(enabled) },
                        isContinueBrowsingToggleEnabled = if (uiState.isConnected) uiState.lastConnectedDevice?.isPlus == true else true,
                        continueBrowsingSubtitle = "Prompt to open shared links with AirSync+",
                        isSendNowPlayingEnabled = uiState.isSendNowPlayingEnabled,
                        onToggleSendNowPlaying = { enabled -> viewModel.setSendNowPlayingEnabled(enabled) }
                    )

                    DeviceInfoCard(
                        deviceName = uiState.deviceNameInput,
                        localIp = deviceInfo.localIp,
                        onDeviceNameChange = { viewModel.updateDeviceName(it) }
                    )

                    // Developer Mode Card
                    AnimatedVisibility(
                        visible = uiState.isDeveloperModeVisible,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        DeveloperModeCard(
                            isDeveloperMode = uiState.isDeveloperMode,
                            onToggleDeveloperMode = { viewModel.setDeveloperMode(it) },
                            isLoading = uiState.isLoading,
                            onSendDeviceInfo = {
                                val message = JsonUtil.createDeviceInfoJson(
                                    deviceInfo.name,
                                    deviceInfo.localIp,
                                    uiState.port.toIntOrNull() ?: 6996,
                                    versionName ?: "2.0.0"
                                )
                                sendMessage(message)
                            },
                            onSendNotification = {
                                val testNotification = TestNotificationUtil.generateRandomNotification()
                                val message = JsonUtil.createNotificationJson(
                                    testNotification.id,
                                    testNotification.title,
                                    testNotification.body,
                                    testNotification.appName,
                                    testNotification.packageName
                                )
                                sendMessage(message)
                            },
                            onSendDeviceStatus = {
                                val message = DeviceInfoUtil.generateDeviceStatusJson(context)
                                sendMessage(message)
                            },
                            uiState = uiState
                        )
                    }

                    // Manual Icon Sync Button
                    OutlinedButton(
                        onClick = { viewModel.manualSyncAppIcons(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (uiState.isDeveloperModeVisible) 0.dp else 20.dp),
                        shape = RoundedCornerShape(
                            topStart = if (uiState.isDeveloperModeVisible) minCornerRadius else ExtraCornerRadius,
                            topEnd = if (uiState.isDeveloperModeVisible) minCornerRadius else ExtraCornerRadius,
                            bottomStart = ExtraCornerRadius,
                            bottomEnd = ExtraCornerRadius
                        ),
                        enabled = uiState.isConnected && !uiState.isIconSyncLoading
                    ) {
                        if (uiState.isIconSyncLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (uiState.isIconSyncLoading) "Syncing Icons..." else "Sync App Icons")
                    }

                    // Icon Sync Message Display
                    AnimatedVisibility(
                        visible = uiState.iconSyncMessage.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(ExtraCornerRadius),
                            colors = CardDefaults.cardColors(
                                containerColor = if (uiState.iconSyncMessage.contains("Successfully"))
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uiState.iconSyncMessage,
                                    modifier = Modifier.weight(1f),
                                    color = if (uiState.iconSyncMessage.contains("Successfully"))
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer
                                )
                                TextButton(onClick = { viewModel.clearIconSyncMessage() }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Dialogs
    if (uiState.isDialogVisible) {
        ConnectionDialog(
            deviceName = deviceInfo.name,
            localIp = deviceInfo.localIp,
            desktopIp = uiState.ipAddress,
            port = uiState.port,
            pcName = pcName ?: uiState.lastConnectedDevice?.name,
            isPlus = uiState.lastConnectedDevice?.isPlus ?: isPlus,
            onDismiss = { viewModel.setDialogVisible(false) },
            onConnect = {
                viewModel.setDialogVisible(false)
                connect()
            }
        )
    }

    // About Dialog - controlled by parent via showAboutDialog
    if (showAboutDialog) {
        AboutDialog(
            onDismissRequest = onDismissAbout,
            onToggleDeveloperMode = { viewModel.toggleDeveloperModeVisibility() }
        )
    }
}