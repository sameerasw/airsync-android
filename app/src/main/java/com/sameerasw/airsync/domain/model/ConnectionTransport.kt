package com.sameerasw.airsync.domain.model

enum class ConnectionTransport {
    LOCAL,
    EXTENDED,
    UNKNOWN;

    companion object {
        fun fromIp(ipAddress: String?): ConnectionTransport {
            val ip = ipAddress?.trim().orEmpty()
            if (ip.isEmpty()) return UNKNOWN

            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("127.") || ip.startsWith("169.254.")) {
                return LOCAL
            }

            if (ip.startsWith("172.")) {
                val parts = ip.split(".")
                val secondOctet = parts.getOrNull(1)?.toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return LOCAL
                }
            }

            if (ip.startsWith("100.")) {
                return EXTENDED
            }

            return EXTENDED
        }
    }
}
