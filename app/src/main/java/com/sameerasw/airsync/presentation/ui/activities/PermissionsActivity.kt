package com.sameerasw.airsync.presentation.ui.activities

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.sameerasw.airsync.presentation.ui.screens.PermissionsScreen
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.PermissionUtil

class PermissionsActivity : ComponentActivity() {

    // Permission launchers
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUI() }

    private val callLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUI() }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUI() }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUI() }

    private var refreshCounter by mutableStateOf(0)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        super.onCreate(savedInstanceState)

        // Disable scrim on 3-button navigation (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        setContent {
            AirSyncTheme {
                val scrollBehavior =
                    TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text("Permissions")
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior
                        )
                    }
                ) { innerPadding ->
                    PermissionsScreen(
                        modifier = Modifier.padding(innerPadding),
                        onRequestNotificationPermission = {
                            requestNotificationPermission()
                        },
                        onRequestCallLogPermission = {
                            requestCallLogPermission()
                        },
                        onRequestContactsPermission = {
                            requestContactsPermission()
                        },
                        onRequestPhonePermission = {
                            requestPhonePermission()
                        },
                        refreshTrigger = refreshCounter
                    )
                }
            }
        }
    }

    private fun refreshUI() {
        refreshCounter++
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

    override fun onResume() {
        super.onResume()
        // Refresh permissions display when returning to this activity
        refreshUI()
    }
}

