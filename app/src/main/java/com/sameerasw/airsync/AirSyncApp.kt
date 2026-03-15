package com.sameerasw.airsync

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
    private var activityCount = 0

    companion object {
        private var instance: AirSyncApp? = null
        fun isAppForeground(): Boolean = instance?.isForeground() ?: false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initSentry()
        initAirBridge()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount--
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun isForeground(): Boolean = activityCount > 0

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
