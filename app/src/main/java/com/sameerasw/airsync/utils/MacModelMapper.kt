package com.sameerasw.airsync.utils

import android.content.Context
import androidx.annotation.DrawableRes
import com.sameerasw.airsync.AirSyncApp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.ConnectedDevice
import org.json.JSONObject

object MacModelMapper {

    enum class MacFamily {
        MACBOOK_AIR,
        MACBOOK_PRO,
        MAC_MINI,
        IMAC,
        MAC_STUDIO,
        MAC_PRO,
        MACBOOK_NEO
    }

    private var isInitialized = false

    // Maps model identifiers (e.g., "Mac17,4") to their category name (e.g., "MacBook Air") and icon generation
    private val modelToCategory = mutableMapOf<String, String>()
    private val modelToIconGen = mutableMapOf<String, String>()
    private val modelToMarketingName = mutableMapOf<String, String>()

    private fun ensureLoaded() {
        if (isInitialized) return
        val context = AirSyncApp.getContext()
        if (context != null) {
            loadFromContext(context)
        }
    }

    fun init(context: Context) {
        if (!isInitialized) {
            loadFromContext(context)
        }
    }

    private fun loadFromContext(context: Context) {
        try {
            val jsonString = context.assets.open("mac_device_mappings.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val keys = root.keys()
            while (keys.hasNext()) {
                val category = keys.next()
                val categoryObj = root.getJSONObject(category)
                val modelKeys = categoryObj.keys()
                while (modelKeys.hasNext()) {
                    val key = modelKeys.next()
                    if (key.endsWith("_icon")) {
                        val baseModel = key.removeSuffix("_icon")
                        modelToIconGen[baseModel] = categoryObj.getString(key)
                    } else {
                        val marketingName = categoryObj.getString(key)
                        modelToCategory[key] = category
                        modelToMarketingName[key] = marketingName
                    }
                }
            }
            isInitialized = true
        } catch (_: Exception) {
            // Fallback gracefully
        }
    }

    fun getFamily(device: ConnectedDevice?): MacFamily {
        return getFamily(device?.name ?: "", device?.model, device?.deviceType)
    }

    fun getFamily(name: String, model: String?, deviceType: String?): MacFamily {
        ensureLoaded()
        val modelStr = model?.replace(" ", "") ?: ""
        val nameStr = name.replace(" ", "").lowercase()
        val typeStr = deviceType?.replace(" ", "")?.lowercase() ?: ""
        val hay = "$nameStr$modelStr$typeStr".lowercase()

        val jsonCategory = if (modelStr.isNotEmpty()) modelToCategory[modelStr] else null

        return when {
            jsonCategory == "MacBook Air" || hay.contains("macbookair") -> MacFamily.MACBOOK_AIR
            jsonCategory == "MacBook Pro" || hay.contains("macbookpro") -> MacFamily.MACBOOK_PRO
            jsonCategory == "MacBook Neo" || modelStr.contains("Mac17,5", ignoreCase = true) || hay.contains("macbookneo") -> MacFamily.MACBOOK_NEO
            jsonCategory == "Mac mini" || hay.contains("macmini") -> MacFamily.MAC_MINI
            jsonCategory == "iMac" || hay.contains("imac") -> MacFamily.IMAC
            jsonCategory == "Mac Studio" || hay.contains("macstudio") -> MacFamily.MAC_STUDIO
            jsonCategory == "Mac Pro" || hay.contains("macpro") -> MacFamily.MAC_PRO
            hay.contains("air") -> MacFamily.MACBOOK_AIR
            hay.contains("pro") -> MacFamily.MACBOOK_PRO
            else -> MacFamily.MACBOOK_AIR
        }
    }

    /**
     * Returns the asset path for the 3D model if available (MacBook Pro / MacBook Air), or null otherwise.
     */
    fun get3DModelPath(device: ConnectedDevice?): String? {
        ensureLoaded()
        val name = device?.name ?: ""
        val model = device?.model
        val deviceType = device?.deviceType

        val modelStr = model?.replace(" ", "") ?: ""
        val nameStr = name.replace(" ", "").lowercase()
        val typeStr = deviceType?.replace(" ", "")?.lowercase() ?: ""
        val hay = "$nameStr$modelStr$typeStr".lowercase()

        // Exclude desktop / non-laptop models
        val jsonCategory = if (modelStr.isNotEmpty()) modelToCategory[modelStr] else null
        if (jsonCategory == "Mac mini" || jsonCategory == "iMac" || jsonCategory == "Mac Studio" || jsonCategory == "Mac Pro" ||
            hay.contains("macmini") || hay.contains("imac") || hay.contains("macstudio") || hay.contains("macpro")
        ) {
            return null
        }

        return when (getFamily(device)) {
            MacFamily.MACBOOK_AIR -> "models/macbook_air.glb"
            MacFamily.MACBOOK_PRO -> "models/macbook_pro.glb"
            else -> null
        }
    }

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
        return getPreviewRes(name, model, deviceType)
    }

    @DrawableRes
    fun getTileIconRes(name: String, model: String?, deviceType: String?): Int {
        return when (getFamily(name, model, deviceType)) {
            MacFamily.MACBOOK_AIR, MacFamily.MACBOOK_PRO, MacFamily.MACBOOK_NEO -> R.drawable.rounded_laptop_mac_24
            MacFamily.MAC_MINI -> R.drawable.ic_mac_mini_24
            MacFamily.IMAC -> R.drawable.ic_desktop_24
            MacFamily.MAC_STUDIO -> R.drawable.ic_mac_studio_24
            MacFamily.MAC_PRO -> R.drawable.ic_mac_pro_24
        }
    }

    @DrawableRes
    private fun resolveDrawable(model: String, hay: String): Int {
        ensureLoaded()
        val jsonCategory = if (model.isNotEmpty()) modelToCategory[model] else null
        val iconGen = if (model.isNotEmpty()) modelToIconGen[model] else null

        return when {
            // Explicit Icon Gen from JSON
            iconGen == "macbook.gen1" -> R.drawable.macbook_air_gen2
            iconGen == "macmini.gen2" -> R.drawable.macmini_gen3

            // Specific JSON mapped categories & fallback heuristics
            jsonCategory == "MacBook Air" -> {
                if (model.startsWith("MacBookAir")) R.drawable.macbook_air_gen2 else R.drawable.macbook_air_gen3
            }
            jsonCategory == "MacBook Pro" -> {
                if (model.startsWith("MacBookPro15") || model.startsWith("MacBookPro16") || model.startsWith("MacBookPro17") || model == "Mac14,7")
                    R.drawable.macbook_pro_gen2
                else
                    R.drawable.macbook_pro_gen3
            }
            jsonCategory == "Mac mini" -> {
                if (model == "Mac16,10" || model == "Mac16,11") R.drawable.macmini_gen3 else R.drawable.macmini_gen1
            }
            jsonCategory == "iMac" -> {
                if (model.startsWith("iMac19") || model.startsWith("iMac20") || model.startsWith("iMacPro")) R.drawable.imac_gen2 else R.drawable.imac_gen3
            }
            jsonCategory == "Mac Studio" -> R.drawable.macstudio_gen1
            jsonCategory == "Mac Pro" -> R.drawable.macpro_gen3
            jsonCategory == "MacBook Neo" || model.contains("Mac17,5", ignoreCase = true) -> R.drawable.macbook_neo

            // Keyword fallback
            hay.contains("macbookair") -> R.drawable.macbook_air_gen2
            hay.contains("macbookpro") -> R.drawable.macbook_pro_gen3
            hay.contains("macmini") -> R.drawable.macmini_gen1
            hay.contains("imac") -> R.drawable.imac_gen3
            hay.contains("macstudio") -> R.drawable.macstudio_gen1
            hay.contains("macpro") -> R.drawable.macpro_gen3
            hay.contains("macbookneo") -> R.drawable.macbook_neo

            else -> R.drawable.macbook_air_gen2
        }
    }
}
