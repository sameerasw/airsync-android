package com.sameerasw.airsync.models

data class CallLogEntry(
    val id: String,
    val number: String,
    val contactName: String?,
    val type: Int, // 1 = incoming, 2 = outgoing, 3 = missed
    val date: Long,
    val duration: Long, // in seconds
    val isRead: Boolean
)

data class OngoingCall(
    val id: String,
    val number: String,
    val contactName: String?,
    val state: CallState,
    val startTime: Long,
    val isIncoming: Boolean
)

enum class CallState {
    RINGING,
    ACTIVE,
    HELD,
    DISCONNECTED
}
