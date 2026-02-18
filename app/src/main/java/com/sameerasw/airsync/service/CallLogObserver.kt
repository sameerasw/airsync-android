package com.sameerasw.airsync.service

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.provider.CallLog
import android.util.Log
import com.sameerasw.airsync.domain.model.CallDirection
import com.sameerasw.airsync.domain.model.CallEvent
import com.sameerasw.airsync.domain.model.CallState
import com.sameerasw.airsync.utils.ContactLookupHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ContentObserver that watches the CallLog.Calls content provider for new/changed entries.
 * Queries recently added call logs and emits call event objects for synchronization.
 */
class CallLogObserver(
    handler: Handler,
    private val onNewCallLogs: (List<CallEvent>) -> Unit,
    private val contactLookupHelper: ContactLookupHelper,
    private val deviceId: String,
    private val scope: CoroutineScope
) : ContentObserver(handler) {

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        // Trigger call log query and emit events
        scope.launch {
            onNewCallLogs(emptyList()) // Will be called by the service with queried events
        }
    }

    /**
     * Query recent call logs since a given timestamp
     */
    suspend fun queryRecentCallLogs(
        resolver: ContentResolver,
        sinceMillis: Long
    ): List<CallEvent> {
        return try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NEW,
                "subscription_id"  // For multi-SIM support
            )
            val selection = "${CallLog.Calls.DATE} >= ?"
            val selectionArgs = arrayOf(sinceMillis.toString())

            resolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"  // Most recent first
            )?.use { cursor ->
                val results = mutableListOf<CallEvent>()
                val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val subIdIndex = cursor.getColumnIndex("subscription_id")

                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLong(idIndex)
                        val number = cursor.getString(numberIndex) ?: ""
                        val type = cursor.getInt(typeIndex)
                        val date = cursor.getLong(dateIndex)
                        val duration = cursor.getLong(durationIndex)
                        val simSlot = if (subIdIndex >= 0) {
                            try {
                                cursor.getInt(subIdIndex)
                            } catch (e: Exception) {
                                null
                            }
                        } else null

                        val direction = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING.apiValue
                            CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING.apiValue
                            CallLog.Calls.MISSED_TYPE -> CallDirection.INCOMING.apiValue // Missed calls are incoming
                            else -> CallDirection.INCOMING.apiValue
                        }

                        val state = when (type) {
                            CallLog.Calls.MISSED_TYPE -> CallState.MISSED.name.lowercase()
                            else -> CallState.IDLE.name.lowercase()
                        }

                        // Look up contact name asynchronously
                        val contactName = try {
                            contactLookupHelper.findContactName(number)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error looking up contact for $number", e)
                            null
                        }

                        val callEvent = CallEvent(
                            eventId = UUID.randomUUID().toString(),
                            deviceId = deviceId,
                            timestamp = date,
                            direction = direction,
                            state = state,
                            number = number,
                            normalizedNumber = contactLookupHelper.normalizeNumber(number),
                            contactName = contactName,
                            simSlot = simSlot,
                            callLogId = id,
                            durationSec = duration
                        )
                        results.add(callEvent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing call log entry", e)
                        continue
                    }
                }
                Log.d(TAG, "Found ${results.size} recent call logs")
                results
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading call log", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error querying call logs", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "CallLogObserver"
    }
}

