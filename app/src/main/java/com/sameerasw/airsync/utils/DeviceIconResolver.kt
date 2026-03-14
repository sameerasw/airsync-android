package com.sameerasw.airsync.utils

import android.content.Context
import androidx.annotation.DrawableRes
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.domain.model.ConnectedDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Resolves which icon to use for a given connected Mac device.
 * Rules (for now):
 * - MacBook Air/Pro -> use existing laptop icon
 * - iMac -> desktop/monitor icon
 * - Mac mini -> small box icon
 * - Mac Studio -> medium tower icon
 * - Mac Pro -> tall tower icon
 * - Fallback -> laptop icon
 *
 * We only check the top category name (MacBook Air, MacBook Pro, iMac, Mac mini, Mac Studio, Mac Pro)
 * and we match case-insensitively against deviceType/model/name.
 */
object DeviceIconResolver {

    @DrawableRes
    fun getIconRes(device: ConnectedDevice?): Int {
        return MacModelMapper.getIconRes(device)
    }

    @DrawableRes
    fun getTileIconRes(device: ConnectedDevice?): Int {
        return MacModelMapper.getTileIconRes(device)
    }

    @DrawableRes
    fun getIconResForName(name: String?, model: String?, deviceType: String?): Int {
        return MacModelMapper.getIconRes(name ?: "", model, deviceType)
    }

    /** Convenience for places without direct device: read last device once. */
    @DrawableRes
    fun getLastDeviceIconRes(context: Context): Int = runBlocking {
        return@runBlocking try {
            val ds = DataStoreManager(context)
            val last = ds.getLastConnectedDevice().first()
            getIconRes(last)
        } catch (_: Exception) {
            R.drawable.macbook_air_gen2
        }
    }
}
