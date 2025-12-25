package com.sameerasw.airsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.sameerasw.airsync.models.SmsMessage
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            return
        }

        for (smsMessage in messages) {
            val address = smsMessage.originatingAddress ?: continue
            val body = smsMessage.messageBody ?: continue
            val timestamp = smsMessage.timestampMillis

            Log.d(TAG, "SMS received from $address")

            // Get contact name
            val contactName = getContactName(context, address)

            // Create SMS message object
            val message = SmsMessage(
                id = timestamp.toString(),
                threadId = "", // Will be populated when queried from database
                address = address,
                body = body,
                date = timestamp,
                type = 1, // Received
                read = false,
                contactName = contactName
            )

            // Send to Mac
            sendSmsNotification(context, message)
        }
    }

    private fun sendSmsNotification(context: Context, message: SmsMessage) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JsonUtil.createSmsNotificationJson(message)
                WebSocketUtil.sendMessage(json)
                Log.d(TAG, "SMS notification sent")
                
                // Also sync updated SMS threads to macOS
                kotlinx.coroutines.delay(500) // Wait for SMS to be written to database
                com.sameerasw.airsync.utils.SyncManager.syncDataToMac(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending SMS notification", e)
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
