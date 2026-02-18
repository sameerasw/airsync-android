package com.sameerasw.airsync.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object AppIconUtil {
    private const val TAG = "AppIconUtil"
    private const val ICON_SIZE = 128 // Standard icon size

    /**
     * Get app icons for all installed notification apps and convert to base64
     */
    suspend fun getAppIconsAsBase64(context: Context, packages: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val iconMap = mutableMapOf<String, String>()
            val packageManager = context.packageManager

            packages.forEach { packageName ->
                try {
                    // Get app icon
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val icon = packageManager.getApplicationIcon(appInfo)

                    // Convert to base64
                    val base64Icon = drawableToBase64(icon)
                    if (base64Icon != null) {
                        iconMap[packageName] = base64Icon
                        Log.d(TAG, "Successfully converted icon for $packageName")
                    } else {
                        Log.w(TAG, "Failed to convert icon for $packageName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting icon for $packageName: ${e.message}")
                }
            }

            Log.d(TAG, "Collected ${iconMap.size} app icons")
            iconMap
        }

    /**
     * Convert a drawable to base64 encoded PNG string
     */
    private fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = drawableToBitmap(drawable)
            val outputStream = ByteArrayOutputStream()

            // Compress as PNG with high quality
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val byteArray = outputStream.toByteArray()

            // Convert to base64 with data URI prefix
            "data:image/png;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting drawable to base64: ${e.message}")
            null
        }
    }

    /**
     * Convert drawable to bitmap with consistent sizing
     */
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            // If it's already a bitmap, scale it to standard size
            return drawable.bitmap.scale(ICON_SIZE, ICON_SIZE)
        }

        // Create bitmap from drawable
        val bitmap = createBitmap(ICON_SIZE, ICON_SIZE)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return bitmap
    }
}
