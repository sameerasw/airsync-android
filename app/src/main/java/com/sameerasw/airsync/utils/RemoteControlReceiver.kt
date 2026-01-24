package com.sameerasw.airsync.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Receives and processes remote control commands from the client
 */
class RemoteControlReceiver {

    companion object {
        private const val TAG = "RemoteControlReceiver"
    }

    private val inputHandler: RemoteInputHandler?
        get() = RemoteInputHandler.getInstance()

    /**
     * Process incoming remote control command
     * Expected JSON format:
     * {
     *   "type": "touch|swipe|scroll|key",
     *   "x": 0.5,
     *   "y": 0.5,
     *   "action": "tap|long_press|double_tap",
     *   "endX": 0.6,  // for swipe
     *   "endY": 0.7,  // for swipe
     *   "scrollAmount": -0.1,  // for scroll
     *   "keyCode": 4  // for key events (back, home, etc.)
     * }
     */
    fun processCommand(commandJson: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val json = JSONObject(commandJson)
                val type = json.getString("type")

                when (type) {
                    "touch" -> handleTouch(json)
                    "swipe" -> handleSwipe(json)
                    "scroll" -> handleScroll(json)
                    "key" -> handleKey(json)
                    else -> Log.w(TAG, "Unknown command type: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process command: $commandJson", e)
            }
        }
    }

    private fun handleTouch(json: JSONObject) {
        val x = json.getDouble("x").toFloat()
        val y = json.getDouble("y").toFloat()
        val actionStr = json.optString("action", "tap")
        
        val action = when (actionStr) {
            "long_press" -> TouchAction.LONG_PRESS
            "double_tap" -> TouchAction.DOUBLE_TAP
            else -> TouchAction.TAP
        }

        val duration = when (action) {
            TouchAction.LONG_PRESS -> 500L
            TouchAction.DOUBLE_TAP -> 50L
            else -> 50L
        }

        inputHandler?.injectTouch(x, y, action, duration)
        
        // For double tap, inject second tap
        if (action == TouchAction.DOUBLE_TAP) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                inputHandler?.injectTouch(x, y, TouchAction.TAP, 50L)
            }, 100)
        }

        Log.d(TAG, "Touch: $action at ($x, $y)")
    }

    private fun handleSwipe(json: JSONObject) {
        val startX = json.getDouble("x").toFloat()
        val startY = json.getDouble("y").toFloat()
        val endX = json.getDouble("endX").toFloat()
        val endY = json.getDouble("endY").toFloat()
        val duration = json.optLong("duration", 300)

        inputHandler?.injectSwipe(startX, startY, endX, endY, duration)
        Log.d(TAG, "Swipe: ($startX, $startY) -> ($endX, $endY)")
    }

    private fun handleScroll(json: JSONObject) {
        val x = json.getDouble("x").toFloat()
        val y = json.getDouble("y").toFloat()
        val scrollAmount = json.getDouble("scrollAmount").toFloat()

        inputHandler?.injectScroll(x, y, scrollAmount)
        Log.d(TAG, "Scroll: at ($x, $y) amount=$scrollAmount")
    }

    private fun handleKey(json: JSONObject) {
        val keyCode = json.getInt("keyCode")
        // Implement key event injection if needed
        Log.d(TAG, "Key event: keyCode=$keyCode")
    }
}
