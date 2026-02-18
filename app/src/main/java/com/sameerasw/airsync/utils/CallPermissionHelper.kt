package com.sameerasw.airsync.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Helper object for managing call-related runtime permissions.
 * Provides centralized checks and permission request flows.
 */
object CallPermissionHelper {
    /**
     * Required permissions for call monitoring functionality
     */
    val CALL_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE
    )

    /**
     * Check if all required call permissions are granted
     */
    fun hasAllCallPermissions(context: Context): Boolean {
        return CALL_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if a specific permission is granted
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get list of permissions that need to be requested
     */
    fun getMissingPermissions(context: Context): Array<String> {
        return CALL_PERMISSIONS.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /**
     * Get permission rationale messages
     */
    fun getPermissionRationale(permission: String): String = when (permission) {
        Manifest.permission.READ_CALL_LOG -> {
            "Call logs access allows AirSync to detect incoming, outgoing, and missed calls to sync with your Mac."
        }

        Manifest.permission.READ_CONTACTS -> {
            "Contacts access helps match phone numbers to contact names for better readability."
        }

        Manifest.permission.READ_PHONE_STATE -> {
            "Phone state access enables real-time detection of incoming and outgoing calls."
        }

        else -> "This permission is required for call monitoring."
    }
}
