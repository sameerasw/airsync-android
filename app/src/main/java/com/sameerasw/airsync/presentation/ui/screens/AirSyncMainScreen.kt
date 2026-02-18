package com.sameerasw.airsync.presentation.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Phonelink
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarExitDirection.Companion.Bottom
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity
import com.sameerasw.airsync.presentation.ui.components.AirSyncFloatingToolbar
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.presentation.ui.components.SettingsView
import com.sameerasw.airsync.presentation.ui.components.cards.ConnectionStatusCard
import com.sameerasw.airsync.presentation.ui.components.cards.LastConnectedDeviceCard
import com.sameerasw.airsync.presentation.ui.components.cards.ManualConnectionCard
import com.sameerasw.airsync.presentation.ui.components.cards.RateAppCard
import com.sameerasw.airsync.presentation.ui.components.dialogs.ConnectionDialog
import com.sameerasw.airsync.presentation.ui.components.sheets.AboutBottomSheet
import com.sameerasw.airsync.presentation.ui.components.sheets.HelpSupportBottomSheet
import com.sameerasw.airsync.presentation.ui.models.AirSyncTab
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
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
    showAboutDialog: Boolean = false,
    onDismissAbout: () -> Unit = {},
    showHelpSheet: Boolean = false,
    onDismissHelp: () -> Unit = {},
    onTitleChange: (String) -> Unit = {}
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connectScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()
    var hasProcessedQrDialog by remember { mutableStateOf(false) }
    var hasAppliedInitialTab by remember { mutableStateOf(false) }
    val pagerState =
        rememberPagerState(initialPage = 0, pageCount = { if (uiState.isConnected) 4 else 2 })
    val navCallbackState = rememberUpdatedState(onNavigateToApps)
    LaunchedEffect(navCallbackState.value) {
    }
    var fabVisible by remember { mutableStateOf(true) }
    var fabExpanded by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(false) } // State for Keyboard Sheet in Remote Tab
    var loadingHapticsJob by remember { mutableStateOf<Job?>(null) }

    // Initial tab navigation logic
    LaunchedEffect(Unit) {
        if (!hasAppliedInitialTab) {
            // Wait up to 2 seconds for initial connection (e.g. auto-reconnect on start)
            withTimeoutOrNull(2000) {
                snapshotFlow { uiState.isConnected }.filter { it }.first()
            }

            if (uiState.isConnected) {
                val targetPage = when (uiState.defaultTab) {
                    "connect" -> 0
                    "remote" -> 1
                    "clipboard" -> 2
                    "dynamic" -> {
                        // Check if music is playing on Mac
                        if (uiState.macDeviceStatus?.music?.isPlaying == true) 1 else 2
                    }

                    else -> 0
                }
                if (targetPage > 0 && targetPage < (if (uiState.isConnected) 4 else 2)) {
                    pagerState.scrollToPage(targetPage)
                }
            }
            hasAppliedInitialTab = true
        }
    }

    // For export/import flow
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    rememberNavController()
    val exitAlwaysScrollBehavior =
        FloatingToolbarDefaults.exitAlwaysScrollBehavior(exitDirection = Bottom)

    fun connect(deviceId: String? = null) {
        // Check if critical permissions are missing
        val criticalPermissions =
            com.sameerasw.airsync.utils.PermissionUtil.getCriticalMissingPermissions(context)
        if (criticalPermissions.isNotEmpty()) {
            Toast.makeText(context, "Missing permissions", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.setConnectingDeviceId(deviceId)
        viewModel.setConnectionStatus(isConnected = false, isConnecting = true)
        viewModel.setUserManuallyDisconnected(false)

        scope.launch(Dispatchers.Main) {
            val result = withTimeoutOrNull(20000) {
                var connectionResult: Boolean? = null
                WebSocketUtil.connect(
                    context = context,
                    ipAddress = uiState.ipAddress,
                    port = uiState.port.toIntOrNull() ?: 6996,
                    symmetricKey = uiState.symmetricKey,
                    manualAttempt = true,
                    onHandshakeTimeout = {
                        scope.launch(Dispatchers.Main) {
                            try {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            } catch (_: Exception) {
                            }
                            viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
                            WebSocketUtil.disconnect(context)
                            viewModel.showAuthFailure(
                                "Connection failed due to authentication failure. Please check the encryption key by re-scanning the QR code."
                            )
                        }
                    },
                    onConnectionStatus = { connected ->
                        connectionResult = connected
                    },
                    onMessage = { response ->
                        scope.launch(Dispatchers.Main) {
                            Log.d("AirSyncMainScreen", "Message received: $response")
                            viewModel.setResponse("Received: $response")
                        }
                    }
                )

                // Wait for the connection result
                while (connectionResult == null) {
                    delay(100)
                }
                connectionResult
            }

            if (result == null) {
                // Timeout occurred
                try {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } catch (_: Exception) {
                }
                viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
                WebSocketUtil.disconnect(context)
                Toast.makeText(context, "Connection Timed Out", Toast.LENGTH_SHORT).show()
                viewModel.setResponse("Connection Timed Out")
            } else {
                val connected = result ?: false
                viewModel.setConnectionStatus(isConnected = connected, isConnecting = false)
                if (connected) {
                    viewModel.setResponse("Connected successfully!")
                    val plusStatus = uiState.lastConnectedDevice?.isPlus ?: isPlus
                    viewModel.saveLastConnectedDevice(pcName, plusStatus, uiState.symmetricKey)
                } else {
                    viewModel.setResponse("Failed to connect")
                }
            }
        }
    }

    // CreateDocument launcher for export (MIME application/json)
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            Toast.makeText(context, "Export cancelled", Toast.LENGTH_SHORT).show()
            viewModel.setLoading(false)
            return@rememberLauncherForActivityResult
        }

        // Write pendingExportJson to uri
        scope.launch(Dispatchers.IO) {
            try {
                val json = pendingExportJson
                if (json == null) {
                    // Nothing to write
                    viewModel.setLoading(false)
                    return@launch
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Export successful", Toast.LENGTH_SHORT).show()
                    viewModel.setLoading(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    viewModel.setLoading(false)
                }
            }
        }
    }

    // OpenDocument launcher for import (allow picking JSON)
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(context, "Import cancelled", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        viewModel.setLoading(true)
        scope.launch(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)?.bufferedReader()
                    ?.use { it.readText() }
                if (input == null) {
                    scope.launch(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read file", Toast.LENGTH_LONG).show()
                        viewModel.setLoading(false)
                    }
                    return@launch
                }

                val success = viewModel.importDataFromJson(context, input)
                scope.launch(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(context, "Import successful", Toast.LENGTH_SHORT).show()
                        viewModel.initializeState(context)
                    } else {
                        Toast.makeText(context, "Import failed or invalid file", Toast.LENGTH_LONG)
                            .show()
                    }
                    viewModel.setLoading(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
                    viewModel.setLoading(false)
                }
            }
        }
    }

    // QR Scanner launcher
    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val qrCode = result.data?.getStringExtra("QR_CODE")
            if (!qrCode.isNullOrEmpty()) {
                // Parse the QR code (expected format: airsync://ip:port?name=...&plus=...&key=...)
                try {
                    val uri = qrCode.toUri()

                    // Handle potential multiple IPs in host part (e.g., ip1,ip2,ip3)
                    var ip = uri.host ?: ""
                    var port = uri.port.takeIf { it != -1 }?.toString() ?: ""

                    if (ip.isEmpty() || port.isEmpty()) {
                        // Fallback manual parsing if URI host is null due to commas
                        val authority = qrCode.substringAfter("://").substringBefore("?")
                        if (authority.contains(":")) {
                            ip = authority.substringBeforeLast(":")
                            port = authority.substringAfterLast(":")
                        } else {
                            ip = authority
                        }
                    }

                    // Parse query parameters
                    var pcName: String? = null
                    var isPlus = false
                    var symmetricKey: String? = null

                    val queryPart = uri.toString().substringAfter('?', "")
                    if (queryPart.isNotEmpty()) {
                        val params = queryPart.split('?')
                        val paramMap = params.associate { param ->
                            val parts = param.split('=', limit = 2)
                            val key = parts.getOrNull(0) ?: ""
                            val value = parts.getOrNull(1) ?: ""
                            key to value
                        }
                        pcName = paramMap["name"]?.let { URLDecoder.decode(it, "UTF-8") }
                        isPlus = paramMap["plus"]?.toBooleanStrictOrNull() ?: false
                        symmetricKey = paramMap["key"]
                    }

                    if (ip.isNotEmpty() && port.isNotEmpty()) {
                        // Update UI state with scanned values
                        viewModel.updateIpAddress(ip)
                        viewModel.updatePort(port)
                        viewModel.updateManualPcName(pcName ?: "")
                        viewModel.updateManualIsPlus(isPlus)
                        if (!symmetricKey.isNullOrEmpty()) {
                            viewModel.updateSymmetricKey(symmetricKey)
                        }

                        // Trigger connection
                        scope.launch {
                            delay(300)  // Brief delay to ensure UI updates
                            connect()
                        }
                    } else {
                        Toast.makeText(context, "Invalid QR code format", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Failed to parse QR code: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    fun disconnect() {
        scope.launch {
            viewModel.setUserManuallyDisconnectedAwait(true)
            WebSocketUtil.disconnect(context)
            viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
            viewModel.clearClipboardHistory()
            viewModel.setResponse("Disconnected")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initializeState(
            context,
            initialIp,
            initialPort,
            showConnectionDialog && !hasProcessedQrDialog,
            pcName,
            isPlus,
            symmetricKey
        )

        // Start network monitoring for dynamic Wi-Fi changes
        viewModel.startNetworkMonitoring(context)

        // Refresh permissions on app launch
        viewModel.refreshPermissions(context)
    }

    // Refresh permissions when app resumes from pause
    DisposableEffect(lifecycle) {
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
            }
        }
        lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
        }
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
        val last = state.value
        snapshotFlow { state.value }.collect { value ->
            val delta = value - last
            if (delta > 2) fabVisible = false
            else if (delta < -2) fabVisible = true
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
            // Register callback to track clipboard history
            ClipboardSyncManager.setOnClipboardSentCallback { text ->
                viewModel.addClipboardEntry(text, isFromPc = false)
            }
            ClipboardSyncManager.startSync(context)
        } else {
            ClipboardSyncManager.stopSync(context)
        }
    }

    LaunchedEffect(Unit) {
        com.sameerasw.airsync.utils.WebSocketMessageHandler.setOnClipboardEntryCallback { text ->
            Log.d(
                "AirSyncMainScreen",
                "Incoming clipboard update via WebSocketMessageHandler: ${text.take(50)}"
            )
            viewModel.addClipboardEntry(text, isFromPc = true)
        }
    }

    // Start/stop loading haptics when connecting
    LaunchedEffect(uiState.isConnecting) {
        if (uiState.isConnecting) {
            loadingHapticsJob = HapticUtil.startLoadingHaptics(haptics, lifecycle)
        } else {
            loadingHapticsJob?.cancel()
            loadingHapticsJob = null
        }
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


    fun launchScanner(context: Context) {
        // Launch our custom QR Scanner Activity
        val scannerIntent = Intent(context, QRScannerActivity::class.java)
        qrScannerLauncher.launch(scannerIntent)
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
            ClipboardSyncManager.setOnClipboardSentCallback(null)
            ClipboardSyncManager.stopSync(context)
        }
    }

    // Define tabs
    val tabs = remember(uiState.isConnected) {
        if (uiState.isConnected) {
            listOf(
                AirSyncTab("Connect", Icons.Outlined.Phonelink, 0),
                AirSyncTab("Remote", Icons.Filled.Gamepad, 1),
                AirSyncTab("Clipboard", Icons.Filled.ContentPaste, 2),
                AirSyncTab("Settings", Icons.Filled.Settings, 3)
            )
        } else {
            listOf(
                AirSyncTab("Connect", Icons.Filled.Phonelink, 0),
                AirSyncTab("Settings", Icons.Filled.Settings, 1)
            )
        }
    }

    // Update title based on current tab
    LaunchedEffect(pagerState.currentPage, tabs) {
        val currentTab = tabs.getOrNull(pagerState.currentPage)
        if (currentTab != null) {
            val title = if (currentTab.title == "Connect") "AirSync" else currentTab.title
            onTitleChange(title)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(
                if (pagerState.currentPage != 2) exitAlwaysScrollBehavior
                else remember {
                    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {}
                }
            ),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        // Track page changes for haptic feedback on swipe
        LaunchedEffect(pagerState.currentPage) {
            snapshotFlow { pagerState.currentPage }.collect { _ ->
                HapticUtil.performLightTick(haptics)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                modifier = modifier.fillMaxSize(),
                state = pagerState
            ) { page ->
                when (page) {
                    0 -> {
                        // Connect tab content
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 0.dp)
                                .verticalScroll(connectScrollState)
                                .padding(horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {

                            Spacer(modifier = Modifier.height(0.dp))

                            RoundedCardContainer {

                                // Rating Prompt Card
                                AnimatedVisibility(
                                    visible = uiState.shouldShowRatingPrompt,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    RateAppCard(
                                        onDismiss = { viewModel.setRatingCardDismissed() },
                                        onRate = { viewModel.setAppRated() }
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
                            }

                            RoundedCardContainer {
                                // Nearby Devices (UDP Discovery)
                                val discoveredDevices by viewModel.discoveredDevices.collectAsState()

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
                                            onToggleAutoReconnect = { enabled ->
                                                viewModel.setAutoReconnectEnabled(
                                                    enabled
                                                )
                                            },
                                            onQuickConnect = {
                                                // Check if we can use network-aware connection first
                                                val networkAwareDevice =
                                                    viewModel.getNetworkAwareLastConnectedDevice()
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

                                AnimatedVisibility(
                                    visible = !uiState.isConnected,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.extraSmall,
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Available Devices",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )

                                                Switch(
                                                    checked = uiState.isDeviceDiscoveryEnabled,
                                                    onCheckedChange = { enabled ->
                                                        HapticUtil.performClick(haptics)
                                                        viewModel.setDeviceDiscoveryEnabled(
                                                            context,
                                                            enabled
                                                        )
                                                    },
                                                    thumbContent = if (uiState.isDeviceDiscoveryEnabled) {
                                                        {
                                                            Icon(
                                                                painter = painterResource(R.drawable.rounded_android_wifi_3_bar_24),
                                                                contentDescription = null,
                                                                modifier = Modifier.size(
                                                                    SwitchDefaults.IconSize
                                                                ),
                                                            )
                                                        }
                                                    } else null
                                                )
                                            }

                                            AnimatedVisibility(
                                                visible = uiState.isDeviceDiscoveryEnabled,
                                                enter = expandVertically() + fadeIn(),
                                                exit = shrinkVertically() + fadeOut()
                                            ) {
                                                Column {
                                                    if (discoveredDevices.isEmpty()) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(
                                                                4.dp
                                                            )
                                                        ) {
                                                            LoadingIndicator()

                                                            Text(
                                                                text = "Scanning...",
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(vertical = 8.dp)
                                                            )
                                                        }
                                                    }

                                                    discoveredDevices.forEachIndexed { index, device ->
                                                        if (index > 0) {
                                                            HorizontalDivider(
                                                                modifier = Modifier.padding(vertical = 8.dp),
                                                                thickness = 0.5.dp,
                                                                color = MaterialTheme.colorScheme.outlineVariant.copy(
                                                                    alpha = 0.5f
                                                                )
                                                            )
                                                        }

                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable {
                                                                    HapticUtil.performClick(haptics)
                                                                    viewModel.updateIpAddress(device.getBestIp())
                                                                    viewModel.updatePort(device.port.toString())
                                                                    viewModel.updateManualPcName(
                                                                        device.name
                                                                    )
                                                                    connect(device.id)
                                                                }
                                                                .padding(vertical = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.apple),
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                            Spacer(modifier = Modifier.width(12.dp))
                                                            Column {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(
                                                                        text = device.name,
                                                                        style = MaterialTheme.typography.bodyLarge
                                                                    )
                                                                    Spacer(
                                                                        modifier = Modifier.width(
                                                                            8.dp
                                                                        )
                                                                    )
                                                                    if (device.hasLocalIp()) {
                                                                        Icon(
                                                                            painter = painterResource(
                                                                                R.drawable.rounded_android_wifi_3_bar_24
                                                                            ),
                                                                            contentDescription = "Wi-Fi",
                                                                            modifier = Modifier.size(
                                                                                14.dp
                                                                            ),
                                                                            tint = MaterialTheme.colorScheme.primary
                                                                        )
                                                                    }
                                                                    if (device.hasTailscaleIp()) {
                                                                        if (device.hasLocalIp()) Spacer(
                                                                            modifier = Modifier.width(
                                                                                4.dp
                                                                            )
                                                                        )
                                                                        Icon(
                                                                            painter = painterResource(
                                                                                R.drawable.rounded_network_node_24
                                                                            ),
                                                                            contentDescription = "Tailscale",
                                                                            modifier = Modifier.size(
                                                                                14.dp
                                                                            ),
                                                                            tint = MaterialTheme.colorScheme.secondary
                                                                        )
                                                                    }
                                                                }
                                                                Text(
                                                                    text = "${device.getBestIp()}:${device.port}",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.weight(1f))
                                                            if (uiState.isConnecting && uiState.connectingDeviceId == device.id) {
                                                                CircularWavyProgressIndicator(
                                                                    modifier = Modifier.size(20.dp)
                                                                )
                                                            } else {
                                                                Icon(
                                                                    Icons.AutoMirrored.Filled.ArrowForward,
                                                                    contentDescription = "Connect",
                                                                    modifier = Modifier.size(20.dp),
                                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

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
                                            onConnect = { viewModel.prepareForManualConnection() },
                                            onQrScanClick = { launchScanner(context) }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    1 -> {
                        if (uiState.isConnected) {
                            // When connected: page 1 = Remote
                            RemoteControlScreen(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 100.dp),
                                showKeyboard = showKeyboard,
                                onDismissKeyboard = { showKeyboard = false }
                            )
                        } else {
                            // When disconnected: page 1 = Settings
                            SettingsView(
                                modifier = Modifier.fillMaxSize(),
                                context = context,
                                innerPaddingBottom = 0.dp,
                                uiState = uiState,
                                deviceInfo = deviceInfo,
                                versionName = versionName,
                                viewModel = viewModel,
                                scrollState = settingsScrollState,
                                scope = scope,
                                onSendMessage = { message -> sendMessage(message) },
                                onExport = { json ->
                                    pendingExportJson = json
                                    createDocLauncher.launch("airsync_settings_${System.currentTimeMillis()}.json")
                                },
                                onImport = { openDocLauncher.launch(arrayOf("application/json")) },
                                onResetOnboarding = { viewModel.resetOnboarding() }
                            )
                        }
                    }

                    2 -> {
                        if (uiState.isConnected) {
                            // When connected: page 2 = Clipboard
                            ClipboardScreen(
                                clipboardHistory = uiState.clipboardHistory,
                                isConnected = true,
                                onSendText = { text ->
                                    viewModel.addClipboardEntry(text, isFromPc = false)
                                    val clipboardJson = JsonUtil.createClipboardUpdateJson(text)
                                    WebSocketUtil.sendMessage(clipboardJson)
                                },
                                onClearHistory = { viewModel.clearClipboardHistory() },
                                isHistoryEnabled = uiState.isClipboardHistoryEnabled,
                                onHistoryToggle = { viewModel.setClipboardHistoryEnabled(it) },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 100.dp)
                            )
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }

                    3 -> {
                        // Page 3 only exists when connected = Settings tab
                        SettingsView(
                            modifier = Modifier.fillMaxSize(),
                            context = context,
                            innerPaddingBottom = 0.dp,
                            uiState = uiState,
                            deviceInfo = deviceInfo,
                            versionName = versionName,
                            viewModel = viewModel,
                            scrollState = settingsScrollState,
                            scope = scope,
                            onSendMessage = { message -> sendMessage(message) },
                            onExport = { json ->
                                pendingExportJson = json
                                createDocLauncher.launch("airsync_settings_${System.currentTimeMillis()}.json")
                            },
                            onImport = { openDocLauncher.launch(arrayOf("application/json")) },
                            onResetOnboarding = { viewModel.resetOnboarding() }
                        )
                    }
                }
            }

            AirSyncFloatingToolbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset - 12.dp)
                    .zIndex(1f),
                currentPage = pagerState.currentPage,
                tabs = tabs,
                onTabSelected = { index ->
                    scope.launch {
                        val distance = kotlin.math.abs(index - pagerState.currentPage)
                        if (distance == 1) {
                            pagerState.animateScrollToPage(index)
                        } else {
                            pagerState.scrollToPage(index)
                        }
                    }
                },
                scrollBehavior = exitAlwaysScrollBehavior,
                floatingActionButton = {
                    val currentTab = tabs.getOrNull(pagerState.currentPage)
                    FloatingToolbarDefaults.StandardFloatingActionButton(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            when (currentTab?.title) {
                                "Remote" -> {
                                    showKeyboard = !showKeyboard
                                }

                                "Clipboard" -> {
                                    viewModel.clearClipboardHistory()
                                }

                                else -> { // Connect or Settings
                                    if (uiState.isConnected) {
                                        disconnect()
                                    } else {
                                        launchScanner(context)
                                    }
                                }
                            }
                        }
                    ) {
                        when (currentTab?.title) {
                            "Remote" -> {
                                Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard")
                            }

                            "Clipboard" -> {
                                Icon(Icons.Rounded.Delete, contentDescription = "Clear History")
                            }

                            else -> { // Connect or Settings
                                if (uiState.isConnected) {
                                    Icon(
                                        imageVector = Icons.Filled.LinkOff,
                                        contentDescription = "Disconnect"
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.QrCodeScanner,
                                        contentDescription = "Scan QR"
                                    )
                                }
                            }
                        }
                    }
                }
            )
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

    // About Bottom Sheet - controlled by parent via showAboutDialog
    if (showAboutDialog) {
        AboutBottomSheet(
            onDismissRequest = onDismissAbout,
            onToggleDeveloperMode = { viewModel.toggleDeveloperModeVisibility() }
        )
    }

    // Help & Support Bottom Sheet
    if (showHelpSheet) {
        HelpSupportBottomSheet(
            onDismissRequest = onDismissHelp
        )
    }
}
