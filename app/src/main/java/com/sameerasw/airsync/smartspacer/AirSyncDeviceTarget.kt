package com.sameerasw.airsync.smartspacer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.kieronquinn.app.smartspacer.sdk.model.CompatibilityState
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceTarget
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.TapAction
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Text
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerTargetProvider
import com.kieronquinn.app.smartspacer.sdk.utils.TargetTemplate
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.airsync.utils.DeviceIconResolver
import com.sameerasw.airsync.utils.DevicePreviewResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class AirSyncDeviceTarget : SmartspacerTargetProvider() {

    override fun getSmartspaceTargets(smartspacerId: String): List<SmartspaceTarget> {
        return try {
            val context = provideContext()
            val isConnected = WebSocketUtil.isConnected()
            val dataStoreManager = DataStoreManager.getInstance(context)

            // Get the Smartspacer setting
            val showWhenDisconnected = runBlocking {
                dataStoreManager.getSmartspacerShowWhenDisconnected().first()
            }

            // Get last connected device info
            val lastDevice = runBlocking {
                dataStoreManager.getLastConnectedDevice().first()
            }

            // Don't show target if never connected
            if (lastDevice == null && !isConnected) {
                return emptyList()
            }

            // If not connected and user disabled "show when disconnected", hide the target
            if (!isConnected && !showWhenDisconnected) {
                return emptyList()
            }

            val deviceName = lastDevice?.name ?: "Unknown Device"
            val deviceModel = lastDevice?.model

            // Get Mac status (battery and media info)
            val macStatus = runBlocking {
                dataStoreManager.getMacStatusForWidget().first()
            }

            // Build subtitle with battery, media info, or device model
            val subtitle = when {
                isConnected && macStatus.batteryLevel != null -> {
                    // Show battery percentage when connected
                    val batteryText = "${macStatus.batteryLevel}%"
                    // Add media info if available
                    if (!macStatus.title.isNullOrBlank()) {
                        if (!macStatus.artist.isNullOrBlank()) {
                            "$batteryText • ${macStatus.title} — ${macStatus.artist}"
                        } else {
                            "$batteryText • ${macStatus.title}"
                        }
                    } else {
                        batteryText
                    }
                }
                isConnected && !macStatus.title.isNullOrBlank() -> {
                    // Show media info only if no battery
                    if (!macStatus.artist.isNullOrBlank()) {
                        "${macStatus.title} — ${macStatus.artist}"
                    } else {
                        macStatus.title
                    }
                }
                isConnected -> "Connected"
                deviceModel != null -> "Disconnected • $deviceModel"
                else -> "Disconnected • Tap to reconnect"
            }

            // Use DeviceIconResolver for the small icon (shown on right)
            val iconRes = DeviceIconResolver.getIconRes(lastDevice)

            // Use DevicePreviewResolver for the large device preview image (shown on left)
            val deviceImageRes = DevicePreviewResolver.getPreviewRes(lastDevice)

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            // Use Basic template for Samsung One UI compatibility (Image template may not work on all Samsung devices)
            val isSamsungDevice = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            
            val target = if (isSamsungDevice) {
                // Use Basic template for Samsung - more compatible with One UI
                TargetTemplate.Basic(
                    id = "airsync_device_$smartspacerId",
                    componentName = ComponentName(context, AirSyncDeviceTarget::class.java),
                    title = Text(deviceName),
                    subtitle = Text(subtitle),
                    icon = com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Icon(
                        Icon.createWithResource(context, iconRes)
                    ),
                    onClick = TapAction(intent = tapIntent)
                ).create().apply {
                    canBeDismissed = false
                    isSensitive = false
                }
            } else {
                // Use Image template for other devices
                TargetTemplate.Image(
                    context = context,
                    id = "airsync_device_$smartspacerId",
                    componentName = ComponentName(context, AirSyncDeviceTarget::class.java),
                    title = Text(deviceName),
                    subtitle = Text(subtitle),
                    icon = com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Icon(
                        Icon.createWithResource(context, iconRes)
                    ),
                    image = com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Icon(
                        Icon.createWithResource(context, deviceImageRes)
                    ),
                    onClick = TapAction(intent = tapIntent)
                ).create().apply {
                    canBeDismissed = false
                    isSensitive = false
                }
            }

            listOf(target)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override fun getConfig(smartspacerId: String?): Config {
        return Config(
            label = "AirSync Device",
            description = "Shows your connected Mac device status",
            icon = Icon.createWithResource(provideContext(), R.drawable.ic_laptop_24),
            allowAddingMoreThanOnce = false,
            compatibilityState = CompatibilityState.Compatible
        )
    }

    override fun onDismiss(smartspacerId: String, targetId: String): Boolean {
        // Target cannot be dismissed
        return false
    }

    override fun onProviderRemoved(smartspacerId: String) {
        // Cleanup if needed when target is removed
    }

    companion object {
        fun notifyChange(context: Context) {
            notifyChange(context, AirSyncDeviceTarget::class.java)
        }
    }
}
