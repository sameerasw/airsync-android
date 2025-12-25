package com.sameerasw.airsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sameerasw.airsync.presentation.ui.activities.ScreenShareActivity

class MirrorRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.sameerasw.airsync.MIRROR_REQUEST") {
            val newIntent = Intent(context, ScreenShareActivity::class.java).apply {
                putExtras(intent.extras!!)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(newIntent)
        }
    }
}
