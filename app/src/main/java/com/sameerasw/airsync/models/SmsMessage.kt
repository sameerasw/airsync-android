package com.sameerasw.airsync.models

data class SmsMessage(
    val id: String,
    val threadId: String,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1 = received, 2 = sent
    val read: Boolean,
    val contactName: String? = null
)

data class SmsThread(
    val threadId: String,
    val address: String,
    val contactName: String?,
    val messageCount: Int,
    val snippet: String,
    val date: Long,
    val unreadCount: Int
)
