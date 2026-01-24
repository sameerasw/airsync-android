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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.Phonelink
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sameerasw.airsync.presentation.ui.components.cards.LastConnectedDeviceCard
import com.sameerasw.airsync.presentation.ui.components.cards.ManualConnectionCard
import com.sameerasw.airsync.presentation.ui.components.cards.ConnectionStatusCard
import com.sameerasw.airsync.presentation.ui.components.cards.IncomingFileTransferCard
import com.sameerasw.airsync.presentation.ui.components.dialogs.AboutDialog
import com.sameerasw.airsync.presentation.ui.components.dialogs.ConnectionDialog
import com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity
import org.json.JSONObject
import kotlinx.coroutines.Job
import java.net.URLDecoder
import androidx.core.net.toUri
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.presentation.ui.components.SettingsView

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
    onNavigateToHealth: () -> Unit = {},
    onNavigateToFileTransfer: () -> Unit = {},
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connectScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()
    var hasProcessedQrDialog by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { if (uiState.isConnected) 3 else 2 })
    val navCallbackState = rememberUpdatedState(onNavigateToApps)
    LaunchedEffect(navCallbackState.value) {
    }
    var fabVisible by remember { mutableStateOf(true) }
    var fabExpanded by remember { mutableStateOf(true) }
    var loadingHapticsJob by remember { mutableStateOf<Job?>(null) }

    // For export/import flow
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    
    fun connect() {
        // Check if critical permissions are missing
        val criticalPermissions = com.sameerasw.airsync.utils.PermissionUtil.getCriticalMissingPermissions(context)
        if (criticalPermissions.isNotEmpty()) {
            Toast.makeText(context, "Missing permissions", Toast.LENGTH_SHORT).show()
            return
        }

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
                        val plusStatus = uiState.lastConnectedDevice?.isPlus ?: isPlus
                        viewModel.saveLastConnectedDevice(pcName, plusStatus, uiState.symmetricKey)
                    } else {
                        viewModel.setResponse("Failed to connect")
                    }
                }
            },
            onMessage = { response ->
                scope.launch(Dispatchers.Main) {
                    Log.d("AirSyncMainScreen", "Message received: $response")
                    viewModel.setResponse("Received: $response")
                    try {
                        val json = JSONObject(response)
                        Log.d("AirSyncMainScreen", "Message type: ${json.optString("type")}")
                        // Note: Clipboard updates are now handled by WebSocketMessageHandler callback
                        // which ensures consistency regardless of connection method (manual or auto-reconnect)
                    } catch (e: Exception) {
                        Log.e("AirSyncMainScreen", "Error processing message: ${e.message}", e)
                    }
                }
            }
        )
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
                val input = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
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
                        Toast.makeText(context, "Import failed or invalid file", Toast.LENGTH_LONG).show()
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
                    val ip = uri.host ?: ""
                    val port = uri.port.takeIf { it != -1 }?.toString() ?: ""

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
                    Toast.makeText(context, "Failed to parse QR code: ${e.message}", Toast.LENGTH_SHORT).show()
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
        viewModel.initializeState(context, initialIp, initialPort, showConnectionDialog && !hasProcessedQrDialog, pcName, isPlus, symmetricKey)

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
            Log.d("AirSyncMainScreen", "Incoming clipboard update via WebSocketMessageHandler: ${text.take(50)}")
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

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            // Hide FAB on Clipboard tab
            if (pagerState.currentPage != 1) {
                AnimatedVisibility(visible = fabVisible, enter = scaleIn(), exit = scaleOut()) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            HapticUtil.performClick(haptics)
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
            }
        },
        bottomBar = {
            // Dynamic tab list - only include Clipboard when connected
            val items = if (uiState.isConnected) {
                listOf("Connect", "Clipboard", "Settings")
            } else {
                listOf("Connect", "Settings")
            }
            val selectedIcons = if (uiState.isConnected) {
                listOf(Icons.Filled.Phonelink, Icons.Filled.ContentPaste, Icons.Filled.Settings)
            } else {
                listOf(Icons.Filled.Phonelink, Icons.Filled.Settings)
            }
            val unselectedIcons = if (uiState.isConnected) {
                listOf(Icons.Outlined.Phonelink, Icons.Rounded.ContentPaste, Icons.Outlined.Settings)
            } else {
                listOf(Icons.Outlined.Phonelink, Icons.Outlined.Settings)
            }
            NavigationBar(
                windowInsets = NavigationBarDefaults.windowInsets
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = {
                            val selected = pagerState.currentPage == index
                            val iconOffset by animateDpAsState(targetValue = if (selected) 0.dp else 2.dp, label = "NavIconOffset")

                            // Show badge on Settings tab if there are missing permissions
                            if (item == "Settings") {
                                BadgedBox(
                                    badge = {
                                        if (uiState.missingPermissions.isNotEmpty()) {
                                            Badge() // Show dot without number
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (selected) selectedIcons[index] else unselectedIcons[index],
                                        contentDescription = item,
                                        modifier = Modifier.offset(y = iconOffset)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (selected) selectedIcons[index] else unselectedIcons[index],
                                    contentDescription = item,
                                    modifier = Modifier.offset(y = iconOffset)
                                )
                            }
                        },
                        label = {
                            val selected = pagerState.currentPage == index
                            val alpha by animateFloatAsState(targetValue = if (selected) 1f else 0f, label = "NavLabelAlpha")
                            // Keep label space reserved (alwaysShowLabel=true) and fade it in/out to avoid icon jumps
                            Text(item, modifier = Modifier.alpha(alpha))
                        },
                        alwaysShowLabel = true,
                        selected = pagerState.currentPage == index,
                        onClick = {
                            HapticUtil.performLightTick(haptics)
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        // Track page changes for haptic feedback on swipe
        LaunchedEffect(pagerState.currentPage) {
            snapshotFlow { pagerState.currentPage }.collect { _ ->
                HapticUtil.performLightTick(haptics)
            }
        }

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
                            .padding(bottom = innerPadding.calculateBottomPadding())
                            .verticalScroll(connectScrollState)
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {

                        Spacer(modifier = Modifier.height(0.dp))

                        RoundedCardContainer {

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
                        
                        // Incoming File Transfer Card - shows when receiving files
                        IncomingFileTransferCard()

                        RoundedCardContainer{
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
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                1 -> {
                    if (uiState.isConnected) {
                        // When connected: page 1 = Clipboard
                        ClipboardScreen(
                            clipboardHistory = uiState.clipboardHistory,
                            isConnected = true,
                            onSendText = { text ->
                                viewModel.addClipboardEntry(text, isFromPc = false)
                                val clipboardJson = JsonUtil.createClipboardUpdateJson(text)
                                WebSocketUtil.sendMessage(clipboardJson)
                            },
                            onClearHistory = { viewModel.clearClipboardHistory() },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = innerPadding.calculateBottomPadding())
                        )
                    } else {
                        // When disconnected: page 1 = Settings
                        SettingsView(
                            modifier = Modifier.fillMaxSize(),
                            context = context,
                            innerPaddingBottom = innerPadding.calculateBottomPadding(),
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
                            onNavigateToHealth = onNavigateToHealth,
                            onNavigateToFileTransfer = onNavigateToFileTransfer
                        )
                    }
                }
                2 -> {
                    // Page 2 only exists when connected = Settings tab
                    SettingsView(
                        modifier = Modifier.fillMaxSize(),
                        context = context,
                        innerPaddingBottom = innerPadding.calculateBottomPadding(),
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
                        onNavigateToHealth = onNavigateToHealth,
                        onNavigateToFileTransfer = onNavigateToFileTransfer
                    )
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
