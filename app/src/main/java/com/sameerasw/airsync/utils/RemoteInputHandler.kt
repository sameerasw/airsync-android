package com.sameerasw.airsync.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Handles remote input events (touch, gestures) for screen mirroring
 * Requires AccessibilityService permissions
 */
class RemoteInputHandler : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteInputHandler"
        private var instance: RemoteInputHandler? = null
        
        fun getInstance(): RemoteInputHandler? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Remote input handler connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for input injection
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Remote input handler destroyed")
    }

    /**
     * Inject a touch event at normalized coordinates (0.0 to 1.0)
     */
    fun injectTouch(normalizedX: Float, normalizedY: Float, action: TouchAction, durationMs: Long = 50) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gesture dispatch requires Android N+")
            return
        }

        try {
            val displayMetrics = resources.displayMetrics
            val x = normalizedX * displayMetrics.widthPixels
            val y = normalizedY * displayMetrics.heightPixels

            val path = Path().apply {
                moveTo(x, y)
            }

            val gestureBuilder = GestureDescription.Builder()
            val strokeDescription = GestureDescription.StrokeDescription(path, 0, durationMs)
            gestureBuilder.addStroke(strokeDescription)

            val gesture = gestureBuilder.build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.v(TAG, "Touch gesture completed: $action at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Touch gesture cancelled")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject touch", e)
        }
    }

    /**
     * Inject a swipe gesture
     */
    fun injectSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gesture dispatch requires Android N+")
            return
        }

        try {
            val displayMetrics = resources.displayMetrics
            val x1 = startX * displayMetrics.widthPixels
            val y1 = startY * displayMetrics.heightPixels
            val x2 = endX * displayMetrics.widthPixels
            val y2 = endY * displayMetrics.heightPixels

            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }

            val gestureBuilder = GestureDescription.Builder()
            val strokeDescription = GestureDescription.StrokeDescription(path, 0, durationMs)
            gestureBuilder.addStroke(strokeDescription)

            val gesture = gestureBuilder.build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.v(TAG, "Swipe gesture completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Swipe gesture cancelled")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject swipe", e)
        }
    }

    /**
     * Inject a scroll gesture
     */
    fun injectScroll(normalizedX: Float, normalizedY: Float, scrollAmount: Float) {
        val displayMetrics = resources.displayMetrics
        val startY = normalizedY * displayMetrics.heightPixels
        val endY = startY + (scrollAmount * displayMetrics.heightPixels)
        
        injectSwipe(
            normalizedX,
            normalizedY,
            normalizedX,
            endY / displayMetrics.heightPixels,
            200
        )
    }
}

enum class TouchAction {
    TAP,
    LONG_PRESS,
    DOUBLE_TAP
}
