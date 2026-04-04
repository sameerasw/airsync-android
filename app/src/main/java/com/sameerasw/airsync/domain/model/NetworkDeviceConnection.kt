package com.sameerasw.airsync.domain.model

data class NetworkDeviceConnection(
    val deviceName: String,
    val networkConnections: Map<String, String>,
    val candidateIps: List<String> = emptyList(),
    val port: String,
    val lastConnected: Long,
    val isPlus: Boolean,
    val symmetricKey: String? = null,
    // New: device model and type information reported by the desktop
    val model: String? = null,
    val deviceType: String? = null
) {
    companion object {
        fun isTailscaleIp(ip: String): Boolean = ip.startsWith("100.")

        fun isPreferredLanIp(ip: String): Boolean {
            if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
            if (!ip.startsWith("172.")) return false

            val parts = ip.split(".")
            if (parts.size < 2) return false
            val secondOctet = parts[1].toIntOrNull() ?: return false
            return secondOctet in 16..31
        }

        fun rankIps(ips: Iterable<String>): List<String> {
            return ips
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedWith(
                    compareBy<String>(
                        { !isPreferredLanIp(it) },
                        { isTailscaleIp(it) },
                        { it }
                    )
                )
        }
    }

    // get client IP for current network
    fun getClientIpForNetwork(ourIp: String): String? {
        return networkConnections[ourIp]
    }

    fun getOrderedReconnectCandidates(ourIp: String?): List<String> {
        val prioritized = mutableListOf<String>()

        if (!ourIp.isNullOrBlank()) {
            networkConnections[ourIp]?.let { prioritized.add(it) }
        }

        prioritized.addAll(networkConnections.values)
        prioritized.addAll(candidateIps)

        return rankIps(prioritized)
    }

    // create ConnectedDevice for current network
    fun toConnectedDevice(ourIp: String): ConnectedDevice? {
        val clientIp = getClientIpForNetwork(ourIp)
        return if (clientIp != null) {
            ConnectedDevice(
                name = deviceName,
                ipAddress = clientIp,
                port = this.port,
                lastConnected = this.lastConnected,
                isPlus = this.isPlus,
                symmetricKey = this.symmetricKey,
                model = this.model,
                deviceType = this.deviceType
            )
        } else null
    }
}
