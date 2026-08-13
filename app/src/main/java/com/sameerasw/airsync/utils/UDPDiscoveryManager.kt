package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator
import com.sameerasw.airsync.utils.discovery.DiscoverySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val ips: Set<String>,
    val port: Int,
    val type: String, // "mac" or "android"
    val lastSeen: Long = System.currentTimeMillis(),
    val discoverySource: DiscoverySource = DiscoverySource.UDP
) {
    // check if it has a local IP (non-Tailscale)
    fun hasLocalIp(): Boolean = ips.any { !it.startsWith("100.") }

    // check if it has a Tailscale IP
    fun hasTailscaleIp(): Boolean = ips.any { it.startsWith("100.") }

    // Best IP for connection
    fun getBestIp(): String = ips.find { !it.startsWith("100.") } ?: ips.firstOrNull() ?: ""
}

enum class DiscoveryMode {
    ACTIVE,  // Continuous broadcasting (Foreground)
    PASSIVE  // Listening only (Background)
}

@Deprecated("Use DiscoveryOrchestrator instead", ReplaceWith("DiscoveryOrchestrator", "com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator"))
object UDPDiscoveryManager {
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
        get() = DiscoveryOrchestrator.discoveredDevices

    fun start(context: Context, discoveryEnabled: Boolean = true) {
        DiscoveryOrchestrator.start(context, discoveryEnabled)
    }

    fun stop(context: Context? = null) {
        context?.let { DiscoveryOrchestrator.stop(it) }
    }

    fun setDiscoveryMode(context: Context, mode: DiscoveryMode) {
        DiscoveryOrchestrator.setDiscoveryMode(context, mode)
    }

    fun burstBroadcast(context: Context, durationMs: Long = 30000) {
        DiscoveryOrchestrator.burstBroadcast(context, durationMs)
    }

    fun refreshSocket() {
        DiscoveryOrchestrator.refreshSocket()
    }

    fun setDiscoveryEnabled(context: Context, enabled: Boolean) {
        DiscoveryOrchestrator.setDiscoveryEnabled(context, enabled)
    }
}
