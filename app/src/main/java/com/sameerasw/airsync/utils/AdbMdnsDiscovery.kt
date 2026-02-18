package com.sameerasw.airsync.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Discovers active ADB wireless ports on the network using mDNS.
 * This is equivalent to the "adb mdns services" command output.
 */
class AdbMdnsDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val TAG = "AdbMdnsDiscovery"
    private val discoveredServices = mutableListOf<AdbServiceInfo>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    data class AdbServiceInfo(
        val serviceName: String,
        val hostAddress: String,
        val port: Int
    )

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Discovery Started for service type: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                // Resolve the service to get host and port information
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(
                            TAG,
                            "Resolve failed for ${serviceInfo.serviceName}: Error code $errorCode"
                        )
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        val host = resolvedInfo.host
                        val port = resolvedInfo.port
                        val hostAddress = host?.hostAddress ?: "unknown"
                        Log.i(
                            TAG,
                            "ADB Device Found: $hostAddress:$port (${resolvedInfo.serviceName})"
                        )

                        // Store the discovered service
                        discoveredServices.add(
                            AdbServiceInfo(
                                serviceName = resolvedInfo.serviceName,
                                hostAddress = hostAddress,
                                port = port
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                // Remove from discovered services
                discoveredServices.removeAll { it.serviceName == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "Discovery stopped for service type: $regType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: Error code $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: Error code $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }
    }

    /**
     * Start mDNS discovery for ADB wireless services (_adb-tls-connect._tcp)
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.w(TAG, "Discovery already in progress")
            return
        }
        try {
            discoveredServices.clear()
            discoveryListener = createDiscoveryListener()
            // _adb-tls-connect._tcp is the standard for Android 11+ Wireless ADB
            nsdManager.discoverServices(
                "_adb-tls-connect._tcp.",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener!!
            )
            isDiscovering = true
            Log.d(TAG, "Started ADB wireless discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    /**
     * Stop mDNS discovery
     */
    fun stopDiscovery() {
        if (!isDiscovering || discoveryListener == null) {
            return
        }
        try {
            nsdManager.stopServiceDiscovery(discoveryListener!!)
            isDiscovering = false
            Log.d(TAG, "Stopped ADB wireless discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
    }

    /**
     * Get the list of discovered ADB services
     */
    fun getDiscoveredServices(): List<AdbServiceInfo> {
        return discoveredServices.toList()
    }

    /**
     * Log all discovered ADB ports to logcat
     */
    fun logDiscoveredPorts() {
        if (discoveredServices.isEmpty()) {
            Log.i(TAG, "No ADB wireless services discovered")
        } else {
            Log.i(TAG, "=== Discovered ADB Wireless Services ===")
            discoveredServices.forEach { service ->
                Log.i(
                    TAG,
                    "Service: ${service.serviceName} | Host: ${service.hostAddress} | Port: ${service.port}"
                )
            }
            Log.i(TAG, "=========================================")
        }
    }
}

