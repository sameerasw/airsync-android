package com.sameerasw.airsync.models

import com.google.gson.annotations.SerializedName

data class MirrorResponse(
    @SerializedName("type") val type: String,
    @SerializedName("data") val data: MirrorResponseData
)

data class MirrorResponseData(
    @SerializedName("status") val status: String,
    @SerializedName("mode") val mode: String?,
    @SerializedName("package") val pkg: String?,
    @SerializedName("transport") val transport: String?,
    @SerializedName("wsUrl") val wsUrl: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("ok") val ok: Boolean = false // For simple acks
)
