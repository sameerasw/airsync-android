package com.sameerasw.airsync.utils

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object WallpaperHandler {
    private const val TAG = "WallpaperHandler"
    private val isSending = AtomicBoolean(false)

    suspend fun sendWallpaper(context: Context) {
        if (isSending.getAndSet(true)) {
            Log.d(TAG, "Wallpaper send already in progress.")
            sendWallpaperResponse(null, "Wallpaper send already in progress.")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)
                val wallpaperDrawable = wallpaperManager.drawable

                if (wallpaperDrawable == null) {
                    Log.e(TAG, "Wallpaper drawable is null.")
                    sendWallpaperResponse(null, "Could not retrieve wallpaper.")
                    return@withContext
                }

                val bitmap: Bitmap = if (wallpaperDrawable is BitmapDrawable) {
                    wallpaperDrawable.bitmap
                } else {
                    // Handle cases where the wallpaper is not a simple bitmap (e.g., live wallpapers)
                    val bmp = Bitmap.createBitmap(
                        wallpaperDrawable.intrinsicWidth.coerceAtLeast(1),
                        wallpaperDrawable.intrinsicHeight.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bmp)
                    wallpaperDrawable.setBounds(0, 0, canvas.width, canvas.height)
                    wallpaperDrawable.draw(canvas)
                    bmp
                }

                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val byteArray = outputStream.toByteArray()
                val base64Wallpaper = Base64.encodeToString(byteArray, Base64.DEFAULT)

                sendWallpaperResponse(base64Wallpaper, "Wallpaper sent successfully.")
                Log.d(TAG, "Wallpaper sent successfully.")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending wallpaper: ${e.message}", e)
                sendWallpaperResponse(null, "Error sending wallpaper: ${e.message}")
            } finally {
                isSending.set(false)
            }
        }
    }

    private fun sendWallpaperResponse(base64Wallpaper: String?, message: String) {
        val response = JSONObject().apply {
            put("type", "wallpaperResponse")
            val data = JSONObject().apply {
                put("success", base64Wallpaper != null)
                put("message", message)
                base64Wallpaper?.let { put("wallpaper", it) }
            }
            put("data", data)
        }
        WebSocketUtil.sendMessage(response.toString())
    }
}