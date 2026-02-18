package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.QuickSettingsUtil

@Composable
fun QuickSettingsTipCard(
    isQSTileAdded: Boolean = false
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.dp),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add Quick Settings Tile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "For quick connection and scanner access with long press.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                OutlinedButton(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        QuickSettingsUtil.requestAddQuickSettingsTile(context)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = !isQSTileAdded
                ) {
                    Text(if (isQSTileAdded) "Added" else "Add Tile")
                }
            }
        }
    }
}

