package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.sameerasw.airsync.models.HealthData
import com.sameerasw.airsync.models.HealthDataType
import com.sameerasw.airsync.models.HealthSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object HealthConnectUtil {
    private const val TAG = "HealthConnectUtil"

    // Required permissions for Health Connect
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class)
    )

    fun isAvailable(context: Context): Boolean {
        return try {
            // Try to get the client - if it fails, Health Connect is not available
            HealthConnectClient.getOrCreate(context)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect not available: ${e.message}")
            false
        }
    }

    suspend fun hasPermissions(context: Context): Boolean {
        return try {
            val healthConnectClient = HealthConnectClient.getOrCreate(context)
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            // Check if we have at least the basic permissions
            val basicPermissions = setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class)
            )
            basicPermissions.all { it in granted }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    suspend fun getTodaySteps(context: Context): Int? {
        val startOfDay = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS)
        val startInstant = startOfDay.atZone(ZoneId.systemDefault()).toInstant()
        val endInstant = Instant.now()
        return getStepsForRange(context, startInstant, endInstant)
    }

    private suspend fun getStepsForRange(context: Context, start: Instant, end: Instant): Int? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                val total = response.records.sumOf { it.count.toInt() }
                if (total > 0) total else null
            } catch (e: Exception) {
                Log.e(TAG, "Error reading steps", e)
                null
            }
        }
    }

    suspend fun getTodayDistance(context: Context): Double? {
        val startOfDay = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS)
        val startInstant = startOfDay.atZone(ZoneId.systemDefault()).toInstant()
        val endInstant = Instant.now()
        return getDistanceForRange(context, startInstant, endInstant)
    }

    private suspend fun getDistanceForRange(context: Context, start: Instant, end: Instant): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        DistanceRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                val total = response.records.sumOf { it.distance.inMeters } / 1000.0
                if (total > 0) total else null
            } catch (e: Exception) {
                Log.e(TAG, "Error reading distance", e)
                null
            }
        }
    }

    suspend fun getTodayCalories(context: Context): Int? {
        val startOfDay = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS)
        val startInstant = startOfDay.atZone(ZoneId.systemDefault()).toInstant()
        val endInstant = Instant.now()
        return getCaloriesForRange(context, startInstant, endInstant)
    }

    private suspend fun getCaloriesForRange(context: Context, start: Instant, end: Instant): Int? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        TotalCaloriesBurnedRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                val total = response.records.sumOf { it.energy.inKilocalories }.toInt()
                if (total > 0) total else null
            } catch (e: Exception) {
                Log.e(TAG, "Error reading calories", e)
                null
            }
        }
    }

    suspend fun getLatestHeartRate(context: Context): Int? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val endInstant = Instant.now()
                val startInstant = endInstant.minus(1, ChronoUnit.HOURS)

                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant)
                    )
                )

                response.records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute?.toInt()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading heart rate", e)
                null
            }
        }
    }

    /**
     * Get heart rate statistics (min, max, avg) for a date range
     * Returns null for each stat if no data available
     */
    private suspend fun getHeartRateStats(context: Context, start: Instant, end: Instant): HeartRateStats {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )

                val allSamples = response.records.flatMap { it.samples }
                
                if (allSamples.isEmpty()) {
                    return@withContext HeartRateStats(null, null, null)
                }

                val bpmValues = allSamples.map { it.beatsPerMinute }
                HeartRateStats(
                    min = bpmValues.minOrNull()?.toInt(),
                    max = bpmValues.maxOrNull()?.toInt(),
                    avg = bpmValues.average().toInt()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error reading heart rate stats", e)
                HeartRateStats(null, null, null)
            }
        }
    }

    suspend fun getTodayActiveMinutes(context: Context): Int? {
        val startOfDay = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS)
        val startInstant = startOfDay.atZone(ZoneId.systemDefault()).toInstant()
        val endInstant = Instant.now()
        return getActiveMinutesForRange(context, startInstant, endInstant)
    }

    private suspend fun getActiveMinutesForRange(context: Context, start: Instant, end: Instant): Int? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        ActiveCaloriesBurnedRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )

                // Estimate active minutes from active calories (rough approximation)
                val activeCalories = response.records.sumOf { it.energy.inKilocalories }
                val minutes = (activeCalories / 5).toInt() // Rough estimate: 5 cal/min
                if (minutes > 0) minutes else null
            } catch (e: Exception) {
                Log.e(TAG, "Error reading active minutes", e)
                null
            }
        }
    }

    suspend fun getLastNightSleep(context: Context): Long? {
        val now = LocalDateTime.now()
        val startOfYesterday = now.minusDays(1).truncatedTo(ChronoUnit.DAYS)
        val startInstant = startOfYesterday.atZone(ZoneId.systemDefault()).toInstant()
        val endInstant = Instant.now()
        return getSleepForRange(context, startInstant, endInstant)
    }

    private suspend fun getSleepForRange(context: Context, start: Instant, end: Instant): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )

                val totalSleepMinutes = response.records.sumOf { session ->
                    val duration = java.time.Duration.between(session.startTime, session.endTime)
                    duration.toMinutes()
                }

                if (totalSleepMinutes > 0) totalSleepMinutes else null
            } catch (e: Exception) {
                Log.e(TAG, "Error reading sleep", e)
                null
            }
        }
    }

    suspend fun getTodaySummary(context: Context): HealthSummary? {
        return getSummaryForDate(context, System.currentTimeMillis())
    }

    /**
     * Get health summary for a specific date
     * @param date Timestamp in milliseconds for the requested date
     * @return HealthSummary with date matching the request, or null on error
     */
    suspend fun getSummaryForDate(context: Context, date: Long): HealthSummary? {
        return withContext(Dispatchers.IO) {
            try {
                // Convert timestamp to start/end of day
                val localDate = Instant.ofEpochMilli(date)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                
                val startOfDay = localDate.atStartOfDay(ZoneId.systemDefault())
                val startInstant = startOfDay.toInstant()
                val endInstant = startOfDay.plusDays(1).toInstant()
                
                Log.d(TAG, "Fetching health data for date: $date (${localDate})")
                
                // Get heart rate stats (min, max, avg)
                val heartRateStats = getHeartRateStats(context, startInstant, endInstant)
                
                HealthSummary(
                    date = date, // Return the requested date, not current time
                    steps = getStepsForRange(context, startInstant, endInstant),
                    distance = getDistanceForRange(context, startInstant, endInstant),
                    calories = getCaloriesForRange(context, startInstant, endInstant),
                    activeMinutes = getActiveMinutesForRange(context, startInstant, endInstant),
                    heartRateAvg = heartRateStats.avg,
                    heartRateMin = heartRateStats.min,
                    heartRateMax = heartRateStats.max,
                    sleepDuration = getSleepForRange(context, startInstant, endInstant),
                    // Additional fields
                    floorsClimbed = getFloorsClimbedForRange(context, startInstant, endInstant),
                    weight = getLatestWeightForRange(context, startInstant, endInstant),
                    bloodPressureSystolic = getLatestBloodPressureForRange(context, startInstant, endInstant)?.first,
                    bloodPressureDiastolic = getLatestBloodPressureForRange(context, startInstant, endInstant)?.second,
                    oxygenSaturation = getLatestOxygenSaturationForRange(context, startInstant, endInstant),
                    restingHeartRate = getRestingHeartRateForRange(context, startInstant, endInstant),
                    vo2Max = getLatestVo2MaxForRange(context, startInstant, endInstant),
                    bodyTemperature = getLatestBodyTemperatureForRange(context, startInstant, endInstant),
                    bloodGlucose = getLatestBloodGlucoseForRange(context, startInstant, endInstant),
                    hydration = getHydrationForRange(context, startInstant, endInstant)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error getting health summary for date $date", e)
                null
            }
        }
    }

    private data class HeartRateStats(
        val min: Int?,
        val max: Int?,
        val avg: Int?
    )

    suspend fun getRecentHealthData(context: Context, hours: Int = 24): List<HealthData> {
        return withContext(Dispatchers.IO) {
            val data = mutableListOf<HealthData>()
            
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val endInstant = Instant.now()
                val startInstant = endInstant.minus(hours.toLong(), ChronoUnit.HOURS)

                // Get steps
                val stepsResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant)
                    )
                )
                stepsResponse.records.forEach { record ->
                    data.add(
                        HealthData(
                            timestamp = record.startTime.toEpochMilli(),
                            dataType = HealthDataType.STEPS,
                            value = record.count.toDouble(),
                            unit = "steps",
                            source = record.metadata.dataOrigin.packageName
                        )
                    )
                }

                // Get heart rate
                val heartRateResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant)
                    )
                )
                heartRateResponse.records.forEach { record ->
                    record.samples.forEach { sample ->
                        data.add(
                            HealthData(
                                timestamp = sample.time.toEpochMilli(),
                                dataType = HealthDataType.HEART_RATE,
                                value = sample.beatsPerMinute.toDouble(),
                                unit = "bpm",
                                source = record.metadata.dataOrigin.packageName
                            )
                        )
                    }
                }

                data.sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting recent health data", e)
                emptyList()
            }
        }
    }

    // ========== Additional Health Metrics ==========

    private suspend fun getFloorsClimbedForRange(context: Context, start: Instant, end: Instant): Int? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        FloorsClimbedRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                val total = response.records.sumOf { it.floors }.toInt()
                if (total > 0) total else null
            } catch (e: Exception) {
                Log.e(TAG, "Error reading floors climbed", e)
                null
            }
        }
    }

    private suspend fun getLatestWeightForRange(context: Context, start: Instant, end: Instant): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        WeightRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                response.records.lastOrNull()?.weight?.inKilograms
            } catch (e: Exception) {
                Log.e(TAG, "Error reading weight", e)
                null
            }
        }
    }

    private suspend fun getLatestBloodPressureForRange(context: Context, start: Instant, end: Instant): Pair<Int, Int>? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
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
                Log.e(TAG, "Error reading blood pressure", e)
                null
            }
        }
    }

    private suspend fun getLatestOxygenSaturationForRange(context: Context, start: Instant, end: Instant): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        OxygenSaturationRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                response.records.lastOrNull()?.percentage?.value
            } catch (e: Exception) {
                Log.e(TAG, "Error reading oxygen saturation", e)
                null
            }
        }
    }

    private suspend fun getRestingHeartRateForRange(context: Context, start: Instant, end: Instant): Int? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        RestingHeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                response.records.lastOrNull()?.beatsPerMinute?.toInt()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading resting heart rate", e)
                null
            }
        }
    }

    private suspend fun getLatestVo2MaxForRange(context: Context, start: Instant, end: Instant): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        Vo2MaxRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                response.records.lastOrNull()?.vo2MillilitersPerMinuteKilogram
            } catch (e: Exception) {
                Log.e(TAG, "Error reading VO2 max", e)
                null
            }
        }
    }

    private suspend fun getLatestBodyTemperatureForRange(context: Context, start: Instant, end: Instant): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        BodyTemperatureRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                response.records.lastOrNull()?.temperature?.inCelsius
            } catch (e: Exception) {
                Log.e(TAG, "Error reading body temperature", e)
                null
            }
        }
    }

    private suspend fun getLatestBloodGlucoseForRange(context: Context, start: Instant, end: Instant): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        BloodGlucoseRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                response.records.lastOrNull()?.level?.inMillimolesPerLiter
            } catch (e: Exception) {
                Log.e(TAG, "Error reading blood glucose", e)
                null
            }
        }
    }

    private suspend fun getHydrationForRange(context: Context, start: Instant, end: Instant): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val healthConnectClient = HealthConnectClient.getOrCreate(context)
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        HydrationRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                val total = response.records.sumOf { it.volume.inLiters }
                if (total > 0) total else null
            } catch (e: Exception) {
                Log.e(TAG, "Error reading hydration", e)
                null
            }
        }
    }
}
