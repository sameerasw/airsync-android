package com.sameerasw.airsync.utils

import android.content.Context
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject

object MacRemoteActions {
    const val LOCK_SCREEN = "lock_screen"
    const val SCREENSAVER = "screensaver"

    fun sendRemoteAction(action: String, value: Any? = null): Boolean {
        val data = JSONObject().put("action", action)
        if (value != null) data.put("value", value)
        val json = JSONObject().put("type", "remoteControl").put("data", data)
        return WebSocketUtil.sendMessage(json.toString())
    }

    fun disconnect(context: Context) {
        WebSocketUtil.disconnect(context, manual = true)
    }

    suspend fun reconnect(context: Context) {
        val store = DataStoreManager.getInstance(context)
        store.setUserManuallyDisconnected(false)
        val last = store.getLastConnectedDevice().first() ?: return
        WebSocketUtil.connect(
            context = context,
            ipAddress = last.ipAddress,
            port = last.port.toIntOrNull() ?: 6996,
            symmetricKey = last.symmetricKey,
            manualAttempt = true,
        )
    }
}
