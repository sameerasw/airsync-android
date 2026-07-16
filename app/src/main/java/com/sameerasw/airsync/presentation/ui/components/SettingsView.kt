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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.DeviceInfo
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.presentation.ui.components.cards.ClipboardFeaturesCard
import com.sameerasw.airsync.presentation.ui.components.cards.DefaultTabCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeveloperModeCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeviceInfoCard
import com.sameerasw.airsync.presentation.ui.components.cards.ExpandNetworkingCard
import com.sameerasw.airsync.presentation.ui.components.cards.IconToggleItem
import com.sameerasw.airsync.presentation.ui.components.cards.MediaSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.NotificationSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.PermissionsCard
import com.sameerasw.airsync.presentation.ui.components.cards.QuickSettingsTilesCard
import com.sameerasw.airsync.presentation.ui.components.cards.SmartspacerCard
import com.sameerasw.airsync.presentation.ui.components.sheets.AppSelectionSheet
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
    var showAppSelectionSheet by remember { mutableStateOf(false) }

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
            com.sameerasw.airsync.presentation.ui.components.cards.IconToggleItem(
                iconRes = com.sameerasw.airsync.R.drawable.rounded_info_24,
                title = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.label_help_guides),
                description = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.subtitle_help_guides),
                showToggle = false,
                onClick = {
                    HapticUtil.performClick(haptics)
                    onShowHelp()
                }
            )

            QuickSettingsTilesCard(
                isConnectionTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                    context,
                    com.sameerasw.airsync.service.AirSyncTileService::class.java
                ),
                isClipboardTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                    context,
                    com.sameerasw.airsync.service.ClipboardTileService::class.java
                )
            )
        }

        // App Section
        SettingsCategory(title = stringResource(R.string.cat_app)) {
            DefaultTabCard(
                currentDefaultTab = uiState.defaultTab,
                onDefaultTabChange = { tab -> viewModel.setDefaultTab(tab) }
            )

            IconToggleItem(
                title = stringResource(R.string.label_use_blur),
                description = when {
                    com.sameerasw.airsync.utils.DeviceInfoUtil.isBlurProblematicDevice() ->
                        stringResource(R.string.subtitle_blur_disabled_samsung)

                    uiState.isPowerSaveMode && uiState.isBlurSettingEnabled ->
                        stringResource(R.string.subtitle_blur_disabled_power_save)

                    else -> stringResource(R.string.subtitle_use_blur)
                },
                iconRes = R.drawable.rounded_blur_on_24,
                isChecked = uiState.isBlurSettingEnabled,
                onCheckedChange = { enabled: Boolean ->
                    viewModel.setUseBlurEnabled(enabled, context)
                },
                enabled = !com.sameerasw.airsync.utils.DeviceInfoUtil.isBlurProblematicDevice()
            )

            IconToggleItem(
                title = stringResource(R.string.label_pitch_black_theme),
                description = stringResource(R.string.subtitle_pitch_black_theme),
                iconRes = R.drawable.rounded_dark_mode_24,
                isChecked = uiState.isPitchBlackThemeEnabled,
                onCheckedChange = { enabled: Boolean ->
                    viewModel.setPitchBlackThemeEnabled(enabled)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.label_notify_on_crash),
                description = stringResource(R.string.subtitle_notify_on_crash),
                iconRes = R.drawable.rounded_bug_report_24,
                isChecked = uiState.isNotifyOnCrashEnabled,
                onCheckedChange = { enabled: Boolean ->
                    viewModel.setNotifyOnCrashEnabled(enabled)
                }
            )
        }

        // Notifications Section
        SettingsCategory(title = stringResource(R.string.cat_notifications)) {
            NotificationSyncCard(
                isNotificationEnabled = uiState.isNotificationEnabled,
                isNotificationSyncEnabled = uiState.isNotificationSyncEnabled,
                onToggleSync = { enabled ->
                    viewModel.setNotificationSyncEnabled(enabled)
                },
                onGrantPermissions = { viewModel.setPermissionDialogVisible(true) }
            )

            if (uiState.isNotificationSyncEnabled && uiState.isNotificationEnabled) {
                IconToggleItem(
                    title = stringResource(R.string.action_select_apps),
                    description = stringResource(R.string.subtitle_to_be_notified),
                    iconRes = R.drawable.rounded_notification_settings_24,
                    showToggle = false,
                    onClick = {
                        HapticUtil.performClick(haptics)
                        viewModel.loadNotificationApps(context)
                        showAppSelectionSheet = true
                    }
                )
            }
        }

        // Clipboard Section
        SettingsCategory(title = stringResource(R.string.cat_clipboard)) {
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
        }

        // Media Section
        SettingsCategory(title = stringResource(R.string.cat_media)) {
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
        }

        // Files Section
        SettingsCategory(title = stringResource(R.string.cat_files)) {
            IconToggleItem(
                title = "Quick Share",
                description = "Allow receiving files from nearby devices",
                iconRes = R.drawable.quick_share,
                isChecked = uiState.isQuickShareEnabled,
                onCheckedChange = { enabled: Boolean ->
                    viewModel.setQuickShareEnabled(context, enabled)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.label_file_access),
                description = stringResource(R.string.subtitle_file_access),
                iconRes = R.drawable.rounded_folder_managed_24,
                isChecked = uiState.isFileAccessEnabled,
                onCheckedChange = { enabled: Boolean ->
                    viewModel.setFileAccessEnabled(context, enabled)
                }
            )
        }

        // Integration Section
        SettingsCategory(title = "Integration") {
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
                IconToggleItem(
                    title = stringResource(R.string.connect_to_essentials),
                    description = stringResource(R.string.connect_to_essentials_summary),
                    iconRes = R.drawable.essentials_icon,
                    isChecked = uiState.isEssentialsConnectionEnabled,
                    onCheckedChange = { enabled: Boolean ->
                        viewModel.setEssentialsConnectionEnabled(enabled)
                    }
                )
            } else {
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    headlineContent = { Text(stringResource(R.string.download_essentials)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.download_essentials_summary
                            )
                        )
                    },
                    trailingContent = {
                        Button(
                            onClick = {
                                HapticUtil.performClick(haptics)
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

        // Widget Section
        SettingsCategory(title = "Widget") {
            com.sameerasw.airsync.presentation.ui.components.sliders.ConfigSliderItem(
                title = "Widget Transparency",
                value = uiState.widgetTransparency,
                onValueChange = { viewModel.setWidgetTransparency(it) }
            )
        }

        // Connection Section
        SettingsCategory(title = "Connection") {
            DeviceInfoCard(
                deviceName = uiState.deviceNameInput,
                localIp = deviceInfo.localIp,
                onDeviceNameChange = { viewModel.updateDeviceName(it) }
            )

            ExpandNetworkingCard(context)
        }

        // Advanced / Developer Section
        AnimatedVisibility(
            visible = uiState.isDeveloperModeVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            SettingsCategory(title = "Advanced") {
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
                            versionName ?: "2.0.0",
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

        AboutSection(
            onAvatarLongClick = onToggleDeveloperMode
        )

        Spacer(modifier = Modifier.height(180.dp))
    }

    if (showAppSelectionSheet) {
        val apps by viewModel.notificationApps.collectAsState()
        AppSelectionSheet(
            onDismissRequest = { showAppSelectionSheet = false },
            apps = apps,
            onAppToggle = { pkg, enabled ->
                viewModel.toggleNotificationApp(context, pkg, enabled)
            },
            onSaveAll = { updatedList ->
                viewModel.saveAllNotificationApps(context, updatedList)
            },
            isLoading = apps.isEmpty()
        )
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

@Composable
fun SettingsCategory(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsCategoryTitle(title)
        RoundedCardContainer {
            content()
        }
    }
}
