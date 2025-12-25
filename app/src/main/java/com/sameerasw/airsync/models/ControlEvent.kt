package com.sameerasw.airsync.models

data class ControlEvent(
    val type: String,
    val x: Float? = null,
    val y: Float? = null,
    val keyCode: Int? = null
)
