package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R

@Composable
fun ClipboardFeaturesCard(
    isClipboardSyncEnabled: Boolean,
    onToggleClipboardSync: (Boolean) -> Unit,
    // Continue Browsing props
    isContinueBrowsingEnabled: Boolean,
    onToggleContinueBrowsing: (Boolean) -> Unit,
    // Control the UI enabled state and subtitle for Continue Browsing
    isContinueBrowsingToggleEnabled: Boolean,
    continueBrowsingSubtitle: String,
    // Keep previous link props
    isKeepPreviousLinkEnabled: Boolean,
    onToggleKeepPreviousLink: (Boolean) -> Unit,
    // Cellular Network Sync props
    isCellularSyncEnabled: Boolean,
    onToggleCellularSync: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        IconToggleItem(
            iconRes = R.drawable.ic_clipboard_24,
            title = "Clipboard Sync",
            description = "Update Android clipboard automatically",
            isChecked = isClipboardSyncEnabled,
            onCheckedChange = onToggleClipboardSync
        )
        IconToggleItem(
            iconRes = R.drawable.rounded_network_node_24,
            title = "Cellular Network Sync",
            description = "Sync 5G/4G status to Mac",
            isChecked = isCellularSyncEnabled,
            onCheckedChange = onToggleCellularSync
        )
        IconToggleItem(
            iconRes = R.drawable.outline_open_in_browser_24,
            title = "Continue browsing",
            description = continueBrowsingSubtitle,
            isChecked = isContinueBrowsingEnabled,
            onCheckedChange = onToggleContinueBrowsing,
            enabled = isContinueBrowsingToggleEnabled
        )
        IconToggleItem(
            iconRes = R.drawable.rounded_history_24,
            title = "Keep previous link",
            description = "Without replacing",
            isChecked = isKeepPreviousLinkEnabled,
            onCheckedChange = onToggleKeepPreviousLink,
            enabled = isContinueBrowsingToggleEnabled
        )
    }
}