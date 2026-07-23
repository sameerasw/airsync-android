package com.sameerasw.airsync.utils.discovery

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.DiscoveredDevice
import com.sameerasw.airsync.utils.DiscoveryMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object DiscoveryOrchestrator {
    private const val TAG = "DiscoveryOrchestrator"

    private val mdnsBackend = MdnsDiscoveryBackend()
    private val udpBackend = UdpDiscoveryBackend()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var mergeJob: Job? = null
    private var burstJob: Job? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isDiscoveryEnabled = true

    @Volatile
    private var currentMode = DiscoveryMode.ACTIVE

    fun start(context: Context, discoveryEnabled: Boolean = true) {
        isDiscoveryEnabled = discoveryEnabled
        if (isRunning) {
            updateBackends(context)
            return
        }

        isRunning = true
        Log.d(TAG, "Starting Discovery Orchestrator (Enabled: $isDiscoveryEnabled, Mode: $currentMode)")

        startMergeJob()
        updateBackends(context)
    }

    fun stop(context: Context) {
        Log.d(TAG, "Stopping Discovery Orchestrator")
        isRunning = false
        mergeJob?.cancel()
        mergeJob = null
        burstJob?.cancel()
        burstJob = null

        mdnsBackend.stop(context)
        udpBackend.stop(context)
        _discoveredDevices.value = emptyList()
    }

    fun setDiscoveryMode(context: Context, mode: DiscoveryMode) {
        if (currentMode == mode) return
        Log.d(TAG, "Orchestrator changing mode to: $mode")
        currentMode = mode
        if (isRunning) {
            updateBackends(context)
        }
    }

    fun burstBroadcast(context: Context, durationMs: Long = 30000) {
        if (!isDiscoveryEnabled || !isRunning) {
            Log.d(TAG, "Orchestrator skipping burst broadcast: not enabled/running")
            return
        }

        Log.d(TAG, "Starting orchestrator burst broadcast for ${durationMs}ms")
        burstJob?.cancel()
        burstJob = CoroutineScope(Dispatchers.IO).launch {
            val endTime = System.currentTimeMillis() + durationMs
            while (isRunning && System.currentTimeMillis() < endTime) {
                udpBackend.broadcastPresence(context)
                delay(3000)
            }
            Log.d(TAG, "Orchestrator burst broadcast completed")
        }
    }

    fun refreshSocket() {
        if (isRunning) {
            udpBackend.refreshSocket()
        }
    }

    fun setDiscoveryEnabled(context: Context, enabled: Boolean) {
        if (isDiscoveryEnabled == enabled) return
        Log.d(TAG, "Orchestrator discovery enabled changed to: $enabled")
        isDiscoveryEnabled = enabled
        updateBackends(context)
    }

    private fun startMergeJob() {
        mergeJob?.cancel()
        mergeJob = CoroutineScope(Dispatchers.IO).launch {
            combine(mdnsBackend.discoveredDevices, udpBackend.discoveredDevices) { mdnsList, udpList ->
                val mergedMap = mutableMapOf<String, DiscoveredDevice>()
                
                // Merge UDP devices first
                for (device in udpList) {
                    mergedMap[device.id] = device
                }

                // Merge mDNS devices second, preferring mDNS details
                for (device in mdnsList) {
                    val existing = mergedMap[device.id]
                    if (existing != null) {
                        mergedMap[device.id] = existing.copy(
                            ips = existing.ips + device.ips,
                            lastSeen = maxOf(existing.lastSeen, device.lastSeen),
                            name = device.name,
                            discoverySource = DiscoverySource.MDNS
                        )
                    } else {
                        mergedMap[device.id] = device
                    }
                }
                mergedMap.values.toList()
            }.collect {
                _discoveredDevices.value = it
            }
        }
    }

    private fun updateBackends(context: Context) {
        if (!isDiscoveryEnabled) {
            Log.d(TAG, "Discovery disabled, stopping all backends")
            mdnsBackend.stop(context)
            udpBackend.stop(context)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val ds = DataStoreManager.getInstance(context)
            val expandNetworkingEnabled = try {
                ds.getExpandNetworkingEnabled().first()
            } catch (e: Exception) {
                true
            }

            withContext(Dispatchers.Main) {
                // Configure backend modes
                mdnsBackend.setModeWithContext(context, currentMode)
                udpBackend.setModeWithContext(context, currentMode)

                // API 32+ gets the clean mDNS flow as primary
                if (android.os.Build.VERSION.SDK_INT >= 32) {
                    mdnsBackend.start(context)
                } else {
                    // Older APIs run mDNS passively for discovery only (no advertiser registration)
                    mdnsBackend.setModeWithContext(context, DiscoveryMode.PASSIVE)
                    mdnsBackend.start(context)
                }

                // UDP runs if expand networking is enabled
                if (expandNetworkingEnabled) {
                    udpBackend.start(context)
                } else {
                    udpBackend.stop(context)
                }
            }
        }
    }
}
