package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onResetOnboarding: () -> Unit,
    // Icon Sync Parameters
    isIconSyncLoading: Boolean,
    iconSyncMessage: String,
    onManualSyncIcons: () -> Unit,
    onClearIconSyncMessage: () -> Unit,
    isConnected: Boolean
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
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

                    // Consolidated Icon Sync Section
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Icons",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onManualSyncIcons()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isConnected && !isIconSyncLoading
                    ) {
                        if (isIconSyncLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isIconSyncLoading) "Syncing Icons..." else "Sync App Icons")
                    }

                    AnimatedVisibility(
                        visible = iconSyncMessage.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraSmall,
                            colors = CardDefaults.cardColors(
                                containerColor = if (iconSyncMessage.contains("Successfully"))
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = iconSyncMessage,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (iconSyncMessage.contains("Successfully"))
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer
                                )
                                TextButton(onClick = {
                                    HapticUtil.performClick(haptics)
                                    onClearIconSyncMessage()
                                }) {
                                    Text("Dismiss", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    // Sentry section
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Crash Reporting",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            throw RuntimeException("Test Crash from Developer Options")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simulate Crash")
                    }
                }
            }
        }
    }
}