package com.sameerasw.airsync.presentation.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.presentation.ui.components.dialogs.PermissionExplanationDialog
import com.sameerasw.airsync.presentation.ui.components.dialogs.PermissionType
import com.sameerasw.airsync.utils.PermissionUtil
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun PermissionsScreen(
    modifier: Modifier = Modifier,
    onRequestNotificationPermission: (() -> Unit)? = null,
    onRequestCallLogPermission: (() -> Unit)? = null,
    onRequestContactsPermission: (() -> Unit)? = null,
    onRequestPhonePermission: (() -> Unit)? = null,
    onRequestAnswerCallPermission: (() -> Unit)? = null,
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    // Recompute permissions when refreshTrigger changes
    val criticalPermissions by remember(refreshTrigger) {
        derivedStateOf { PermissionUtil.getCriticalMissingPermissions(context) }
    }
    val optionalPermissions by remember(refreshTrigger) {
        derivedStateOf { PermissionUtil.getOptionalMissingPermissions(context) }
    }

    var showDialog by remember { mutableStateOf<PermissionType?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RoundedCardContainer {
            // Check if all permissions are granted
            if (criticalPermissions.isEmpty() && optionalPermissions.isEmpty()) {
                // All permissions granted
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "(/^▽^)/    All Permissions Granted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            } else {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraSmall,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(modifier = Modifier.padding(16.dp)) {
                                Column {
                                    Text(
                                        text = "Missing permissions",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (criticalPermissions.isNotEmpty())
                                            MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onSecondaryContainer
                                    )

                                    Text(
                                        text = "Open to read more and grant",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                OutlinedButton(onClick = {
                                    HapticUtil.performClick(haptics)
                                    // Force recomposition by incrementing refreshTrigger through parent
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh"
                                    )
                                }
                            }
                        }

                        // Show critical permissions first
                        if (criticalPermissions.isNotEmpty()) {

                            criticalPermissions.forEach { permission ->
                                when (permission) {
                                    "Notification Access" -> {
                                        PermissionButton(
                                            permissionName = permission,
                                            description = "Required for syncing notifications",
                                            onExplainClick = {
                                                showDialog = PermissionType.NOTIFICATION_ACCESS
                                            },
                                            isCritical = true
                                        )
                                    }
                                }
                            }
                        }

                        // Show optional permissions
                        if (optionalPermissions.isNotEmpty()) {
                            optionalPermissions.forEach { permission ->
                                when (permission) {
                                    "Post Notifications" -> {
                                        PermissionButton(
                                            permissionName = permission,
                                            description = "Show connection status and alerts",
                                            onExplainClick = {
                                                showDialog = PermissionType.POST_NOTIFICATIONS
                                            },
                                            isCritical = false
                                        )
                                    }

                                    "Background App Usage" -> {
                                        PermissionButton(
                                            permissionName = permission,
                                            description = "Keep the app alive when inactive",
                                            onExplainClick = {
                                                showDialog = PermissionType.BACKGROUND_USAGE
                                            },
                                            isCritical = false
                                        )
                                    }

                                    "Wallpaper Access" -> {
                                        PermissionButton(
                                            permissionName = permission,
                                            description = "Enables wallpaper sync",
                                            onExplainClick = {
                                                showDialog = PermissionType.WALLPAPER_ACCESS
                                            },
                                            isCritical = false
                                        )
                                    }

                                    "Call Log Access" -> {
                                        PermissionButton(
                                            permissionName = permission,
                                            description = "Enables call log sync",
                                            onExplainClick = {
                                                showDialog = PermissionType.CALL_LOG
                                            },
                                            isCritical = false
                                        )
                                    }

                                    "Contacts Access" -> {
                                        PermissionButton(
                                            permissionName = permission,
                                            description = "Enables contacts sync",
                                            onExplainClick = {
                                                showDialog = PermissionType.CONTACTS
                                            },
                                            isCritical = false
                                        )
                                    }

                                    "Phone Access" -> {
                                        PermissionButton(
                                            permissionName = permission,
                                            description = "Enables phone state access",
                                            onExplainClick = { showDialog = PermissionType.PHONE },
                                            isCritical = false
                                        )
                                    }

                                    "Answer Calls" -> {
                                        PermissionButton(
                                            permissionName = permission,
                                            description = "Enables ending calls from Mac",
                                            onExplainClick = { showDialog = PermissionType.ANSWER_PHONE_CALLS },
                                            isCritical = false
                                        )
                                    }
                                }
                            }
                        }
                    }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Show permission explanation dialog
    showDialog?.let { permissionType ->
        PermissionExplanationDialog(
            permissionType = permissionType,
            onDismiss = { showDialog = null },
            onGrantPermission = {
                when (permissionType) {
                    PermissionType.NOTIFICATION_ACCESS -> {
                        PermissionUtil.openNotificationListenerSettings(context)
                    }
                    PermissionType.POST_NOTIFICATIONS -> {
                        onRequestNotificationPermission?.invoke()
                    }
                    PermissionType.BACKGROUND_USAGE -> {
                        PermissionUtil.openBatteryOptimizationSettings(context)
                    }
                    PermissionType.WALLPAPER_ACCESS -> {
                        PermissionUtil.openManageExternalStorageSettings(context)
                    }
                    PermissionType.CALL_LOG -> {
                        onRequestCallLogPermission?.invoke()
                    }
                    PermissionType.CONTACTS -> {
                        onRequestContactsPermission?.invoke()
                    }
                    PermissionType.PHONE -> {
                        onRequestPhonePermission?.invoke()
                    }
                    PermissionType.ANSWER_PHONE_CALLS -> {
                        onRequestAnswerCallPermission?.invoke()
                    }
                }
            }
        )
    }
}

@Composable
private fun PermissionButton(
    permissionName: String,
    description: String,
    onExplainClick: () -> Unit,
    isCritical: Boolean
) {
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permissionName,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCritical)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCritical)
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }

            if (isCritical) {
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onExplainClick()
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Open")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onExplainClick()
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Open")
                }
            }
        }
    }
}


