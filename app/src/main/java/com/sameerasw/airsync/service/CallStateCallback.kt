package com.sameerasw.airsync.service

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.sameerasw.airsync.domain.model.CallDirection
import com.sameerasw.airsync.domain.model.CallEvent
import com.sameerasw.airsync.utils.ContactPhotoUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import com.google.gson.Gson
import com.sameerasw.airsync.utils.ContactLookupHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * Manages call state transitions and sends call events to the Mac app via WebSocket.
 * Tracks the state machine: IDLE -> RINGING -> OFFHOOK -> IDLE
 * 
 * Receives state changes from CallReceiver (BroadcastReceiver) which listens to
 * PHONE_STATE and NEW_OUTGOING_CALL broadcasts.
 * 
 * Uses debouncing to wait for complete call information before sending events.
 */
class CallStateListener(private val context: Context) {

    companion object {
        private const val TAG = "CallStateListener"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var isIncomingCall = false
        private var callStartTime = 0L
        private var currentPhoneNumber: String? = null
        
        // Debouncing: wait this long for phone number to arrive after state change
        private const val PHONE_NUMBER_WAIT_MS = 500L
        private var pendingSendJob: Job? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val contactLookupHelper = ContactLookupHelper(context)

    fun onCallStateChanged(context: Context, state: Int, number: String?) {
        Log.d(TAG, "onCallStateChanged - lastState: $lastState, newState: $state, number: $number")

        // Update the current phone number if provided
        if (!number.isNullOrBlank() && number != "null") {
            currentPhoneNumber = number
            Log.d(TAG, "Updated currentPhoneNumber to: $currentPhoneNumber")
        }

        // Ignore if no state change
        if (lastState == state) {
            Log.d(TAG, "No state change (still in state $state), ignoring duplicate")
            return
        }

        Log.d(TAG, "State transition detected: $lastState -> $state")

        // Cancel any pending send from previous state change
        pendingSendJob?.cancel()

        // Schedule sending with a slight delay to wait for complete information
        pendingSendJob = scope.launch {
            // Wait a bit for phone number to potentially arrive
            delay(PHONE_NUMBER_WAIT_MS)
            
            // Use the stored number or the one just provided
            val phoneNumber = currentPhoneNumber
            Log.d(TAG, "Processing state transition after delay - state: $state, phone: $phoneNumber")

            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    isIncomingCall = true
                    callStartTime = System.currentTimeMillis()
                    Log.d(TAG, "RINGING: Incoming call from $phoneNumber")
                    sendCallEvent(context, "ringing", CallDirection.INCOMING.apiValue, phoneNumber)
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                        Log.d(TAG, "OFFHOOK: Incoming call answered - $phoneNumber")
                        sendCallEvent(context, "offhook", CallDirection.INCOMING.apiValue, phoneNumber)
                    } else {
                        isIncomingCall = false
                        callStartTime = System.currentTimeMillis()
                        Log.d(TAG, "OFFHOOK: Outgoing call started to $phoneNumber")
                        sendCallEvent(context, "offhook", CallDirection.OUTGOING.apiValue, phoneNumber)
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    when (lastState) {
                        TelephonyManager.CALL_STATE_RINGING -> {
                            Log.d(TAG, "IDLE: Missed call from $phoneNumber")
                            sendCallEvent(context, "missed", CallDirection.INCOMING.apiValue, phoneNumber)
                        }
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            if (isIncomingCall) {
                                Log.d(TAG, "IDLE: Incoming call ended - $phoneNumber")
                                sendCallEvent(context, "idle", CallDirection.INCOMING.apiValue, phoneNumber)
                            } else {
                                Log.d(TAG, "IDLE: Outgoing call ended - $phoneNumber")
                                sendCallEvent(context, "idle", CallDirection.OUTGOING.apiValue, phoneNumber)
                            }
                        }
                    }
                    isIncomingCall = false
                    callStartTime = 0L
                    currentPhoneNumber = null
                }
            }

            lastState = state
        }
    }

    /**
     * Send a call event to the Mac app via WebSocket with all details
     * Only sends if WebSocket is connected to Mac
     */
    private suspend fun sendCallEvent(
        context: Context,
        state: String,
        direction: String,
        phoneNumber: String?
    ) {
        // Only send call events when connected to Mac
        if (!WebSocketUtil.isConnected()) {
            Log.d(TAG, "Skipping call event - not connected to Mac (state: $state)")
            return
        }
        
        try {
            val displayNumber = phoneNumber?.takeIf { it.isNotBlank() } ?: "Unknown"
            
            Log.d(TAG, "Preparing call event - state: $state, direction: $direction, number: $displayNumber")
            
            // Lookup contact name
            val contactName = if (displayNumber != "Unknown") {
                try {
                    val name = contactLookupHelper.findContactName(displayNumber)
                    // Validate: never use the phone number as a contact name
                    if (!name.isNullOrEmpty() && name != displayNumber) {
                        Log.d(TAG, "✓ Contact found: $name for number: $displayNumber")
                        name
                    } else {
                        if (!name.isNullOrEmpty()) {
                            Log.d(TAG, "⚠ Contact name equals phone number, treating as unknown: $displayNumber")
                        }
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error looking up contact for $displayNumber", e)
                    null
                }
            } else {
                null
            }
            
            Log.d(TAG, "Contact lookup complete - name: ${contactName ?: "Unknown"}")

            // Normalize phone number - validate that result is actually normalized
            val normalizedNumber = if (displayNumber != "Unknown") {
                try {
                    val normalized = contactLookupHelper.normalizeNumber(displayNumber)
                    // Validate: normalized number should start with + for E.164 format
                    if (normalized.startsWith("+")) {
                        Log.d(TAG, "✓ Number normalized: $displayNumber -> $normalized")
                        normalized
                    } else {
                        Log.w(TAG, "⚠ Normalization failed or returned invalid format: $normalized, using original: $displayNumber")
                        displayNumber
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error normalizing number $displayNumber: ${e.message}")
                    displayNumber
                }
            } else {
                null
            }

            // Get contact photo only for initial ringing or offhook events (not for idle/missed)
            var contactPhoto: String? = null
            if ((state == "ringing" || state == "offhook") && displayNumber != "Unknown") {
                try {
                    contactPhoto = ContactPhotoUtil.getContactPhotoBase64(context, displayNumber)
                    if (!contactPhoto.isNullOrEmpty()) {
                        Log.d(TAG, "✅ Contact photo retrieved: ${contactPhoto.length} bytes")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error retrieving contact photo: ${e.message}")
                }
            }

            // Create call event with all details - strict validation, no fallback values
            val callEvent = CallEvent(
                eventId = UUID.randomUUID().toString(),
                deviceId = "",
                timestamp = System.currentTimeMillis(),
                direction = direction,
                state = state,
                number = displayNumber,
                normalizedNumber = normalizedNumber?.takeIf { it.isNotEmpty() },  // Only if non-empty
                contactName = contactName?.takeIf { it.isNotEmpty() },  // Only if non-empty
                contactPhoto = contactPhoto?.takeIf { it.isNotEmpty() },  // Only if non-empty
                simSlot = null,
                callLogId = null,
                durationSec = null
            )

            // Log the event details for debugging
            Log.d(TAG, "CallEvent details:")
            Log.d(TAG, "  - number: ${callEvent.number}")
            Log.d(TAG, "  - normalizedNumber: ${callEvent.normalizedNumber ?: "null"}")
            Log.d(TAG, "  - contactName: ${callEvent.contactName ?: "null"}")
            Log.d(TAG, "  - contactPhoto: ${if (callEvent.contactPhoto != null) "${callEvent.contactPhoto.length} bytes" else "null"}")

            // Send to Mac via WebSocket
            val gson = Gson()
            val eventJson = gson.toJson(callEvent)
            
            val json = JSONObject()
            json.put("type", "call_event")
            json.put("data", JSONObject(eventJson))

            val messageStr = json.toString()
            Log.d(TAG, "Sending complete call event to Mac (event_size=${messageStr.length} bytes): state=$state, number=$displayNumber")

            val sent = WebSocketUtil.sendMessage(messageStr)
            if (sent) {
                Log.d(TAG, "✅ Successfully sent call_event: state=$state, direction=$direction, number=$displayNumber, contact=$contactName, normalized=$normalizedNumber, photo=${if (contactPhoto != null) "yes" else "no"}")
            } else {
                Log.w(TAG, "❌ Failed to send call_event - WebSocket not connected. Will retry on next broadcast.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending call event", e)
            e.printStackTrace()
        }
    }
}

