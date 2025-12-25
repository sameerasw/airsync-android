package com.sameerasw.airsync.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Audio capture service for mirroring Android audio to Mac.
 * Requires Android 10+ (API 29) for AudioPlaybackCapture API.
 */
class AudioCaptureService(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    companion object {
        private const val TAG = "AudioCaptureService"
        
        // Audio configuration
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 2
        const val BITS_PER_SAMPLE = 16
        
        // Buffer size for ~50ms of audio
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8) / 20) // At least 50ms
    }
    
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var isCapturing = false
    
    // Throttling to prevent flooding WebSocket
    private var lastFrameSentTime = 0L
    private val minFrameIntervalMs = 40L // ~25 frames per second max
    
    /**
     * Check if audio capture is supported on this device
     */
    fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
    
    /**
     * Start capturing audio from the device
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startCapture(): Boolean {
        if (!isSupported()) {
            Log.e(TAG, "Audio capture not supported on this Android version (requires API 29+)")
            return false
        }
        
        if (isCapturing) {
            Log.w(TAG, "Audio capture already running")
            return true
        }
        
        // Check for RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return false
        }
        
        try {
            // Create audio playback capture configuration
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            
            // Create AudioRecord with playback capture
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()
            
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(BUFFER_SIZE * 2)
                .build()
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            // Send audio start message to Mac
            sendAudioStart()
            
            // Start recording
            audioRecord?.startRecording()
            isCapturing = true
            
            // Start capture loop
            captureJob = CoroutineScope(Dispatchers.IO).launch {
                captureLoop()
            }
            
            Log.d(TAG, "Audio capture started: sampleRate=$SAMPLE_RATE, channels=$CHANNELS, bufferSize=$BUFFER_SIZE")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio capture", e)
            stopCapture()
            return false
        }
    }
    
    /**
     * Stop capturing audio
     */
    fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        audioRecord = null
        
        // Send audio stop message to Mac
        sendAudioStop()
        
        Log.d(TAG, "Audio capture stopped")
    }
    
    private suspend fun captureLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        var frameCount = 0L
        var accumulatedBuffer = ByteArray(0)
        
        while (isCapturing && captureJob?.isActive == true) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                if (bytesRead > 0) {
                    // Accumulate audio data
                    accumulatedBuffer += buffer.copyOf(bytesRead)
                    
                    // Only send if enough time has passed (throttle to prevent flooding)
                    val now = System.currentTimeMillis()
                    if (now - lastFrameSentTime >= minFrameIntervalMs && accumulatedBuffer.isNotEmpty()) {
                        sendAudioFrame(accumulatedBuffer, frameCount++)
                        accumulatedBuffer = ByteArray(0)
                        lastFrameSentTime = now
                    }
                } else if (bytesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: $bytesRead")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in capture loop", e)
                break
            }
        }
        
        Log.d(TAG, "Capture loop ended, sent $frameCount frames")
    }
    
    private fun sendAudioStart() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = """{"type":"audioStart","data":{"sampleRate":$SAMPLE_RATE,"channels":$CHANNELS,"bitsPerSample":$BITS_PER_SAMPLE,"format":"pcm"}}"""
                WebSocketUtil.sendMessage(json)
                Log.d(TAG, "Sent audioStart message")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audioStart", e)
            }
        }
    }
    
    private fun sendAudioStop() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = """{"type":"audioStop","data":{}}"""
                WebSocketUtil.sendMessage(json)
                Log.d(TAG, "Sent audioStop message")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audioStop", e)
            }
        }
    }
    
    private fun sendAudioFrame(data: ByteArray, frameIndex: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val base64Data = Base64.encodeToString(data, Base64.NO_WRAP)
                val json = """{"type":"audioFrame","data":{"frame":"$base64Data","index":$frameIndex,"size":${data.size}}}"""
                WebSocketUtil.sendMessage(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio frame", e)
            }
        }
    }
}
