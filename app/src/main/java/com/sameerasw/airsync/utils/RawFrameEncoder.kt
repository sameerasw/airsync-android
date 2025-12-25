package com.sameerasw.airsync.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.util.Log
import com.sameerasw.airsync.domain.model.MirroringOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Raw frame encoder that captures screen frames without H.264 encoding
 * Sends frames as compressed JPEG for simplicity and compatibility
 * Much faster than H.264 encoding and doesn't require FFmpeg on Mac
 */
class RawFrameEncoder(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val backgroundHandler: Handler,
    private val sendFrame: (ByteArray, FrameMetadata) -> Unit,
    private val mirroringOptions: MirroringOptions
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var streamingJob: Job? = null
    private var isStreaming = false
    
    private var encoderWidth: Int = 0
    private var encoderHeight: Int = 0
    
    // Frame rate control - use nanoseconds for better precision
    private var lastFrameTime = 0L
    private val frameIntervalNs = 1_000_000_000L / mirroringOptions.fps // ns between frames
    
    // Adaptive frame skipping for smooth scrolling
    private var consecutiveSkips = 0
    private val maxConsecutiveSkips = 2 // Skip at most 2 frames to maintain smoothness
    
    // Performance tracking
    private var framesSent = 0
    private var lastLogTime = System.currentTimeMillis()
    private var totalBytes = 0L
    
    companion object {
        private const val TAG = "RawFrameEncoder"
        private const val MAX_IMAGES = 3 // Triple buffering for smoother capture
        private const val LOG_INTERVAL_MS = 5000L // Log stats every 5 seconds
    }
    
    data class FrameMetadata(
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val format: String = "jpeg"
    )
    
    fun startCapture() {
        Log.d(TAG, "Starting raw frame capture...")
        try {
            val metrics = context.resources.displayMetrics
            val displayWidth = metrics.widthPixels
            val displayHeight = metrics.heightPixels
            
            // Calculate scaled dimensions
            val scale = minOf(
                mirroringOptions.maxWidth.toFloat() / displayWidth,
                1f // Don't scale up
            )
            encoderWidth = (displayWidth * scale).toInt()
            encoderHeight = (displayHeight * scale).toInt()
            
            // Align to 16 pixels for better performance
            encoderWidth = (encoderWidth / 16) * 16
            encoderHeight = (encoderHeight / 16) * 16
            
            Log.d(TAG, "Capture resolution: ${encoderWidth}x${encoderHeight} @ ${mirroringOptions.fps}fps")
            
            // Create ImageReader for capturing frames
            imageReader = ImageReader.newInstance(
                encoderWidth,
                encoderHeight,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )
            
            imageReader?.setOnImageAvailableListener({ reader ->
                if (isStreaming) {
                    processFrame(reader)
                }
            }, backgroundHandler)
            
            // Create virtual display
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "RawFrameCapture",
                encoderWidth,
                encoderHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )
            
            isStreaming = true
            Log.d(TAG, "✅ Raw frame capture started")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start raw frame capture", e)
            stopCapture()
        }
    }
    
    private fun processFrame(reader: ImageReader) {
        // Frame rate limiting with nanosecond precision
        val currentTime = System.nanoTime()
        val timeSinceLastFrame = currentTime - lastFrameTime
        
        // Adaptive frame skipping - allow slight bursts for smoother scrolling
        // but maintain target FPS on average
        val minInterval = frameIntervalNs * 7 / 10 // Allow 30% faster bursts
        if (timeSinceLastFrame < minInterval) {
            consecutiveSkips++
            // Force frame through if we've skipped too many
            if (consecutiveSkips < maxConsecutiveSkips) {
                return
            }
        }
        
        lastFrameTime = currentTime
        consecutiveSkips = 0
        
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return
            
            // Convert Image to Bitmap with error handling
            val bitmap = imageToBitmap(image)
            if (bitmap != null) {
                try {
                    // Adaptive JPEG quality based on frame rate
                    // Higher quality when FPS is stable, lower when struggling
                    val baseQuality = (mirroringOptions.quality * 100).toInt()
                    val jpegQuality = if (consecutiveSkips > 0) {
                        // Reduce quality slightly when skipping frames
                        (baseQuality * 0.85).toInt().coerceIn(50, 70)
                    } else {
                        baseQuality.coerceIn(55, 80)
                    }
                    
                    val jpegData = bitmapToJpeg(bitmap, jpegQuality)
                    
                    // Always recycle bitmap to prevent memory leaks
                    bitmap.recycle()
                    
                    if (jpegData != null) {
                        val metadata = FrameMetadata(
                            width = encoderWidth,
                            height = encoderHeight,
                            timestamp = System.currentTimeMillis()
                        )
                        sendFrame(jpegData, metadata)
                        
                        // Track performance
                        framesSent++
                        totalBytes += jpegData.size.toLong()
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime >= LOG_INTERVAL_MS) {
                            val elapsed = (now - lastLogTime) / 1000.0
                            val fps = framesSent / elapsed
                            val kbps = (totalBytes * 8 / 1024) / elapsed
                            val avgSize = if (framesSent > 0) totalBytes / framesSent / 1024 else 0
                            Log.d(TAG, "📊 Performance: ${String.format("%.1f", fps)} FPS, ${String.format("%.0f", kbps)} kbps, avg size: ${avgSize}KB")
                            framesSent = 0
                            totalBytes = 0
                            lastLogTime = now
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing bitmap", e)
                    bitmap.recycle() // Ensure cleanup even on error
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            image?.close()
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            // Create bitmap with exact dimensions to avoid extra allocation
            val bitmap = if (rowPadding == 0) {
                // No padding - direct copy
                Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
                    copyPixelsFromBuffer(buffer)
                }
            } else {
                // Has padding - need to crop
                val tempBitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                tempBitmap.copyPixelsFromBuffer(buffer)
                val croppedBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height)
                tempBitmap.recycle() // Free temp bitmap immediately
                croppedBitmap
            }
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }
    
    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        return try {
            // Use optimized buffer size
            val outputStream = ByteArrayOutputStream(encoderWidth * encoderHeight / 4)
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress bitmap to JPEG", e)
            null
        }
    }
    
    fun stopCapture() {
        Log.i(TAG, "Stopping raw frame capture...")
        isStreaming = false
        
        // Cancel any pending jobs
        streamingJob?.cancel()
        streamingJob = null
        
        // Release resources in correct order
        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing image listener", e)
        }
        
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        }
        
        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing image reader", e)
        }
        
        // Reset state
        lastFrameTime = 0L
        consecutiveSkips = 0
        framesSent = 0
        totalBytes = 0
        
        Log.i(TAG, "✅ Raw frame capture stopped and cleaned up")
    }
}
