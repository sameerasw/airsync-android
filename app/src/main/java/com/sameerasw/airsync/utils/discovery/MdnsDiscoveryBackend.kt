@file:Suppress("DEPRECATION")
package com.sameerasw.airsync.utils.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.DeviceInfoUtil
import com.sameerasw.airsync.utils.DiscoveredDevice
import com.sameerasw.airsync.utils.DiscoveryMode
import com.sameerasw.airsync.utils.PermissionUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class MdnsDiscoveryBackend : DiscoveryBackend {
    companion object {
        private const val TAG = "MdnsDiscoveryBackend"
        private const val SERVICE_TYPE = "_airsync._tcp."
    }

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val serviceNameToId = ConcurrentHashMap<String, String>()
    private val resolveMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false
    private var currentMode = DiscoveryMode.ACTIVE

    override fun start(context: Context) {
        if (!PermissionUtil.isLocalNetworkPermissionGranted(context)) {
            Log.d(TAG, "Skipping mDNS Discovery Backend start: local network permission not granted")
            return
        }
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Starting mDNS Discovery Backend")

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        serviceNameToId.clear()
        _discoveredDevices.value = emptyList()

        startBrowsing()
        if (android.os.Build.VERSION.SDK_INT >= 32 || currentMode == DiscoveryMode.ACTIVE) {
            registerService(context)
        }
    }

    override fun stop(context: Context) {
        Log.d(TAG, "Stopping mDNS Discovery Backend")
        isRunning = false
        stopBrowsing()
        unregisterService()
        nsdManager = null
        serviceNameToId.clear()
        _discoveredDevices.value = emptyList()
    }

    override fun setMode(mode: DiscoveryMode) {
        currentMode = mode
    }

    fun setModeWithContext(context: Context, mode: DiscoveryMode) {
        if (currentMode == mode) return
        currentMode = mode
        if (isRunning) {
            if (android.os.Build.VERSION.SDK_INT >= 32) {
                registerService(context)
            } else {
                if (mode == DiscoveryMode.PASSIVE) {
                    unregisterService()
                } else {
                    registerService(context)
                }
            }
        }
    }

    override fun broadcastPresence(context: Context) {
        // mDNS is continuous/passive, no-op for manual burst triggers unless we want to re-register
    }

    private fun registerService(context: Context) {
        val manager = nsdManager ?: return
        if (registrationListener != null) return

        val ds = DataStoreManager.getInstance(context)
        val customName = try {
            runBlocking { ds.getDeviceName().first() }
        } catch (e: Exception) {
            ""
        }
        val deviceName = if (customName.isNotBlank()) customName else android.os.Build.MODEL
        val deviceId = DeviceInfoUtil.getDeviceId(context)

        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = "AirSync-$deviceName"
            port = 8888 // Wakeup service port
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
            setAttribute("port", "8888")
            setAttribute("ver", "1")
            setAttribute("type", "android")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Successfully registered mDNS service: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS registration failed for ${info.serviceName}: $errorCode")
                registrationListener = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Successfully unregistered mDNS service: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed: $errorCode")
                registrationListener = null
            }
        }

        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering mDNS service: ${e.message}")
            registrationListener = null
        }
    }

    private fun unregisterService() {
        val manager = nsdManager ?: return
        val listener = registrationListener ?: return
        try {
            manager.unregisterService(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering mDNS service: ${e.message}")
        }
        registrationListener = null
    }

    private fun startBrowsing() {
        val manager = nsdManager ?: return
        if (discoveryListener != null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Discovery started for: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("airsync")) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                val id = serviceNameToId.remove(serviceInfo.serviceName)
                if (id != null) {
                    val currentList = _discoveredDevices.value.filter { it.id != id }
                    _discoveredDevices.value = currentList
                }
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "mDNS Discovery stopped: $regType")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Discovery start failed: $errorCode")
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Discovery stop failed: $errorCode")
                discoveryListener = null
            }
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service discovery: ${e.message}")
            discoveryListener = null
        }
    }

    private fun stopBrowsing() {
        val manager = nsdManager ?: return
        val listener = discoveryListener ?: return
        try {
            manager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service discovery: ${e.message}")
        }
        discoveryListener = null
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        scope.launch {
            resolveMutex.withLock {
                val resolved = withTimeoutOrNull(5000L) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                        val resolveListener = object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.e(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                                if (continuation.isActive) continuation.resume(null)
                            }

                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName}")
                                if (continuation.isActive) continuation.resume(resolvedInfo)
                            }
                        }
                        try {
                            manager.resolveService(serviceInfo, resolveListener)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting resolve: ${e.message}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                if (resolved != null) {
                    handleResolvedService(resolved)
                } else {
                    Log.w(TAG, "Resolve timed out for ${serviceInfo.serviceName}")
                }
            }
        }
    }

    private fun handleResolvedService(resolvedInfo: NsdServiceInfo) {
        try {
            val txtRecords = resolvedInfo.attributes
            val type = txtRecords["type"]?.let { String(it, Charsets.UTF_8) } ?: "mac"
            if (type != "mac") {
                return
            }
            val id = txtRecords["id"]?.let { String(it, Charsets.UTF_8) } ?: resolvedInfo.serviceName
            val name = txtRecords["name"]?.let { String(it, Charsets.UTF_8) } ?: resolvedInfo.serviceName
            val port = txtRecords["port"]?.let { String(it, Charsets.UTF_8).toIntOrNull() } ?: resolvedInfo.port

            val hostAddress = resolvedInfo.host?.hostAddress ?: return
            
            serviceNameToId[resolvedInfo.serviceName] = id

            val device = DiscoveredDevice(
                id = id,
                name = name,
                ips = setOf(hostAddress),
                port = port,
                type = "mac", // Mac side is server
                lastSeen = System.currentTimeMillis(),
                discoverySource = DiscoverySource.MDNS
            )

            updateDeviceList(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling resolved service: ${e.message}")
        }
    }

    private fun updateDeviceList(device: DiscoveredDevice) {
        val currentList = _discoveredDevices.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == device.id }
        if (index != -1) {
            val existing = currentList[index]
            val updatedIps = existing.ips.toMutableSet()
            updatedIps.addAll(device.ips)
            currentList[index] = existing.copy(
                ips = updatedIps,
                lastSeen = System.currentTimeMillis(),
                name = device.name,
                discoverySource = DiscoverySource.MDNS
            )
        } else {
            currentList.add(device)
        }
        _discoveredDevices.value = currentList
    }
}
