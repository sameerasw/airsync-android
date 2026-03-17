package com.sameerasw.airsync.presentation.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.domain.model.DeviceInfo
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.presentation.ui.components.cards.ClipboardFeaturesCard
import com.sameerasw.airsync.presentation.ui.components.cards.DefaultTabCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeveloperModeCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeviceInfoCard
import com.sameerasw.airsync.presentation.ui.components.cards.ExpandNetworkingCard
import com.sameerasw.airsync.presentation.ui.components.cards.MediaSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.NotificationSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.PermissionsCard
import com.sameerasw.airsync.presentation.ui.components.cards.QuickSettingsTilesCard
import com.sameerasw.airsync.presentation.ui.components.cards.SendNowPlayingCard
import com.sameerasw.airsync.presentation.ui.components.cards.SmartspacerCard
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    onResetOnboarding: () -> Unit = {},
    onShowHelp: () -> Unit = {},
    onToggleDeveloperMode: () -> Unit = {}
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

        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topSpacing = (statusBarHeight - 24.dp).coerceAtLeast(0.dp)

        Spacer(
            modifier = Modifier
                .height(topSpacing)
                .fillMaxWidth()
        )

        // Top Section (Untitled)
        RoundedCardContainer {
            PermissionsCard(missingPermissionsCount = uiState.missingPermissions.size)

            // Help and guides card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        HapticUtil.performClick(haptics)
                        onShowHelp()
                    },
                shape = MaterialTheme.shapes.extraSmall,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.label_help_guides),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.subtitle_help_guides),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = com.sameerasw.airsync.R.drawable.rounded_keyboard_arrow_right_24),
                        contentDescription = "Open help",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            QuickSettingsTilesCard(
                isConnectionTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                    context,
                    com.sameerasw.airsync.service.AirSyncTileService::class.java
                ),
                isClipboardTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                    context,
                    com.sameerasw.airsync.service.ClipboardTileService::class.java
                ),
                isQuickShareTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                    context,

                )
            )
        }

        // App Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsCategoryTitle(androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.cat_app))
            RoundedCardContainer {
                DefaultTabCard(
                    currentDefaultTab = uiState.defaultTab,
                    onDefaultTabChange = { tab -> viewModel.setDefaultTab(tab) }
                )

                SendNowPlayingCard(
                    isSendNowPlayingEnabled = uiState.isBlurSettingEnabled,
                    onToggleSendNowPlaying = { enabled: Boolean ->
                        viewModel.setUseBlurEnabled(enabled, context)
                    },
                    title = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.label_use_blur),
                    subtitle = when {
                        com.sameerasw.airsync.utils.DeviceInfoUtil.isBlurProblematicDevice() ->
                            androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.subtitle_blur_disabled_samsung)

                        uiState.isPowerSaveMode && uiState.isBlurSettingEnabled ->
                            androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.subtitle_blur_disabled_power_save)

                        else -> androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.subtitle_use_blur)
                    },
                    enabled = !com.sameerasw.airsync.utils.DeviceInfoUtil.isBlurProblematicDevice()
                )

                SendNowPlayingCard(
                    isSendNowPlayingEnabled = uiState.isPitchBlackThemeEnabled,
                    onToggleSendNowPlaying = { enabled: Boolean ->
                        viewModel.setPitchBlackThemeEnabled(enabled)
                    },
                    title = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.label_pitch_black_theme),
                    subtitle = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.subtitle_pitch_black_theme)
                )

                SendNowPlayingCard(
                    isSendNowPlayingEnabled = uiState.isSentryReportingEnabled,
                    onToggleSendNowPlaying = { enabled: Boolean ->
                        viewModel.setSentryReportingEnabled(enabled)
                    },
                    title = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.label_error_reporting),
                    subtitle = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.subtitle_error_reporting)
                )
            }
        }

        // Sync Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsCategoryTitle("Sync")
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

                MediaSyncCard(
                    isSendNowPlayingEnabled = uiState.isSendNowPlayingEnabled,
                    onToggleSendNowPlaying = { enabled ->
                        viewModel.setSendNowPlayingEnabled(enabled)
                    },
                    isMacMediaControlsEnabled = uiState.isMacMediaControlsEnabled,
                    onToggleMacMediaControls = { enabled ->
                        viewModel.setMacMediaControlsEnabled(enabled)
                    }
                )

                SendNowPlayingCard(
                    isSendNowPlayingEnabled = uiState.isQuickShareEnabled,
                    onToggleSendNowPlaying = { enabled: Boolean ->
                        viewModel.setQuickShareEnabled(context, enabled)
                    },
                    title = "Quick Share",
                    subtitle = "Allow receiving files from nearby devices"
                )
            }
        }

        // Integration Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsCategoryTitle("Integration")
            RoundedCardContainer {
                SmartspacerCard(
                    isSmartspacerShowWhenDisconnected = uiState.isSmartspacerShowWhenDisconnected,
                    onToggleSmartspacerShowWhenDisconnected = { enabled: Boolean ->
                        viewModel.setSmartspacerShowWhenDisconnected(enabled)
                    }
                )

                val isEssentialsInstalled = try {
                    context.packageManager.getPackageInfo("com.sameerasw.essentials", 0)
                    true
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    false
                }

                if (isEssentialsInstalled) {
                    SendNowPlayingCard(
                        isSendNowPlayingEnabled = uiState.isEssentialsConnectionEnabled,
                        onToggleSendNowPlaying = { enabled: Boolean ->
                            viewModel.setEssentialsConnectionEnabled(enabled)
                        },
                        title = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.connect_to_essentials),
                        subtitle = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.connect_to_essentials_summary)
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraSmall,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        androidx.compose.material3.ListItem(
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            headlineContent = { Text(androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.download_essentials)) },
                            supportingContent = {
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.sameerasw.airsync.R.string.download_essentials_summary
                                    )
                                )
                            },
                            trailingContent = {
                                Button(
                                    onClick = {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://github.com/sameerasw/essentials/releases/latest")
                                        )
                                        intent.flags =
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        context.startActivity(intent)
                                    }
                                ) {
                                    Text("Download")
                                }
                            }
                        )
                    }
                }
            }
        }

        // Widget Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsCategoryTitle("Widget")
            RoundedCardContainer {
                com.sameerasw.airsync.presentation.ui.components.sliders.ConfigSliderItem(
                    title = "Widget Transparency",
                    value = uiState.widgetTransparency,
                    onValueChange = { viewModel.setWidgetTransparency(it) }
                )
            }
        }

        // Connection Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsCategoryTitle("Connection")
            RoundedCardContainer {
                DeviceInfoCard(
                    deviceName = uiState.deviceNameInput,
                    localIp = deviceInfo.localIp,
                    onDeviceNameChange = { viewModel.updateDeviceName(it) }
                )

                ExpandNetworkingCard(context)
            }
        }

        // Developer Mode
        AnimatedVisibility(
            visible = uiState.isDeveloperModeVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsCategoryTitle("Advanced")
                RoundedCardContainer {
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
                            val deviceId =
                                com.sameerasw.airsync.utils.DeviceInfoUtil.getDeviceId(context)
                            val message = com.sameerasw.airsync.utils.JsonUtil.createDeviceInfoJson(
                                deviceId,
                                deviceInfo.name,
                                deviceInfo.localIp,
                                uiState.port.toIntOrNull() ?: 6996,
                                versionName ?: "3.0.0",
                                adbPorts
                            )
                            onSendMessage(message)
                        },
                        onSendNotification = {
                            val testNotification =
                                com.sameerasw.airsync.utils.TestNotificationUtil.generateRandomNotification()

                            // Store ID for mock dismissal support
                            com.sameerasw.airsync.utils.NotificationDismissalUtil.storeTestNotificationId(
                                testNotification.id
                            )

                            val message =
                                com.sameerasw.airsync.utils.JsonUtil.createNotificationJson(
                                    testNotification.id,
                                    testNotification.title,
                                    testNotification.body,
                                    testNotification.appName,
                                    testNotification.packageName,
                                    testNotification.priority,
                                    testNotification.actions
                                )
                            onSendMessage(message)
                        },
                        onSendDeviceStatus = {
                            val message =
                                com.sameerasw.airsync.utils.DeviceInfoUtil.generateDeviceStatusJson(
                                    context
                                )
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
                        },
                        onResetOnboarding = {
                            onResetOnboarding()
                        },
                        isIconSyncLoading = uiState.isIconSyncLoading,
                        iconSyncMessage = uiState.iconSyncMessage,
                        onManualSyncIcons = {
                            viewModel.manualSyncAppIcons(context)
                        },
                        onClearIconSyncMessage = {
                            viewModel.clearIconSyncMessage()
                        },
                        isConnected = uiState.isConnected
                    )
                }
            }
        }

        AboutSection(
            onAvatarLongClick = onToggleDeveloperMode
        )

        Spacer(modifier = Modifier.height(180.dp))
    }
}

@Composable
fun SettingsCategoryTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

