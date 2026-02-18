package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun ManualConnectionCard(
    isConnected: Boolean,
    lastConnected: Boolean,
    uiState: UiState,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPcNameChange: (String) -> Unit,
    onIsPlusChange: (Boolean) -> Unit,
    onSymmetricKeyChange: (String) -> Unit,
    onConnect: () -> Unit,
    onQrScanClick: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    HapticUtil.performLightTick(haptics)
                    expanded = !expanded
                }
            ) {
                Text("Manual Connection", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Icon(
                    painter = painterResource(
                        if (expanded) R.drawable.outline_expand_circle_up_24 else R.drawable.outline_expand_circle_down_24
                    ),
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    // QR Scanner button
                    if (onQrScanClick != null) {
                        Button(
                            onClick = {
                                HapticUtil.performClick(haptics)
                                onQrScanClick()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.rounded_qr_code_scanner_24),
                                contentDescription = "Scan QR Code",
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(end = 8.dp)
                            )
                            Text("Scan QR Code")
                        }
                    }
                    OutlinedTextField(
                        value = uiState.ipAddress,
                        onValueChange = onIpChange,
                        label = { Text("IP Address") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = uiState.port,
                        onValueChange = onPortChange,
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = uiState.manualPcName,
                        onValueChange = onPcNameChange,
                        label = { Text("PC Name (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                    OutlinedTextField(
                        value = uiState.symmetricKey ?: "",
                        onValueChange = onSymmetricKeyChange,
                        label = { Text("Encryption Key") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AirSync+")
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = uiState.manualIsPlus,
                            onCheckedChange = { enabled ->
                                if (enabled) HapticUtil.performToggleOn(haptics) else HapticUtil.performToggleOff(
                                    haptics
                                )
                                onIsPlusChange(enabled)
                            }
                        )
                    }
                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onConnect()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}
