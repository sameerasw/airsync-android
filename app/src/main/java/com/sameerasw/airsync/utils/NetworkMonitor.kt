package com.sameerasw.airsync.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.sameerasw.airsync.domain.model.NetworkInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object NetworkMonitor {
    private const val TAG = "NetworkMonitor"

    /**
     * Monitor network changes and emit new network information
     */
    fun observeNetworkChanges(context: Context): Flow<NetworkInfo> = callbackFlow {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        var lastNetworkInfo: NetworkInfo? = null

        fun getCurrentNetworkInfo(): NetworkInfo {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

            val isConnected = networkCapabilities != null
            val isWifi =
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val ipAddress = if (isWifi) {
                DeviceInfoUtil.getWifiIpAddress(context)
            } else {
                DeviceInfoUtil.getLocalIpAddress()
            }

            return NetworkInfo(isConnected, isWifi, ipAddress)
        }

        fun sendIfChanged() {
            val info = getCurrentNetworkInfo()
            if (info != lastNetworkInfo) {
                lastNetworkInfo = info
                trySend(info)
            }
        }

        // Send initial state
        sendIfChanged()

        // Use NetworkCallback for newer versions
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                sendIfChanged()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                sendIfChanged()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val info = getCurrentNetworkInfo()
                if (info != lastNetworkInfo) {
                    Log.d(TAG, "Network capabilities changed and network info updated: $network")
                    lastNetworkInfo = info
                    trySend(info)
                }
            }
        }

        val request = NetworkRequest.Builder().build()

        connectivityManager.registerNetworkCallback(request, networkCallback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }.distinctUntilChanged()

}
