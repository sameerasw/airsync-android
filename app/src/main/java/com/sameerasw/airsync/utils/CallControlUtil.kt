package com.sameerasw.airsync.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

object CallControlUtil {
    private const val TAG = "CallControlUtil"

    /**
     * Programmatically accept an incoming call.
     * Uses TelecomManager on API 26+ if permission is granted, falling back to KEYCODE_HEADSETHOOK emulation.
     */
    fun acceptCall(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                try {
                    val telecomManager =
                        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    if (telecomManager != null) {
                        Log.d(TAG, "Accepting ringing call via TelecomManager")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            @Suppress("DEPRECATION")
                            telecomManager.acceptRingingCall(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                        } else {
                            @Suppress("DEPRECATION")
                            telecomManager.acceptRingingCall()
                        }
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to accept ringing call via TelecomManager, falling back", e)
                }
            } else {
                Log.w(
                    TAG,
                    "ANSWER_PHONE_CALLS permission not granted, falling back to media key hook"
                )
            }
        }

        // Fallback: Dispatch HEADSETHOOK media key event
        emulateHeadsetHookClick(context)
    }

    /**
     * Programmatically end or decline a call.
     * Uses TelecomManager on API 28+ if permission is granted, falling back to KEYCODE_HEADSETHOOK emulation.
     */
    fun endCall(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                try {
                    val telecomManager =
                        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    if (telecomManager != null) {
                        Log.d(TAG, "Ending/declining call via TelecomManager")
                        @Suppress("DEPRECATION")
                        val success = telecomManager.endCall()
                        Log.d(TAG, "TelecomManager.endCall returned: $success")
                        if (success) {
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to end call via TelecomManager, falling back", e)
                }
            } else {
                Log.w(
                    TAG,
                    "ANSWER_PHONE_CALLS permission not granted, falling back to media key hook"
                )
            }
        }

        // Fallback: Dispatch HEADSETHOOK media key event
        emulateHeadsetHookClick(context)
    }

    /**
     * Emulates clicking a hardware headset button (down and up events for KEYCODE_HEADSETHOOK).
     * This acts as a reliable system-wide fallback to answer/end calls.
     */
    private fun emulateHeadsetHookClick(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                Log.d(TAG, "Dispatching KEYCODE_HEADSETHOOK click to AudioManager")
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
            } else {
                Log.e(TAG, "AudioManager not available, cannot emulate headset hook")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error emulating headset hook click", e)
        }
    }
}
