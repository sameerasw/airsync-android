package com.sameerasw.airsync.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.activities.ClipboardActionActivity

object ShortcutUtil {
    private const val SHORTCUT_ID_SEND_TO_MAC = "shortcut_send_to_mac"

    fun updateShareShortcut(context: Context, macName: String?) {
        val macDisplayName = macName ?: context.getString(R.string.your_mac)
        
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID_SEND_TO_MAC)
            .setShortLabel(macDisplayName)
            .setLongLabel(context.getString(R.string.tab_clipboard) + " - " + macDisplayName)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_laptop_24))
            .setIntent(
                Intent(context, ClipboardActionActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "") // Placeholder
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            .setCategories(setOf("com.sameerasw.airsync.categories.SHARE_TARGET"))
            .setLongLived(true)
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun removeShareShortcut(context: Context) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(SHORTCUT_ID_SEND_TO_MAC))
    }
}
