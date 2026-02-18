package com.sameerasw.airsync.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.sameerasw.airsync.domain.model.NotificationApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppUtil {
    private const val TAG = "AppUtil"

    /**
     * Get all installed apps
     */
    suspend fun getInstalledApps(context: Context): List<NotificationApp> =
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager

                // Find apps with launcher entry points
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

                val apps = pm.queryIntentActivities(mainIntent, 0)
                    .mapNotNull { resolveInfo ->
                        val appInfo = resolveInfo.activityInfo.applicationInfo

                        if (appInfo.packageName.contains("airsync")) return@mapNotNull null

                        try {
                            NotificationApp(
                                packageName = appInfo.packageName,
                                appName = pm.getApplicationLabel(appInfo).toString(),
                                isEnabled = true,
                                icon = pm.getApplicationIcon(appInfo),
                                isSystemApp = isSystemApp(appInfo),
                                lastUpdated = System.currentTimeMillis()
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Error loading app ${appInfo.packageName}: ${e.message}")
                            null
                        }
                    }
                    .sortedBy { it.appName.lowercase() }

                apps
            } catch (e: Exception) {
                Log.e(TAG, "Error getting installed apps: ${e.message}")
                emptyList()
            }
        }

    /**
     * Merge installed apps with saved preferences, keeping user settings and adding new apps
     */
    fun mergeWithSavedApps(
        installedApps: List<NotificationApp>,
        savedApps: List<NotificationApp>
    ): List<NotificationApp> {
        val savedAppsMap = savedApps.associateBy { it.packageName }

        return installedApps.map { installedApp ->
            val savedApp = savedAppsMap[installedApp.packageName]
            savedApp?.// Use saved preferences but update app info
            copy(
                appName = installedApp.appName,
                icon = installedApp.icon,
                isSystemApp = installedApp.isSystemApp
            )
                ?: // New app, use defaults
                installedApp
        }.sortedBy { it.appName.lowercase() }
    }

    /**
     * Get launcher app package names only (lightweight - avoids loading icons/labels)
     */
    fun getLauncherPackageNames(context: Context): List<String> {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(mainIntent, 0)
                .map { it.activityInfo.applicationInfo.packageName }
                .filter { !it.contains("airsync") }
                .distinct()
                .sorted()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting launcher package names: ${e.message}")
            emptyList()
        }
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        return appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

}
