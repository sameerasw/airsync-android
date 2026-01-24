package com.sameerasw.airsync.health

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.models.HealthSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local storage cache for health data
 * Stores health summaries by date to reduce Health Connect queries
 */
object HealthDataCache {
    private const val TAG = "HealthDataCache"
    private const val CACHE_DIR = "health_cache"
    private const val CACHE_EXPIRY_DAYS = 30 // Keep data for 30 days
    
    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    private fun getCacheFile(context: Context, date: Long): File {
        val localDate = Instant.ofEpochMilli(date)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return File(getCacheDir(context), "health_${localDate}.json")
    }
    
    /**
     * Save health summary to cache
     */
    suspend fun saveSummary(context: Context, summary: HealthSummary) = withContext(Dispatchers.IO) {
        try {
            val file = getCacheFile(context, summary.date)
            val json = JSONObject().apply {
                put("date", summary.date)
                put("steps", summary.steps)
                put("distance", summary.distance)
                put("calories", summary.calories)
                put("activeMinutes", summary.activeMinutes)
                put("heartRateAvg", summary.heartRateAvg)
                put("heartRateMin", summary.heartRateMin)
                put("heartRateMax", summary.heartRateMax)
                put("sleepDuration", summary.sleepDuration)
                put("floorsClimbed", summary.floorsClimbed)
                put("weight", summary.weight)
                put("bloodPressureSystolic", summary.bloodPressureSystolic)
                put("bloodPressureDiastolic", summary.bloodPressureDiastolic)
                put("oxygenSaturation", summary.oxygenSaturation)
                put("restingHeartRate", summary.restingHeartRate)
                put("vo2Max", summary.vo2Max)
                put("bodyTemperature", summary.bodyTemperature)
                put("bloodGlucose", summary.bloodGlucose)
                put("hydration", summary.hydration)
                put("cachedAt", System.currentTimeMillis())
            }
            file.writeText(json.toString())
            Log.d(TAG, "Cached health data for date: ${summary.date}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving health cache", e)
        }
    }
    
    /**
     * Load health summary from cache
     * Returns null if not cached, cache is stale, or data is corrupted
     */
    suspend fun loadSummary(context: Context, date: Long): HealthSummary? = withContext(Dispatchers.IO) {
        try {
            val file = getCacheFile(context, date)
            if (!file.exists()) {
                return@withContext null
            }
            
            val content = file.readText()
            if (content.isBlank()) {
                Log.w(TAG, "Cache file is empty, deleting: ${file.name}")
                file.delete()
                return@withContext null
            }
            
            val json = try {
                JSONObject(content)
            } catch (e: Exception) {
                Log.e(TAG, "Corrupted cache file, deleting: ${file.name}", e)
                file.delete()
                return@withContext null
            }
            
            val cachedAt = json.optLong("cachedAt", 0)
            
            // Check if cache is stale (older than 1 hour for today, otherwise keep indefinitely)
            val isToday = isToday(date)
            if (isToday && System.currentTimeMillis() - cachedAt > 3600_000) {
                Log.d(TAG, "Cache stale for today's date")
                return@withContext null
            }
            
            // Validate date field exists and is reasonable
            val cachedDate = json.optLong("date", 0)
            if (cachedDate <= 0) {
                Log.w(TAG, "Invalid date in cache, deleting: ${file.name}")
                file.delete()
                return@withContext null
            }
            
            // Helper to safely get nullable Int (returns null if key is null or missing)
            fun getOptionalInt(key: String): Int? = 
                if (json.isNull(key) || !json.has(key)) null else json.optInt(key)
            
            // Helper to safely get nullable Long
            fun getOptionalLong(key: String): Long? = 
                if (json.isNull(key) || !json.has(key)) null else json.optLong(key)
            
            // Helper to safely get nullable Double
            fun getOptionalDouble(key: String): Double? = 
                if (json.isNull(key) || !json.has(key)) null else json.optDouble(key)
            
            HealthSummary(
                date = cachedDate,
                steps = getOptionalInt("steps"),
                distance = getOptionalDouble("distance"),
                calories = getOptionalInt("calories"),
                activeMinutes = getOptionalInt("activeMinutes"),
                heartRateAvg = getOptionalInt("heartRateAvg"),
                heartRateMin = getOptionalInt("heartRateMin"),
                heartRateMax = getOptionalInt("heartRateMax"),
                sleepDuration = getOptionalLong("sleepDuration"),
                floorsClimbed = getOptionalInt("floorsClimbed"),
                weight = getOptionalDouble("weight"),
                bloodPressureSystolic = getOptionalInt("bloodPressureSystolic"),
                bloodPressureDiastolic = getOptionalInt("bloodPressureDiastolic"),
                oxygenSaturation = getOptionalDouble("oxygenSaturation"),
                restingHeartRate = getOptionalInt("restingHeartRate"),
                vo2Max = getOptionalDouble("vo2Max"),
                bodyTemperature = getOptionalDouble("bodyTemperature"),
                bloodGlucose = getOptionalDouble("bloodGlucose"),
                hydration = getOptionalDouble("hydration")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading health cache", e)
            null
        }
    }
    
    /**
     * Get cached summary or fetch from Health Connect
     */
    suspend fun getSummaryWithCache(
        context: Context,
        date: Long,
        fetchFromHealthConnect: suspend (Long) -> HealthSummary?
    ): HealthSummary? {
        // Try cache first
        val cached = loadSummary(context, date)
        if (cached != null) {
            Log.d(TAG, "Using cached health data for date: $date")
            return cached
        }
        
        // Fetch from Health Connect
        Log.d(TAG, "Fetching fresh health data for date: $date")
        val fresh = fetchFromHealthConnect(date)
        
        // Cache the result
        if (fresh != null) {
            saveSummary(context, fresh)
        }
        
        return fresh
    }
    
    /**
     * Clear old cache files
     */
    suspend fun cleanOldCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getCacheDir(context)
            val cutoffDate = LocalDate.now().minusDays(CACHE_EXPIRY_DAYS.toLong())
            
            cacheDir.listFiles()?.forEach { file ->
                try {
                    // Extract date from filename: health_2024-12-31.json
                    val dateStr = file.nameWithoutExtension.removePrefix("health_")
                    val fileDate = LocalDate.parse(dateStr)
                    
                    if (fileDate.isBefore(cutoffDate)) {
                        file.delete()
                        Log.d(TAG, "Deleted old cache file: ${file.name}")
                    }
                } catch (e: Exception) {
                    // Skip invalid files
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning old cache", e)
        }
    }
    
    /**
     * Clear cache for a specific date (useful for forcing refresh)
     */
    suspend fun clearCacheForDate(context: Context, date: Long) = withContext(Dispatchers.IO) {
        try {
            val file = getCacheFile(context, date)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Cleared cache for date: $date")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for date", e)
        }
    }
    
    /**
     * Clear today's cache
     */
    suspend fun clearTodayCache(context: Context) = withContext(Dispatchers.IO) {
        clearCacheForDate(context, System.currentTimeMillis())
    }
    
    private fun isToday(timestamp: Long): Boolean {
        val date = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return date == LocalDate.now()
    }
}
