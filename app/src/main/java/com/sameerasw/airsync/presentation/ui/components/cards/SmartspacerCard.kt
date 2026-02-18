package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
fun SmartspacerCard(
    isSmartspacerShowWhenDisconnected: Boolean,
    onToggleSmartspacerShowWhenDisconnected: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Smartspacer", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Show Smartspacer when disconnected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isSmartspacerShowWhenDisconnected,
                onCheckedChange = { enabled ->
                    if (enabled) HapticUtil.performToggleOn(haptics) else HapticUtil.performToggleOff(
                        haptics
                    )
                    onToggleSmartspacerShowWhenDisconnected(enabled)
                }
            )
        }
    }
}

