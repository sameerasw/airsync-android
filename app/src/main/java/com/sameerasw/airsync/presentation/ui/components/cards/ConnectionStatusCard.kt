package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.presentation.ui.components.AirSyncLoadingAnimation
import com.sameerasw.airsync.presentation.ui.components.MacDevicePreview
import com.sameerasw.airsync.presentation.ui.components.sheets.ConnectionSettingsBottomSheet
import com.sameerasw.airsync.utils.HapticUtil

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    onDisconnect: () -> Unit,
    connectedDevice: ConnectedDevice? = null,
    lastConnected: Boolean,
    uiState: UiState,
    isAutoReconnectEnabled: Boolean = false,
    onToggleAutoReconnect: ((Boolean) -> Unit)? = null,
    onQuickConnect: (() -> Unit)? = null,
    isPageVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var showBottomSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraSmall,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceBright
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 160.dp)
                    .animateContentSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MacDevicePreview(
                    connectedDevice = connectedDevice,
                    isConnected = isConnected,
                    isPageVisible = isPageVisible,
                    modifier = Modifier.fillMaxWidth()
                )

                if (connectedDevice != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                connectedDevice.name,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (!isConnected) {
                                val lastConnectedTime = remember(connectedDevice.lastConnected) {
                                    val currentTime = System.currentTimeMillis()
                                    val diffMinutes = (currentTime - connectedDevice.lastConnected) / (1000 * 60)
                                    when {
                                        diffMinutes < 1 -> "Just now"
                                        diffMinutes < 60 -> "${diffMinutes}m ago"
                                        diffMinutes < 1440 -> "${diffMinutes / 60}h ago"
                                        else -> "${diffMinutes / 1440}d ago"
                                    }
                                }
                                Text(
                                    "Last seen $lastConnectedTime",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (connectedDevice.isPlus)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        ) {
                            Text(
                                text = if (connectedDevice.isPlus) "PLUS" else "FREE",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (connectedDevice.isPlus)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isConnected) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val ips = uiState.ipAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            ips.forEach { ip ->
                                val isActive = ip == uiState.activeIp
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.animateContentSize()
                                ) {
                                    Text(
                                        text = "$ip:${connectedDevice.port}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isConnected && connectedDevice != null && onQuickConnect != null) {
                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onQuickConnect()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.rounded_sync_desktop_24),
                            contentDescription = "Quick connect",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Quick Connect")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (isConnected) 0.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusText = when {
                        isConnecting -> "Connecting..."
                        isConnected -> "Syncing"
                        else -> "Disconnected"
                    }

                    if (isConnecting) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { LoadingIndicator() }
                    }

                    if (isConnected) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            AirSyncLoadingAnimation(
                                isPlus = connectedDevice?.isPlus == true,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    } else if (!isConnecting) {
                        Icon(
                            painter = painterResource(id = R.drawable.rounded_devices_off_24),
                            contentDescription = "Disconnected",
                            modifier = Modifier.padding(end = 8.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )

                    if (isConnected) {
                        Button(
                            onClick = {
                                HapticUtil.performClick(haptics)
                                onDisconnect()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rounded_devices_off_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = "Disconnect",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        if (!isConnected && onToggleAutoReconnect != null) {
            IconToggleItem(
                iconRes = R.drawable.rounded_compare_arrows_24,
                title = stringResource(R.string.bluetooth_settings_card_title),
                description = stringResource(R.string.bluetooth_settings_card_desc),
                showToggle = false,
                onClick = {
                    HapticUtil.performClick(haptics)
                    showBottomSheet = true
                }
            )

            if (showBottomSheet) {
                ConnectionSettingsBottomSheet(
                    isAutoReconnectEnabled = isAutoReconnectEnabled,
                    onToggleAutoReconnect = onToggleAutoReconnect,
                    onDismissRequest = { showBottomSheet = false }
                )
            }
        }
    }
}