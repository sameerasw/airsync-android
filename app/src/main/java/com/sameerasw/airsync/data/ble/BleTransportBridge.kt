package com.sameerasw.airsync.data.ble

import android.util.Log
import com.sameerasw.airsync.domain.model.AudioInfo
import com.sameerasw.airsync.domain.model.BatteryInfo
import com.sameerasw.airsync.utils.CallControlUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Base64

object BleTransportBridge {
    private const val TAG = "BleTransportBridge"

    private var gattServer: BleGattServer? = null

    fun initialize(server: BleGattServer) {
        gattServer = server
    }

    fun deriveAuthToken(symmetricKey: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(symmetricKey.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(hash.copyOf(12))
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving auth token: ${e.message}")
            ""
        }
    }

    // --- Outbound (Android -> Mac) ---

    fun sendNotification(pkg: String, appName: String, title: String, text: String) {
        val payload = listOf(pkg, appName, title, text).joinToString(BleConstants.DELIMITER)
        gattServer?.sendChunkedNotification(BleConstants.CHAR_NOTIFICATION_DATA, payload)
    }

    fun sendBatteryStatus(battery: BatteryInfo) {
        val level = battery.level.toByte()
        gattServer?.sendNotification(BleConstants.CHAR_BATTERY_LEVEL, byteArrayOf(level))
    }

    fun sendMediaState(audio: AudioInfo) {
        val payload = listOf(
            if (audio.isPlaying) "1" else "0",
            audio.title,
            audio.artist,
            audio.volume.toString(),
            if (audio.isMuted) "1" else "0",
            audio.likeStatus,
            "" // Avoid sending heavy base64 art over BLE to conserve bandwidth
        ).joinToString(BleConstants.DELIMITER)

        gattServer?.sendChunkedNotification(BleConstants.CHAR_MEDIA_STATE, payload)
    }

    fun sendSystemState(isDnd: Boolean, isPowerSave: Boolean) {
        val payload = listOf(
            if (isDnd) "1" else "0",
            if (isPowerSave) "1" else "0"
        ).joinToString(BleConstants.DELIMITER)

        gattServer?.sendNotification(BleConstants.CHAR_SYSTEM_STATE, payload.toByteArray())
    }

    fun sendClipboard(text: String) {
        gattServer?.sendChunkedNotification(BleConstants.CHAR_CLIPBOARD_DATA_NOTIFY, text)
    }

    fun sendNotificationDismissal(id: String) {
        gattServer?.sendChunkedNotification(BleConstants.CHAR_NOTIFICATION_DISMISS_NOTIFY, id)
    }

    fun sendDeviceName() {
        val name = android.os.Build.MODEL
        gattServer?.sendChunkedNotification(BleConstants.CHAR_DEVICE_NAME, name)
    }

    // --- Inbound (Mac -> Android) ---

    fun handleMediaControl(action: String, context: android.content.Context) {
        Log.d(TAG, "Media control from BLE: $action")
        when {
            action == "playPause" -> com.sameerasw.airsync.utils.MediaControlUtil.playPause(context)
            action == "next" -> com.sameerasw.airsync.utils.MediaControlUtil.skipNext(context)
            action == "previous" -> com.sameerasw.airsync.utils.MediaControlUtil.skipPrevious(
                context
            )

            action == "callAccept" -> CallControlUtil.acceptCall(context)
            action == "callDecline" || action == "callEnd" -> CallControlUtil.endCall(context)

            // Volume Controls over BLE
            action == "volumeUp" -> com.sameerasw.airsync.utils.VolumeControlUtil.increaseVolume(
                context
            )

            action == "volumeDown" -> com.sameerasw.airsync.utils.VolumeControlUtil.decreaseVolume(
                context
            )

            action == "muteToggle" -> com.sameerasw.airsync.utils.VolumeControlUtil.toggleMute(
                context
            )

            action.startsWith("setVolume|") -> {
                val volStr = action.substringAfter("setVolume|")
                val vol = volStr.toIntOrNull()
                if (vol != null && vol in 0..100) {
                    com.sameerasw.airsync.utils.VolumeControlUtil.setVolume(context, vol)
                }
            }

            action.startsWith("toggleNotif|") -> {
                val parts = action.split("|")
                if (parts.size >= 3) {
                    val pkg = parts[1]
                    val state = parts[2] == "true"
                    Log.d(TAG, "Received toggleAppNotif via BLE: pkg=$pkg, state=$state")
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val dataStoreManager =
                                com.sameerasw.airsync.data.local.DataStoreManager.getInstance(
                                    context
                                )
                            val currentApps =
                                dataStoreManager.getNotificationApps().first().toMutableList()
                            val idx = currentApps.indexOfFirst { it.packageName == pkg }
                            if (idx != -1) {
                                currentApps[idx] = currentApps[idx].copy(
                                    isEnabled = state,
                                    lastUpdated = System.currentTimeMillis()
                                )
                                dataStoreManager.saveNotificationApps(currentApps)
                                Log.d(
                                    TAG,
                                    "Successfully toggled app notification preference via BLE for $pkg to $state"
                                )
                            } else {
                                val isSystemApp = try {
                                    val applicationInfo =
                                        context.packageManager.getApplicationInfo(pkg, 0)
                                    (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                                } catch (_: Exception) {
                                    false
                                }
                                val newApp = com.sameerasw.airsync.domain.model.NotificationApp(
                                    packageName = pkg,
                                    appName = pkg,
                                    isEnabled = state,
                                    isSystemApp = isSystemApp,
                                    lastUpdated = System.currentTimeMillis()
                                )
                                currentApps.add(newApp)
                                dataStoreManager.saveNotificationApps(currentApps)
                                Log.d(
                                    TAG,
                                    "Saved new app notification preference via BLE for $pkg to $state"
                                )
                            }
                            com.sameerasw.airsync.utils.SyncManager.checkAndSyncDeviceStatus(
                                context,
                                forceSync = true
                            )
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "Error toggling app notification preference via BLE: ${e.message}"
                            )
                        }
                    }
                }
            }
        }
    }

    fun handleNotificationAction(data: String, context: android.content.Context) {
        Log.d(TAG, "Notification action from BLE: $data")
        val parts = data.split(BleConstants.DELIMITER)
        if (parts.size >= 2) {
            val id = parts[0]
            val actionName = parts[1]
            com.sameerasw.airsync.utils.NotificationDismissalUtil.performNotificationAction(
                id,
                actionName
            )
        }
    }
}
