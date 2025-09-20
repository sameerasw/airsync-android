package com.sameerasw.airsync.utils

import android.app.WallpaperManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.lang.Exception
import java.lang.reflect.Method

object WallpaperUtil {
    private const val TAG = "WallpaperUtil"
    private const val MAX_WALLPAPER_SIZE = 1920
    private const val JPEG_QUALITY = 85

    // Callback interface for user wallpaper selection
    interface WallpaperSelectionCallback {
        fun onUserSelectionRequested(message: String = "All automated methods failed — require user selection")
    }

    private var wallpaperSelectionCallback: WallpaperSelectionCallback? = null

    /**
     * Set the callback for user wallpaper selection
     */
    fun setWallpaperSelectionCallback(callback: WallpaperSelectionCallback?) {
        wallpaperSelectionCallback = callback
    }

    /**
     * Public entry: get user's current static wallpaper as a Base64 JPEG (or null).
     * Tries multiple strategies (HyperOS/MIUI/Samsung/AOSP).
     */
    fun getWallpaperAsBase64(context: Context): String? {
        try {
            val wm = WallpaperManager.getInstance(context)

            // If Android 14+ we mostly rely on runtime attempts (AppOps may block).
            // Still check a minimal storage permission when appropriate.
            if (!hasWallpaperPermissions(context)) {
                Log.w(TAG, "Missing wallpaper permissions (best-effort will continue)")
                // continue — we'll try APIs that may not need extra permissions (builtInDrawable)
            }

            // 1) Try builtInDrawable (HyperOS/MIUI often exposes current static wallpaper here).
            try {
                val built = safeGetBuiltInDrawable(wm)
                if (built != null) {
                    Log.d(TAG, "Succeeded via builtInDrawable")
                    return drawableToBase64AndCleanup(built)
                } else {
                    Log.d(TAG, "builtInDrawable returned null")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "builtInDrawable blocked: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "builtInDrawable error: ${e.message}")
            }

            // 2) Try standard getDrawable with flags (system/home/lock)
            try {
                val sys = tryGetDrawableWithFlags(wm)
                if (sys != null) {
                    Log.d(TAG, "Succeeded via getDrawable / peekDrawable path")
                    return drawableToBase64AndCleanup(sys)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "getDrawable/peekDrawable blocked: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "getDrawable/peekDrawable error: ${e.message}")
            }

            // 3) Try getWallpaperFile via reflection (ParcelFileDescriptor -> Bitmap)
            try {
                val pfdDrawable = tryGetWallpaperViaReflection(wm, context)
                if (pfdDrawable != null) {
                    Log.d(TAG, "Succeeded via reflection getWallpaperFile")
                    return drawableToBase64AndCleanup(pfdDrawable)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Reflection getWallpaperFile failed: ${e.message}")
            }

            // 4) Probe OEM / external storage paths (MIUI, Samsung, SystemUI backup)
            try {
                val fileDrawable = probeOemWallpaperPaths(context)
                if (fileDrawable != null) {
                    Log.d(TAG, "Succeeded via OEM/external wallpaper path probe")
                    return drawableToBase64AndCleanup(fileDrawable)
                } else {
                    Log.d(TAG, "No OEM/external wallpaper found or readable")
                }
            } catch (e: Exception) {
                Log.d(TAG, "OEM path probe failed: ${e.message}")
            }

            // 5) As final resort: request user to pick the wallpaper via UI callback
            Log.w(TAG, "All automated methods failed — require user selection")
            wallpaperSelectionCallback?.onUserSelectionRequested("All automated methods failed — please select your wallpaper manually")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "getWallpaperAsBase64 unexpected: ${e.message}")
            return null
        }
    }

    // ----------------------
    // Helpers & strategies
    // ----------------------

    private fun safeGetBuiltInDrawable(wm: WallpaperManager): Drawable? {
        return try {
            // getBuiltInDrawable was added as getBuiltInDrawable() / property; call it directly
            wm.builtInDrawable
        } catch (e: Throwable) {
            Log.d(TAG, "safeGetBuiltInDrawable throwable: ${e.message}")
            null
        }
    }

    private fun tryGetDrawableWithFlags(wm: WallpaperManager): Drawable? {
        // Try several public methods in order of usefulness. Catch SecurityException separately.
        //  - FLAG_SYSTEM or FLAG_LOCK if available
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // prefer explicit flag API when available
                try {
                    return wm.getDrawable(WallpaperManager.FLAG_SYSTEM)
                } catch (_: SecurityException) { /* blocked */ }
            }
        } catch (ignore: Throwable) {}

        // peekDrawable exists on many builds
        try {
            val peek = wm.peekDrawable()
            if (peek != null) return peek
        } catch (e: Throwable) {
            Log.d(TAG, "peekDrawable error: ${e.message}")
        }

        // fallback to getDrawable()
        try {
            val d = wm.drawable
            if (d != null) return d
        } catch (e: Throwable) {
            Log.d(TAG, "wm.drawable error: ${e.message}")
        }

        return null
    }

    /**
     * Reflection attempt: call hidden getWallpaperFile or getDrawable(int,boolean) if present,
     * or read internal parcel descriptor via service method. This is last-resort and may fail
     * on most modern phones; handle exceptions gracefully.
     */
    private fun tryGetWallpaperViaReflection(wm: WallpaperManager, ctx: Context): Drawable? {
        try {
            val clazz = wm.javaClass

            // 1) Try getWallpaperFile(int which)
            try {
                val m = clazz.getMethod("getWallpaperFile", Int::class.javaPrimitiveType)
                m.isAccessible = true
                val pfd = m.invoke(wm, WallpaperManager.FLAG_SYSTEM) as? ParcelFileDescriptor
                if (pfd != null) {
                    pfd.use { p ->
                        val fis = FileInputStream(p.fileDescriptor)
                        val bmp = BitmapFactory.decodeStream(fis)
                        return bmp?.let { BitmapDrawable(ctx.resources, it) }
                    }
                }
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "getWallpaperFile method not present: ${e.message}")
            } catch (se: SecurityException) {
                Log.d(TAG, "getWallpaperFile blocked: ${se.message}")
            } catch (e: Exception) {
                Log.d(TAG, "getWallpaperFile invoke failed: ${e.message}")
            }

            // 2) Try getDrawable(int, boolean) hidden variant
            try {
                val m2 = clazz.getDeclaredMethod("getDrawable", Int::class.java, Boolean::class.java)
                m2.isAccessible = true
                val result = m2.invoke(wm, WallpaperManager.FLAG_SYSTEM, false) as? Drawable
                if (result != null) return result
            } catch (e: Exception) {
                Log.d(TAG, "hidden getDrawable(flag,boolean) failed: ${e.message}")
            }

        } catch (e: Throwable) {
            Log.d(TAG, "reflection overall failed: ${e.message}")
        }
        return null
    }

    /**
     * Probe common OEM/external paths where vendors (MIUI, Samsung, SystemUI) may keep a copy.
     * Requires the app have permission to read external storage or MANAGE_EXTERNAL_STORAGE on older Android,
     * or that the file is world-readable. Returns first readable Drawable found.
     */
    private fun probeOemWallpaperPaths(context: Context): Drawable? {
        val candidates = arrayListOf<String>()

        // MIUI / HyperOS theme manager copy (reported path)
        candidates.add("/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.wallpaper")
        candidates.add("/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data/content")
        // Samsung wallpaper resource folder reported by users
        candidates.add("/storage/emulated/0/Android/data/com.samsung.android.wallpaper.res/files")
        // SystemUI backup wallpapers (observed on some devices)
        candidates.add("/storage/emulated/0/Android/data/com.android.systemui/files/backupwallpapers")
        // Generic pictures/wallpapers folders
        candidates.add("/storage/emulated/0/Pictures/Wallpapers")
        candidates.add("/storage/emulated/0/Download")

        for (base in candidates) {
            try {
                val dir = File(base)
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    if (!f.isFile) continue
                    // quick heuristic: common image extensions or mrc (miui)
                    val name = f.name.lowercase()
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".png") || name.endsWith(".mrc") || name.endsWith(".webp")) {
                        try {
                            val fis = FileInputStream(f)
                            val bmp = BitmapFactory.decodeStream(fis)
                            fis.close()
                            if (bmp != null) {
                                Log.d(TAG, "Found OEM wallpaper candidate: ${f.absolutePath}")
                                return BitmapDrawable(context.resources, bmp)
                            }
                        } catch (io: Exception) {
                            Log.d(TAG, "Failed to read ${f.absolutePath}: ${io.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "probe path error for $base : ${e.message}")
            }
        }
        return null
    }

    // Convert Drawable -> resized Bitmap -> base64, with cleanup.
    private fun drawableToBase64AndCleanup(drawable: Drawable): String? {
        val bitmap = drawableToBitmap(drawable) ?: return null
        val resized = resizeBitmapIfNeeded(bitmap)
        val base64 = bitmapToBase64(resized, Bitmap.CompressFormat.JPEG, JPEG_QUALITY)
        try {
            if (resized != bitmap && !resized.isRecycled) resized.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (e: Exception) {
            Log.d(TAG, "bitmap recycle error: ${e.message}")
        }
        return base64
    }

    private fun hasWallpaperPermissions(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                // On Android 14+, best-effort: the OS will still enforce AppOps; still return true to attempt APIs.
                true
            }
            else -> PermissionUtil.hasManageExternalStoragePermission()
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        try {
            if (drawable is BitmapDrawable) {
                drawable.bitmap?.let { return it }
            }
            val bmp = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
                createBitmap(1, 1)
            } else {
                createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
            }
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bmp
        } catch (e: Exception) {
            Log.e(TAG, "drawableToBitmap error: ${e.message}")
            return null
        }
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= MAX_WALLPAPER_SIZE && height <= MAX_WALLPAPER_SIZE) return bitmap
        val ratio = minOf(MAX_WALLPAPER_SIZE.toFloat() / width, MAX_WALLPAPER_SIZE.toFloat() / height)
        val newW = (width * ratio).toInt()
        val newH = (height * ratio).toInt()
        Log.d(TAG, "Resizing wallpaper from ${width}x${height} to ${newW}x${newH}")
        return bitmap.scale(newW, newH)
    }

    fun bitmapToBase64(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String? {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(format, quality, baos)
            val bytes = baos.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "bitmapToBase64 error: ${e.message}")
            null
        }
    }

    /**
     * Convert URI to Bitmap for wallpaper processing
     */
    fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val contentResolver = context.contentResolver
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "uriToBitmap error: ${e.message}")
            null
        }
    }

    /**
     * Process user-selected wallpaper URI and return base64
     */
    fun processUserSelectedWallpaper(context: Context, uri: Uri): String? {
        try {
            val bitmap = uriToBitmap(context, uri)
            if (bitmap != null) {
                val resized = resizeBitmapIfNeeded(bitmap)
                val base64 = bitmapToBase64(resized, Bitmap.CompressFormat.JPEG, JPEG_QUALITY)
                try {
                    if (resized != bitmap && !resized.isRecycled) resized.recycle()
                    if (!bitmap.isRecycled) bitmap.recycle()
                } catch (e: Exception) {
                    Log.d(TAG, "bitmap recycle error: ${e.message}")
                }
                return base64
            }
        } catch (e: Exception) {
            Log.e(TAG, "processUserSelectedWallpaper error: ${e.message}")
        }
        return null
    }
}
