package com.sameerasw.airsync.presentation.ui.components.cards

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.AirBridgeClient
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.launch

// Card for AirBridge relay settings and connection status
@Composable
fun AirBridgeCard(context: Context) {
    val ds = remember { DataStoreManager(context) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var enabled by remember { mutableStateOf(false) }
    var relayUrl by remember { mutableStateOf("") }
    var pairingId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }

    val connectionState by AirBridgeClient.connectionState.collectAsState()
    val peerReallyActive by AirBridgeClient.peerReallyActive.collectAsState()

    LaunchedEffect(Unit) {
        launch { ds.getAirBridgeEnabled().collect { enabled = it } }
        launch { ds.getAirBridgeRelayUrl().collect { relayUrl = it } }
        launch { ds.getAirBridgePairingId().collect { pairingId = it } }
        launch { ds.getAirBridgeSecret().collect { secret = it } }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AirBridge Relay", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Connect via relay server when not on the same network",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { newValue ->
                        enabled = newValue
                        if (newValue) HapticUtil.performToggleOn(haptics)
                        else HapticUtil.performToggleOff(haptics)
                        scope.launch {
                            ds.setAirBridgeEnabled(newValue)
                            if (newValue) {
                                AirBridgeClient.connect(context)
                            } else {
                                AirBridgeClient.disconnect()
                            }
                        }
                    }
                )
            }

            // Expanded settings
            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Connection status
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (connectionState) {
                                        AirBridgeClient.State.DISCONNECTED -> Color.Gray
                                        AirBridgeClient.State.CONNECTING -> Color(0xFFFFA000)
                                        AirBridgeClient.State.REGISTERING -> Color(0xFFFFA000)
                                        AirBridgeClient.State.WAITING_FOR_PEER -> Color(0xFFFFD600)
                                        AirBridgeClient.State.RELAY_ACTIVE -> Color(0xFF4CAF50)
                                        AirBridgeClient.State.FAILED -> Color.Red
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (connectionState) {
                                AirBridgeClient.State.DISCONNECTED -> "Disconnected"
                                AirBridgeClient.State.CONNECTING -> "Connecting..."
                                AirBridgeClient.State.REGISTERING -> "Registering..."
                                AirBridgeClient.State.WAITING_FOR_PEER -> "Waiting for Mac..."
                                AirBridgeClient.State.RELAY_ACTIVE -> "Relay Active"
                                AirBridgeClient.State.FAILED -> "Connection Failed"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    if (connectionState == AirBridgeClient.State.RELAY_ACTIVE && !peerReallyActive) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF9800))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Peer offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9800)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Relay URL
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = { relayUrl = it },
                        label = { Text("Relay Server URL") },
                        placeholder = { Text("airbridge.yourdomain.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    var credentialsVisible by remember { mutableStateOf(false) }

                    // Pairing ID (paste from Mac)
                    OutlinedTextField(
                        value = pairingId,
                        onValueChange = { pairingId = it },
                        label = { Text("Pairing ID") },
                        placeholder = { Text("Your Pairing ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (credentialsVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            androidx.compose.material3.IconButton(onClick = { credentialsVisible = !credentialsVisible }) {
                                Icon(
                                    imageVector = if (credentialsVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (credentialsVisible) "Hide credentials" else "Show credentials"
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Secret (paste from Mac)
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("Secret") },
                        placeholder = { Text("Your Secret") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (credentialsVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            androidx.compose.material3.IconButton(onClick = { credentialsVisible = !credentialsVisible }) {
                                Icon(
                                    imageVector = if (credentialsVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (credentialsVisible) "Hide credentials" else "Show credentials"
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Save & Reconnect
                    Button(
                        onClick = {
                            scope.launch {
                                ds.setAirBridgeRelayUrl(relayUrl)
                                ds.setAirBridgePairingId(pairingId)
                                ds.setAirBridgeSecret(secret)
                                AirBridgeClient.disconnect()
                                kotlinx.coroutines.delay(500)
                                AirBridgeClient.connect(context)
                                Toast.makeText(context, "Settings saved, reconnecting...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save & Reconnect")
                    }
                }
            }
        }
    }
}
