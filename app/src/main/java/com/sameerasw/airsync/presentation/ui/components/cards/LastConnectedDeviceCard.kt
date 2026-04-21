package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.utils.DevicePreviewResolver
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun LastConnectedDeviceCard(
    device: ConnectedDevice,
    isAutoReconnectEnabled: Boolean,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onQuickConnect: () -> Unit,
    onConnectWithRelay: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                "Last Connected Device",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val previewRes = DevicePreviewResolver.getPreviewRes(device)
                Image(
                    painter = painterResource(id = previewRes),
                    contentDescription = "Connected Mac preview",
                    modifier = Modifier
                        .fillMaxWidth(0.45f),
                    contentScale = ContentScale.Fit,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column{

                    Text(
                        "${device.name}",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    val lastConnectedTime = remember(device.lastConnected) {
                        val currentTime = System.currentTimeMillis()
                        val diffMinutes = (currentTime - device.lastConnected) / (1000 * 60)
                        when {
                            diffMinutes < 1 -> "Just now"
                            diffMinutes < 60 -> "${diffMinutes}m ago"
                            diffMinutes < 1440 -> "${diffMinutes / 60}h ago"
                            else -> "${diffMinutes / 1440}d ago"
                        }
                    }
                    Text(
                        "Last seen $lastConnectedTime",
                        style = MaterialTheme.typography.bodyMedium
                    )

                }

                // Display status badge - PLUS or FREE
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (device.isPlus)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = if (device.isPlus) "PLUS" else "FREE",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (device.isPlus)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

//            device.deviceType?.let { type ->
//                Text("Type: $type", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
//            }



            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onQuickConnect()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .requiredHeight(65.dp),
                ) {
                    Icon(
                        painter = painterResource(id = com.sameerasw.airsync.R.drawable.rounded_sync_desktop_24),
                        contentDescription = "Quick connect",
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Quick Connect")
                }

                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onConnectWithRelay()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .requiredHeight(65.dp),
                ) {
                    Text("Connect with Relay")
                }
            }

            // Auto-reconnect toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto reconnect", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = isAutoReconnectEnabled, onCheckedChange = { enabled ->
                    if (enabled) HapticUtil.performToggleOn(haptics) else HapticUtil.performToggleOff(
                        haptics
                    )
                    onToggleAutoReconnect(enabled)
                })
            }

        }
    }
}