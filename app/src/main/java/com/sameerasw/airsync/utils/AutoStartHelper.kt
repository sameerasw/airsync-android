package com.sameerasw.airsync.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

object AutoStartHelper {

    fun isAutoStartSupported(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return listOf("xiaomi", "oppo", "vivo", "letv", "honor").contains(manufacturer)
    }

    fun getAutoStartIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()

        try {
            when (manufacturer) {
                "xiaomi" -> intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                "oppo" -> intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                "vivo" -> intent.component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                "letv" -> intent.component = ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
                "honor" -> intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                else -> return null
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }
}
