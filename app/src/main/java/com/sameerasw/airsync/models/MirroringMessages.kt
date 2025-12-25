
package com.sameerasw.airsync.models

import com.google.gson.annotations.SerializedName

data class MirrorStartRequest(
    @SerializedName("type") val type: String = "mirrorStart",
    @SerializedName("data") val data: MirrorStartData
)

data class MirrorStartData(
    @SerializedName("fps") val fps: Int? = null,
    @SerializedName("quality") val quality: Float? = null,
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null
)

data class MirrorFrame(
    @SerializedName("type") val type: String = "mirrorFrame",
    @SerializedName("data") val data: MirrorFrameData
)

data class MirrorFrameData(
    @SerializedName("image") val image: String,
    @SerializedName("format") val format: String,
    @SerializedName("ts") val timestamp: Long? = null,
    @SerializedName("seq") val sequence: Int? = null
)

data class MirrorStopRequest(
    @SerializedName("type") val type: String = "mirrorStop",
    @SerializedName("data") val data: Map<String, Any> = emptyMap()
)
