package com.sameerasw.airsync.presentation.ui.components.cards

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.activities.PermissionsActivity
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun PermissionsCard(
    missingPermissionsCount: Int = 0
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                HapticUtil.performClick(haptics)
                val intent = Intent(context, PermissionsActivity::class.java)
                context.startActivity(intent)
            },
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = if (missingPermissionsCount > 0)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (missingPermissionsCount > 0)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (missingPermissionsCount > 0)
                        "$missingPermissionsCount missing"
                    else
                        "All permissions granted",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (missingPermissionsCount > 0)
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Icon(
                painter = painterResource(id = R.drawable.rounded_keyboard_arrow_right_24),
                contentDescription = "Open permissions",
                modifier = Modifier.size(24.dp),
                tint = if (missingPermissionsCount > 0)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

