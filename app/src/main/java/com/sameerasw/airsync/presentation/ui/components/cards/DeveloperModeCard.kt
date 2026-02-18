package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun DeveloperModeCard(
    isDeveloperMode: Boolean,
    onToggleDeveloperMode: (Boolean) -> Unit,
    isLoading: Boolean,
    onSendDeviceInfo: () -> Unit,
    onSendNotification: () -> Unit,
    onSendDeviceStatus: () -> Unit,
    onExportData: () -> Unit,
    onImportData: () -> Unit,
    onResetOnboarding: () -> Unit
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Developer Mode", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = isDeveloperMode,
                    onCheckedChange = { enabled ->
                        if (enabled) HapticUtil.performToggleOn(haptics) else HapticUtil.performToggleOff(
                            haptics
                        )
                        onToggleDeveloperMode(enabled)
                    }
                )
            }

            if (isDeveloperMode) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Test Functions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onSendDeviceInfo()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Text("Send Device Info")
                    }

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onSendNotification()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Text("Send Test Notification")
                    }

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onSendDeviceStatus()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Text("Send Device Status")
                    }

                    // New: export/import split buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                HapticUtil.performClick(haptics)
                                onExportData()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading
                        ) {
                            Text("Export Data")
                        }

                        Button(
                            onClick = {
                                HapticUtil.performClick(haptics)
                                onImportData()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading
                        ) {
                            Text("Import Data")
                        }
                    }

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onResetOnboarding()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Text("Reset Onboarding")
                    }

                }

                // Removed preview/response display to avoid crashes from large/encoded payloads
            }
        }
    }
}