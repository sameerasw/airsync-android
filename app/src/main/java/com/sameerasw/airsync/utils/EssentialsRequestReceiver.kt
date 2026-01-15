package com.sameerasw.airsync.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class EssentialsRequestReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "EssentialsReqReceiver"
        const val ACTION_REQUEST_MAC_BATTERY = "com.sameerasw.airsync.action.REQUEST_MAC_BATTERY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REQUEST_MAC_BATTERY) {
            Log.d(TAG, "Received Mac battery status request from Essentials")
            MacDeviceStatusManager.broadcastCurrentStatus(context)
        }
    }
}
