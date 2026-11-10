package com.sameerasw.airsync.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.service.AirSyncService
import com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Exported BroadcastReceiver allowing external automation and companion apps
 * to pause or resume AirSync background sync.
 */
class SyncControlReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SyncControlReceiver"
        const val ACTION_PAUSE_SYNC = "com.sameerasw.airsync.action.PAUSE_SYNC"
        const val ACTION_RESUME_SYNC = "com.sameerasw.airsync.action.RESUME_SYNC"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_PAUSE_SYNC && action != ACTION_RESUME_SYNC) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_PAUSE_SYNC -> {
                        Log.d(TAG, "Received PAUSE_SYNC broadcast")
                        pauseSync(appContext)
                    }
                    ACTION_RESUME_SYNC -> {
                        Log.d(TAG, "Received RESUME_SYNC broadcast")
                        resumeSync(appContext)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun pauseSync(context: Context) {
        val dataStoreManager = DataStoreManager.getInstance(context)
        dataStoreManager.setAppPaused(true)
        WebSocketUtil.stopAutoReconnect(context)
        WebSocketUtil.disconnect(context, manual = true)
        DiscoveryOrchestrator.stop(context)
        if (AirSyncService.isRunning()) {
            AirSyncService.stop(context)
        }
        ShortcutUtil.refreshShortcuts(context, false)
        Log.d(TAG, "AirSync sync paused successfully")
    }

    private suspend fun resumeSync(context: Context) {
        val dataStoreManager = DataStoreManager.getInstance(context)
        dataStoreManager.setAppPaused(false)
        dataStoreManager.setUserManuallyDisconnected(false)
        val isDiscovery = dataStoreManager.getDeviceDiscoveryEnabled().first()
        DiscoveryOrchestrator.start(context, isDiscovery)
        AirSyncService.startScanning(context)
        WebSocketUtil.requestAutoReconnect(context)
        ShortcutUtil.refreshShortcuts(context, false)
        Log.d(TAG, "AirSync sync resumed successfully")
    }
}
