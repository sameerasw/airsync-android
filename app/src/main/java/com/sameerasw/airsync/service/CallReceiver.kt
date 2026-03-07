package com.sameerasw.airsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * BroadcastReceiver that listens for telephony events (incoming/outgoing calls).
 * Handles both PHONE_STATE and NEW_OUTGOING_CALL broadcasts.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        private var listener: CallStateListener? = null
        private var savedNumber: String? = null
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Broadcast received: ${intent.action}")

        // Initialize the listener if it's the first time
        if (listener == null) {
            listener = CallStateListener(context)
        }

        // Handle outgoing call - capture the number BEFORE the call is placed
        if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            savedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            Log.d(TAG, "New outgoing call to: $savedNumber")
            // The PHONE_STATE broadcast will follow immediately after
            return
        }

        // Handle call state changes (incoming/outgoing)
        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone state changed: $stateStr, incoming number: $incomingNumber")

        // Convert string state to integer
        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> TelephonyManager.CALL_STATE_IDLE
        }

        // For incoming calls, prefer the incoming number from PHONE_STATE
        // For outgoing calls, use the saved number from NEW_OUTGOING_CALL
        val numberToUse = when {
            state == TelephonyManager.CALL_STATE_RINGING -> incomingNumber ?: savedNumber
            savedNumber != null -> savedNumber
            else -> incomingNumber
        }

        Log.d(TAG, "Passing to listener - state: $state, number: $numberToUse")
        listener?.onCallStateChanged(context, state, numberToUse)
    }
}
