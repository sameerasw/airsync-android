package com.sameerasw.airsync.utils

import FileBrowserUtil
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.sameerasw.airsync.BuildConfig
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.data.repository.AirSyncRepositoryImpl
import com.sameerasw.airsync.service.MediaNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Central handler for all incoming WebSocket messages from the Mac server.
 * Dispatches messages to specific handler methods based on the 'type' field.
 */
object WebSocketMessageHandler {
    private const val TAG = "WebSocketMessageHandler"

    // Track if we're currently receiving playing media from Mac to prevent feedback loop
    private var isReceivingPlayingMedia = false

    // Callback for clipboard entry history tracking
    private var onClipboardEntryReceived: ((text: String) -> Unit)? = null

    fun setOnClipboardEntryCallback(callback: ((text: String) -> Unit)?) {
        onClipboardEntryReceived = callback
        Log.d(
            TAG,
            "Clipboard entry callback ${if (callback != null) "registered" else "unregistered"}"
        )
    }

    // Callback for volume updates (0-100)
    private var onMacVolumeReceived: ((Int) -> Unit)? = null

    fun setOnMacVolumeCallback(callback: ((Int) -> Unit)?) {
        onMacVolumeReceived = callback
    }

    // Callback for modifier status updates
    private var onModifierStatusReceived: ((JSONObject) -> Unit)? = null

    fun setOnModifierStatusCallback(callback: ((JSONObject) -> Unit)?) {
        onModifierStatusReceived = callback
    }

    /**
     * Handle incoming WebSocket messages from Mac.
     * Parses the JSON payload and routes to the appropriate private handler method.
     *
     * @param context Application context for performing actions.
     * @param message Raw JSON message string.
     */
    fun handleIncomingMessage(context: Context, message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val data = json.optJSONObject("data")

            if (type != "ping") {
                Log.d(TAG, "Handling message type: $type")
            }

            when (type) {
                "clipboardUpdate" -> handleClipboardUpdate(context, data)
                "fileTransferInit" -> handleFileTransferInit(context, data)
                "fileChunk" -> handleFileChunk(context, data)
                "fileTransferComplete" -> handleFileTransferComplete(context, data)
                "volumeControl" -> handleVolumeControl(context, data)
                "mediaControl" -> handleMediaControl(context, data)
                "dismissNotification" -> handleNotificationDismissal(data)
                "notificationAction" -> handleNotificationAction(data)
                "disconnectRequest" -> handleDisconnectRequest(context)
                "toggleAppNotif" -> handleToggleAppNotification(context, data)
                "toggleNowPlaying" -> handleToggleNowPlaying(context, data)
                "macVolume" -> handleMacVolume(data)
                "modifierStatus" -> handleModifierStatus(data)
                "ping" -> handlePing(context)
                "status" -> handleMacDeviceStatus(context, data)
                "macInfo" -> handleMacInfo(context, data)
                "refreshAdbPorts" -> handleRefreshAdbPorts(context)
                "fileChunkAck" -> handleFileChunkAck(data)
                "transferVerified" -> handleTransferVerified(data)
                "fileTransferCancel" -> handleFileTransferCancel(context, data)
                "browseLs" -> handleBrowseLs(context, data)
                "filePull" -> handleFilePull(context, data)
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming message: ${e.message}")
        }
    }

    // MARK: - File Transfer Handlers

    /**
     * Initializes an incoming file transfer session.
     * Prepares the `FileReceiver` to accept chunks.
     */
    private fun handleFileTransferInit(context: Context, data: JSONObject?) {
        try {
            if (data == null) return
            val id = data.optString("id", java.util.UUID.randomUUID().toString())
            val name = data.optString("name")
            val size = data.optInt("size", 0)
            val mime = data.optString("mime", "application/octet-stream")
            val chunkSize = data.optInt("chunkSize", 64 * 1024)
            val checksumVal = data.optString("checksum", "")
            val isClipboard = data.optBoolean("isClipboard", false)

            FileReceiver.handleInit(
                context,
                id,
                name,
                size,
                mime,
                chunkSize,
                if (checksumVal.isBlank()) null else checksumVal,
                isClipboard
            )
            Log.d(TAG, "Started incoming file transfer: $name ($size bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error in file init: ${e.message}")
        }
    }

    /**
     * Processes a single chunk of file data.
     * Delegates to `FileReceiver` for writing.
     */
    private fun handleFileChunk(context: Context, data: JSONObject?) {
        try {
            if (data == null) return
            val id = data.optString("id", "default")
            val index = data.optInt("index", 0)
            val chunk = data.optString("chunk", "")
            if (chunk.isNotEmpty()) {
                FileReceiver.handleChunk(context, id, index, chunk)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in file chunk: ${e.message}")
        }
    }

    /**
     * Finalizes the incoming file transfer.
     * Triggers completion notifications and cleanup.
     */
    private fun handleFileTransferComplete(context: Context, data: JSONObject?) {
        try {
            if (data == null) return
            val id = data.optString("id", "default")
            FileReceiver.handleComplete(context, id)
        } catch (e: Exception) {
            Log.e(TAG, "Error in file complete: ${e.message}")
        }
    }

    // MARK: - Clipboard & Control Handlers

    /**
     * Updates the system clipboard with text from the Mac.
     * Uses `ClipboardSyncManager` to avoid feedback loops by tracking origin.
     */
    private fun handleClipboardUpdate(context: Context, data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Clipboard update data is null")
                return
            }

            val text = data.optString("text")
            if (!text.isNullOrEmpty()) {
                Log.d(TAG, "Clipboard update received from desktop: ${text.take(50)}...")

                // Notify ViewModel/UI to add entry to clipboard history
                onClipboardEntryReceived?.invoke(text)

                // Update system clipboard
                ClipboardSyncManager.handleClipboardUpdate(context, text)
                Log.d(TAG, "Clipboard updated from desktop: ${text.take(50)}...")
            } else {
                Log.w(TAG, "Clipboard update received but text is empty")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard update: ${e.message}")
        }
    }

    /**
     * Handles volume control commands (set, increase, decrease, mute).
     * Sends a response back to the Mac indicating success or failure.
     */
    private fun handleVolumeControl(context: Context, data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Volume control data is null")
                sendVolumeControlResponse("setVolume", false, "No data provided")
                return
            }

            when (val action = data.optString("action")) {
                "setVolume" -> {
                    val volume = data.optInt("volume", -1)
                    if (volume in 0..100) {
                        val success = VolumeControlUtil.setVolume(context, volume)
                        sendVolumeControlResponse(
                            action,
                            success,
                            if (success) "Volume set to $volume%" else "Failed to set volume"
                        )

                        // Send updated device status after volume change
                        if (success) {
                            SyncManager.onVolumeChanged(context)
                        }
                    } else {
                        sendVolumeControlResponse(action, false, "Invalid volume value: $volume")
                    }
                }

                "increaseVolume" -> {
                    val increment = data.optInt("increment", 10)
                    val success = VolumeControlUtil.increaseVolume(context, increment)
                    sendVolumeControlResponse(
                        action,
                        success,
                        if (success) "Volume increased by $increment%" else "Failed to increase volume"
                    )

                    if (success) {
                        SyncManager.onVolumeChanged(context)
                    }
                }

                "decreaseVolume" -> {
                    val decrement = data.optInt("decrement", 10)
                    val success = VolumeControlUtil.decreaseVolume(context, decrement)
                    sendVolumeControlResponse(
                        action,
                        success,
                        if (success) "Volume decreased by $decrement%" else "Failed to decrease volume"
                    )

                    if (success) {
                        SyncManager.onVolumeChanged(context)
                    }
                }

                "toggleMute" -> {
                    val success = VolumeControlUtil.toggleMute(context)
                    sendVolumeControlResponse(
                        action,
                        success,
                        if (success) "Mute toggled" else "Failed to toggle mute"
                    )

                    if (success) {
                        SyncManager.onVolumeChanged(context)
                    }
                }

                else -> {
                    Log.w(TAG, "Unknown volume control action: $action")
                    sendVolumeControlResponse(action, false, "Unknown action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling volume control: ${e.message}")
            sendVolumeControlResponse("unknown", false, "Error: ${e.message}")
        }
    }

    /**
     * Handles media control commands (play/pause, next, previous, like).
     * Sends a response back to Mac and updates local media state after a short delay.
     */
    private fun handleMediaControl(context: Context, data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Media control data is null")
                sendMediaControlResponse("unknown", false, "No data provided")
                return
            }

            val action = data.optString("action")
            var success = false
            var message: String

            when (action) {
                "playPause" -> {
                    success = MediaControlUtil.playPause(context)
                    message = if (success) "Play/pause toggled" else "Failed to toggle play/pause"
                }

                "play" -> {
                    success = MediaControlUtil.playPause(context)
                    message = if (success) "Playback started" else "Failed to start playback"
                }

                "pause" -> {
                    success = MediaControlUtil.playPause(context)
                    message = if (success) "Playback paused" else "Failed to pause playback"
                }

                "next" -> {
                    // Suppress automatic media updates before executing skip command
                    SyncManager.suppressMediaUpdatesForSkip()
                    success = MediaControlUtil.skipNext(context)
                    message =
                        if (success) "Skipped to next track" else "Failed to skip to next track"
                }

                "previous" -> {
                    // Suppress automatic media updates before executing skip command
                    SyncManager.suppressMediaUpdatesForSkip()
                    success = MediaControlUtil.skipPrevious(context)
                    message =
                        if (success) "Skipped to previous track" else "Failed to skip to previous track"
                }

                "stop" -> {
                    success = MediaControlUtil.stop(context)
                    message = if (success) "Playback stopped" else "Failed to stop playback"
                }
                // New: toggle like controls
                "toggleLike" -> {
                    success = MediaControlUtil.toggleLike(context)
                    message = if (success) "Like toggled" else "Failed to toggle like"
                }

                "like" -> {
                    success = MediaControlUtil.like(context)
                    message = if (success) "Liked" else "Failed to like"
                }

                "unlike" -> {
                    success = MediaControlUtil.unlike(context)
                    message = if (success) "Unliked" else "Failed to unlike"
                }

                else -> {
                    Log.w(TAG, "Unknown media control action: $action")
                    message = "Unknown action: $action"
                }
            }

            sendMediaControlResponse(action, success, message)

            // Send updated media state after successful control
            if (success) {
                // For track skip actions (next/previous), add a delay to allow media player to update
                CoroutineScope(Dispatchers.IO).launch {
                    val delayMs = when (action) {
                        "next", "previous" -> 1200L
                        else -> 400L // smaller delay for like/others
                    }
                    delay(delayMs)
                    SyncManager.onMediaStateChanged(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling media control: ${e.message}")
            sendMediaControlResponse("unknown", false, "Error: ${e.message}")
        }
    }

    /**
     * Attempts to dismiss a notification on the Android device by ID.
     */
    private fun handleNotificationDismissal(data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Notification dismissal data is null")
                sendNotificationDismissalResponse("unknown", false, "No data provided")
                return
            }

            val notificationId = data.optString("id")
            if (notificationId.isEmpty()) {
                sendNotificationDismissalResponse(
                    notificationId,
                    false,
                    "No notification ID provided"
                )
                return
            }

            val success = NotificationDismissalUtil.dismissNotification(notificationId)
            val message =
                if (success) "Notification dismissed" else "Failed to dismiss notification or notification not found"

            sendNotificationDismissalResponse(notificationId, success, message)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification dismissal: ${e.message}")
            sendNotificationDismissalResponse("unknown", false, "Error: ${e.message}")
        }
    }

    /**
     * Executes an action on a notification (e.g., Reply, Archive).
     * Supports both action buttons and direct replies.
     */
    private fun handleNotificationAction(data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Notification action data is null")
                sendNotificationActionResponse("unknown", "", false, "No data provided")
                return
            }

            val notificationId = data.optString("id")
            if (notificationId.isEmpty()) {
                sendNotificationActionResponse(
                    notificationId,
                    "",
                    false,
                    "No notification ID provided"
                )
                return
            }

            // We accept either "name" or legacy "action" for action name
            val actionName = data.optString("name", data.optString("action", "")).ifEmpty { "" }
            val replyText = data.optString("text")

            if (actionName.isEmpty()) {
                sendNotificationActionResponse(
                    notificationId,
                    actionName,
                    false,
                    "No action name provided"
                )
                return
            }

            val success = NotificationDismissalUtil.performNotificationAction(
                notificationId,
                actionName,
                replyText
            )
            val message = if (success) {
                if (replyText.isNotEmpty()) "Reply sent" else "Action invoked"
            } else {
                "Failed to perform action or notification not found"
            }

            sendNotificationActionResponse(notificationId, actionName, success, message)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification action: ${e.message}")
            sendNotificationActionResponse("unknown", "", false, "Error: ${e.message}")
        }
    }

    private fun handlePing(context: Context) {
        try {
            // Respond to ping with current device status to keep connection alive
            // We must force sync here because the server expects a response to every ping
            SyncManager.checkAndSyncDeviceStatus(context, forceSync = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ping: ${e.message}")
        }
    }

    private fun handleDisconnectRequest(context: Context) {
        try {
            // Mark as intentional disconnect to prevent auto-reconnect
            kotlinx.coroutines.runBlocking {
                try {
                    val dataStoreManager = DataStoreManager(context)
                    dataStoreManager.setUserManuallyDisconnected(true)
                } catch (_: Exception) {
                }
            }
            // Immediately disconnect the WebSocket
            WebSocketUtil.disconnect()
            Log.d(TAG, "WebSocket disconnected as per request")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling disconnect request: ${e.message}")
        }
    }

    /**
     * Processes status updates received from the Mac (battery, music, etc.).
     * Updates local storage and triggers widget refresh if needed.
     */
    private fun handleMacDeviceStatus(context: Context, data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Mac device status data is null")
                return
            }

            Log.d(TAG, "Received Mac device status: $data")

            // Parse battery information
            val battery = data.optJSONObject("battery")
            val batteryLevel = battery?.optInt("level", 0) ?: 0
            val isCharging = battery?.optBoolean("isCharging", false) ?: false

            // Parse music information
            val music = data.optJSONObject("music")
            val isPlaying = music?.optBoolean("isPlaying", false) ?: false
            val title = music?.optString("title", "") ?: ""
            val artist = music?.optString("artist", "") ?: ""
            val volume = music?.optInt("volume", 50) ?: 50
            val isMuted = music?.optBoolean("isMuted", false) ?: false

            val albumArt =
                if (music?.has("albumArt") == true) music.optString("albumArt", "") else null

            val likeStatus = music?.optString("likeStatus", "none") ?: "none"

            val isPaired = data.optBoolean("isPaired", true)

            // Pause/resume media listener based on Mac media playback status
            val hasActiveMedia = isPlaying && (title.isNotEmpty() || artist.isNotEmpty())
            if (hasActiveMedia) {
                MediaNotificationListener.pauseMediaListener()
            } else {
                MediaNotificationListener.resumeMediaListener()
            }

            // Update the Mac device status manager with all media info
            MacDeviceStatusManager.updateStatus(
                context = context,
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                isPaired = isPaired,
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                volume = volume,
                isMuted = isMuted,
                albumArt = albumArt,
                likeStatus = likeStatus
            )

            // Persist a lightweight snapshot for widget consumption and throttle widget refresh
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ds = DataStoreManager(context)
                    ds.saveMacStatusForWidget(batteryLevel, isCharging, title, artist)

                    // Throttle widget updates to once per minute to reduce battery usage
                    val lastRefresh = ds.getMacWidgetRefreshedAt().first() ?: 0L
                    val now = System.currentTimeMillis()
                    if (now - lastRefresh >= 30_000L) {
                        com.sameerasw.airsync.widget.AirSyncWidgetProvider.updateAllWidgets(context)
                        ds.setMacWidgetRefreshedAt(now)
                    }
                } catch (_: Exception) {
                }
            }

            Log.d(TAG, "Mac device status updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Mac device status: ${e.message}")
        }
    }

    /**
     * Handles the 'macInfo' handshake/update message containing Mac specs and installed apps.
     * Updates device info in the database and synchronizes app icons if needed.
     */
    private fun handleMacInfo(context: Context, data: JSONObject?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (data == null) {
                    Log.e(TAG, "macInfo data is null")
                    return@launch
                }

                val macName = data.optString("name", "")
                val isPlus = data.optBoolean("isPlusSubscription", false)
                val macVersion = data.optString("version", "2.0.0")

                Log.d(
                    TAG,
                    "Processing macInfo - name: '$macName', isPlus: $isPlus, version: '$macVersion'"
                )

                // Version compatibility check
                val minVersion = BuildConfig.MIN_MAC_APP_VERSION
                if (isVersionOutdated(macVersion, minVersion)) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Mac app is outdated ($macVersion < $minVersion). Please update the mac app and reconnect.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                val savedAppPackagesJson = data.optJSONArray("savedAppPackages")
                val savedPackages = mutableSetOf<String>()
                if (savedAppPackagesJson != null) {
                    for (i in 0 until savedAppPackagesJson.length()) {
                        val pkg = savedAppPackagesJson.optString(i)
                        if (!pkg.isNullOrBlank()) savedPackages.add(pkg)
                    }
                }

                // Update last connected device info with Mac name and Plus flag
                try {
                    val ds = DataStoreManager(context)
                    val last = ds.getLastConnectedDevice().first()
                    if (last != null) {
                        // Extract model and device type from macInfo
                        val model = data.optString("model", "").ifBlank { null }
                        val deviceType = when {
                            data.has("type") -> data.optString("type", "").ifBlank { null }
                            data.has("deviceType") -> data.optString("deviceType", "")
                                .ifBlank { null }

                            else -> null
                        }

                        Log.d(
                            TAG,
                            "Updating device: name='${if (macName.isNotBlank()) macName else last.name}', isPlus=$isPlus, model='$model', type='$deviceType'"
                        )

                        ds.saveLastConnectedDevice(
                            last.copy(
                                name = if (macName.isNotBlank()) macName else last.name,
                                isPlus = isPlus,
                                lastConnected = System.currentTimeMillis(),
                                model = model,
                                deviceType = deviceType
                            )
                        )

                        Log.d(TAG, "Device info updated successfully in storage")

                        // Also update the network-aware device storage if possible
                        try {
                            val ourIp = DeviceInfoUtil.getWifiIpAddress(context) ?: ""
                            val clientIp = last.ipAddress
                            val port = last.port
                            val symmetricKey = last.symmetricKey

                            if (clientIp.isNotBlank() && ourIp.isNotBlank()) {
                                ds.saveNetworkDeviceConnection(
                                    deviceName = if (macName.isNotBlank()) macName else last.name,
                                    ourIp = ourIp,
                                    clientIp = clientIp,
                                    port = port,
                                    isPlus = isPlus,
                                    symmetricKey = symmetricKey,
                                    model = model,
                                    deviceType = deviceType
                                )
                                Log.d(TAG, "Network device info also updated successfully")
                            }
                        } catch (e: Exception) {
                            Log.w(
                                TAG,
                                "Unable to update network device info from macInfo: ${e.message}"
                            )
                        }

                        // Force update the last connected timestamp for network device as well
                        try {
                            if (macName.isNotBlank()) {
                                ds.updateNetworkDeviceLastConnected(
                                    macName,
                                    System.currentTimeMillis()
                                )
                                Log.d(TAG, "Network device timestamp updated")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Unable to update network device timestamp: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to update connected device info from macInfo: ${e.message}")
                }

                // Build Android launcher package list (lightweight)
                val androidPackages = try {
                    AppUtil.getLauncherPackageNames(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get launcher package names: ${e.message}")
                    emptyList()
                }

                // Decide how to sync icons based on differences between Android and Mac package lists
                val androidSet = androidPackages.toSet()
                val savedSet = savedPackages.toSet()

                if (savedSet.isEmpty()) {
                    // Mac has none; send full current Android list
                    Log.d(
                        TAG,
                        "macInfo: Mac has no saved packages; syncing full list of ${androidPackages.size} apps"
                    )
                    SyncManager.sendOptimizedAppIcons(context, androidPackages)
                    return@launch
                }

                val newOnAndroid = androidSet - savedSet // apps present on Android but not on Mac
                val missingOnAndroid =
                    savedSet - androidSet // apps present on Mac but uninstalled on Android

                if (newOnAndroid.isNotEmpty() || missingOnAndroid.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "macInfo: App list changed (new=${newOnAndroid.size}, missing=${missingOnAndroid.size}); syncing full list of ${androidPackages.size} apps"
                    )
                    // Send the full current Android list so desktop can add new and remove missing
                    SyncManager.sendOptimizedAppIcons(context, androidPackages, fetchIcons = true)
                } else {
                    Log.d(
                        TAG,
                        "macInfo: No app list changes; skipping icon extraction but syncing metadata"
                    )
                    // Sync metadata (enabled/disabled states) without re-sending heavy icon data
                    SyncManager.sendOptimizedAppIcons(context, androidPackages, fetchIcons = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling macInfo: ${e.message}")
            }
        }
    }

    // Helper method to check if we should send media controls to prevent feedback loop
    fun shouldSendMediaControl(): Boolean {
        return !isReceivingPlayingMedia
    }

    private fun sendVolumeControlResponse(action: String, success: Boolean, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val response = JsonUtil.createVolumeControlResponse(action, success, message)
            WebSocketUtil.sendMessage(response)
        }
    }

    private fun sendMediaControlResponse(action: String, success: Boolean, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val response = JsonUtil.createMediaControlResponse(action, success, message)
            WebSocketUtil.sendMessage(response)
        }
    }

    private fun sendNotificationDismissalResponse(id: String, success: Boolean, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val response = JsonUtil.createNotificationDismissalResponse(id, success, message)
            WebSocketUtil.sendMessage(response)
        }
    }

    private fun sendNotificationActionResponse(
        id: String,
        actionName: String,
        success: Boolean,
        message: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val response =
                JsonUtil.createNotificationActionResponse(id, actionName, success, message)
            WebSocketUtil.sendMessage(response)
        }
    }

    private fun handleToggleAppNotification(context: Context, data: JSONObject?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (data == null) {
                    Log.e(TAG, "Toggle app notification data is null")
                    return@launch
                }

                val packageName = data.optString("package")
                val stateString = data.optString("state")

                if (packageName.isEmpty()) {
                    Log.e(TAG, "Package name is empty in toggle app notification")
                    return@launch
                }

                val newState = stateString.toBoolean()

                Log.d(TAG, "Toggling notification for package: $packageName to state: $newState")

                // Get the repository
                val dataStoreManager = DataStoreManager(context)
                val repository = AirSyncRepositoryImpl(dataStoreManager)

                // Get current apps
                val currentApps = repository.getNotificationApps().first().toMutableList()

                // Find and update the app
                val appIndex = currentApps.indexOfFirst { it.packageName == packageName }

                if (appIndex != -1) {
                    // Update existing app
                    val updatedApp = currentApps[appIndex].copy(isEnabled = newState)
                    currentApps[appIndex] = updatedApp

                    // Save updated apps
                    repository.saveNotificationApps(currentApps)

                    Log.d(
                        TAG,
                        "Successfully updated notification state for $packageName to $newState"
                    )

                    // Send confirmation response back
                    val responseMessage = JsonUtil.createToggleAppNotificationResponse(
                        packageName = packageName,
                        success = true,
                        newState = newState,
                        message = "App notification state updated successfully"
                    )
                    WebSocketUtil.sendMessage(responseMessage)
                } else {
                    Log.w(TAG, "App with package name $packageName not found in notification apps")

                    // Send error response
                    val responseMessage = JsonUtil.createToggleAppNotificationResponse(
                        packageName = packageName,
                        success = false,
                        newState = newState,
                        message = "App not found"
                    )
                    WebSocketUtil.sendMessage(responseMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling toggle app notification: ${e.message}")
                val packageName = data?.optString("package") ?: ""
                val newState = data?.optString("state")?.toBoolean() ?: false
                val responseMessage = JsonUtil.createToggleAppNotificationResponse(
                    packageName = packageName,
                    success = false,
                    newState = newState,
                    message = "Error: ${e.message}"
                )
                WebSocketUtil.sendMessage(responseMessage)
            }
        }
    }

    private fun handleToggleNowPlaying(context: Context, data: JSONObject?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (data == null) {
                    Log.e(TAG, "toggleNowPlaying data is null")
                    val resp =
                        JsonUtil.createToggleNowPlayingResponse(false, null, "No data provided")
                    WebSocketUtil.sendMessage(resp)
                    return@launch
                }
                // Accept either boolean or string "true"/"false"
                val hasBoolean = data.has("state") && (data.opt("state") is Boolean)
                val newState = if (hasBoolean) data.optBoolean("state") else data.optString("state")
                    .toBoolean()

                val ds = DataStoreManager(context)
                ds.setSendNowPlayingEnabled(newState)
                MediaNotificationListener.setNowPlayingEnabled(context, newState)

                val resp = JsonUtil.createToggleNowPlayingResponse(
                    true,
                    newState,
                    "Now playing set to $newState"
                )
                WebSocketUtil.sendMessage(resp)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling toggleNowPlaying: ${e.message}")
                val resp =
                    JsonUtil.createToggleNowPlayingResponse(false, null, "Error: ${e.message}")
                WebSocketUtil.sendMessage(resp)
            }
        }
    }

    private fun handleMacVolume(data: JSONObject?) {
        try {
            if (data == null) return
            val volume = data.optInt("volume", -1)
            if (volume >= 0) {
                Log.d(TAG, "Received Mac volume update: $volume")
                onMacVolumeReceived?.invoke(volume)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling macVolume: ${e.message}")
        }
    }

    private fun handleModifierStatus(data: JSONObject?) {
        try {
            if (data == null) return
            Log.d(TAG, "Received modifier status update: $data")
            onModifierStatusReceived?.invoke(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling modifierStatus: ${e.message}")
        }
    }

    private fun handleFileChunkAck(data: JSONObject?) {
        try {
            if (data == null) return
            val id = data.optString("id")
            val index = data.optInt("index")
            FileSender.handleAck(id, index)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling fileChunkAck: ${e.message}")
        }
    }

    private fun handleTransferVerified(data: JSONObject?) {
        try {
            if (data == null) return
            val id = data.optString("id")
            val verified = data.optBoolean("verified")
            FileSender.handleVerified(id, verified)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling transferVerified: ${e.message}")
        }
    }

    private fun handleFileTransferCancel(context: Context, data: JSONObject?) {
        try {
            if (data == null) return
            val id = data.optString("id")
            if (id.isNotEmpty()) {
                Log.d(TAG, "Received transfer cancel request for $id")
                // Try cancelling both directions
                FileReceiver.cancelTransfer(context, id)
                FileSender.cancelTransfer(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling fileTransferCancel: ${e.message}")
        }
    }

    private fun handleBrowseLs(context: Context, data: JSONObject?) {
        try {
            val path = data?.optString("path")
            val showHidden = data?.optBoolean("showHidden", false) ?: false
            Log.d(TAG, "Browse request for path: $path, showHidden: $showHidden")
            val response = FileBrowserUtil.listDirectory(path, showHidden)
            WebSocketUtil.sendMessage(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling browseLs: ${e.message}")
        }
    }

    private fun handleFilePull(context: Context, data: JSONObject?) {
        try {
            val path = data?.optString("path")
            if (path.isNullOrEmpty()) return
            Log.d(TAG, "File pull request for path: $path")
            val file = java.io.File(path)
            if (file.exists() && file.isFile) {
                FileSender.sendFile(context, android.net.Uri.fromFile(file))
            } else {
                Log.e(TAG, "File pull failed: File does not exist or is not a file: $path")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling filePull: ${e.message}")
        }
    }

    private fun handleRefreshAdbPorts(context: Context) {
        Log.d(TAG, "Request to refresh ADB ports received")
        SyncManager.sendDeviceInfoNow(context)
    }

    private fun isVersionOutdated(current: String, min: String): Boolean {
        return try {
            val currentParts = current.split(".").map { it.toInt() }
            val minParts = min.split(".").map { it.toInt() }

            val maxLen = maxOf(currentParts.size, minParts.size)
            for (i in 0 until maxLen) {
                val currentPart = if (i < currentParts.size) currentParts[i] else 0
                val minPart = if (i < minParts.size) minParts[i] else 0

                if (currentPart < minPart) return true
                if (currentPart > minPart) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
