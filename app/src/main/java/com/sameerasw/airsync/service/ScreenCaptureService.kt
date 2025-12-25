
package com.sameerasw.airsync.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.RawFrameEncoder
import com.sameerasw.airsync.utils.ScreenMirroringManager
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var screenMirroringManager: ScreenMirroringManager? = null
    private var rawFrameEncoder: RawFrameEncoder? = null
    private var audioCaptureService: AudioCaptureService? = null
    private var useRawFrames = true // Use raw frames by default, H.264 as fallback
    private var enableAudio = false // Audio mirroring flag

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // Black overlay for hiding screen content
    private var blackOverlayView: android.view.View? = null
    private var windowManager: android.view.WindowManager? = null

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.d(TAG, "MediaProjection session stopped by user.")
            stopMirroring()
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 123
        private const val NOTIFICATION_CHANNEL_ID = "ScreenCaptureChannel"
        const val ACTION_START = "com.sameerasw.airsync.service.ScreenCaptureService.START"
        const val ACTION_STOP = "com.sameerasw.airsync.service.ScreenCaptureService.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIRRORING_OPTIONS = "mirroringOptions"

        private val _isStreaming = MutableStateFlow(false)
        val isStreaming = _isStreaming.asStateFlow()

        var instance: ScreenCaptureService? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        handlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Prevent duplicate mirroring sessions
                if (_isStreaming.value) {
                    Log.w(TAG, "Screen mirroring already active, ignoring duplicate start request")
                    return START_NOT_STICKY
                }

                startForegroundWithServiceType()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val mirroringOptions = intent.getParcelableExtra<com.sameerasw.airsync.domain.model.MirroringOptions>(EXTRA_MIRRORING_OPTIONS)

                if (resultCode == Activity.RESULT_OK && data != null && mirroringOptions != null) {
                    _isStreaming.value = true
                    // Reset pending flag now that mirroring has started
                    com.sameerasw.airsync.utils.MirrorRequestHelper.resetPendingFlag()
                    initializeMediaProjection(resultCode, data, mirroringOptions)
                    if (useRawFrames) {
                        rawFrameEncoder?.startCapture()
                    } else {
                        screenMirroringManager?.startMirroring()
                    }
                } else {
                    Log.e(TAG, "Invalid start parameters for screen capture. Stopping service.")
                    com.sameerasw.airsync.utils.MirrorRequestHelper.resetPendingFlag()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopMirroring()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithServiceType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    private fun initializeMediaProjection(resultCode: Int, data: Intent, mirroringOptions: com.sameerasw.airsync.domain.model.MirroringOptions) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(mediaProjectionCallback, backgroundHandler)
        
        // Check if we should use raw frames or H.264
        useRawFrames = mirroringOptions.useRawFrames ?: true
        enableAudio = mirroringOptions.enableAudio
        
        if (useRawFrames) {
            Log.d(TAG, "Using raw frame encoder (JPEG)")
            rawFrameEncoder = RawFrameEncoder(this, mediaProjection!!, backgroundHandler!!, ::sendRawFrame, mirroringOptions)
        } else {
            Log.d(TAG, "Using H.264 encoder (fallback)")
            screenMirroringManager = ScreenMirroringManager(this, mediaProjection!!, backgroundHandler!!, ::sendMirrorFrame, mirroringOptions)
        }
        
        // Initialize audio capture if enabled (Android 10+)
        if (enableAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "Initializing audio capture")
            audioCaptureService = AudioCaptureService(this, mediaProjection!!)
            if (audioCaptureService?.isSupported() == true) {
                audioCaptureService?.startCapture()
            } else {
                Log.w(TAG, "Audio capture not supported on this device")
            }
        } else if (enableAudio) {
            Log.w(TAG, "Audio capture requires Android 10+ (API 29)")
        }
        
        // Send mirrorStart message to Mac
        sendMirrorStart(mirroringOptions)
    }
    
    private fun sendMirrorStart(options: com.sameerasw.airsync.domain.model.MirroringOptions) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get actual screen dimensions
                val displayMetrics = resources.displayMetrics
                val width = minOf(displayMetrics.widthPixels, options.maxWidth)
                val height = (displayMetrics.heightPixels * width) / displayMetrics.widthPixels
                
                val json = JsonUtil.createMirrorStartJson(
                    fps = options.fps,
                    quality = options.quality,
                    width = width,
                    height = height
                )
                WebSocketUtil.sendMessage(json)
                Log.d(TAG, "Sent mirrorStart: fps=${options.fps}, quality=${options.quality}, width=$width, height=$height")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending mirrorStart", e)
            }
        }
    }

    fun stopMirroring() {
        if (!_isStreaming.compareAndSet(expect = true, update = false)) return
        Log.d(TAG, "Stopping screen capture.")

        // Stop audio capture first
        audioCaptureService?.stopCapture()
        audioCaptureService = null

        rawFrameEncoder?.stopCapture()
        rawFrameEncoder = null
        
        screenMirroringManager?.stopMirroring()
        screenMirroringManager = null
        
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        
        // Send mirrorStop message to Mac
        sendMirrorStop()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun sendMirrorStop() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = """{"type":"mirrorStop","data":{}}"""
                WebSocketUtil.sendMessage(json)
                Log.d(TAG, "Sent mirrorStop to Mac")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending mirrorStop", e)
            }
        }
    }

    fun resendConfig() {
        backgroundHandler?.post {
            screenMirroringManager?.resendConfig()
        }
    }

    private fun sendMirrorFrame(frame: ByteArray, bufferInfo: MediaCodec.BufferInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val base64Frame = Base64.encodeToString(frame, Base64.NO_WRAP)
            val json = JsonUtil.createMirrorFrameJson(base64Frame, bufferInfo.presentationTimeUs, isConfig)
            WebSocketUtil.sendMessage(json)
        }
    }
    
    private fun sendRawFrame(frame: ByteArray, metadata: RawFrameEncoder.FrameMetadata) {
        CoroutineScope(Dispatchers.IO).launch {
            val base64Frame = Base64.encodeToString(frame, Base64.NO_WRAP)
            val json = """{"type":"mirrorFrame","data":{"frame":"$base64Frame","format":"${metadata.format}","timestamp":${metadata.timestamp},"isConfig":false}}"""
            WebSocketUtil.sendMessage(json)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Screen Capture",
            NotificationManager.IMPORTANCE_DEFAULT  // Changed from LOW to show actions
        ).apply {
            description = "Shows when screen mirroring is active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("AirSync Mirror")
            .setContentText("Screen mirroring is active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop Mirroring",
                    pendingStopIntent
                ).build()
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.BigTextStyle()
                .bigText("Screen mirroring is active. Tap 'Stop Mirroring' button below to end the session."))
            .build()
    }

    fun showBlackOverlay() {
        try {
            if (blackOverlayView != null) {
                Log.d(TAG, "Black overlay already shown")
                return
            }
            
            // Check if we have overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    Log.e(TAG, "❌ No SYSTEM_ALERT_WINDOW permission - cannot show overlay")
                    Log.e(TAG, "💡 Enable 'Display over other apps' in Settings → Apps → AirSync → Advanced")
                    return
                }
            }
            
            windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            
            // Create black overlay view (screen curtain)
            blackOverlayView = android.view.View(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // Make it completely opaque
                alpha = 1.0f
            }
            
            // Set up window parameters for full-screen overlay (screen curtain)
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                // Screen curtain flags: not focusable, not touchable, covers everything
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.OPAQUE
            )
            
            // Position at top-left, covering entire screen
            params.x = 0
            params.y = 0
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            
            // Highest priority to ensure it's on top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            // Add overlay to window
            windowManager?.addView(blackOverlayView, params)
            Log.d(TAG, "✅ Screen curtain (black overlay) shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing screen curtain: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    fun hideBlackOverlay() {
        try {
            blackOverlayView?.let { view ->
                windowManager?.removeView(view)
                blackOverlayView = null
                Log.d(TAG, "✅ Black overlay hidden successfully")
            } ?: run {
                Log.d(TAG, "ℹ️ Black overlay already hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error hiding black overlay: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        hideBlackOverlay() // Clean up overlay on service destroy
        stopMirroring()
        handlerThread?.quitSafely()
        instance = null
        super.onDestroy()
    }
}
