package com.sameerasw.airsync.receiver
 
 import android.content.BroadcastReceiver
 import android.content.Context
 import android.content.Intent
 import android.util.Log
 import com.sameerasw.airsync.service.AirSyncService
 
 class BootCompletedReceiver : BroadcastReceiver() {
     override fun onReceive(context: Context, intent: Intent) {
         if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
             Log.d("BootCompletedReceiver", "Boot completed, starting AirSyncService")
             try {
                 AirSyncService.startScanning(context)
             } catch (e: Exception) {
                 Log.e("BootCompletedReceiver", "Failed to start service on boot", e)
             }
         }
     }
 }
