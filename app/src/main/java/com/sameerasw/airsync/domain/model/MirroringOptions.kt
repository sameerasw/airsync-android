package com.sameerasw.airsync.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MirroringOptions(
    val fps: Int = 30,  // 30 FPS for smooth mirroring
    val quality: Float = 0.65f,  // 65% JPEG quality - optimized for lower latency
    val maxWidth: Int = 960,  // 960p for better performance and lower latency
    val bitrateKbps: Int = 4000,  // Only used for H.264 fallback
    val useRawFrames: Boolean? = true,  // Use raw JPEG frames by default
    val enableAudio: Boolean = false  // Enable audio mirroring (requires Android 10+)
) : Parcelable
