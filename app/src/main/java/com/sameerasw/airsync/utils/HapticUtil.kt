package com.sameerasw.airsync.utils

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object HapticUtil {
    /**
     * Perform a light tick haptic - for subtle interactions like swiping
     */
    fun performLightTick(haptics: HapticFeedback?) {
        try {
            haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } catch (_: Exception) {
        }
    }

    /**
     * Perform a medium tick haptic - for toggles and buttons
     */
    fun performClick(haptics: HapticFeedback?) {
        try {
            haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
        } catch (_: Exception) {
        }
    }

    /**
     * Perform a toggle ON haptic - slightly stronger
     */
    fun performToggleOn(haptics: HapticFeedback?) {
        try {
            haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
        } catch (_: Exception) {
        }
    }

    /**
     * Perform a toggle OFF haptic - slightly lighter
     */
    fun performToggleOff(haptics: HapticFeedback?) {
        try {
            haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } catch (_: Exception) {
        }
    }

    /**
     * Perform a success haptic - 3 quick taps for successful operations
     */
    fun performSuccess(haptics: HapticFeedback?) {
        try {
            CoroutineScope(Dispatchers.Main).launch {
                haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(100)
                haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(50)
                haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Start repeating haptic ticks for loading states
     * Returns a Job that can be cancelled to stop the haptics
     *
     * If a lifecycle is provided, haptics will only fire while the lifecycle
     * is at least STARTED (i.e. the app/screen is in foreground). This prevents
     * haptics from triggering while the app is backgrounded.
     */
    fun startLoadingHaptics(haptics: HapticFeedback?, lifecycle: Lifecycle? = null): Job {
        return CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                try {
                    val shouldRun =
                        lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
                    if (shouldRun) {
                        performLightTick(haptics)
                        delay(200) // 5 times per second
                    } else {
                        // Backoff while app is backgrounded; poll until it becomes STARTED again
                        delay(200)
                    }
                } catch (_: Exception) {
                    // swallow any exceptions from lifecycle or haptics and continue
                    delay(200)
                }
            }
        }
    }
}
