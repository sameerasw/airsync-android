package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.utils.DevicePreviewResolver
import com.sameerasw.airsync.utils.HapticUtil

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
        isConnected -> Color(0xFF4CAF50) // Green
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
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            gradientColor.copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        start = Offset(0f, 1f),
                        end = Offset.Infinite
                    )
                )
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
                    contentScale = ContentScale.Fit
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
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${connectedDevice.ipAddress}:${connectedDevice.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    isConnecting -> "Connecting..."
                    isConnected -> "Syncing"
                    else -> "Disconnected"
                }

                if (isConnecting) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp)) }
                }

                if (isConnected) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp)) }
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

                if (isConnected) {
                    OutlinedButton(onClick = {
                        HapticUtil.performClick(haptics)
                        onDisconnect()
                    }) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }


}