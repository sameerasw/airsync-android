package com.sameerasw.airsync.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.domain.model.MacBattery
import com.sameerasw.airsync.domain.model.MacDeviceStatus
import com.sameerasw.airsync.domain.model.MacMusicInfo
import com.sameerasw.airsync.service.MacMediaPlayerService
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object MacDeviceStatusManager {
    private const val TAG = "MacDeviceStatusManager"

    private val _macDeviceStatus = MutableStateFlow<MacDeviceStatus?>(null)
    val macDeviceStatus: StateFlow<MacDeviceStatus?> = _macDeviceStatus.asStateFlow()

    private val _albumArt = MutableStateFlow<Bitmap?>(null)
    val albumArt: StateFlow<Bitmap?> = _albumArt.asStateFlow()

    fun updateStatus(
        context: Context,
        batteryLevel: Int,
        isCharging: Boolean,
        isPaired: Boolean,
        isPlaying: Boolean,
        title: String,
        artist: String,
        volume: Int,
        isMuted: Boolean,
        albumArt: String,
        likeStatus: String
    ) {
        try {
            val macBattery = MacBattery(level = batteryLevel, isCharging = isCharging)
            val macMusicInfo = MacMusicInfo(
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                volume = volume,
                isMuted = isMuted,
                albumArt = albumArt,
                likeStatus = likeStatus
            )

            val status = MacDeviceStatus(
                battery = macBattery,
                isPaired = isPaired,
                music = macMusicInfo
            )

            _macDeviceStatus.value = status

            // Decode album art if available
            val bitmap = decodeAlbumArt(albumArt)
            _albumArt.value = bitmap

            // Start/update or stop the Mac media player service based on media state and USER SETTING
            CoroutineScope(Dispatchers.IO).launch {
                val ds = DataStoreManager(context)
                val isMediaControlsEnabled = ds.getMacMediaControlsEnabled().first()
                val isConnected = WebSocketUtil.isConnected()

                if (isConnected && isMediaControlsEnabled && (title.isNotEmpty() || artist.isNotEmpty() || isPlaying)) {
                    MacMediaPlayerService.startMacMedia(context, title, artist, isPlaying, bitmap)
                    Log.d(TAG, "Started/Updated Mac media player service")
                } else {
                    MacMediaPlayerService.stopMacMedia(context)
                    if (!isMediaControlsEnabled) {
                        Log.d(TAG, "Stopped Mac media player service - controls explicitly disabled by user")
                    } else if (!isConnected) {
                        Log.d(TAG, "Stopped Mac media player service - device disconnected")
                    } else {
                        Log.d(TAG, "Stopped Mac media player service - no active media")
                    }
                }
            }

            Log.d(TAG, "Mac device status updated - Playing: $isPlaying, Title: $title, Artist: $artist")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Mac device status: ${e.message}")
        }
    }

    private fun decodeAlbumArt(base64String: String): Bitmap? {
        return try {
            if (base64String.isNotEmpty()) {
                val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding album art: ${e.message}")
            null
        }
    }

    fun cleanup() {
        try {
            _albumArt.value = null
            _macDeviceStatus.value = null
            Log.d(TAG, "Mac device status cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up: ${e.message}")
        }
    }
}
