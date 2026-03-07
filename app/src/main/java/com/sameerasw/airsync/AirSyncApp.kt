package com.sameerasw.airsync

import android.app.Application
import com.sameerasw.airsync.data.local.DataStoreManager
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AirSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initSentry()
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
}
