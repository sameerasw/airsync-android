package com.sameerasw.airsync.utils

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object ClipboardSyncManager {
    private const val TAG = "ClipboardSyncManager"

    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var isEnabled = false
    private var lastSentText: String? = null
    private var lastReceivedText: String? = null
    private var syncJob: Job? = null
    private val syncScope = CoroutineScope(Dispatchers.IO)

    // Callback for clipboard history tracking
    private var onClipboardSent: ((text: String) -> Unit)? = null

    fun setOnClipboardSentCallback(callback: ((text: String) -> Unit)?) {
        onClipboardSent = callback
    }

    /**
     * Start clipboard sync
     */
    fun startSync(context: Context) {
        if (isEnabled) {
            Log.d(TAG, "Clipboard sync already enabled")
            return
        }

        syncJob = syncScope.launch {
            try {
                val dataStoreManager = DataStoreManager(context)
                val isClipboardSyncEnabled = dataStoreManager.getClipboardSyncEnabled().first()

                if (!isClipboardSyncEnabled) {
                    Log.d(TAG, "Clipboard sync is disabled in settings")
                    return@launch
                }

                // Clipboard monitoring disabled - only sync via manual share method
                isEnabled = true
                Log.d(TAG, "Clipboard sync started (share method only)")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting clipboard sync: ${e.message}")
            }
        }
    }

    /**
     * Stop clipboard sync
     */
    fun stopSync(context: Context) {
        if (!isEnabled) return

        clipboardListener?.let { listener ->
            ClipboardUtil.unregisterClipboardListener(context, listener)
            clipboardListener = null
        }

        syncJob?.cancel()
        isEnabled = false
        Log.d(TAG, "Clipboard sync stopped")
    }

    /**
     * Send clipboard text to desktop
     */
    private fun syncClipboardToDesktop(text: String) {
        if (text.isBlank()) return

        syncScope.launch(Dispatchers.IO) {
            try {
                lastSentText = text
                val clipboardJson = JsonUtil.createClipboardUpdateJson(text)

                Log.d(TAG, "Sending clipboard JSON: $clipboardJson")

                val success = WebSocketUtil.sendMessage(clipboardJson)
                if (success) {
                    Log.d(TAG, "Clipboard synced to desktop: ${text.take(50)}...")
                    // Notify callback about sent clipboard
                    onClipboardSent?.invoke(text)
                } else {
                    Log.w(TAG, "Failed to sync clipboard to desktop")
                    // Reset lastSentText if sending failed
                    lastSentText = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing clipboard to desktop: ${e.message}")
                // Reset lastSentText if sending failed
                lastSentText = null
            }
        }
    }

    private fun isLinkOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Split by whitespace to check if it's only a single token (no other text)
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size != 1) return false

        val url = tokens[0]

        // Check for explicit protocols
        if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("ftp://", ignoreCase = true)
        ) {
            return true
        }

        // Check for www prefix without protocol
        if (url.startsWith("www.", ignoreCase = true)) {
            return isValidDomainFormat(url.substring(4))
        }

        // Check if it looks like a domain name (no protocol, no www)
        return isValidDomainFormat(url)
    }

    private fun isValidDomainFormat(domain: String): Boolean {
        if (domain.isEmpty()) return false

        // Basic domain validation regex
        // Matches: domain.com, sub.domain.com, domain.co.uk, etc.
        val domainRegex = Regex(
            "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
                    "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*" +
                    "\\.[a-zA-Z]{2,}$",
            RegexOption.IGNORE_CASE
        )

        return domainRegex.matches(domain)
    }

    /**
     * Handle clipboard update received from desktop
     */
    fun handleClipboardUpdate(context: Context, text: String) {
        try {
            lastReceivedText = text

            // Always update clipboard with the full received text
            val updated = ClipboardUtil.setClipboardText(context, text)
            if (updated) {
                Log.d(TAG, "Clipboard updated from desktop: ${text.take(200)}")
            } else {
                Log.w(TAG, "Failed to update clipboard from desktop")
            }

            // Check Continue Browsing setting and show notification only if the text is a pure link
            val dataStoreManager = DataStoreManager(context)
            syncScope.launch {
                val continueEnabled = try {
                    dataStoreManager.getContinueBrowsingEnabled().first()
                } catch (_: Exception) {
                    true
                }
                val keepPrevious = try {
                    dataStoreManager.getKeepPreviousLinkEnabled().first()
                } catch (_: Exception) {
                    true
                }
                // Only for Plus and while connected
                val isConnected = WebSocketUtil.isConnected()
                val last = try {
                    dataStoreManager.getLastConnectedDevice().first()
                } catch (_: Exception) {
                    null
                }
                val isPlus = last?.isPlus == true
                if (continueEnabled && isConnected && isPlus && isLinkOnly(text)) {
                    NotificationUtil.showContinueBrowsingLink(context, text.trim(), keepPrevious)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard update: ${e.message}")
        }
    }

    /**
     * Manually sync specific text (share target)
     */
    fun syncTextToDesktop(text: String) {
        if (text.isBlank()) return

        try {
            val clipboardJson = JsonUtil.createClipboardUpdateJson(text)

            val success = WebSocketUtil.sendMessage(clipboardJson)
            if (success) {
                Log.d(TAG, "Text shared to desktop: ${text.take(50)}...")
                // Notify callback about sent clipboard
                onClipboardSent?.invoke(text)
            } else {
                Log.w(TAG, "Failed to share text to desktop")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing text to desktop: ${e.message}")
        }
    }
}
