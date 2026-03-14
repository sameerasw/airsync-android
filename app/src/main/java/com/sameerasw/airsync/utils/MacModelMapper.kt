package com.sameerasw.airsync.utils

import androidx.annotation.DrawableRes
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.ConnectedDevice

object MacModelMapper {

    @DrawableRes
    fun getPreviewRes(device: ConnectedDevice?): Int {
        if (device == null) return R.drawable.macbook_air_gen2
        return getPreviewRes(device.name, device.model, device.deviceType)
    }

    @DrawableRes
    fun getIconRes(device: ConnectedDevice?): Int {
        if (device == null) return R.drawable.macbook_air_gen2
        return getIconRes(device.name, device.model, device.deviceType)
    }

    @DrawableRes
    fun getTileIconRes(device: ConnectedDevice?): Int {
        if (device == null) return R.drawable.rounded_laptop_mac_24
        return getTileIconRes(device.name, device.model, device.deviceType)
    }

    @DrawableRes
    fun getPreviewRes(name: String, model: String?, deviceType: String?): Int {
        val modelStr = model?.replace(" ", "") ?: ""
        val nameStr = name.replace(" ", "").lowercase()
        val typeStr = deviceType?.replace(" ", "")?.lowercase() ?: ""
        val hay = "$nameStr$modelStr$typeStr".lowercase()
        return resolveDrawable(modelStr, hay)
    }

    @DrawableRes
    fun getIconRes(name: String, model: String?, deviceType: String?): Int {
        // For now, same logic as preview as per user request to use these vectors everywhere
        return getPreviewRes(name, model, deviceType)
    }

    @DrawableRes
    fun getTileIconRes(name: String, model: String?, deviceType: String?): Int {
        val modelStr = model?.replace(" ", "") ?: ""
        val nameStr = name.replace(" ", "").lowercase()
        val typeStr = deviceType?.replace(" ", "")?.lowercase() ?: ""
        val hay = "$nameStr$modelStr$typeStr".lowercase()
        
        return when {
            hay.contains("macbookair") -> R.drawable.rounded_laptop_mac_24
            hay.contains("macbookpro") -> R.drawable.rounded_laptop_mac_24
            hay.contains("macmini") -> R.drawable.ic_mac_mini_24
            hay.contains("imac") -> R.drawable.ic_desktop_24
            hay.contains("macstudio") -> R.drawable.ic_mac_studio_24
            hay.contains("macpro") -> R.drawable.ic_mac_pro_24
            hay.contains("macbookneo") -> R.drawable.rounded_laptop_mac_24
            else -> R.drawable.rounded_laptop_mac_24
        }
    }

    @DrawableRes
    private fun resolveDrawable(model: String, hay: String): Int {
        // 1) Explicit Model Matching based on user mapping
        return when {
            // MacBook Air Gen 3
            isMatch(model, listOf("Mac17,4", "Mac17,3", "Mac16,13", "Mac16,12", "Mac15,13", "Mac15,12", "Mac14,15", "Mac14,2")) -> R.drawable.macbook_air_gen3

            // MacBook Air Gen 2
            isMatch(model, listOf("MacBookAir10,1", "MacBookAir9,1", "MacBookAir8,2", "MacBookAir8,1")) -> R.drawable.macbook_air_gen2

            // MacBook Pro Gen 3
            isMatch(model, listOf("Mac17,7", "Mac17,9", "Mac17,6", "Mac17,8", "Mac17,2", "Mac16,1", "Mac16,6", "Mac16,8", "Mac16,7", "Mac16,5", "Mac15,3", "Mac15,6", "Mac15,8", "Mac15,10", "Mac15,7", "Mac15,9", "Mac15,11", "Mac14,5", "Mac14,9", "Mac14,6", "Mac14,10", "MacBookPro18,3", "MacBookPro18,4", "MacBookPro18,1", "MacBookPro18,2")) -> R.drawable.macbook_pro_gen3

            // MacBook Pro Gen 2
            isMatch(model, listOf("Mac14,7", "MacBookPro17,1", "MacBookPro16,3", "MacBookPro16,2", "MacBookPro16,1", "MacBookPro16,4", "MacBookPro15,4", "MacBookPro15,1", "MacBookPro15,3", "MacBookPro15,2")) -> R.drawable.macbook_pro_gen2

            // Mac mini Gen 3
            isMatch(model, listOf("Mac16,11", "Mac16,10")) -> R.drawable.macmini_gen3

            // iMac Gen 3
            isMatch(model, listOf("Mac16,3", "Mac16,2", "Mac15,5", "Mac15,4", "iMac21,1", "iMac21,2")) -> R.drawable.imac_gen3

            // iMac Gen 2
            isMatch(model, listOf("iMac20,1", "iMac20,2", "iMac19,1", "iMac19,2", "iMacPro1,1")) -> R.drawable.imac_gen2

            // MacBook Neo
            model.contains("Mac17,5", ignoreCase = true) -> R.drawable.macbook_neo

            // 2) Category-based fallbacks if no specific model match
            hay.contains("macbookair") -> R.drawable.macbook_air_gen2
            hay.contains("macbookpro") -> R.drawable.macbook_pro_gen3
            hay.contains("macmini") -> R.drawable.macmini_gen1
            hay.contains("imac") -> R.drawable.imac_gen3
            hay.contains("macstudio") -> R.drawable.macstudio_gen1
            hay.contains("macpro") -> R.drawable.macpro_gen3
            hay.contains("macbookneo") -> R.drawable.macbook_neo

            // 3) Final Absolute Fallback
            else -> R.drawable.macbook_air_gen2
        }
    }

    private fun isMatch(model: String, list: List<String>): Boolean {
        return list.any { model.contains(it, ignoreCase = true) }
    }
}
