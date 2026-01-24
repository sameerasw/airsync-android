package com.sameerasw.airsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import android.net.Uri
import android.provider.ContactsContract
import com.sameerasw.airsync.models.CallState
import com.sameerasw.airsync.models.OngoingCall
import com.sameerasw.airsync.service.LiveNotificationService
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class PhoneStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PhoneStateReceiver"
        private var currentCall: OngoingCall? = null
        private var callStartTime: Long = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone state changed: $state, number: $incomingNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Incoming call ringing
                handleRinging(context, incomingNumber)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Call answered or outgoing call started
                handleOffHook(context, incomingNumber)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Call ended
                handleIdle(context)
            }
        }
    }

    private fun handleRinging(context: Context, number: String?) {
        if (number == null) return

        val contactName = getContactName(context, number)
        val call = OngoingCall(
            id = UUID.randomUUID().toString(),
            number = number,
            contactName = contactName,
            state = CallState.RINGING,
            startTime = System.currentTimeMillis(),
            isIncoming = true
        )

        currentCall = call
        callStartTime = System.currentTimeMillis()

        // Send to Mac
        sendCallNotification(context, call)
    }

    private fun handleOffHook(context: Context, number: String?) {
        val call = currentCall
        if (call != null) {
            // Update existing call to active
            val updatedCall = call.copy(state = CallState.ACTIVE)
            currentCall = updatedCall
            sendCallNotification(context, updatedCall)
        } else if (number != null) {
            // Outgoing call
            val contactName = getContactName(context, number)
            val newCall = OngoingCall(
                id = UUID.randomUUID().toString(),
                number = number,
                contactName = contactName,
                state = CallState.ACTIVE,
                startTime = System.currentTimeMillis(),
                isIncoming = false
            )
            currentCall = newCall
            callStartTime = System.currentTimeMillis()
            sendCallNotification(context, newCall)
        }
    }

    private fun handleIdle(context: Context) {
        val call = currentCall
        if (call != null) {
            val duration = (System.currentTimeMillis() - callStartTime) / 1000
            val endedCall = call.copy(state = CallState.DISCONNECTED)
            sendCallNotification(context, endedCall)
            currentCall = null
            callStartTime = 0
            
            // Sync updated call logs to macOS after call ends
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(1000) // Wait for call log to be written to database
                com.sameerasw.airsync.utils.SyncManager.syncDataToMac(context)
            }
        }
    }

    private fun sendCallNotification(context: Context, call: OngoingCall) {
        // Only send call notifications when connected to Mac
        if (!WebSocketUtil.isConnected()) {
            Log.d(TAG, "Skipping call notification - not connected to Mac (state: ${call.state})")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Send to Mac via WebSocket - Mac will show the notification
                val json = JsonUtil.createCallNotificationJson(call)
                val sent = WebSocketUtil.sendMessage(json)
                if (sent) {
                    Log.d(TAG, "Call notification sent to Mac: ${call.state}")
                } else {
                    Log.w(TAG, "Failed to send call notification to Mac")
                }
                
                // Note: We don't show call notifications on Android
                // The Mac app handles displaying call notifications to the user
            } catch (e: Exception) {
                Log.e(TAG, "Error sending call notification", e)
            }
        }
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name", e)
        }

        return null
    }
}
