package com.sameerasw.airsync.island

data class AirSyncIslandState(
    val enabled: Boolean = true,
    val connected: Boolean = false,
    val hasDevice: Boolean = false,
    val name: String = "",
    val model: String? = null,
    val deviceType: String? = null,
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val showBattery: Boolean = true,
)
