package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.presentation.ui.components.RotatingAppIcon
import com.sameerasw.airsync.presentation.ui.components.SlowlyRotatingAppIcon
import com.sameerasw.airsync.utils.DevicePreviewResolver
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
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Determine gradient color
    val gradientColor = when {
        isConnected -> MaterialTheme.colorScheme.primary
        isConnecting -> Color(0xFFFFC107) // Yellow
        else -> Color(0xFFF44336) // Red
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (isConnected) 160.dp else 50.dp)
            .animateContentSize(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
//                .background(
//                    brush = Brush.linearGradient(
//                        colors = listOf(
//                            gradientColor.copy(alpha = 0.3f),
//                            Color.Transparent
//                        ),
//                        start = Offset(0f, 1f),
//                        end = Offset.Infinite
//                    )
//                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1) Device image at the top (only when connected)
            if (isConnected) {
                val previewRes = DevicePreviewResolver.getPreviewRes(connectedDevice)
                Image(
                    painter = painterResource(id = previewRes),
                    contentDescription = "Connected Mac preview",
                    modifier = Modifier
                        .fillMaxWidth(0.75f),
                    contentScale = ContentScale.Fit,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
            }

            // 2) Device info block (when connected)
            if (isConnected && connectedDevice != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${connectedDevice.name}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (connectedDevice.isPlus)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.padding(start = 16.dp)
                    ) {
                        Text(
                            text = if (connectedDevice.isPlus) "PLUS" else "FREE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connectedDevice.isPlus)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.isRelayConnection) {
                        // When connected via relay only, show AirBridge indicator instead of LAN IPs
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.animateContentSize()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = com.sameerasw.airsync.R.drawable.rounded_web_24),
                                    contentDescription = "AirBridge",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = "via AirBridge relay",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    } else {
                        val ips =
                            uiState.ipAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }
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

            // 3) Connection status row last
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SlowlyRotatingAppIcon(
                            modifier = Modifier
                                .size(54.dp)
                        )
                    }
//                    Icon(
//                        painter = painterResource(id = com.sameerasw.airsync.R.drawable.rounded_devices_24),
//                        contentDescription = "Connected",
//                        modifier = Modifier.padding(end = 8.dp),
//                        tint = MaterialTheme.colorScheme.primary
//                    )

                } else if (!isConnecting) {
                    Icon(
                        painter = painterResource(id = com.sameerasw.airsync.R.drawable.rounded_devices_off_24),
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

                // Connection transport indicator (WiFi vs Relay)
                if (isConnected) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (uiState.isRelayConnection)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (uiState.isRelayConnection)
                                        com.sameerasw.airsync.R.drawable.rounded_web_24
                                    else
                                        com.sameerasw.airsync.R.drawable.rounded_android_wifi_3_bar_24
                                ),
                                contentDescription = if (uiState.isRelayConnection) "Relay connection" else "Local connection",
                                modifier = Modifier.size(16.dp),
                                tint = if (uiState.isRelayConnection)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = if (uiState.isRelayConnection) "Relay" else "Local",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isRelayConnection)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                if (isConnected) {

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onDisconnect()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceBright,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier
                            .height(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = com.sameerasw.airsync.R.drawable.rounded_devices_off_24),
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


}