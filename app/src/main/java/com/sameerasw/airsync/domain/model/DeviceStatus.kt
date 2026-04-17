package com.sameerasw.airsync.domain.model

data class BatteryInfo(
    val level: Int,
    val isCharging: Boolean
)

data class AudioInfo(
    val isPlaying: Boolean,
    val title: String,
    val artist: String,
    val volume: Int,
    val isMuted: Boolean,
    val albumArt: String? = null,
    val likeStatus: String = "none",
    val durationMs: Long = -1L,
    val positionMs: Long = -1L,
    // True when the media session is in STATE_BUFFERING (position not advancing).
    val isBuffering: Boolean = false,
    // System.currentTimeMillis() at the moment positionMs was captured,
    // so the Mac can compensate for network transit time.
    val positionTimestampMs: Long = -1L
)

data class MediaInfo(
    val isPlaying: Boolean,
    val title: String,
    val artist: String,
    val albumArt: String? = null,
    val likeStatus: String = "none",
    val durationMs: Long = -1L,
    val positionMs: Long = -1L,
    val isBuffering: Boolean = false,
    val positionTimestampMs: Long = -1L
)