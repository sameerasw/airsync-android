package com.sameerasw.airsync.utils

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Manages keyguard/lock screen interactions for the Notes app.
 * Handles checking lock status and unlock requests with authentication.
 */
object KeyguardHelper {
    private const val TAG = "KeyguardHelper"

    /**
     * Checks if the device keyguard (lock screen) is currently active.
     */
    fun isKeyguardLocked(context: Context): Boolean {
        return try {
            val keyguardManager =
                context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.isKeyguardLocked
        } catch (e: Exception) {
            Log.e(TAG, "Error checking keyguard status", e)
            false
        }
    }

    /**
     * Checks if the device is currently secure (has PIN, pattern, or biometric).
     */
    fun isKeyguardSecure(context: Context): Boolean {
        return try {
            val keyguardManager =
                context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.isKeyguardSecure
        } catch (e: Exception) {
            Log.e(TAG, "Error checking keyguard secure status", e)
            false
        }
    }

    /**
     * Requests the device keyguard (lock screen) to be dismissed.
     * This will prompt the user to authenticate if the device is secure.
     */
    fun requestDismissKeyguard(
        activity: Activity,
        onDismissSucceeded: () -> Unit = {},
        onDismissError: () -> Unit = {},
        onDismissCancelled: () -> Unit = {}
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "requestDismissKeyguard requires API 28+")
            return
        }

        try {
            val keyguardManager =
                activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            keyguardManager.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissError() {
                        Log.d(TAG, "Unlock failed - Dismissing keyguard is not feasible")
                        onDismissError()
                    }

                    override fun onDismissSucceeded() {
                        Log.d(TAG, "Unlock succeeded - Device is now unlocked")
                        onDismissSucceeded()
                    }

                    override fun onDismissCancelled() {
                        Log.d(TAG, "Unlock failed - User cancelled operation")
                        onDismissCancelled()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting keyguard dismissal", e)
            onDismissError()
        }
    }
}

