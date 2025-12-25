package com.sameerasw.airsync.utils

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.models.SmsMessage
import com.sameerasw.airsync.models.SmsThread

object SmsUtil {
    private const val TAG = "SmsUtil"

    fun hasPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun getAllThreads(context: Context, limit: Int = 50): List<SmsThread> {
        if (!hasPermissions(context)) {
            Log.w(TAG, "SMS permissions not granted")
            return emptyList()
        }

        val threads = mutableListOf<SmsThread>()
        
        // Query messages directly and group by thread_id
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )

        try {
            // Get all messages sorted by date
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val seenThreads = mutableSetOf<String>()
                
                while (cursor.moveToNext() && seenThreads.size < limit) {
                    try {
                        val threadId = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                        
                        // Skip if we've already processed this thread
                        if (threadId in seenThreads) continue
                        seenThreads.add(threadId)
                        
                        val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                        val snippet = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                        val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
                        
                        val contactName = getContactName(context, address)
                        val messageCount = getThreadMessageCount(context, threadId)
                        val unreadCount = getUnreadCount(context, threadId)

                        threads.add(
                            SmsThread(
                                threadId = threadId,
                                address = address,
                                contactName = contactName,
                                messageCount = messageCount,
                                snippet = snippet,
                                date = date,
                                unreadCount = unreadCount
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing thread", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SMS threads", e)
        }

        return threads
    }
    
    private fun getThreadMessageCount(context: Context, threadId: String): Int {
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(Telephony.Sms._ID)
        
        try {
            context.contentResolver.query(
                uri,
                projection,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId),
                null
            )?.use { cursor ->
                return cursor.count
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting thread message count", e)
        }
        
        return 0
    }

    fun getMessagesInThread(context: Context, threadId: String, limit: Int = 100): List<SmsMessage> {
        if (!hasPermissions(context)) {
            Log.w(TAG, "SMS permissions not granted")
            return emptyList()
        }

        val messages = mutableListOf<SmsMessage>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId),
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
                    val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                    val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    val read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1

                    val contactName = getContactName(context, address)

                    messages.add(
                        SmsMessage(
                            id = id,
                            threadId = threadId,
                            address = address,
                            body = body,
                            date = date,
                            type = type,
                            read = read,
                            contactName = contactName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading messages in thread", e)
        }

        return messages
    }

    fun sendSms(context: Context, address: String, message: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SEND_SMS permission not granted")
            return false
        }

        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            
            if (parts.size == 1) {
                smsManager.sendTextMessage(address, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(address, null, parts, null, null)
            }
            
            Log.d(TAG, "SMS sent to $address")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
            false
        }
    }

    fun markAsRead(context: Context, messageId: String): Boolean {
        if (!hasPermissions(context)) {
            return false
        }

        return try {
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }
            
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId)
            )
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error marking message as read", e)
            false
        }
    }

    private fun getUnreadCount(context: Context, threadId: String): Int {
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(Telephony.Sms._ID)
        
        context.contentResolver.query(
            uri,
            projection,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId),
            null
        )?.use { cursor ->
            return cursor.count
        }
        
        return 0
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

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
