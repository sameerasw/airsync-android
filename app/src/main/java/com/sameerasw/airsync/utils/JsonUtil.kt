package com.sameerasw.airsync.utils

object JsonUtil {

    /**
     * Ensures JSON string is a single line by removing all newlines and extra whitespace
     */
    fun toSingleLine(json: String): String {
        return json.replace(Regex("\\s*\\n\\s*"), " ")
                  .replace(Regex("\\s+"), " ")
                  .trim()
    }

    /**
     * Creates a single-line JSON string for device info
     */
    fun createDeviceInfoJson(name: String, ipAddress: String, port: Int, version: String): String {
        return """{"type":"device","data":{"name":"$name","ipAddress":"$ipAddress","port":$port,"version":"$version","adbPorts":[]}}"""
    }

    /**
     * Creates a single-line JSON string for device info with ADB ports
     */
    fun createDeviceInfoJson(name: String, ipAddress: String, port: Int, version: String, adbPorts: List<String>): String {
        val portsJson = adbPorts.joinToString(",") { "\"$it\"" }
        return """{"type":"device","data":{"name":"$name","ipAddress":"$ipAddress","port":$port,"version":"$version","adbPorts":[$portsJson]}}"""
    }

    /**
     * Creates a single-line JSON string for device info with wallpaper
     */
    fun createDeviceInfoJson(name: String, ipAddress: String, port: Int, version: String, wallpaperBase64: String?, adbPorts: List<String>): String {
        val wallpaperJson = if (wallpaperBase64 != null) {
            ""","wallpaper":"$wallpaperBase64""""
        } else {
            ""
        }
        val portsJson = adbPorts.joinToString(",") { "\"$it\"" }
        return """{"type":"device","data":{"name":"$name","ipAddress":"$ipAddress","port":$port,"version":"$version","adbPorts":[$portsJson]$wallpaperJson}}"""
    }

    /**
     * Creates a single-line JSON string for notifications with unique ID
     */
    fun createNotificationJson(id: String, title: String, body: String, app: String, packageName: String): String {
        return """{"type":"notification","data":{"id":"$id","title":"$title","body":"$body","app":"$app","package":"$packageName"}}"""
    }

    /**
     * Creates a single-line JSON string for notifications with actions included.
     * actions: list of objects { name: String, type: "button" | "reply" }
     */
    fun createNotificationJson(
        id: String,
        title: String,
        body: String,
        app: String,
        packageName: String,
        actions: List<Pair<String, String>>
    ): String {
        val actionsJson = if (actions.isNotEmpty()) {
            val items = actions.joinToString(",") { (name, type) ->
                """{"name":"${escape(name)}","type":"${escape(type)}"}"""
            }
            ",\"actions\":[${items}]"
        } else {
            ""
        }
        return """{"type":"notification","data":{"id":"$id","title":"${escape(title)}","body":"${escape(body)}","app":"${escape(app)}","package":"${escape(packageName)}"$actionsJson}}"""
    }

    /**
     * Creates a one-line JSON for Android->Mac notification state updates (e.g., dismissals).
     * If action is not provided, defaults to "dismiss" when dismissed=true.
     */
    fun createNotificationUpdateJson(id: String, dismissed: Boolean = true, action: String? = null): String {
        val safeAction = action ?: if (dismissed) "dismiss" else null
        val actionPart = safeAction?.let { ",\"action\":\"${escape(it)}\"" } ?: ""
        return """{"type":"notificationUpdate","data":{"id":"$id","dismissed":$dismissed$actionPart}}"""
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    /**
     * Creates a single-line JSON string for device status with media playing state
     */
    fun createDeviceStatusJson(
        batteryLevel: Int,
        isCharging: Boolean,
        isPaired: Boolean,
        isPlaying: Boolean,
        title: String,
        artist: String,
        volume: Int,
        isMuted: Boolean,
        albumArt: String?,
        likeStatus: String
    ): String {
        val albumArtJson = if (albumArt != null) ",\"albumArt\":\"$albumArt\"" else ""
        return """{"type":"status","data":{"battery":{"level":$batteryLevel,"isCharging":$isCharging},"isPaired":$isPaired,"music":{"isPlaying":$isPlaying,"title":"$title","artist":"$artist","volume":$volume,"isMuted":$isMuted$albumArtJson,"likeStatus":"$likeStatus"}}}"""
    }

    /**
     * Creates a response JSON for notification dismissal result
     */
    fun createNotificationDismissalResponse(id: String, success: Boolean, message: String = ""): String {
        return """{"type":"dismissalResponse","data":{"id":"$id","success":$success,"message":"$message"}}"""
    }

    /**
     * Creates a response JSON for notification action result
     */
    fun createNotificationActionResponse(id: String, actionName: String, success: Boolean, message: String = ""): String {
        return """{"type":"notificationActionResponse","data":{"id":"$id","action":"${escape(actionName)}","success":$success,"message":"${escape(message)}"}}"""
    }

    /**
     * Creates a response JSON for media control result
     */
    fun createMediaControlResponse(action: String, success: Boolean, message: String = ""): String {
        return """{"type":"mediaControlResponse","data":{"action":"$action","success":$success,"message":"$message"}}"""
    }

    /**
     * Creates a response JSON for volume control result
     */
    fun createVolumeControlResponse(action: String, success: Boolean, message: String = ""): String {
        return """{"type":"volumeControlResponse","data":{"action":"$action","success":$success,"message":"$message"}}"""
    }

    /**
     * Creates a JSON string for app icons
     */
    fun createAppIconsJson(apps: List<com.sameerasw.airsync.domain.model.NotificationApp>, iconMap: Map<String, String>): String {
        val appEntries = apps.joinToString(",") { app ->
            val iconData = iconMap[app.packageName] ?: ""
            """
            "${app.packageName}": {
                "name": "${app.appName}",
                "icon": "$iconData",
                "listening": ${app.isEnabled},
                "systemApp": ${app.isSystemApp}
            }
            """.trimIndent()
        }
        return """{"type":"appIcons","data":{ $appEntries }}"""
    }

    /**
     * Creates a JSON string for clipboard updates
     */
    fun createClipboardUpdateJson(text: String): String {
        // Escape quotes and newlines in the text
        val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return """{"type":"clipboardUpdate","data":{"text":"$escapedText"}}"""
    }

    /**
     * Creates a response JSON for app notification toggle result
     */
    fun createToggleAppNotificationResponse(
        packageName: String,
        success: Boolean,
        newState: Boolean,
        message: String = ""
    ): String {
        return """{"type":"toggleAppNotifResponse","data":{"package":"$packageName","success":$success,"newState":$newState,"message":"$message"}}"""
    }

    /**
     * Creates a response JSON for toggleNowPlaying command
     */
    fun createToggleNowPlayingResponse(success: Boolean, newState: Boolean?, message: String = ""): String {
        val statePart = newState?.let { ",\"state\":$it" } ?: ""
        return """{"type":"toggleNowPlayingResponse","data":{"success":$success$statePart,"message":"${escape(message)}"}}"""
    }

    /**
     * Creates a JSON for a mirror request to start or stop screen mirroring.
     */
    fun createMirrorRequestJson(
        action: String, // "start" or "stop"
        mode: String = "device",
        packageName: String = "",
        fps: Int = 30,
        quality: Float = 0.6f,
        maxWidth: Int = 1280
    ): String {
        return """{"type":"mirrorRequest","data":{"action":"$action","mode":"$mode","package":"$packageName","options":{"transport":"websocket","fps":$fps,"quality":$quality,"maxWidth":$maxWidth}}}"""
    }

    /**
     * Creates a JSON to signal that mirroring is starting with specific parameters.
     */
    fun createMirrorStartJson(fps: Int, quality: Float, width: Int, height: Int): String {
        return """{"type":"mirrorStart","data":{"fps":$fps,"quality":$quality,"width":$width,"height":$height}}"""
    }

    /**
     * Creates a JSON for a single mirror frame.
     */
    fun createMirrorFrameJson(frameBase64: String, pts: Long, isConfig: Boolean): String {
        return """{"type":"mirrorFrame","data":{"frame":"$frameBase64","pts":$pts,"isConfig":$isConfig}}"""
    }

    /**
     * Creates a JSON to stop the mirroring session.
     */
    fun createMirrorStopJson(): String {
        return """{"type":"mirrorStop","data":{}}"""
    }

    /**
     * Creates a response JSON for input event result
     */
    fun createInputEventResponse(inputType: String, success: Boolean, message: String = ""): String {
        return """{"type":"inputEventResponse","data":{"inputType":"${escape(inputType)}","success":$success,"message":"${escape(message)}"}}"""
    }

    /**
     * Creates a JSON for SMS notification
     */
    fun createSmsNotificationJson(message: com.sameerasw.airsync.models.SmsMessage): String {
        val contactNameJson = message.contactName?.let { ",\"contactName\":\"${escape(it)}\"" } ?: ""
        return """{"type":"smsReceived","data":{"id":"${message.id}","threadId":"${message.threadId}","address":"${escape(message.address)}","body":"${escape(message.body)}","date":${message.date},"type":${message.type},"read":${message.read}$contactNameJson}}"""
    }

    /**
     * Creates a JSON for SMS thread list
     */
    fun createSmsThreadsJson(threads: List<com.sameerasw.airsync.models.SmsThread>): String {
        val threadsJson = threads.joinToString(",") { thread ->
            val contactNameJson = thread.contactName?.let { "\"${escape(it)}\"" } ?: "null"
            """{"threadId":"${thread.threadId}","address":"${escape(thread.address)}","contactName":$contactNameJson,"messageCount":${thread.messageCount},"snippet":"${escape(thread.snippet)}","date":${thread.date},"unreadCount":${thread.unreadCount}}"""
        }
        return """{"type":"smsThreads","data":{"threads":[$threadsJson]}}"""
    }

    /**
     * Creates a JSON for messages in a thread
     */
    fun createSmsMessagesJson(messages: List<com.sameerasw.airsync.models.SmsMessage>): String {
        val messagesJson = messages.joinToString(",") { message ->
            val contactNameJson = message.contactName?.let { "\"${escape(it)}\"" } ?: "null"
            """{"id":"${message.id}","threadId":"${message.threadId}","address":"${escape(message.address)}","body":"${escape(message.body)}","date":${message.date},"type":${message.type},"read":${message.read},"contactName":$contactNameJson}"""
        }
        return """{"type":"smsMessages","data":{"messages":[$messagesJson]}}"""
    }

    /**
     * Creates a JSON for SMS send response
     */
    fun createSmsSendResponse(success: Boolean, message: String = ""): String {
        return """{"type":"smsSendResponse","data":{"success":$success,"message":"${escape(message)}"}}"""
    }

    /**
     * Creates a JSON for call log entries
     */
    fun createCallLogsJson(callLogs: List<com.sameerasw.airsync.models.CallLogEntry>): String {
        val logsJson = callLogs.joinToString(",") { log ->
            val contactNameJson = log.contactName?.let { "\"${escape(it)}\"" } ?: "null"
            val typeString = com.sameerasw.airsync.utils.CallLogUtil.getCallTypeString(log.type)
            """{"id":"${log.id}","number":"${escape(log.number)}","contactName":$contactNameJson,"type":"$typeString","date":${log.date},"duration":${log.duration},"isRead":${log.isRead}}"""
        }
        return """{"type":"callLogs","data":{"logs":[$logsJson]}}"""
    }

    /**
     * Creates a JSON for ongoing call notification
     */
    fun createCallNotificationJson(call: com.sameerasw.airsync.models.OngoingCall): String {
        val contactNameJson = call.contactName?.let { "\"${escape(it)}\"" } ?: "null"
        val stateString = call.state.name.lowercase()
        return """{"type":"callNotification","data":{"id":"${call.id}","number":"${escape(call.number)}","contactName":$contactNameJson,"state":"$stateString","startTime":${call.startTime},"isIncoming":${call.isIncoming}}}"""
    }

    /**
     * Creates a JSON for health data summary
     * Per spec: Use null for missing data, NEVER send 0 for heart rate if no data
     */
    fun createHealthSummaryJson(summary: com.sameerasw.airsync.models.HealthSummary): String {
        val stepsJson = summary.steps?.let { "$it" } ?: "null"
        val distanceJson = summary.distance?.let { "$it" } ?: "null"
        val caloriesJson = summary.calories?.let { "$it" } ?: "null"
        val activeMinutesJson = summary.activeMinutes?.let { "$it" } ?: "null"
        val heartRateAvgJson = summary.heartRateAvg?.let { "$it" } ?: "null"
        val heartRateMinJson = summary.heartRateMin?.let { "$it" } ?: "null"
        val heartRateMaxJson = summary.heartRateMax?.let { "$it" } ?: "null"
        val sleepDurationJson = summary.sleepDuration?.let { "$it" } ?: "null"
        val floorsClimbedJson = summary.floorsClimbed?.let { "$it" } ?: "null"
        val weightJson = summary.weight?.let { "$it" } ?: "null"
        val bloodPressureSystolicJson = summary.bloodPressureSystolic?.let { "$it" } ?: "null"
        val bloodPressureDiastolicJson = summary.bloodPressureDiastolic?.let { "$it" } ?: "null"
        val oxygenSaturationJson = summary.oxygenSaturation?.let { "$it" } ?: "null"
        val restingHeartRateJson = summary.restingHeartRate?.let { "$it" } ?: "null"
        val vo2MaxJson = summary.vo2Max?.let { "$it" } ?: "null"
        val bodyTemperatureJson = summary.bodyTemperature?.let { "$it" } ?: "null"
        val bloodGlucoseJson = summary.bloodGlucose?.let { "$it" } ?: "null"
        val hydrationJson = summary.hydration?.let { "$it" } ?: "null"
        
        return """{"type":"healthSummary","data":{"date":${summary.date},"steps":$stepsJson,"distance":$distanceJson,"calories":$caloriesJson,"activeMinutes":$activeMinutesJson,"heartRateAvg":$heartRateAvgJson,"heartRateMin":$heartRateMinJson,"heartRateMax":$heartRateMaxJson,"sleepDuration":$sleepDurationJson,"floorsClimbed":$floorsClimbedJson,"weight":$weightJson,"bloodPressureSystolic":$bloodPressureSystolicJson,"bloodPressureDiastolic":$bloodPressureDiastolicJson,"oxygenSaturation":$oxygenSaturationJson,"restingHeartRate":$restingHeartRateJson,"vo2Max":$vo2MaxJson,"bodyTemperature":$bodyTemperatureJson,"bloodGlucose":$bloodGlucoseJson,"hydration":$hydrationJson}}"""
    }

    /**
     * Creates a JSON for health data list
     */
    fun createHealthDataJson(dataList: List<com.sameerasw.airsync.models.HealthData>): String {
        val dataJson = dataList.joinToString(",") { data ->
            """{"timestamp":${data.timestamp},"dataType":"${data.dataType.name}","value":${data.value},"unit":"${escape(data.unit)}","source":"${escape(data.source)}"}"""
        }
        return """{"type":"healthData","data":{"records":[$dataJson]}}"""
    }

    /**
     * Creates a response JSON for call action
     */
    fun createCallActionResponse(action: String, success: Boolean, message: String = ""): String {
        return """{"type":"callActionResponse","data":{"action":"${escape(action)}","success":$success,"message":"${escape(message)}"}}"""
    }

    /**
     * Creates a JSON for mirror status response
     */
    fun createMirrorStatusJson(isActive: Boolean, message: String = ""): String {
        return """{"type":"mirrorStatus","data":{"isActive":$isActive,"message":"${escape(message)}"}}"""
    }

    /**
     * Creates a JSON for file transfer with checksum
     */
    fun createFileTransferJson(fileName: String, fileSize: Long, chunks: List<String>, checksum: String? = null): String {
        val chunksJson = chunks.joinToString(",") { chunk ->
            "\"${escape(chunk)}\""
        }
        val checksumPart = checksum?.let { ",\"checksum\":\"$it\"" } ?: ""
        return """{"type":"fileTransfer","data":{"fileName":"${escape(fileName)}","fileSize":$fileSize,"chunks":[$chunksJson]$checksumPart}}"""
    }

    /**
     * Creates a response JSON for Mac media control action
     */
    fun createMacMediaControlResponse(action: String, success: Boolean, message: String = ""): String {
        return """{"type":"macMediaControlResponse","data":{"action":"${escape(action)}","success":$success,"message":"${escape(message)}"}}"""
    }
}
