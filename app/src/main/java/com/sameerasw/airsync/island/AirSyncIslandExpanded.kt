package com.sameerasw.airsync.island

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.MacModelMapper
import com.sameerasw.island.sdk.HostInfo
import com.sameerasw.island.sdk.compose.islandSnapshot

private val ButtonBackground = Color.White.copy(alpha = 0.12f)
private val DangerTint = Color(0xFFFF8A80)

@Composable
fun AirSyncIslandExpanded(
    state: AirSyncIslandState,
    host: HostInfo?,
    onOpenApp: () -> Unit,
    onLock: () -> Unit,
    onScreensaver: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onHeight: (Int) -> Unit,
    onSnapshot: (Bitmap) -> Unit,
) {
    val density = LocalDensity.current
    val clearTop = with(density) { (host?.cameraClearancePx ?: 0).toDp() }
    val compactHeight = with(density) { (host?.compactHeightPx ?: 0).toDp() }
    val cameraSlot = with(density) { (host?.cameraSlotWidthPx ?: 0).toDp() }
    val outset = with(density) { (host?.outsetPx ?: 0).toDp() }
    val sidePadding = with(density) { (host?.expandedPaddingPx ?: 0).toDp() } + outset + 8.dp
    val corner = with(density) { (host?.cornerPx ?: 0).toDp() }

    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .onSizeChanged { onHeight(it.height) }
            .islandSnapshot(onSnapshot = onSnapshot)
            .clip(RoundedCornerShape(corner))
            .clickable(onClick = onOpenApp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height((clearTop - compactHeight).coerceAtLeast(0.dp)))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(compactHeight.coerceAtLeast(32.dp))
                    .padding(horizontal = sidePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.name.ifBlank { stringResource(R.string.island_default_device_name) },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(cameraSlot))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.connected && state.batteryLevel in 0..100) {
                        Text(
                            text = (if (state.isCharging) "⚡ " else "") + "${state.batteryLevel}%",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
            Image(
                painter = painterResource(MacModelMapper.getPreviewRes(state.name, state.model, state.deviceType)),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp)
                    .height(88.dp),
            )
            Text(
                text = stringResource(if (state.connected) R.string.island_connected else R.string.disconnected),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sidePadding)
                    .padding(top = 14.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.connected) {
                    ActionButton(R.drawable.rounded_lock_24, stringResource(R.string.action_lock_screen), Color.White, onLock, Modifier.weight(1f))
                    ActionButton(
                        R.drawable.rounded_screenshot_monitor_24,
                        stringResource(R.string.action_screensaver),
                        Color.White,
                        onScreensaver,
                        Modifier.weight(1f),
                    )
                    ActionButton(R.drawable.rounded_link_off_24, stringResource(R.string.disconnect), DangerTint, onDisconnect, Modifier.weight(1f))
                } else {
                    ActionButton(R.drawable.rounded_sync_desktop_24, stringResource(R.string.island_reconnect), Color.White, onReconnect, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: Int, label: String, tint: Color, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ButtonBackground)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(text = label, color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
