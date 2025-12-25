package com.sameerasw.airsync.models

data class HealthData(
    val timestamp: Long,
    val dataType: HealthDataType,
    val value: Double,
    val unit: String,
    val source: String
)

enum class HealthDataType {
    STEPS,
    HEART_RATE,
    DISTANCE,
    CALORIES,
    SLEEP,
    BLOOD_PRESSURE,
    BLOOD_OXYGEN,
    WEIGHT,
    ACTIVE_MINUTES,
    FLOORS_CLIMBED
}

data class HealthSummary(
    val date: Long,
    val steps: Int?,
    val distance: Double?, // km
    val calories: Int?,
    val activeMinutes: Int?,
    val heartRateAvg: Int?,
    val heartRateMin: Int?,
    val heartRateMax: Int?,
    val sleepDuration: Long?, // in minutes
    // Additional fields
    val floorsClimbed: Int?,
    val weight: Double?, // kg
    val bloodPressureSystolic: Int?,
    val bloodPressureDiastolic: Int?,
    val oxygenSaturation: Double?, // percentage
    val restingHeartRate: Int?,
    val vo2Max: Double?,
    val bodyTemperature: Double?, // celsius
    val bloodGlucose: Double?, // mmol/L
    val hydration: Double? // liters
)
