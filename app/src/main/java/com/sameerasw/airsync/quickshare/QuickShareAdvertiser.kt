package com.sameerasw.airsync.quickshare

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Handles mDNS advertisement for Quick Share.
 * Advertisement format synchronized with NearDrop/Nearby Connections.
 */
class QuickShareAdvertiser(private val context: Context) {
    companion object {
        private const val TAG = "QuickShareAdvertiser"
        private const val SERVICE_TYPE = "_FC9F5ED42C8A._tcp."
        private const val SERVICE_ID_HASH = "fM5e" // Base64 of 0xFC, 0x9F, 0x5E (after PCP 0x23 and 4-byte ID)
        // Actually, let's calculate it properly.
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val endpointId: String by lazy {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        (1..4).map { alphabet.random() }.joinToString("")
    }

    fun startAdvertising(deviceName: String, port: Int) {
        stopAdvertising()

        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = generateServiceName()
            setPort(port)
            
            // TXT record 'n' contains endpoint info
            val endpointInfo = serializeEndpointInfo(deviceName)
            val endpointInfoBase64 = Base64.encodeToString(endpointInfo, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            setAttribute("n", endpointInfoBase64)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service", e)
        }
    }

    fun stopAdvertising() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        registrationListener = null
    }

    private fun generateServiceName(): String {
        // format: [PCP: 0x23][4-byte ID][Service ID Hash: 0xFC, 0x9F, 0x5E][Reserved: 0, 0]
        val bytes = ByteArray(10)
        bytes[0] = 0x23.toByte()
        System.arraycopy(endpointId.toByteArray(StandardCharsets.US_ASCII), 0, bytes, 1, 4)
        bytes[5] = 0xFC.toByte()
        bytes[6] = 0x9F.toByte()
        bytes[7] = 0x5E.toByte()
        bytes[8] = 0
        bytes[9] = 0
        
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun serializeEndpointInfo(deviceName: String): ByteArray {
        val nameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
        val nameLen = Math.min(nameBytes.size, 255)
        
        // 1 byte: (deviceType << 1) | Visibility(0) | Version(0)
        // Device types: phone=1, tablet=2, computer=3. We'll use phone=1.
        val deviceType = 1
        val firstByte = (deviceType shl 1).toByte()
        
        val bytes = ByteArray(1 + 16 + 1 + nameLen)
        bytes[0] = firstByte
        // 16 random bytes
        val random = java.util.Random()
        val randomBytes = ByteArray(16)
        random.nextBytes(randomBytes)
        System.arraycopy(randomBytes, 0, bytes, 1, 16)
        
        bytes[17] = nameLen.toByte()
        System.arraycopy(nameBytes, 0, bytes, 18, nameLen)
        
        return bytes
    }
}
