package com.sameerasw.airsync.utils

import androidx.annotation.DrawableRes
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.ConnectedDevice

/**
 * Maps a connected Mac device to a large preview image under the main UI.
 * Uses the same classification rules we use for icons:
 * - iMac -> imac.png
 * - Mac mini -> ic_device_macmini.png
 * - Mac Studio -> ic_device_macstudio.png
 * - Mac Pro -> ic_device_macpro.png
 * - MacBook (Air/Pro) -> ic_device_macbook.png
 * - Fallback -> ic_device_macbook.png
 */
object DevicePreviewResolver {

    @DrawableRes
    fun getPreviewRes(device: ConnectedDevice?): Int {
        return MacModelMapper.getPreviewRes(device)
    }
}
