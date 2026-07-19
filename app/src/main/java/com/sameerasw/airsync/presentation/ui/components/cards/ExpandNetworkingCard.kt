package com.sameerasw.airsync.presentation.ui.components.cards

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.launch

@Composable
fun ExpandNetworkingCard(
    context: Context,
    modifier: Modifier = Modifier
) {
    val ds = remember { DataStoreManager(context) }
    val scope = rememberCoroutineScope()
    val enabledFlow = ds.getExpandNetworkingEnabled()
    var enabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        enabledFlow.collect { value ->
            enabled = value
        }
    }

    IconToggleItem(
        modifier = modifier,
        iconRes = R.drawable.rounded_android_wifi_3_bar_24,
        title = "Expand networking",
        description = "Allow connecting to device in VPN (Tailscale)",
        isChecked = enabled,
        onCheckedChange = { value ->
            enabled = value
            scope.launch {
                ds.setExpandNetworkingEnabled(value)
            }
        }
    )
}

