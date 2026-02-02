package com.sameerasw.airsync.presentation.ui.activities

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.ClipboardUtil

class ClipboardActionActivity : Activity() {
    private var hasHandledClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        
        if (hasFocus && !hasHandledClipboard) {
            hasHandledClipboard = true
            handleClipboard()
            finish()
        }
    }

    private fun handleClipboard() {
        try {
            val clipboardText = ClipboardUtil.getClipboardText(this)
            
            if (!clipboardText.isNullOrEmpty()) {
                ClipboardSyncManager.syncTextToDesktop(clipboardText)
                Toast.makeText(this, "Clipboard sent", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to access clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}

