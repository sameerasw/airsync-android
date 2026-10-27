package com.sameerasw.airsync

import com.sameerasw.airsync.utils.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonUtilEscapeTest {

    @Test
    fun escapesQuotesBackslashesAndControlCharacters() {
        val json = JsonUtil.createMediaControlResponse(
            action = "play",
            success = true,
            message = "a\"b\\c\nd\re\tf\u0001g"
        )
        assertEquals(
            """{"type":"mediaControlResponse","data":{"action":"play","success":true,"message":"a\"b\\c\nd\re\tf\u0001g"}}""",
            json
        )
    }

    @Test
    fun escapesMediaTitleAndArtist() {
        val json = JsonUtil.createDeviceStatusJson(
            batteryLevel = 50,
            isCharging = false,
            isPaired = true,
            isPlaying = true,
            title = "Song \"Live\"",
            artist = "A\nB",
            volume = 5,
            isMuted = false,
            albumArt = null,
            likeStatus = "none"
        )
        assertTrue(json.contains(""""title":"Song \"Live\"","artist":"A\nB""""))
    }

    @Test
    fun escapesDeviceName() {
        val json = JsonUtil.createDeviceInfoJson(
            name = "My \"Pixel\"",
            ipAddress = "192.168.1.2",
            port = 6996,
            version = "4.1.0"
        )
        assertTrue(json.contains(""""name":"My \"Pixel\"""""))
    }
}
