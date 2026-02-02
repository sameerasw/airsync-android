package com.sameerasw.airsync.service

import android.app.PendingIntent
import android.content.ClipboardManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.ClipboardUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

@RequiresApi(Build.VERSION_CODES.N)
class ClipboardTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        private const val TAG = "ClipboardTileService"
    }

    // Keep a reference to unregister properly
    private val connectionStatusListener: (Boolean) -> Unit = { status ->
        serviceScope.launch {
            updateTileState(status)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Register for connection status updates
        WebSocketUtil.registerConnectionStatusListener(connectionStatusListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketUtil.unregisterConnectionStatusListener(connectionStatusListener)
        serviceScope.cancel()
    }

    override fun onStartListening() {
        super.onStartListening()
        serviceScope.launch {
            val isConnected = WebSocketUtil.isConnected()
            updateTileState(isConnected)
        }
    }

    override fun onClick() {
        super.onClick()
        
        serviceScope.launch {
            val isConnected = WebSocketUtil.isConnected()
            if (isConnected) {
                try {
                    val intent = android.content.Intent(
                        this@ClipboardTileService, 
                        com.sameerasw.airsync.presentation.ui.activities.ClipboardActionActivity::class.java
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                         val pendingIntent = PendingIntent.getActivity(
                             this@ClipboardTileService,
                             0,
                             intent,
                             PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                         )
                         startActivityAndCollapse(pendingIntent)
                    } else {
                         @Suppress("DEPRECATION")
                         startActivityAndCollapse(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching clipboard activity", e)
                    Toast.makeText(this@ClipboardTileService, "Error launching action", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@ClipboardTileService, "Not connected to any device", Toast.LENGTH_SHORT).show()
                updateTileState(false)
            }
        }
    }

    // Removed direct sendClipboard method as it is now handled by the Activity

    private fun updateTileState(isConnected: Boolean) {
        try {
            qsTile?.apply {
                state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                val labelText = if (isConnected) "Send Clipboard" else "Not Connected"
                label = labelText
                
                // Using existing icon as placeholder
                // icon = Icon.createWithResource(this@ClipboardTileService, R.drawable.ic_laptop_24)
                
                updateTile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tile state", e)
        }
    }
}
