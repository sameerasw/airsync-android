package com.sameerasw.airsync.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.models.CallLogEntry

object CallLogUtil {
    private const val TAG = "CallLogUtil"

    fun hasPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun getCallLogs(context: Context, limit: Int = 100): List<CallLogEntry> {
        if (!hasPermissions(context)) {
            Log.w(TAG, "Call log permissions not granted")
            return emptyList()
        }

        val callLogs = mutableListOf<CallLogEntry>()
        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.IS_READ
        )

        try {
            // Don't use LIMIT in sort order - it causes "Invalid token LIMIT" error on some devices
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    try {
                        val id = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls._ID))
                        val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                        val date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
                        val duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                        val isRead = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.IS_READ)) == 1

                        val contactName = getContactName(context, number)

                        callLogs.add(
                            CallLogEntry(
                                id = id,
                                number = number,
                                contactName = contactName,
                                type = type,
                                date = date,
                                duration = duration,
                                isRead = isRead
                            )
                        )
                        count++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing call log entry", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call logs", e)
        }

        return callLogs
    }

    fun getCallLogsSince(context: Context, sinceTimestamp: Long): List<CallLogEntry> {
        if (!hasPermissions(context)) {
            Log.w(TAG, "Call log permissions not granted")
            return emptyList()
        }

        val callLogs = mutableListOf<CallLogEntry>()
        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.IS_READ
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                "${CallLog.Calls.DATE} > ?",
                arrayOf(sinceTimestamp.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls._ID))
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val isRead = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.IS_READ)) == 1

                    val contactName = getContactName(context, number)

                    callLogs.add(
                        CallLogEntry(
                            id = id,
                            number = number,
                            contactName = contactName,
                            type = type,
                            date = date,
                            duration = duration,
                            isRead = isRead
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call logs since timestamp", e)
        }

        return callLogs
    }

    fun markAsRead(context: Context, callId: String): Boolean {
        if (!hasPermissions(context)) {
            return false
        }

        return try {
            val values = android.content.ContentValues().apply {
                put(CallLog.Calls.IS_READ, 1)
            }

            context.contentResolver.update(
                CallLog.Calls.CONTENT_URI,
                values,
                "${CallLog.Calls._ID} = ?",
                arrayOf(callId)
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error marking call log as read", e)
            false
        }
    }

    fun getCallTypeString(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.MISSED_TYPE -> "missed"
            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
            CallLog.Calls.REJECTED_TYPE -> "rejected"
            CallLog.Calls.BLOCKED_TYPE -> "blocked"
            else -> "unknown"
        }
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
