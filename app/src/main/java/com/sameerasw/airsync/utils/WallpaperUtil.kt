package com.sameerasw.airsync.utils

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

object WallpaperUtil {
    private const val TAG = "WallpaperUtil"
    private const val MAX_WALLPAPER_SIZE = 1920
    private const val JPEG_QUALITY = 85

    fun getWallpaperAsBase64(context: Context): String? {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)

            val wallpaperDrawable = try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        // Explicitly request SYSTEM (home screen) wallpaper only
                        wallpaperManager.getDrawable(WallpaperManager.FLAG_SYSTEM)
                    }
                    else -> {
                        // On older Android, drawable is always home screen
                        wallpaperManager.drawable
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Blocked on HyperOS/OEM: ${e.message}")
                tryFallbacks(wallpaperManager)
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error: ${e.message}")
                tryFallbacks(wallpaperManager)
            }

            if (wallpaperDrawable == null) {
                Log.w(TAG, "Wallpaper drawable is null")
                return null
            }

            val bitmap = drawableToBitmap(wallpaperDrawable) ?: return null
            val resizedBitmap = resizeBitmapIfNeeded(bitmap)
            val base64String = bitmapToBase64(resizedBitmap, Bitmap.CompressFormat.JPEG, JPEG_QUALITY)

            // cleanup
            try {
                if (resizedBitmap != bitmap && !resizedBitmap.isRecycled) {
                    resizedBitmap.recycle()
                }
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error recycling bitmaps: ${e.message}")
            }

            Log.d(TAG, "Successfully encoded wallpaper to base64")
            base64String
        } catch (e: Exception) {
            Log.e(TAG, "Error getting wallpaper: ${e.message}")
            null
        }
    }

    private fun tryFallbacks(wallpaperManager: WallpaperManager): Drawable? {
        return try {
            Log.d(TAG, "Trying peekDrawable() as fallback")
            wallpaperManager.peekDrawable() ?: run {
                Log.d(TAG, "Trying builtInDrawable as fallback")
                wallpaperManager.builtInDrawable ?: run {
                    Log.d(TAG, "Trying reflection as last resort")
                    getWallpaperViaReflection(wallpaperManager)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "All fallbacks failed: ${e.message}")
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
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

    private fun resizeBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= MAX_WALLPAPER_SIZE && height <= MAX_WALLPAPER_SIZE) return bitmap

        val ratio = minOf(
            MAX_WALLPAPER_SIZE.toFloat() / width,
            MAX_WALLPAPER_SIZE.toFloat() / height
        )
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        Log.d(TAG, "Resizing wallpaper from ${width}x${height} to ${newWidth}x${newHeight}")
        return bitmap.scale(newWidth, newHeight)
    }

    private fun bitmapToBase64(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String? {
        return try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(format, quality, byteArrayOutputStream)
            Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting bitmap to base64: ${e.message}")
            null
        }
    }

    private fun getWallpaperViaReflection(wallpaperManager: WallpaperManager): Drawable? {
        return try {
            val clazz = wallpaperManager.javaClass
            val method = clazz.getDeclaredMethod("getDrawable", Int::class.java, Boolean::class.java)
            method.isAccessible = true
            method.invoke(wallpaperManager, WallpaperManager.FLAG_SYSTEM, false) as? Drawable
        } catch (e: Exception) {
            Log.e(TAG, "Reflection failed: ${e.message}")
            null
        }
    }
}
