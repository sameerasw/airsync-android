package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.res.stringResource
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.presentation.ui.components.AirSyncLoadingAnimation
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
    isPaused: Boolean = false,
    onTogglePause: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (isConnected) 160.dp else 50.dp)
                .animateContentSize()
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
                    modifier = Modifier.fillMaxWidth(0.75f),
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
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )

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

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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

            AnimatedVisibility(
                visible = isPaused,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                ) {
                    AirSyncLoadingAnimation(
                        isPlus = connectedDevice?.isPlus == true,
                        modifier = Modifier.size(110.dp)
                    )
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
                    isPaused -> stringResource(R.string.paused)
                    isConnecting -> "Connecting..."
                    isConnected -> "Syncing"
                    else -> stringResource(R.string.disconnected)
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
                        painter = painterResource(id = if (isPaused) R.drawable.rounded_pause_24 else R.drawable.rounded_devices_off_24),
                        contentDescription = statusText,
                        modifier = Modifier.padding(end = 8.dp),
                        tint = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
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
                } else if (!isConnecting && onTogglePause != null) {
                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onTogglePause()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (isPaused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = if (isPaused) R.drawable.rounded_play_arrow_24 else R.drawable.rounded_pause_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = if (isPaused) stringResource(R.string.resume) else stringResource(R.string.pause),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}