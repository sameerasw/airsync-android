package com.sameerasw.airsync.utils

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.service.MediaNotificationListener
import androidx.core.net.toUri

object PermissionUtil {

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val componentName = ComponentName(context, MediaNotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return !TextUtils.isEmpty(flat) && flat.contains(componentName.flattenToString())
    }

    /**
     * Check if the app is whitelisted from battery optimization
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Check if battery optimization permission is required for this Android version
     */
    fun isBatteryOptimizationPermissionRequired(): Boolean {
        return true // for min API level
    }

    /**
     * Open battery optimization settings for the app
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback to app-specific battery settings
            openAppSpecificBatterySettings(context)
        }
    }

    /**
     * Open app-specific battery settings as alternative
     */
    fun openAppSpecificBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Final fallback to general battery optimization settings
            openGeneralBatteryOptimizationSettings(context)
        }
    }

    /**
     * Open general battery optimization settings as fallback
     */
    fun openGeneralBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Final fallback to general settings
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Check if POST_NOTIFICATIONS permission is granted (Android 13+)
     */
    fun isPostNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }
    }

    /**
     * Check if notification permissions are required for this Android version
     */
    fun isNotificationPermissionRequired(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    /**
     * Check if MANAGE_EXTERNAL_STORAGE permission is granted (Android 11+)
     */
    fun hasManageExternalStoragePermission(): Boolean {
            return try {
                android.os.Environment.isExternalStorageManager()
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Open MANAGE_EXTERNAL_STORAGE permission settings
     */
    fun openManageExternalStorageSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${context.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // Fallback to app settings
                openAppSpecificBatterySettings(context)
            }
        }

    /**
     * Check if wallpaper access is available
     */
    fun hasWallpaperAccess(): Boolean {
        return hasManageExternalStoragePermission()
    }
    /**
     * Check if gallery/image access permission is granted
     * READ_MEDIA_IMAGES for API 33+, READ_EXTERNAL_STORAGE for lower
     */
    fun hasReadMediaImagesPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if gallery/image access permission is required for this Android version
     */
    fun isReadMediaImagesPermissionRequired(): Boolean {
        return true // Required on all versions now
    }

    /**
     * Get the correct gallery/image access permission based on API level
     */
    fun getGalleryPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }


    fun getAllMissingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()

        // Check notification listener permission (always required)
        if (!isNotificationListenerEnabled(context)) {
            missing.add("Notification Access")
        }

        // Check POST_NOTIFICATIONS permission (Android 13+)
        if (isNotificationPermissionRequired() && !isPostNotificationPermissionGranted(context)) {
            missing.add("Post Notifications")
        }

        // Check battery optimization permission
        if (isBatteryOptimizationPermissionRequired() && !isBatteryOptimizationDisabled(context)) {
            missing.add("Background App Usage")
        }

        // Check wallpaper access permission (optional)
        if (!hasWallpaperAccess()) {
            missing.add("Wallpaper Access")
        }

        // Check gallery access permission (optional)
        if (isReadMediaImagesPermissionRequired() && !hasReadMediaImagesPermission(context)) {
            missing.add("Gallery Access")
        }

        return missing
    }

    /**
     * Get permissions that are critical for app functionality
     */
    fun getCriticalMissingPermissions(context: Context): List<String> {
        val critical = mutableListOf<String>()

        // Notification listener is critical for the app's main functionality
        if (!isNotificationListenerEnabled(context)) {
            critical.add("Notification Access")
        }

        return critical
    }

    /**
     * Get permissions that are optional but recommended
     */
    fun getOptionalMissingPermissions(context: Context): List<String> {
        val optional = mutableListOf<String>()

        // POST_NOTIFICATIONS is recommended but not critical
        if (isNotificationPermissionRequired() && !isPostNotificationPermissionGranted(context)) {
            optional.add("Post Notifications")
        }

        // Battery optimization is recommended for better reliability
        if (isBatteryOptimizationPermissionRequired() && !isBatteryOptimizationDisabled(context)) {
            optional.add("Background App Usage")
        }

        // Wallpaper access is optional for wallpaper sync feature
        if (!hasWallpaperAccess()) {
            optional.add("Wallpaper Access")
        }

        // Gallery access is optional for image sync feature
        if (isReadMediaImagesPermissionRequired() && !hasReadMediaImagesPermission(context)) {
            optional.add("Gallery Access")
        }

        return optional
    }
}
