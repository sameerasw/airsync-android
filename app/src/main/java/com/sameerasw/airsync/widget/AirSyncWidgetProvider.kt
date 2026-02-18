package com.sameerasw.airsync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.DevicePreviewResolver
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AirSyncWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "AirSyncWidget"
        const val ACTION_DISCONNECT = "com.sameerasw.airsync.widget.ACTION_DISCONNECT"
        const val ACTION_RECONNECT = "com.sameerasw.airsync.widget.ACTION_RECONNECT"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, AirSyncWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)

            val provider = AirSyncWidgetProvider()
            provider.onUpdate(context, appWidgetManager, widgetIds)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "Updating ${appWidgetIds.size} widgets")

        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                updateAllWidgets(context)
            }

            ACTION_DISCONNECT -> {
                try {
                    // Mark manual disconnect and disconnect
                    runBlocking {
                        DataStoreManager(context).setUserManuallyDisconnected(true)
                    }
                    WebSocketUtil.disconnect(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Widget disconnect failed: ${e.message}")
                }
                updateAllWidgets(context)
            }

            ACTION_RECONNECT -> {
                try {
                    val ds = DataStoreManager(context)
                    runBlocking { ds.setUserManuallyDisconnected(false) }
                    // Try to connect using last connected device
                    val last = runBlocking { ds.getLastConnectedDevice().first() }
                    if (last != null) {
                        WebSocketUtil.connect(
                            context = context,
                            ipAddress = last.ipAddress,
                            port = last.port.toIntOrNull() ?: 6996,
                            symmetricKey = last.symmetricKey,
                            manualAttempt = true,
                            onConnectionStatus = { updateAllWidgets(context) },
                            onHandshakeTimeout = { updateAllWidgets(context) }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Widget reconnect failed: ${e.message}")
                }
                updateAllWidgets(context)
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_airsync)

        try {
            val ds = DataStoreManager(context)
            val isConnected = WebSocketUtil.isConnected()
            val isConnecting = WebSocketUtil.isConnecting()
            val lastDevice = runBlocking { ds.getLastConnectedDevice().first() }

            // Device image (large preview) and name
            val previewRes = DevicePreviewResolver.getPreviewRes(lastDevice)
            views.setImageViewResource(R.id.widget_device_image, previewRes)
            // Dim the device image when not connected (including while connecting)
            val alphaFloat = if (isConnected) 1.0f else 0.6f
            val alphaInt = if (isConnected) 255 else 153 // 0.6 * 255 ≈ 153
            // Apply both view alpha and image alpha for broader device compatibility
            views.setFloat(R.id.widget_device_image, "setAlpha", alphaFloat)
            views.setInt(R.id.widget_device_image, "setImageAlpha", alphaInt)
            views.setTextViewText(R.id.widget_device_name, lastDevice?.name ?: "AirSync")

            // Read persisted Mac status snapshot
            val macStatus =
                runBlocking { DataStoreManager(context).getMacStatusForWidget().first() }

            // Battery overlay and secondary line
            if (isConnected && macStatus.batteryLevel != null) {
                val pct = macStatus.batteryLevel.coerceIn(0, 100)
                views.setTextViewText(R.id.widget_battery_text, "$pct%")
                views.setViewVisibility(R.id.widget_battery_container, android.view.View.VISIBLE)
                // Hide secondary line when connected (we will use media info instead)
                views.setViewVisibility(R.id.widget_secondary_line, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_battery_container, android.view.View.GONE)
                val secondaryText = when {
                    lastDevice != null -> {
                        val lastSeenMs = lastDevice.lastConnected
                        if (lastSeenMs > 0) formatLastSeen(lastSeenMs) else "Last seen: unknown"
                    }

                    else -> ""
                }
                views.setTextViewText(R.id.widget_secondary_line, secondaryText)
                views.setViewVisibility(
                    R.id.widget_secondary_line,
                    if (secondaryText.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                )
            }

            // Media info when connected: show title - artist if present
            if (isConnected && !macStatus.title.isNullOrBlank()) {
                val mediaLine =
                    if (!macStatus.artist.isNullOrBlank()) "${macStatus.title} — ${macStatus.artist}" else macStatus.title
                views.setTextViewText(R.id.widget_media_info, mediaLine)
                views.setViewVisibility(R.id.widget_media_info, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_media_info, android.view.View.GONE)
            }


            // Open app when tapping outer container (fallback/default)
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, openAppPendingIntent)

            // Toggle overlay hint visibility: show when can reconnect (disconnected, have last device, not currently connecting)
            val showTapHint = (!isConnected && lastDevice != null && !isConnecting)
            views.setViewVisibility(
                R.id.widget_tap_hint,
                if (showTapHint) android.view.View.VISIBLE else android.view.View.GONE
            )

            // Make the device image tap only reconnect (no disconnect action from widget)
            when {
                isConnected -> {
                    // When connected, tapping image opens the app (no disconnect from widget)
                    views.setOnClickPendingIntent(R.id.widget_device_image, openAppPendingIntent)
                }

                !isConnected && lastDevice != null && !isConnecting -> {
                    val reconnectIntent = Intent(context, AirSyncWidgetProvider::class.java).apply {
                        action = ACTION_RECONNECT
                    }
                    val reconnectPI = PendingIntent.getBroadcast(
                        context, 2, reconnectIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_device_image, reconnectPI)
                }

                else -> {
                    // While connecting or if no device is saved, just open the app
                    views.setOnClickPendingIntent(R.id.widget_device_image, openAppPendingIntent)
                }
            }

            appWidgetManager.updateAppWidget(widgetId, views)
            Log.d(TAG, "Widget $widgetId updated")

        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget $widgetId: ${e.message}")
        }
    }

    // (no additional companion objects)

    private fun formatLastSeen(lastSeenMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = (now - lastSeenMs).coerceAtLeast(0)
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        val text = when {
            diff < minute -> "Last seen just now"
            diff < hour -> {
                val m = (diff / minute).toInt()
                "Last seen ${m} min ago"
            }

            diff < day -> {
                val h = (diff / hour).toInt()
                "Last seen ${h} hr ago"
            }

            else -> {
                val d = (diff / day).toInt()
                "Last seen ${d} day${if (d == 1) "" else "s"} ago"
            }
        }
        return text
    }
}
