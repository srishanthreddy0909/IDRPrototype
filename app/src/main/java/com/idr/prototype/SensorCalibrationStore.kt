package com.idr.prototype

import android.content.Context

data class PersistedSensorCalibration(
    val gyroBias: DoubleArray = DoubleArray(3),
    val eastBias: Double = 0.0,
    val northBias: Double = 0.0,
    val gyroReady: Boolean = false,
    val accelerationReady: Boolean = false
)

/** Stores only measured stationary biases. It does not store or imply sensor accuracy. */
class SensorCalibrationStore(context: Context) {
    private val preferences = context.getSharedPreferences("idr_sensor_calibration", Context.MODE_PRIVATE)

    fun load() = PersistedSensorCalibration(
        gyroBias = DoubleArray(3) { preferences.getLong("gyro_$it", 0L) / SCALE },
        eastBias = preferences.getLong("east", 0L) / SCALE,
        northBias = preferences.getLong("north", 0L) / SCALE,
        gyroReady = preferences.getBoolean("gyro_ready", false),
        accelerationReady = preferences.getBoolean("accel_ready", false)
    )

    fun saveGyro(bias: DoubleArray) {
        preferences.edit().apply {
            bias.forEachIndexed { index, value -> putLong("gyro_$index", (value * SCALE).toLong()) }
            putBoolean("gyro_ready", true)
        }.apply()
    }

    fun saveAcceleration(east: Double, north: Double) {
        preferences.edit().putLong("east", (east * SCALE).toLong())
            .putLong("north", (north * SCALE).toLong()).putBoolean("accel_ready", true).apply()
    }

    fun clear() { preferences.edit().clear().apply() }

    companion object { private const val SCALE = 1_000_000.0 }
}
