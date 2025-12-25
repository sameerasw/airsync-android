package com.sameerasw.airsync.utils

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

object WallpaperUtil {
    private const val TAG = "WallpaperUtil"
    private const val MAX_WALLPAPER_SIZE = 1920 // Maximum size
    private const val JPEG_QUALITY = 85 // JPEG compression quality (0-100)

    /**
     * Gets the current wallpaper and converts it to base64 string
     * @param context Application context
     * @return Base64 encoded wallpaper string or null if unavailable
     */
    fun getWallpaperAsBase64(context: Context): String? {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)

            // Check  permissions
            if (!hasWallpaperPermissions(context)) {
                Log.w(TAG, "Missing wallpaper permissions")
                return null
            }

            val wallpaperDrawable = try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        // Android 14+ - Use FLAG_SYSTEM explicitly
                        wallpaperManager.getDrawable(WallpaperManager.FLAG_SYSTEM)
                    }
                    else -> {
                        // Android 13 and below
                        wallpaperManager.drawable
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException accessing wallpaper: ${e.message}")
                return null
            }

            if (wallpaperDrawable == null) {
                Log.w(TAG, "Wallpaper drawable is null")
                return null
            }

            // Convert drawable to bitmap
            val bitmap = drawableToBitmap(wallpaperDrawable)
            if (bitmap == null) {
                Log.w(TAG, "Failed to convert wallpaper to bitmap")
                return null
            }

            // Resize bitmap if too large
            val resizedBitmap = resizeBitmapIfNeeded(bitmap)

            // Convert to base64
            val base64String = bitmapToBase64(resizedBitmap, Bitmap.CompressFormat.JPEG, JPEG_QUALITY)

            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }
            bitmap.recycle()

            Log.d(TAG, "Successfully encoded wallpaper to base64 (${base64String?.length ?: 0} chars)")
            return base64String

        } catch (e: Exception) {
            Log.e(TAG, "Error getting wallpaper as base64: ${e.message}")
            return null
        }
    }

    /**
     * Check if the app has the required permissions to access wallpaper
     */
    private fun hasWallpaperPermissions(context: Context): Boolean {
        return PermissionUtil.hasReadMediaImagesPermission(context)
    }

    /**
     * Convert Drawable to Bitmap
     */
    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            if (drawable is BitmapDrawable) {
                if (drawable.bitmap != null) {
                    return drawable.bitmap
                }
            }

            val bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
                // Single color bitmap will be created of 1x1 pixel
                createBitmap(1, 1)
            } else {
                createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
            }

            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting drawable to bitmap: ${e.message}")
            null
        }
    }

    /**
     * Resize bitmap if it's too large
     */
    private fun resizeBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= MAX_WALLPAPER_SIZE && height <= MAX_WALLPAPER_SIZE) {
            return bitmap
        }

        val ratio = minOf(
            MAX_WALLPAPER_SIZE.toFloat() / width,
            MAX_WALLPAPER_SIZE.toFloat() / height
        )

        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        Log.d(TAG, "Resizing wallpaper from ${width}x${height} to ${newWidth}x${newHeight}")

        return bitmap.scale(newWidth, newHeight)
    }

    /**
     * Convert Bitmap to Base64 string
     */
    private fun bitmapToBase64(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String? {
        return try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(format, quality, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting bitmap to base64: ${e.message}")
            null
        }
    }
}