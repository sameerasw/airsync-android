package com.sameerasw.airsync.presentation.ui.components.cards

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.launch

@Composable
fun ExpandNetworkingCard(context: Context) {
    val ds = remember { DataStoreManager(context) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val enabledFlow = ds.getExpandNetworkingEnabled()
    var enabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        enabledFlow.collect { value ->
            enabled = value
        }
    }

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
                Text("Expand networking", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Allow connecting to device outside the local network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    if (it) HapticUtil.performToggleOn(haptics) else HapticUtil.performToggleOff(haptics)
                    enabled = it
                    scope.launch {
                        ds.setExpandNetworkingEnabled(it)
                    }
                }
            )
        }
    }
}
