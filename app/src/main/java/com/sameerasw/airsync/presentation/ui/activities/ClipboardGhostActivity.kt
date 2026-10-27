/*
 * Adapted from ClipSync (https://github.com/WinShell-Bhanu/Clipsync)
 * Copyright (c) 2026 Bhanu
 * Licensed under the MIT License. See THIRD_PARTY_NOTICES.md for the full license text.
 */
package com.sameerasw.airsync.presentation.ui.activities

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.sameerasw.airsync.utils.ClipboardSyncManager

/**
 * Invisible activity that holds focus just long enough to read the clipboard, since Android 10+
 * only allows the focused app to read it. Launched by ClipboardAccessibilityService.
 */
class ClipboardGhostActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var hasRead = false
    private var hasFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Taking focus without these makes the IME hide and reappear in the source app
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
        // A 1px floating window gets focus without covering or restyling the system bars
        window.setLayout(1, 1)
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setDimAmount(0f)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        super.onCreate(savedInstanceState)
        disableTransition(open = true)
        handler.postDelayed(::finishSafely, SAFETY_TIMEOUT_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hasRead = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || hasRead || hasFinished) return
        hasRead = true
        handler.postDelayed(::readAndFinish, READ_SETTLE_MS)
    }

    private fun readAndFinish() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
            if (!text.isNullOrBlank()) {
                ClipboardSyncManager.onBackgroundClipboardCaptured(applicationContext, text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clipboard", e)
        } finally {
            finishSafely()
        }
    }

    private fun finishSafely() {
        if (hasFinished) return
        hasFinished = true
        handler.removeCallbacksAndMessages(null)
        finish()
    }

    override fun finish() {
        super.finish()
        disableTransition(open = false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun disableTransition(open: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(
                if (open) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE, 0, 0
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "ClipboardGhostActivity"
        private const val SAFETY_TIMEOUT_MS = 2000L
        private const val READ_SETTLE_MS = 150L

        fun read(context: Context) {
            try {
                context.startActivity(
                    Intent(context, ClipboardGhostActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to launch clipboard reader", e)
            }
        }
    }
}
