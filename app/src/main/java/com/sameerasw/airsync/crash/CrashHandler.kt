package com.sameerasw.airsync.crash

import android.content.Context
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

object CrashHandler {
    fun onCrash(context: Context, deviceInfo: String, stackTrace: String) {
        try {
            // Write report to last_crash.log
            val logFile = File(context.filesDir, "last_crash.log")
            logFile.writeText("$deviceInfo\n\n$stackTrace")

            // Read preference from DataStore synchronously
            val dataStoreManager = DataStoreManager.getInstance(context)
            val notifyEnabled = runBlocking {
                dataStoreManager.getNotifyOnCrashEnabled().first()
            }

            if (notifyEnabled) {
                CrashNotificationHelper.postNotification(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
