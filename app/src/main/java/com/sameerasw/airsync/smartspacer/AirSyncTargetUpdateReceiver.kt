package com.sameerasw.airsync.smartspacer

import android.content.Context
import com.kieronquinn.app.smartspacer.sdk.receivers.SmartspacerTargetUpdateReceiver

class AirSyncTargetUpdateReceiver : SmartspacerTargetUpdateReceiver() {

    override fun onRequestSmartspaceTargetUpdate(
        context: Context,
        requestTargets: List<RequestTarget>
    ) {
        // Notify all AirSync device targets to update
        AirSyncDeviceTarget.notifyChange(context)
    }
}
