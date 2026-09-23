/*
 * Adapted from ClipSync (https://github.com/WinShell-Bhanu/Clipsync)
 * Copyright (c) 2026 Bhanu
 * Licensed under the MIT License. See THIRD_PARTY_NOTICES.md for the full license text.
 */
package com.sameerasw.airsync.service

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.activities.ClipboardGhostActivity
import com.sameerasw.airsync.utils.WebSocketUtil

class ClipboardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastEventTime = 0L
    private var lastLaunchTime = 0L
    private var copyWords = emptyList<String>()
    private var copiedWords = emptyList<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        copyWords = resources.getStringArray(R.array.clipboard_copy_words).map { it.lowercase() }
        copiedWords = resources.getStringArray(R.array.clipboard_copied_words).map { it.lowercase() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || isGameApp(pkg)) return
        if (!WebSocketUtil.isConnected()) return
        if (event.eventTime - lastEventTime < EVENT_DEBOUNCE_MS) return

        try {
            val detected = when (event.eventType) {
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                    event.className == "android.widget.Toast" &&
                            matches(event.text.joinToString(" "), copiedWords)

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val text = "${event.contentDescription ?: ""} ${event.text.joinToString(" ")}"
                    !isCopyright(text) && (sourceHasCopyAction(event) || matches(text, copyWords))
                }

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val text = "${event.contentDescription ?: ""} ${event.text.joinToString(" ")}"
                    !isCopyright(text) && matches(text, copiedWords)
                }

                else -> false
            }

            if (detected) {
                lastEventTime = event.eventTime
                handler.postDelayed(::launchReader, READ_DELAY_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling accessibility event", e)
        }
    }

    private fun sourceHasCopyAction(event: AccessibilityEvent): Boolean {
        val source = event.source ?: return false
        return try {
            source.actionList.any { it.id == AccessibilityNodeInfo.ACTION_COPY }
        } finally {
            if (Build.VERSION.SDK_INT < 34) {
                @Suppress("DEPRECATION")
                source.recycle()
            }
        }
    }

    private fun launchReader() {
        val now = System.currentTimeMillis()
        if (now - lastLaunchTime < LAUNCH_DEBOUNCE_MS) return
        lastLaunchTime = now
        ClipboardGhostActivity.read(this)
    }

    private fun matches(text: String, words: List<String>): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return words.any { lower.contains(it) }
    }

    private fun isCopyright(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("copyright") || lower.contains("©")
    }

    private fun isGameApp(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "ClipboardA11yService"
        private const val EVENT_DEBOUNCE_MS = 1000L
        private const val LAUNCH_DEBOUNCE_MS = 700L
        private const val READ_DELAY_MS = 50L
    }
}
