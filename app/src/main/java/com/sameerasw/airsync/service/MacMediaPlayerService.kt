package com.sameerasw.airsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.WebSocketMessageHandler

class MacMediaPlayerService : Service() {
    companion object {
        const val ACTION_START_MAC_MEDIA = "START_MAC_MEDIA"
        const val ACTION_STOP_MAC_MEDIA = "STOP_MAC_MEDIA"
        const val ACTION_UPDATE_MAC_MEDIA = "UPDATE_MAC_MEDIA"

        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_ALBUM_ART = "album_art"

        private const val TAG = "MacMediaPlayerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mac_media_channel"

        private var serviceInstance: MacMediaPlayerService? = null

        fun startMacMedia(context: android.content.Context, title: String, artist: String, isPlaying: Boolean, albumArt: Bitmap?) {
            val intent = Intent(context, MacMediaPlayerService::class.java).apply {
                action = ACTION_START_MAC_MEDIA
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                // Note: Bitmap cannot be passed via Intent, we'll handle it separately
            }
            context.startForegroundService(intent)
            serviceInstance?.updateAlbumArt(albumArt)
        }

        fun updateMacMedia(context: android.content.Context, title: String, artist: String, isPlaying: Boolean, albumArt: Bitmap?) {
            val intent = Intent(context, MacMediaPlayerService::class.java).apply {
                action = ACTION_UPDATE_MAC_MEDIA
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            context.startService(intent)
            serviceInstance?.updateAlbumArt(albumArt)
        }

        fun stopMacMedia(context: android.content.Context) {
            val intent = Intent(context, MacMediaPlayerService::class.java).apply {
                action = ACTION_STOP_MAC_MEDIA
            }
            context.startService(intent)
        }
    }

    private var mediaSession: MediaSessionCompat? = null
    private var currentAlbumArt: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        createNotificationChannel()
        Log.d(TAG, "MacMediaPlayerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MAC_MEDIA -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                startMacMediaSession(title, artist, isPlaying)
            }
            ACTION_UPDATE_MAC_MEDIA -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                updateMacMediaSession(title, artist, isPlaying)
            }
            ACTION_STOP_MAC_MEDIA -> {
                stopMacMediaSession()
            }
            // Handle media control actions from notification buttons
            "MAC_MEDIA_play" -> {
                sendMacMediaControl("play")
                updatePlaybackState(true)
            }
            "MAC_MEDIA_pause" -> {
                sendMacMediaControl("pause")
                updatePlaybackState(false)
            }
            "MAC_MEDIA_next" -> {
                sendMacMediaControl("next")
            }
            "MAC_MEDIA_previous" -> {
                sendMacMediaControl("previous")
            }
            "MAC_MEDIA_stop" -> {
                sendMacMediaControl("stop")
                stopMacMediaSession()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mac Media Player",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mac media playback controls"
            setShowBadge(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startMacMediaSession(title: String, artist: String, isPlaying: Boolean) {
        try {
            if (mediaSession == null) {
                mediaSession = MediaSessionCompat(this, "MacMediaPlayer").apply {
                    setFlags(
                        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                    )

                    setCallback(object : MediaSessionCompat.Callback() {
                        override fun onPlay() {
                            sendMacMediaControl("play")
                            updatePlaybackState(true)
                        }

                        override fun onPause() {
                            sendMacMediaControl("pause")
                            updatePlaybackState(false)
                        }

                        override fun onSkipToNext() {
                            sendMacMediaControl("next")
                        }

                        override fun onSkipToPrevious() {
                            sendMacMediaControl("previous")
                        }

                        override fun onStop() {
                            sendMacMediaControl("stop")
                            stopMacMediaSession()
                        }
                    })
                }
            }

            updateMediaMetadata(title, artist)
            updatePlaybackState(isPlaying)
            mediaSession?.isActive = true

            val notification = createMediaNotification(title, artist, isPlaying)
            startForeground(NOTIFICATION_ID, notification)

            Log.d(TAG, "Mac media session started as foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Mac media session: ${e.message}")
        }
    }

    private fun updateMacMediaSession(title: String, artist: String, isPlaying: Boolean) {
        try {
            updateMediaMetadata(title, artist)
            updatePlaybackState(isPlaying)

            val notification = createMediaNotification(title, artist, isPlaying)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)

            Log.d(TAG, "Mac media session updated")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Mac media session: ${e.message}")
        }
    }

    private fun updateMediaMetadata(title: String, artist: String) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Playing on Mac")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 180000)

        currentAlbumArt?.let { bitmap ->
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
        }

        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, 30000L, if (isPlaying) 1.0f else 0.0f)
                .setActions(actions)
                .build()
        )
    }

    private fun createMediaNotification(title: String, artist: String, isPlaying: Boolean): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Create media control actions
        val prevAction = NotificationCompat.Action(
            R.drawable.outline_skip_previous_24,
            "Previous",
            createMediaActionPendingIntent("previous")
        )

        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) R.drawable.outline_pause_24 else R.drawable.outline_play_arrow_24,
            if (isPlaying) "Pause" else "Play",
            createMediaActionPendingIntent(if (isPlaying) "pause" else "play")
        )

        val nextAction = NotificationCompat.Action(
            R.drawable.outline_skip_next_24,
            "Next",
            createMediaActionPendingIntent("next")
        )

        val dynamicIcon = com.sameerasw.airsync.utils.DeviceIconResolver.getLastDeviceIconRes(this)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(dynamicIcon)
            .setContentTitle(title.ifEmpty { "Mac Media Player" })
            .setContentText(artist.ifEmpty { "Playing on Mac" })
            .setContentIntent(activityPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)

        currentAlbumArt?.let { bitmap ->
            builder.setLargeIcon(bitmap)
        }

        // This is the key: MediaStyle with session token makes it show in Quick Settings
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2) // Show all three actions

        builder.setStyle(mediaStyle)

        return builder.build()
    }

    private fun createMediaActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MacMediaPlayerService::class.java).apply {
            this.action = "MAC_MEDIA_$action"
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopMacMediaSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            stopForeground(true)
            stopSelf()
            Log.d(TAG, "Mac media session stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Mac media session: ${e.message}")
        }
    }

    private fun sendMacMediaControl(action: String) {
        try {
            // Always send user-initiated control commands (play/pause/next/previous/stop)
            // The shouldSendMediaControl check is only for preventing duplicate state updates,
            // not for blocking user controls
            val controlJson = """{"type":"macMediaControl","data":{"action":"$action"}}"""
            com.sameerasw.airsync.utils.WebSocketUtil.sendMessage(controlJson)
            Log.d(TAG, "Sent Mac media control: $action")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Mac media control: ${e.message}")
        }
    }

    fun updateAlbumArt(albumArt: Bitmap?) {
        currentAlbumArt = albumArt
        // Update existing notification if service is running
        mediaSession?.let {
            val metadata = it.controller.metadata
            if (metadata != null) {
                val title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
                val artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
                val isPlaying = it.controller.playbackState?.state == PlaybackStateCompat.STATE_PLAYING
                updateMacMediaSession(title, artist, isPlaying)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
        stopMacMediaSession()
        Log.d(TAG, "MacMediaPlayerService destroyed")
    }
}
