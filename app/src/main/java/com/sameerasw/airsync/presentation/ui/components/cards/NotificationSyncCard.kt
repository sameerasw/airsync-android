package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
fun NotificationSyncCard(
    isNotificationEnabled: Boolean,
    isNotificationSyncEnabled: Boolean,
    onToggleSync: (Boolean) -> Unit,
    onGrantPermissions: () -> Unit
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Notification Sync", style = MaterialTheme.typography.titleMedium)
                }

                Switch(
                    checked = isNotificationSyncEnabled && isNotificationEnabled,
                    onCheckedChange = { enabled ->
                        if (isNotificationEnabled) {
                            if (enabled) HapticUtil.performToggleOn(haptics) else HapticUtil.performToggleOff(
                                haptics
                            )
                            onToggleSync(enabled)
                        } else {
                            HapticUtil.performClick(haptics)
                            onGrantPermissions()
                        }
                    },
                    enabled = isNotificationEnabled
                )
            }

            if (!isNotificationEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "❌ Notification access required",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

        }
    }
}