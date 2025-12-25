package com.sameerasw.airsync

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sameerasw.airsync.presentation.ui.screens.AirSyncMainScreen
import com.sameerasw.airsync.health.SimpleHealthScreen
import com.sameerasw.airsync.presentation.ui.screens.FileTransferScreen
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.PermissionUtil
import java.net.URLDecoder
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.core.net.toUri
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.sameerasw.airsync.data.local.DataStoreManager
import android.content.Intent
import com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity
import com.sameerasw.airsync.utils.AdbMdnsDiscovery
import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.airsync.utils.NotesRoleManager
import com.sameerasw.airsync.utils.KeyguardHelper
import com.sameerasw.airsync.utils.ContentCaptureManager
import com.sameerasw.airsync.utils.DevicePreviewResolver
import android.widget.Toast

import android.animation.ObjectAnimator
import android.view.animation.AnticipateInterpolator
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.animation.doOnEnd
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import android.widget.ImageView

object AdbDiscoveryHolder {
    private var discovery: AdbMdnsDiscovery? = null

    fun initialize(context: android.content.Context) {
        if (discovery == null) {
            Log.d("AdbDiscoveryHolder", "Initializing ADB discovery")
            discovery = AdbMdnsDiscovery(context.applicationContext)
            discovery?.startDiscovery()
        }
    }

    fun getDiscoveredServices(): List<AdbMdnsDiscovery.AdbServiceInfo> {
        return discovery?.getDiscoveredServices() ?: emptyList()
    }
}

class MainActivity : ComponentActivity() {
    // Flag to keep splash screen visible during app initialization
    private var isAppReady = false

    // Permission launcher for Android 13+ notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
    }

    // Permission launchers for call-related permissions
    private val callLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
    }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
    }

    // Notes Role request launcher
    private val notesRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d("MainActivity", "Notes Role granted successfully")
            Toast.makeText(this, "Notes Role granted!", Toast.LENGTH_SHORT).show()
        } else {
            Log.d("MainActivity", "Notes Role request denied or cancelled")
            Toast.makeText(this, "Notes Role request cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // Content capture launcher
    private val contentCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ContentCaptureManager.handleCaptureResult(
            resultCode = result.resultCode,
            data = result.data,
            onSuccess = { uri ->
                Log.d("MainActivity", "Content captured: $uri")
                Toast.makeText(this, "Content captured successfully", Toast.LENGTH_SHORT).show()
                // TODO: Handle pasting captured image into clipboard chat
            },
            onFailed = {
                Toast.makeText(this, "Content capture failed", Toast.LENGTH_SHORT).show()
            },
            onUserCanceled = {
                Log.d("MainActivity", "User cancelled content capture")
            },
            onWindowModeUnsupported = {
                Toast.makeText(this, "Content capture only available in floating window", Toast.LENGTH_SHORT).show()
            },
            onBlockedByAdmin = {
                Toast.makeText(this, "Content capture blocked by administrator", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrCode = result.data?.getStringExtra("QR_CODE")
            if (qrCode != null) {
                // Parse and connect with the QR code data
                handleQRCodeResult(qrCode)
            }
        } else {
            // User cancelled or invalid QR, do nothing - app remains open
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install and configure the splash screen before any UI rendering
        val splashScreen = installSplashScreen()

        // Make activity draw behind system bars - let the theme handles the colors
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        super.onCreate(savedInstanceState)

        // Dynamically set the splash screen icon based on last connected device
        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            try {
                val splashScreenView = splashScreenViewProvider.view
                val splashIcon = splashScreenViewProvider.iconView

                // Retrieve last connected device in background while showing splash
                var deviceIconRes: Int? = null
                try {
                    val dataStoreManager = DataStoreManager(this@MainActivity)
                    val lastDevice = runBlocking {
                        dataStoreManager.getLastConnectedDevice().first()
                    }

                    // If a last connected device exists, get its preview icon
                    if (lastDevice != null) {
                        deviceIconRes = DevicePreviewResolver.getPreviewRes(lastDevice)
                        Log.d("MainActivity", "Found last connected device, will crossfade to icon: $deviceIconRes")
                    } else {
                        Log.d("MainActivity", "No last connected device found")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error retrieving last connected device: ${e.message}", e)
                }

                // Perform crossfade animation if device icon was found
                if (splashIcon is ImageView && deviceIconRes != null) {
                    // Fade out the original app icon
                    val fadeOutIcon = ObjectAnimator.ofFloat(splashIcon, "alpha", 1f, 0f).apply {
                        duration = 150 // 0.5 seconds
                    }

                    fadeOutIcon.doOnEnd {
                        // Switch to device icon - with null check for OEM device compatibility
                        try {
                            splashIcon.setImageResource(deviceIconRes)
                            Log.d("MainActivity", "Switched to device icon")

                            // Fade in the new device icon
                            val fadeInIcon = ObjectAnimator.ofFloat(splashIcon, "alpha", 0f, 1f).apply {
                                duration = 350 // 0.5 seconds
                            }

                            fadeInIcon.doOnEnd {
                                // Hold on device icon for 0.5s, then start outro animation
                                try {
                                    splashIcon.postDelayed({
                                        startOutroAnimation(splashScreenView, splashIcon, splashScreenViewProvider)
                                    }, 250) // 0.5 second hold
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Error scheduling outro animation: ${e.message}", e)
                                    // Fallback: start outro immediately
                                    startOutroAnimation(splashScreenView, splashIcon, splashScreenViewProvider)
                                }
                            }

                            fadeInIcon.start()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error during icon switch/fade in: ${e.message}", e)
                            // Fallback: skip to outro animation if icon switch fails
                            startOutroAnimation(splashScreenView, splashIcon, splashScreenViewProvider)
                        }
                    }

                    fadeOutIcon.start()
                } else {
                    // No device icon found, or splashIcon is null/not ImageView (OEM device compatibility)
                    when {
                        splashIcon == null -> {
                            Log.w("SplashScreen", "iconView is null - OEM device detected, skipping crossfade")
                        }
                        deviceIconRes == null -> {
                            Log.d("MainActivity", "No device icon resource, proceeding with app icon")
                        }
                        else -> {
                            Log.w("SplashScreen", "iconView is not an ImageView - OEM device detected")
                        }
                    }

                    // Proceed directly to outro after a brief hold
                    try {
                        splashIcon?.postDelayed({
                            startOutroAnimation(splashScreenView, splashIcon, splashScreenViewProvider)
                        }, 500)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error scheduling outro with no icon: ${e.message}", e)
                        // Fallback: start outro immediately
                        startOutroAnimation(splashScreenView, splashIcon, splashScreenViewProvider)
                    }
                }
            } catch (e: Exception) {
                // Fallback for any unexpected exceptions during animation
                Log.e("SplashScreen", "Exception during splash screen animation", e)
                try {
                    splashScreenViewProvider.remove()
                } catch (e2: Exception) {
                    Log.e("SplashScreen", "Exception during splash screen removal", e2)
                }
            }
        }

        // Keep splash screen visible while app is loading
        splashScreen.setKeepOnScreenCondition { !isAppReady }

        // Handle Notes Role intent
        handleNotesRoleIntent(intent)

        // Start ADB discovery once at app startup and keep it running
        AdbDiscoveryHolder.initialize(this)
        Log.d("MainActivity", "Started persistent ADB discovery at app startup")

        // Check if this is a QS tile long-press intent and device is not connected
        if (intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            if (!WebSocketUtil.isConnected()) {
                // Not connected, open QR scanner instead
                val qrScannerIntent = Intent(this, QRScannerActivity::class.java)
                qrScannerLauncher.launch(qrScannerIntent)
                return
            }
        }

        val data: android.net.Uri? = intent?.data
        val ip = data?.host
        val port = data?.port?.takeIf { it != -1 }?.toString()

        // Parse QR code parameters
        var pcName: String? = null
        var isPlus = false
        var symmetricKey: String? = null

        data?.let { uri ->
            val urlString = uri.toString()
            val queryPart = urlString.substringAfter('?', "")
            if (queryPart.isNotEmpty()) {
                val params = queryPart.split('?')
                val paramMap = params.associate {
                    val parts = it.split('=', limit = 2)
                    val key = parts.getOrNull(0) ?: ""
                    val value = parts.getOrNull(1) ?: ""
                    key to value
                }
                pcName = paramMap["name"]?.let { URLDecoder.decode(it, "UTF-8") }
                isPlus = paramMap["plus"]?.toBooleanStrictOrNull() ?: false
                symmetricKey = paramMap["key"]
            }
        }

        val isFromQrScan = data != null

        setContent {
            AirSyncTheme {
                val navController = rememberNavController()
                var showAboutDialog by remember { mutableStateOf(false) }
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    topBar = {
                        LargeTopAppBar(
                            title = {
                                Row {
                                    // Dynamic icon based on last connected device category
                                    val ctx = androidx.compose.ui.platform.LocalContext.current
                                    val ds = remember(ctx) { DataStoreManager(ctx) }
                                    val lastDevice by ds.getLastConnectedDevice().collectAsState(initial = null)
                                    val iconRes = com.sameerasw.airsync.utils.DeviceIconResolver.getIconRes(lastDevice)
                                    Image(
                                        painter = painterResource(id = iconRes),
                                        contentDescription = "AirSync Logo",
                                        modifier = Modifier.size(32.dp),
                                        contentScale = ContentScale.Fit,
                                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "AirSync",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { showAboutDialog = true }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.outline_info_24),
                                        contentDescription = "About"
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior
                        )
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "main",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("main") {
                            AirSyncMainScreen(
                                initialIp = ip,
                                initialPort = port,
                                showConnectionDialog = isFromQrScan,
                                pcName = pcName,
                                isPlus = isPlus,
                                symmetricKey = symmetricKey,
                                showAboutDialog = showAboutDialog,
                                onDismissAbout = { showAboutDialog = false },
                                onNavigateToHealth = { navController.navigate("health") },
                                onNavigateToFileTransfer = { navController.navigate("fileTransfer") }
                            )
                        }
                        composable("health") {
                            SimpleHealthScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("fileTransfer") {
                            FileTransferScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }

                // Mark app as ready after composing so splash screen can exit
                LaunchedEffect(Unit) {
                    isAppReady = true
                }
            }
        }
    }

    /**
     * Animate the outro of the splash screen: fade out screen and scale down icon
     */
    private fun startOutroAnimation(
        splashScreenView: android.view.View,
        splashIcon: android.view.View?,
        splashScreenViewProvider: androidx.core.splashscreen.SplashScreenViewProvider
    ) {
        try {
            // Animate the splash screen view fade out
            val fadeOut = ObjectAnimator.ofFloat(splashScreenView, "alpha", 1f, 0f).apply {
                interpolator = AnticipateInterpolator()
                duration = 750
            }
            fadeOut.doOnEnd {
                splashScreenViewProvider.remove()
            }

            // Safely animate the icon if it exists (OEM device compatibility)
            try {
                @Suppress("SENSELESS_COMPARISON")
                if (splashIcon != null) {
                    // Scale down animation
                    val scaleX = ObjectAnimator.ofFloat(splashIcon, "scaleX", 1f, 0.2f).apply {
                        interpolator = AnticipateInterpolator()
                        duration = 750
                    }

                    val scaleY = ObjectAnimator.ofFloat(splashIcon, "scaleY", 1f, 0.2f).apply {
                        interpolator = AnticipateInterpolator()
                        duration = 750
                    }

                    scaleX.start()
                    scaleY.start()
                } else {
                    Log.w("SplashScreen", "iconView is null - OEM device detected")
                }
            } catch (e: NullPointerException) {
                Log.w("SplashScreen", "NullPointerException on iconView animation - likely OEM device", e)
            }

            fadeOut.start()
        } catch (e: Exception) {
            Log.e("SplashScreen", "Exception during outro animation", e)
            try {
                splashScreenViewProvider.remove()
            } catch (e2: Exception) {
                Log.e("SplashScreen", "Exception removing splash screen", e2)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionUtil.isPostNotificationPermissionGranted(this)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestCallLogPermission() {
        if (!PermissionUtil.isCallLogPermissionGranted(this)) {
            callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    private fun requestContactsPermission() {
        if (!PermissionUtil.isContactsPermissionGranted(this)) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun requestPhonePermission() {
        if (!PermissionUtil.isPhoneStatePermissionGranted(this)) {
            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    private fun handleQRCodeResult(qrCode: String) {
        try {
            val uri = android.net.Uri.parse(qrCode)

            // Validate QR code format: must be airsync scheme with host and port
            if (uri.scheme != "airsync") {
                Log.w("MainActivity", "Invalid QR code scheme: ${uri.scheme}")
                android.widget.Toast.makeText(
                    this,
                    "Invalid QR code format",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }

            val host = uri.host
            val port = uri.port

            if (host.isNullOrEmpty() || port == -1) {
                Log.w("MainActivity", "Invalid QR code: missing host or port")
                android.widget.Toast.makeText(
                    this,
                    "Invalid QR code format",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }

            // Valid QR code, proceed with connection
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(mainIntent)
            finish()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error handling QR code result: ${e.message}", e)
            android.widget.Toast.makeText(
                this,
                "Invalid QR code format",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Handle Notes Role intent
        handleNotesRoleIntent(intent)

        // Check if this is a QS tile long-press intent
        if (intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            // Check if device is connected
            if (!WebSocketUtil.isConnected()) {
                // Not connected, open QR scanner
                val qrScannerIntent = Intent(this, QRScannerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(qrScannerIntent)
                finish()
            }
        }
    }

    /**
     * Ensure ADB discovery is running (started at app startup, this just verifies it's active).
     */
    fun initializeAdbDiscovery() {
        AdbDiscoveryHolder.initialize(this)
    }

    /**
     * Get discovered ADB services from the running mDNS discovery.
     */
    fun getDiscoveredAdbServices(): List<AdbMdnsDiscovery.AdbServiceInfo> {
        return AdbDiscoveryHolder.getDiscoveredServices()
    }

    /**
     * Handles intents related to the Notes Role feature.
     * Extracts stylus mode hint and lock screen status from the intent.
     */
    private fun handleNotesRoleIntent(intent: Intent?) {
        // Check if this is an ACTION_CREATE_NOTE intent
        if (intent?.action == Intent.ACTION_CREATE_NOTE) {
            Log.d("MainActivity", "Received ACTION_CREATE_NOTE intent")

            // Check if stylus mode is requested
            val useStylusMode = NotesRoleManager.shouldUseStylusMode(intent)
            Log.d("MainActivity", "Stylus mode: $useStylusMode")

            // Check if launched from lock screen
            val launchedFromLockScreen = KeyguardHelper.isKeyguardLocked(this)
            Log.d("MainActivity", "Launched from lock screen: $launchedFromLockScreen")

            // If locked and device is secure, request unlock
            if (launchedFromLockScreen && KeyguardHelper.isKeyguardSecure(this)) {
                KeyguardHelper.requestDismissKeyguard(
                    this,
                    onDismissSucceeded = {
                        Log.d("MainActivity", "Device unlocked successfully")
                    },
                    onDismissError = {
                        Log.w("MainActivity", "Failed to unlock device")
                    }
                )
            }
        }
    }

    /**
     * Request the Notes Role for this app.
     * Call this from a UI button or during app initialization.
     */
    fun requestNotesRole() {
        if (NotesRoleManager.isNotesRoleAvailable(this)) {
            NotesRoleManager.requestNotesRole(this, notesRoleLauncher)
        } else {
            Toast.makeText(this, "Notes Role not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launch content capture for note annotation.
     * Should only be called when in floating window mode.
     */
    fun launchContentCapture() {
        if (!NotesRoleManager.isRunningInFloatingWindow(this)) {
            Toast.makeText(this, "Content capture only available in floating window", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContentCaptureManager.isScreenCaptureDisabled(this)) {
            Toast.makeText(this, "Screen capture disabled by administrator", Toast.LENGTH_SHORT).show()
            return
        }

        ContentCaptureManager.launchContentCapture(contentCaptureLauncher)
    }
}
