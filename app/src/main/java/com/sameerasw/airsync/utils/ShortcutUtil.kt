package com.sameerasw.airsync.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.activities.ClipboardActionActivity

object ShortcutUtil {
    const val SHORTCUT_ID_SCAN = "shortcut_scan"
    const val SHORTCUT_ID_RECONNECT = "shortcut_reconnect"
    const val SHORTCUT_ID_CLIPBOARD = "shortcut_clipboard"
    const val SHORTCUT_ID_LOCK = "shortcut_lock"
    const val SHORTCUT_ID_REMOTE = "shortcut_remote"
    const val SHORTCUT_ID_DISCONNECT = "shortcut_disconnect"

    const val DASH_ACTION_RECONNECT = "com.sameerasw.airsync.ACTION_RECONNECT"
    const val DASH_ACTION_CLIPBOARD = "com.sameerasw.airsync.ACTION_CLIPBOARD"
    const val DASH_ACTION_LOCK = "com.sameerasw.airsync.ACTION_LOCK"
    const val DASH_ACTION_REMOTE = "com.sameerasw.airsync.ACTION_REMOTE"
    const val DASH_ACTION_DISCONNECT = "com.sameerasw.airsync.ACTION_DISCONNECT"

    fun refreshShortcuts(context: Context, isConnected: Boolean) {
        val shortcuts = mutableListOf<ShortcutInfoCompat>()

        if (!isConnected) {
            // 1. Scan (Only when disconnected)
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_SCAN)
                    .setShortLabel("Scan")
                    .setLongLabel("Scan QR Code")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_qr_code_scanner_24))
                    .setIntent(Intent(context, com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity::class.java).apply {
                        action = "com.sameerasw.airsync.SCAN_QR"
                    })
                    .build()
            )

            // 2. Reconnect
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_RECONNECT)
                    .setShortLabel("Reconnect")
                    .setLongLabel("Reconnect")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_devices_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_RECONNECT
                    })
                    .build()
            )
        } else {
            // 1. Remote (Direct to Remote tab)
            // Use MainActivity with a specific action
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_REMOTE)
                    .setShortLabel("Remote")
                    .setLongLabel("Remote Control")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_compare_arrows_24))
                    .setIntent(Intent(context, com.sameerasw.airsync.MainActivity::class.java).apply {
                        action = DASH_ACTION_REMOTE
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })
                    .build()
            )

            // 2. Clipboard
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_CLIPBOARD)
                    .setShortLabel("Clipboard")
                    .setLongLabel("Send Clipboard")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_clipboard_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_CLIPBOARD
                    })
                    .build()
            )

            // 3. Lock
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_LOCK)
                    .setShortLabel("Lock")
                    .setLongLabel("Lock Mac")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_lock_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_LOCK
                    })
                    .build()
            )

            // 4. Disconnect
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_DISCONNECT)
                    .setShortLabel("Disconnect")
                    .setLongLabel("Disconnect")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_mimo_disconnect_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_DISCONNECT
                    })
                    .build()
            )
        }

        // Set dynamic shortcuts (replaces existing ones)
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    // Legacy method cleanup
    fun removeShareShortcut(context: Context) {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }
}
