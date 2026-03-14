package com.sameerasw.airsync

import android.app.Application
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.AirBridgeClient
import com.sameerasw.airsync.utils.WebSocketMessageHandler
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AirSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initSentry()
        initAirBridge()
    }

    private fun initSentry() {
        val dataStoreManager = DataStoreManager.getInstance(this)
        val isEnabled = runBlocking { dataStoreManager.getSentryReportingEnabled().first() }

        if (!isEnabled) return

        SentryAndroid.init(this) { options ->
            options.dsn = "https://cb9b0ead9e88e0818269e773cb662141@o4510996760887296.ingest.de.sentry.io/4511002261389392"
            options.isEnabled = true
        }
    }

    private fun initAirBridge() {
        // Wire message handler: relay messages → existing WebSocket message pipeline
        AirBridgeClient.setMessageHandler { context, message ->
            WebSocketMessageHandler.handleIncomingMessage(context, message)
        }

        // Auto-connect if previously enabled
        CoroutineScope(Dispatchers.IO).launch {
            val ds = DataStoreManager.getInstance(this@AirSyncApp)
            val enabled = ds.getAirBridgeEnabled().first()
            if (enabled) {
                AirBridgeClient.connect(this@AirSyncApp)
            }
        }
    }
}
