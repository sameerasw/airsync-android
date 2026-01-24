package com.sameerasw.airsync.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class HealthStats(
    val steps: Long = 0,
    val calories: Double = 0.0,
    val distance: Double = 0.0,
    val heartRate: Long = 0,
    val heartRateMin: Long = 0,
    val heartRateMax: Long = 0,
    val sleepHours: Double = 0.0,
    val activeMinutes: Long = 0,
    val floorsClimbed: Long = 0,
    val weight: Double = 0.0,
    val bloodPressureSystolic: Int = 0,
    val bloodPressureDiastolic: Int = 0,
    val oxygenSaturation: Double = 0.0,
    val restingHeartRate: Long = 0,
    val vo2Max: Double = 0.0,
    val bodyTemperature: Double = 0.0,
    val bloodGlucose: Double = 0.0,
    val hydration: Double = 0.0
)

class SimpleHealthConnectManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SimpleHealthConnect"
        
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(Vo2MaxRecord::class),
            HealthPermission.getReadPermission(BodyTemperatureRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class)
        )
    }
    
    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }
    
    fun isAvailable(): Boolean {
        return try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Health Connect not available", e)
            false
        }
    }
    
    suspend fun hasPermissions(): Boolean = withContext(Dispatchers.IO) {
        try {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            Log.d(TAG, "Total granted permissions: ${granted.size}")
            
            // Check if we have at least the basic permissions (steps, calories, distance)
            val basicPermissions = setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class)
            )
            
            val hasBasic = basicPermissions.all { it in granted }
            Log.d(TAG, "Has basic permissions: $hasBasic")
            
            basicPermissions.forEach { permission ->
                val hasThis = permission in granted
                Log.d(TAG, "Permission $permission: $hasThis")
            }
            
            hasBasic
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }
    
    suspend fun hasAllPermissions(): Boolean = withContext(Dispatchers.IO) {
        try {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            val hasAll = PERMISSIONS.all { it in granted }
            Log.d(TAG, "Has all permissions: $hasAll (${granted.size}/${PERMISSIONS.size})")
            
            if (!hasAll) {
                val missing = PERMISSIONS.filter { it !in granted }
                Log.d(TAG, "Missing permissions: ${missing.size}")
                missing.forEach { permission ->
                    Log.d(TAG, "Missing: $permission")
                }
            }
            
            hasAll
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }
    
    suspend fun getMissingPermissions(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            PERMISSIONS.filter { it !in granted }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting missing permissions", e)
            emptySet()
        }
    }
    
    suspend fun getTodayStats(): HealthStats = withContext(Dispatchers.IO) {
        getStatsForDate(java.time.LocalDate.now())
    }
    
    /**
     * Get health stats for a specific date with caching
     */
    suspend fun getStatsForDate(
        date: java.time.LocalDate,
        forceRefresh: Boolean = false
    ): HealthStats = withContext(Dispatchers.IO) {
        try {
            // Use system default timezone
            val zoneId = ZoneId.systemDefault()
            val startOfDay = date.atStartOfDay(zoneId)
            val start = startOfDay.toInstant()
            
            // For today, use current time; for past dates, use end of day
            val end = if (date == java.time.LocalDate.now()) {
                Instant.now()
            } else {
                startOfDay.plusDays(1).minusSeconds(1).toInstant()
            }
            
            val timestamp = start.toEpochMilli()
            
            Log.d(TAG, "=== Health Data Query ===")
            Log.d(TAG, "Date: $date")
            Log.d(TAG, "Timezone: $zoneId")
            Log.d(TAG, "Start: $start (${startOfDay})")
            Log.d(TAG, "End: $end")
            Log.d(TAG, "Is Today: ${date == java.time.LocalDate.now()}")
            
            // Try cache first unless force refresh
            if (forceRefresh) {
                // Clear cache for this date when force refreshing
                HealthDataCache.clearCacheForDate(context, timestamp)
                Log.d(TAG, "Force refresh: cleared cache for $date")
            } else {
                val cached = HealthDataCache.loadSummary(context, timestamp)
                if (cached != null) {
                    Log.d(TAG, "Using cached health data for $date")
                    Log.d(TAG, "Cached steps: ${cached.steps}, calories: ${cached.calories}")
                    return@withContext HealthStats(
                        steps = cached.steps?.toLong() ?: 0L,
                        calories = cached.calories?.toDouble() ?: 0.0,
                        distance = cached.distance ?: 0.0,
                        heartRate = cached.heartRateAvg?.toLong() ?: 0L,
                        heartRateMin = cached.heartRateMin?.toLong() ?: 0L,
                        heartRateMax = cached.heartRateMax?.toLong() ?: 0L,
                        sleepHours = (cached.sleepDuration ?: 0L) / 60.0,
                        activeMinutes = cached.activeMinutes?.toLong() ?: 0L,
                        floorsClimbed = cached.floorsClimbed?.toLong() ?: 0L,
                        weight = cached.weight ?: 0.0,
                        bloodPressureSystolic = cached.bloodPressureSystolic ?: 0,
                        bloodPressureDiastolic = cached.bloodPressureDiastolic ?: 0,
                        oxygenSaturation = cached.oxygenSaturation ?: 0.0,
                        restingHeartRate = cached.restingHeartRate?.toLong() ?: 0L,
                        vo2Max = cached.vo2Max ?: 0.0,
                        bodyTemperature = cached.bodyTemperature ?: 0.0,
                        bloodGlucose = cached.bloodGlucose ?: 0.0,
                        hydration = cached.hydration ?: 0.0
                    )
                }
            }
            
            Log.d(TAG, "Fetching fresh health data for $date")
            
            // Get heart rate stats
            val heartRateStats = getHeartRateStats(start, end)
            val bloodPressure = getBloodPressure(start, end)
            
            // Check permissions before fetching
            val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
            Log.d(TAG, "Granted permissions count: ${grantedPermissions.size}")
            grantedPermissions.forEach { perm ->
                Log.d(TAG, "  - $perm")
            }
            
            // Fetch each metric individually with logging
            val steps = getSteps(start, end)
            val calories = getCalories(start, end)
            val distance = getDistance(start, end)
            val sleep = getSleep(start, end)
            val activeMinutes = getActiveMinutes(start, end)
            
            Log.d(TAG, "=== Fetched Metrics ===")
            Log.d(TAG, "Steps: $steps")
            Log.d(TAG, "Calories: $calories")
            Log.d(TAG, "Distance: $distance km")
            Log.d(TAG, "Sleep: $sleep hours")
            Log.d(TAG, "Active Minutes: $activeMinutes")
            Log.d(TAG, "Heart Rate - Avg: ${heartRateStats.avg}, Min: ${heartRateStats.min}, Max: ${heartRateStats.max}")
            
            val stats = HealthStats(
                steps = steps,
                calories = calories,
                distance = distance,
                heartRate = heartRateStats.avg,
                heartRateMin = heartRateStats.min,
                heartRateMax = heartRateStats.max,
                sleepHours = sleep,
                activeMinutes = activeMinutes,
                floorsClimbed = getFloorsClimbed(start, end),
                weight = getWeight(start, end),
                bloodPressureSystolic = bloodPressure?.first ?: 0,
                bloodPressureDiastolic = bloodPressure?.second ?: 0,
                oxygenSaturation = getOxygenSaturation(start, end),
                restingHeartRate = getRestingHeartRate(start, end),
                vo2Max = getVo2Max(start, end),
                bodyTemperature = getBodyTemperature(start, end),
                bloodGlucose = getBloodGlucose(start, end),
                hydration = getHydration(start, end)
            )
            
            Log.d(TAG, "Final stats created: $stats")
            
            // Cache the result - preserve 0 values as valid data, only use null for truly missing data
            val summary = com.sameerasw.airsync.models.HealthSummary(
                date = timestamp,
                steps = stats.steps.toInt(),  // 0 steps is valid data
                distance = if (stats.distance > 0) stats.distance else null,
                calories = if (stats.calories > 0) stats.calories.toInt() else null,
                activeMinutes = stats.activeMinutes.toInt(),  // 0 active minutes is valid
                heartRateAvg = if (stats.heartRate > 0) stats.heartRate.toInt() else null,
                heartRateMin = if (stats.heartRateMin > 0) stats.heartRateMin.toInt() else null,
                heartRateMax = if (stats.heartRateMax > 0) stats.heartRateMax.toInt() else null,
                sleepDuration = if (stats.sleepHours > 0) (stats.sleepHours * 60).toLong() else null,
                floorsClimbed = stats.floorsClimbed.toInt(),  // 0 floors is valid
                weight = if (stats.weight > 0) stats.weight else null,
                bloodPressureSystolic = if (stats.bloodPressureSystolic > 0) stats.bloodPressureSystolic else null,
                bloodPressureDiastolic = if (stats.bloodPressureDiastolic > 0) stats.bloodPressureDiastolic else null,
                oxygenSaturation = if (stats.oxygenSaturation > 0) stats.oxygenSaturation else null,
                restingHeartRate = if (stats.restingHeartRate > 0) stats.restingHeartRate.toInt() else null,
                vo2Max = if (stats.vo2Max > 0) stats.vo2Max else null,
                bodyTemperature = if (stats.bodyTemperature > 0) stats.bodyTemperature else null,
                bloodGlucose = if (stats.bloodGlucose > 0) stats.bloodGlucose else null,
                hydration = if (stats.hydration > 0) stats.hydration else null
            )
            HealthDataCache.saveSummary(context, summary)
            
            stats
        } catch (e: Exception) {
            Log.e(TAG, "Error getting health stats for $date", e)
            HealthStats()
        }
    }
    
    private suspend fun getSteps(start: Instant, end: Instant): Long {
        return try {
            Log.d(TAG, "Querying steps from $start to $end")
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val steps = response[StepsRecord.COUNT_TOTAL] ?: 0L
            Log.d(TAG, "Steps result: $steps")
            steps
        } catch (e: Exception) {
            Log.e(TAG, "Error getting steps: ${e.message}", e)
            0L
        }
    }
    
    private suspend fun getCalories(start: Instant, end: Instant): Double {
        return try {
            Log.d(TAG, "Querying calories from $start to $end")
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val calories = response[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
            Log.d(TAG, "Calories result: $calories")
            calories
        } catch (e: Exception) {
            Log.e(TAG, "Error getting calories: ${e.message}", e)
            0.0
        }
    }
    
    private suspend fun getDistance(start: Instant, end: Instant): Double {
        return try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting distance", e)
            0.0
        }
    }
    
    private suspend fun getHeartRate(start: Instant, end: Instant): Long {
        return try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response[HeartRateRecord.BPM_AVG] ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting heart rate", e)
            0L
        }
    }
    
    private suspend fun getSleep(start: Instant, end: Instant): Double {
        return try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val duration = response[SleepSessionRecord.SLEEP_DURATION_TOTAL]
            duration?.toHours()?.toDouble() ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sleep", e)
            0.0
        }
    }
    
    private suspend fun getActiveMinutes(start: Instant, end: Instant): Long {
        return try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val duration = response[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]
            duration?.toMinutes() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active minutes", e)
            0L
        }
    }
    
    private data class HeartRateStats(val min: Long, val max: Long, val avg: Long)
    
    private suspend fun getHeartRateStats(start: Instant, end: Instant): HeartRateStats {
        return try {
            val response = healthConnectClient.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val allSamples = response.records.flatMap { it.samples }
            if (allSamples.isEmpty()) {
                return HeartRateStats(0, 0, 0)
            }
            val bpmValues = allSamples.map { it.beatsPerMinute }
            HeartRateStats(
                min = bpmValues.minOrNull() ?: 0,
                max = bpmValues.maxOrNull() ?: 0,
                avg = bpmValues.average().toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting heart rate stats", e)
            HeartRateStats(0, 0, 0)
        }
    }
    
    private suspend fun getFloorsClimbed(start: Instant, end: Instant): Long {
        return try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting floors climbed", e)
            0L
        }
    }
    
    private suspend fun getWeight(start: Instant, end: Instant): Double {
        return try {
            val response = healthConnectClient.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records.lastOrNull()?.weight?.inKilograms ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting weight", e)
            0.0
        }
    }
    
    private suspend fun getBloodPressure(start: Instant, end: Instant): Pair<Int, Int>? {
        return try {
            val response = healthConnectClient.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    BloodPressureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val latest = response.records.lastOrNull()
            if (latest != null) {
                Pair(
                    latest.systolic.inMillimetersOfMercury.toInt(),
                    latest.diastolic.inMillimetersOfMercury.toInt()
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting blood pressure", e)
            null
        }
    }
    
    private suspend fun getOxygenSaturation(start: Instant, end: Instant): Double {
        return try {
            val response = healthConnectClient.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records.lastOrNull()?.percentage?.value ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting oxygen saturation", e)
            0.0
        }
    }
    
    private suspend fun getRestingHeartRate(start: Instant, end: Instant): Long {
        return try {
            val response = healthConnectClient.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records.lastOrNull()?.beatsPerMinute ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting resting heart rate", e)
            0L
        }
    }
    
    private suspend fun getVo2Max(start: Instant, end: Instant): Double {
        return try {
            val response = healthConnectClient.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    Vo2MaxRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records.lastOrNull()?.vo2MillilitersPerMinuteKilogram ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting VO2 max", e)
            0.0
        }
    }
    
    private suspend fun getBodyTemperature(start: Instant, end: Instant): Double {
        return try {
            val response = healthConnectClient.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    BodyTemperatureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records.lastOrNull()?.temperature?.inCelsius ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting body temperature", e)
            0.0
        }
    }
    
    private suspend fun getBloodGlucose(start: Instant, end: Instant): Double {
        return try {
            val response = healthConnectClient.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    BloodGlucoseRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records.lastOrNull()?.level?.inMillimolesPerLiter ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting blood glucose", e)
            0.0
        }
    }
    
    private suspend fun getHydration(start: Instant, end: Instant): Double {
        return try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(HydrationRecord.VOLUME_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response[HydrationRecord.VOLUME_TOTAL]?.inLiters ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hydration", e)
            0.0
        }
    }
}
