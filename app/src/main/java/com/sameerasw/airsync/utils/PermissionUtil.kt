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

    // Special permission constants
    const val NOTIFICATION_ACCESS = "notification_access"
    const val ACCESSIBILITY_SERVICE = "accessibility_service"
    const val BACKGROUND_APP_USAGE = "background_app_usage"
    const val HEALTH_CONNECT = "health_connect"

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

        // Check call log permission (optional)
        if (!isCallLogPermissionGranted(context)) {
            missing.add("Call Log Access")
        }

        // Check contacts permission (optional)
        if (!isContactsPermissionGranted(context)) {
            missing.add("Contacts Access")
        }

        // Check phone state permission (optional)
        if (!isPhoneStatePermissionGranted(context)) {
            missing.add("Phone Access")
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

        // Call log access is optional for call log sync
        if (!isCallLogPermissionGranted(context)) {
            optional.add("Call Log Access")
        }

        // Contacts access is optional for contacts sync
        if (!isContactsPermissionGranted(context)) {
            optional.add("Contacts Access")
        }

        // Phone state access is optional
        if (!isPhoneStatePermissionGranted(context)) {
            optional.add("Phone Access")
        }

        // Answer phone calls permission (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isAnswerPhoneCallsPermissionGranted(context)) {
                optional.add("Answer Calls")
            }
        }

        return optional
    }

    /**
     * Check if READ_CALL_LOG permission is granted
     */
    fun isCallLogPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if READ_CONTACTS permission is granted
     */
    fun isContactsPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if READ_PHONE_STATE permission is granted
     */
    fun isPhoneStatePermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if READ_MEDIA_IMAGES permission is granted (Android 13+)
     */
    fun hasReadMediaImagesPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // For older versions, check READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Open accessibility settings
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    /**
     * Open Health Connect permissions
     */
    fun openHealthConnectPermissions(context: Context) {
        try {
            val intent = Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra("android.intent.extra.PACKAGE_NAME", context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    /**
     * Open app settings
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Get runtime permissions that can be requested
     */
    fun getRuntimePermissionsToRequest(context: Context): List<String> {
        val permissions = mutableListOf<String>()

        // POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isPostNotificationPermissionGranted(context)) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Call log permission
        if (!isCallLogPermissionGranted(context)) {
            permissions.add(Manifest.permission.READ_CALL_LOG)
        }

        // Contacts permission
        if (!isContactsPermissionGranted(context)) {
            permissions.add(Manifest.permission.READ_CONTACTS)
        }

        // Phone state permission
        if (!isPhoneStatePermissionGranted(context)) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        // Answer phone calls permission (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isAnswerPhoneCallsPermissionGranted(context)) {
                permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }

        // SMS permissions
        if (!isSmsPermissionGranted(context)) {
            permissions.add(Manifest.permission.READ_SMS)
        }
        if (!isSendSmsPermissionGranted(context)) {
            permissions.add(Manifest.permission.SEND_SMS)
        }
        if (!isReceiveSmsPermissionGranted(context)) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }

        // Camera permission
        if (!isCameraPermissionGranted(context)) {
            permissions.add(Manifest.permission.CAMERA)
        }

        // Activity recognition (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!isActivityRecognitionPermissionGranted(context)) {
                permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        // Media images (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasReadMediaImagesPermission(context)) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }

        // Record audio (for audio mirroring)
        if (!isRecordAudioPermissionGranted(context)) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        // Bluetooth permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isBluetoothScanPermissionGranted(context)) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (!isBluetoothConnectPermissionGranted(context)) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!isBluetoothAdvertisePermissionGranted(context)) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }

        return permissions
    }

    /**
     * Check if BLUETOOTH_SCAN permission is granted (Android 12+)
     */
    fun isBluetoothScanPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }
    }

    /**
     * Check if BLUETOOTH_CONNECT permission is granted (Android 12+)
     */
    fun isBluetoothConnectPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }
    }

    /**
     * Check if BLUETOOTH_ADVERTISE permission is granted (Android 12+)
     */
    fun isBluetoothAdvertisePermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }
    }

    /**
     * Check if all Bluetooth/Nearby Devices permissions are granted
     */
    fun isNearbyDevicesPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isBluetoothScanPermissionGranted(context) &&
            isBluetoothConnectPermissionGranted(context) &&
            isBluetoothAdvertisePermissionGranted(context)
        } else {
            // For older versions, check legacy Bluetooth permissions
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if SMS permissions are granted
     */
    fun isSmsPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if SEND_SMS permission is granted
     */
    fun isSendSmsPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if RECEIVE_SMS permission is granted
     */
    fun isReceiveSmsPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if CAMERA permission is granted
     */
    fun isCameraPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if ACTIVITY_RECOGNITION permission is granted
     */
    fun isActivityRecognitionPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }
    }

    /**
     * Check if RECORD_AUDIO permission is granted (for audio mirroring)
     */
    fun isRecordAudioPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if ANSWER_PHONE_CALLS permission is granted
     */
    fun isAnswerPhoneCallsPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not available on older versions
        }
    }

    /**
     * Check if SYSTEM_ALERT_WINDOW permission is granted
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Open overlay permission settings
     */
    fun openOverlaySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = "package:${context.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    /**
     * Check if Health Connect is available on this device
     */
    fun isHealthConnectAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.google.android.apps.healthdata", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Get all permission groups for the permissions screen
     */
    fun getAllPermissionGroups(context: Context): List<com.sameerasw.airsync.models.PermissionGroup> {
        val groups = mutableListOf<com.sameerasw.airsync.models.PermissionGroup>()

        // Core permissions
        groups.add(
            com.sameerasw.airsync.models.PermissionGroup(
                title = "Core",
                description = "Essential permissions for app functionality",
                category = com.sameerasw.airsync.models.PermissionCategory.CORE,
                permissions = listOf(
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = NOTIFICATION_ACCESS,
                        displayName = "Notification Access",
                        description = "Required to sync notifications from your phone",
                        category = com.sameerasw.airsync.models.PermissionCategory.CORE,
                        isGranted = isNotificationListenerEnabled(context),
                        isRequired = true,
                        requiresSpecialHandling = true
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = BACKGROUND_APP_USAGE,
                        displayName = "Background App Usage",
                        description = "Keeps the app running in the background",
                        category = com.sameerasw.airsync.models.PermissionCategory.CORE,
                        isGranted = isBatteryOptimizationDisabled(context),
                        isRequired = true,
                        requiresSpecialHandling = true
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.POST_NOTIFICATIONS,
                        displayName = "Post Notifications",
                        description = "Show notifications on your device",
                        category = com.sameerasw.airsync.models.PermissionCategory.CORE,
                        isGranted = isPostNotificationPermissionGranted(context),
                        isRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        requiresSpecialHandling = false
                    )
                )
            )
        )

        // Messaging permissions
        groups.add(
            com.sameerasw.airsync.models.PermissionGroup(
                title = "Messaging",
                description = "Permissions for SMS and messaging features",
                category = com.sameerasw.airsync.models.PermissionCategory.MESSAGING,
                permissions = listOf(
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.READ_SMS,
                        displayName = "Read SMS",
                        description = "View and sync SMS messages to your Mac",
                        category = com.sameerasw.airsync.models.PermissionCategory.MESSAGING,
                        isGranted = isSmsPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.SEND_SMS,
                        displayName = "Send SMS",
                        description = "Send SMS messages from your Mac",
                        category = com.sameerasw.airsync.models.PermissionCategory.MESSAGING,
                        isGranted = isSendSmsPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.RECEIVE_SMS,
                        displayName = "Receive SMS",
                        description = "Get notified of new SMS messages",
                        category = com.sameerasw.airsync.models.PermissionCategory.MESSAGING,
                        isGranted = isReceiveSmsPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    )
                )
            )
        )

        // Calls permissions
        groups.add(
            com.sameerasw.airsync.models.PermissionGroup(
                title = "Calls",
                description = "Permissions for call-related features",
                category = com.sameerasw.airsync.models.PermissionCategory.CALLS,
                permissions = listOf(
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.READ_CALL_LOG,
                        displayName = "Call Log",
                        description = "View recent calls on your Mac",
                        category = com.sameerasw.airsync.models.PermissionCategory.CALLS,
                        isGranted = isCallLogPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.READ_CONTACTS,
                        displayName = "Contacts",
                        description = "Show caller names in notifications",
                        category = com.sameerasw.airsync.models.PermissionCategory.CALLS,
                        isGranted = isContactsPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.READ_PHONE_STATE,
                        displayName = "Phone State",
                        description = "Detect incoming calls",
                        category = com.sameerasw.airsync.models.PermissionCategory.CALLS,
                        isGranted = isPhoneStatePermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.ANSWER_PHONE_CALLS,
                        displayName = "Answer Calls",
                        description = "Answer calls from your Mac",
                        category = com.sameerasw.airsync.models.PermissionCategory.CALLS,
                        isGranted = isAnswerPhoneCallsPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    )
                )
            )
        )

        // Health permissions
        groups.add(
            com.sameerasw.airsync.models.PermissionGroup(
                title = "Health & Fitness",
                description = "Permissions for health data sync",
                category = com.sameerasw.airsync.models.PermissionCategory.HEALTH,
                permissions = listOf(
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = HEALTH_CONNECT,
                        displayName = "Health Connect",
                        description = "Sync health data (steps, heart rate, etc.)",
                        category = com.sameerasw.airsync.models.PermissionCategory.HEALTH,
                        isGranted = false, // Health Connect permissions are checked separately
                        isRequired = false,
                        requiresSpecialHandling = true
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.ACTIVITY_RECOGNITION,
                        displayName = "Activity Recognition",
                        description = "Track physical activities automatically",
                        category = com.sameerasw.airsync.models.PermissionCategory.HEALTH,
                        isGranted = isActivityRecognitionPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    )
                )
            )
        )

        // Storage permissions
        groups.add(
            com.sameerasw.airsync.models.PermissionGroup(
                title = "Storage",
                description = "Permissions for file access and transfer",
                category = com.sameerasw.airsync.models.PermissionCategory.STORAGE,
                permissions = listOf(
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = "MANAGE_EXTERNAL_STORAGE",
                        displayName = "All Files Access",
                        description = "Transfer files and sync wallpaper",
                        category = com.sameerasw.airsync.models.PermissionCategory.STORAGE,
                        isGranted = hasManageExternalStoragePermission(),
                        isRequired = false,
                        requiresSpecialHandling = true
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.READ_MEDIA_IMAGES,
                        displayName = "Media Images",
                        description = "Access photos for transfer",
                        category = com.sameerasw.airsync.models.PermissionCategory.STORAGE,
                        isGranted = hasReadMediaImagesPermission(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    )
                )
            )
        )

        // Nearby Devices / Bluetooth permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            groups.add(
                com.sameerasw.airsync.models.PermissionGroup(
                    title = "Nearby Devices",
                    description = "Permissions for Bluetooth connectivity",
                    category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                    permissions = listOf(
                        com.sameerasw.airsync.models.PermissionInfo(
                            permission = Manifest.permission.BLUETOOTH_SCAN,
                            displayName = "Bluetooth Scan",
                            description = "Discover nearby Bluetooth devices",
                            category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                            isGranted = isBluetoothScanPermissionGranted(context),
                            isRequired = false,
                            requiresSpecialHandling = false
                        ),
                        com.sameerasw.airsync.models.PermissionInfo(
                            permission = Manifest.permission.BLUETOOTH_CONNECT,
                            displayName = "Bluetooth Connect",
                            description = "Connect to paired Bluetooth devices",
                            category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                            isGranted = isBluetoothConnectPermissionGranted(context),
                            isRequired = false,
                            requiresSpecialHandling = false
                        ),
                        com.sameerasw.airsync.models.PermissionInfo(
                            permission = Manifest.permission.BLUETOOTH_ADVERTISE,
                            displayName = "Bluetooth Advertise",
                            description = "Make device visible to other devices",
                            category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                            isGranted = isBluetoothAdvertisePermissionGranted(context),
                            isRequired = false,
                            requiresSpecialHandling = false
                        )
                    )
                )
            )
        }

        // Special permissions
        groups.add(
            com.sameerasw.airsync.models.PermissionGroup(
                title = "Special",
                description = "Additional permissions for advanced features",
                category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                permissions = listOf(
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.CAMERA,
                        displayName = "Camera",
                        description = "Scan QR codes for quick pairing",
                        category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                        isGranted = isCameraPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = Manifest.permission.RECORD_AUDIO,
                        displayName = "Microphone",
                        description = "Mirror audio from your phone to Mac",
                        category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                        isGranted = isRecordAudioPermissionGranted(context),
                        isRequired = false,
                        requiresSpecialHandling = false
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = "SYSTEM_ALERT_WINDOW",
                        displayName = "Display Over Apps",
                        description = "Show floating windows and overlays",
                        category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                        isGranted = hasOverlayPermission(context),
                        isRequired = false,
                        requiresSpecialHandling = true
                    ),
                    com.sameerasw.airsync.models.PermissionInfo(
                        permission = ACCESSIBILITY_SERVICE,
                        displayName = "Accessibility Service",
                        description = "Advanced device control features",
                        category = com.sameerasw.airsync.models.PermissionCategory.SPECIAL,
                        isGranted = false, // Checked separately
                        isRequired = false,
                        requiresSpecialHandling = true
                    )
                )
            )
        )

        return groups
    }
}
