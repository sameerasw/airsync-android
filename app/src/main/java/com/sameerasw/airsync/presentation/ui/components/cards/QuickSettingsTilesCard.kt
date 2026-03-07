package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.QuickSettingsUtil

@Composable
fun QuickSettingsTilesCard(
    isConnectionTileAdded: Boolean,
    isClipboardTileAdded: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TileItem(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.label_connection_tile),
                iconRes = R.drawable.ic_laptop_24,
                isAdded = isConnectionTileAdded,
                onClick = { context ->
                    QuickSettingsUtil.requestAddQuickSettingsTile(context)
                }
            )

            TileItem(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.label_clipboard_tile),
                iconRes = R.drawable.ic_clipboard_24,
                isAdded = isClipboardTileAdded,
                onClick = { context ->
                    QuickSettingsUtil.requestAddClipboardTile(context)
                }
            )
        }
    }
}

@Composable
private fun TileItem(
    modifier: Modifier = Modifier,
    title: String,
    iconRes: Int,
    isAdded: Boolean,
    onClick: (android.content.Context) -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .alpha(if (isAdded) 0.5f else 1f)
            .clip(MaterialTheme.shapes.medium)
            .background(if (isAdded) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primary)
            .clickable(enabled = !isAdded) {
                HapticUtil.performClick(haptics)
                onClick(context)
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (isAdded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary
        )

        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (isAdded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = if (isAdded) stringResource(R.string.status_added) else stringResource(R.string.status_add),
                style = MaterialTheme.typography.bodySmall,
                color = if (isAdded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
        }
    }
}
