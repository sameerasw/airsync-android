package com.sameerasw.airsync.island

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.MacModelMapper
import com.sameerasw.island.sdk.IslandAction
import com.sameerasw.island.sdk.IslandActionType
import com.sameerasw.island.sdk.IslandCellPayload
import com.sameerasw.island.sdk.IslandInteractionsPayload
import com.sameerasw.island.sdk.IslandItemPayload
import com.sameerasw.island.sdk.IslandLinePayload
import com.sameerasw.island.sdk.IslandPayload
import com.sameerasw.island.sdk.IslandPlacement
import com.sameerasw.island.sdk.IslandTone

class AirSyncIslandContent(val payload: IslandPayload, val assets: Map<String, Bitmap>)

object AirSyncIslandPayload {
    const val ITEM_KEY = "mac"

    private const val PRIORITY = 58
    private const val ICON_ID = "device"
    private const val ICON_SIZE_PX = 96

    fun build(context: Context, state: AirSyncIslandState): AirSyncIslandContent {
        if (!state.enabled || !state.connected) return AirSyncIslandContent(IslandPayload(), emptyMap())

        val assets = HashMap<String, Bitmap>()
        val iconRes = MacModelMapper.getTileIconRes(state.name, state.model, state.deviceType)
        bitmap(context, iconRes)?.let { assets[ICON_ID] = it }

        val hasBattery = state.batteryLevel in 0..100
        val cells = buildList {
            add(IslandCellPayload(key = "$ITEM_KEY.icon", iconId = ICON_ID.takeIf { it in assets }, tone = IslandTone.ACCENT))
            if (state.showBattery && hasBattery) {
                add(IslandCellPayload(key = "$ITEM_KEY.battery", text = "${state.batteryLevel}%", tone = IslandTone.NONE))
            }
        }
        val lineEnd = when {
            !hasBattery -> context.getString(R.string.island_connected)
            state.isCharging -> context.getString(R.string.island_battery_charging, state.batteryLevel)
            else -> "${state.batteryLevel}%"
        }
        val item = IslandItemPayload(
            key = ITEM_KEY,
            priority = PRIORITY,
            placement = IslandPlacement.DYNAMIC,
            compact = cells,
            line = IslandLinePayload(
                start = state.name.ifBlank { context.getString(R.string.island_default_device_name) },
                end = lineEnd,
                iconId = ICON_ID.takeIf { it in assets },
                tone = IslandTone.ACCENT,
            ),
            interactions = IslandInteractionsPayload(
                onLongPress = IslandAction(IslandActionType.OPEN_APP),
            ),
        )
        return AirSyncIslandContent(IslandPayload(items = listOf(item)), assets)
    }

    private fun bitmap(context: Context, @DrawableRes res: Int): Bitmap? =
        ContextCompat.getDrawable(context, res)?.toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
}
