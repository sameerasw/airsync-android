package com.sameerasw.airsync.presentation.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.presentation.ui.components.cards.DeviceInfoCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeveloperModeCard
import com.sameerasw.airsync.presentation.ui.components.cards.ExpandNetworkingCard
import com.sameerasw.airsync.presentation.ui.components.cards.NotificationSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.PermissionsCard
import com.sameerasw.airsync.presentation.ui.components.cards.QuickSettingsTipCard
import com.sameerasw.airsync.presentation.ui.components.cards.ClipboardFeaturesCard
import com.sameerasw.airsync.presentation.ui.components.cards.SendNowPlayingCard
import com.sameerasw.airsync.presentation.ui.components.cards.SmartspacerCard
import com.sameerasw.airsync.presentation.ui.components.cards.BluetoothCard
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalHapticFeedback
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.domain.model.DeviceInfo
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel

/**
 * SettingsView component that displays all settings cards in a scrollable column.
 * Can be used both when disconnected and when connected.
 *
 * @param modifier Modifier to apply to the settings view
 * @param context Android context for utilities
 * @param innerPaddingBottom Bottom padding for safe areas
 * @param uiState The current UI state containing all settings data
 * @param deviceInfo Device information data
 * @param versionName Application version name
 * @param viewModel ViewModel for handling state updates
 * @param scrollState Scroll state for the column
 * @param scope Coroutine scope for async operations
 * @param onSendMessage Callback to send messages
 * @param onExport Callback for export action
 * @param onImport Callback for import action
 */
@Composable
fun SettingsView(
    modifier: Modifier = Modifier,
    context: Context,
    innerPaddingBottom: androidx.compose.ui.unit.Dp,
    uiState: UiState,
    deviceInfo: DeviceInfo,
    versionName: String?,
    viewModel: AirSyncViewModel,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    scope: CoroutineScope = androidx.compose.runtime.rememberCoroutineScope(),
    onSendMessage: (String) -> Unit = {},
    onExport: (String) -> Unit = {},
    onImport: () -> Unit = {},
    onNavigateToHealth: () -> Unit = {},
    onNavigateToFileTransfer: () -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = innerPaddingBottom)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

        Spacer(modifier = Modifier.height(0.dp))

            RoundedCardContainer {
                PermissionsCard(missingPermissionsCount = uiState.missingPermissions.size)
                QuickSettingsTipCard(
                    isQSTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(context)
                )
            }

        // Notifications & Sync Features Section
            RoundedCardContainer {
                NotificationSyncCard(
                    isNotificationEnabled = uiState.isNotificationEnabled,
                    isNotificationSyncEnabled = uiState.isNotificationSyncEnabled,
                    onToggleSync = { enabled ->
                        viewModel.setNotificationSyncEnabled(enabled)
                    },
                    onGrantPermissions = { viewModel.setPermissionDialogVisible(true) }
                )

                ClipboardFeaturesCard(
                    isClipboardSyncEnabled = uiState.isClipboardSyncEnabled,
                    onToggleClipboardSync = { enabled: Boolean ->
                        viewModel.setClipboardSyncEnabled(enabled)
                    },
                    isContinueBrowsingEnabled = uiState.isContinueBrowsingEnabled,
                    onToggleContinueBrowsing = { enabled: Boolean ->
                        viewModel.setContinueBrowsingEnabled(enabled)
                    },
                    isContinueBrowsingToggleEnabled = true,
                    continueBrowsingSubtitle = "Prompt to open shared links in browser",
                    isKeepPreviousLinkEnabled = uiState.isKeepPreviousLinkEnabled,
                    onToggleKeepPreviousLink = { enabled: Boolean ->
                        viewModel.setKeepPreviousLinkEnabled(enabled)
                    }
                )

                SendNowPlayingCard(
                    isSendNowPlayingEnabled = uiState.isSendNowPlayingEnabled,
                    onToggleSendNowPlaying = { enabled: Boolean ->
                        viewModel.setSendNowPlayingEnabled(enabled)
                    }
                )

                SmartspacerCard(
                    isSmartspacerShowWhenDisconnected = uiState.isSmartspacerShowWhenDisconnected,
                    onToggleSmartspacerShowWhenDisconnected = { enabled: Boolean ->
                        viewModel.setSmartspacerShowWhenDisconnected(enabled)
                    }
                )

                // Mac Media Controls toggle for Play Store initiation proof
                SendNowPlayingCard(
                    isSendNowPlayingEnabled = uiState.isMacMediaControlsEnabled,
                    onToggleSendNowPlaying = { enabled: Boolean ->
                        viewModel.setMacMediaControlsEnabled(enabled)
                    },
                    title = "Show Mac Media Controls",
                    subtitle = "Show media controls when Mac is playing music"
                )

                ExpandNetworkingCard(context)
            }

        // Features Section - Health & File Transfer
        RoundedCardContainer {
            // Health & Fitness Button
            Button(
                onClick = {
                    HapticUtil.performClick(haptics)
                    onNavigateToHealth()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text("Health & Fitness")
            }

            // File Transfer Button (only when connected)
            AnimatedVisibility(
                visible = uiState.isConnected,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onNavigateToFileTransfer()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text("Send Files to Mac")
                }
            }
        }
        
        // Bluetooth Connection Section
        RoundedCardContainer {
            BluetoothCard()
        }

        // Device Info Section
            RoundedCardContainer {
                DeviceInfoCard(
                    deviceName = uiState.deviceNameInput,
                    localIp = deviceInfo.localIp,
                    onDeviceNameChange = { viewModel.updateDeviceName(it) }
                )
            }

        // Developer Mode & Icon Sync Section
            RoundedCardContainer {
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
                            val adbPorts = try {
                                val discoveredServices =
                                    com.sameerasw.airsync.AdbDiscoveryHolder.getDiscoveredServices()
                                discoveredServices.map { it.port.toString() }
                            } catch (_: Exception) {
                                emptyList()
                            }
                            val message = com.sameerasw.airsync.utils.JsonUtil.createDeviceInfoJson(
                                deviceInfo.name,
                                deviceInfo.localIp,
                                uiState.port.toIntOrNull() ?: 6996,
                                versionName ?: "2.0.0",
                                adbPorts
                            )
                            onSendMessage(message)
                        },
                        onSendNotification = {
                            val testNotification =
                                com.sameerasw.airsync.utils.TestNotificationUtil.generateRandomNotification()
                            val message = com.sameerasw.airsync.utils.JsonUtil.createNotificationJson(
                                testNotification.id,
                                testNotification.title,
                                testNotification.body,
                                testNotification.appName,
                                testNotification.packageName
                            )
                            onSendMessage(message)
                        },
                        onSendDeviceStatus = {
                            val message =
                                com.sameerasw.airsync.utils.DeviceInfoUtil.generateDeviceStatusJson(context)
                            onSendMessage(message)
                        },
                        onExportData = {
                            viewModel.setLoading(true)
                            scope.launch(Dispatchers.IO) {
                                val json = viewModel.exportAllDataToJson(context)
                                if (json == null) {
                                    scope.launch(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "Export failed",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        viewModel.setLoading(false)
                                    }
                                } else {
                                    scope.launch(Dispatchers.Main) {
                                        onExport(json)
                                    }
                                }
                            }
                        },
                        onImportData = {
                            onImport()
                        }
                    )
                }

                // Manual Icon Sync Button
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        viewModel.manualSyncAppIcons(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraSmall,
                    enabled = uiState.isConnected && !uiState.isIconSyncLoading
                ) {
                    if (uiState.isIconSyncLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp),
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
                        shape = MaterialTheme.shapes.extraSmall,
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
                            TextButton(onClick = {
                                HapticUtil.performClick(haptics)
                                viewModel.clearIconSyncMessage()
                            }) {
                                Text("Dismiss")
                            }
                        }
                    }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

