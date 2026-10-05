package com.idr.prototype

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

class DeviceSensorManager(private val context: Context) : SensorEventListener, LocationListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val calibrationStore = SensorCalibrationStore(context)
    private val storedCalibration = calibrationStore.load()

    private val _sensor = MutableStateFlow(SensorSnapshot())
    val sensor: StateFlow<SensorSnapshot> = _sensor
    private val _gnss = MutableStateFlow(GnssSnapshot())
    val gnss: StateFlow<GnssSnapshot> = _gnss
    private val _heading = MutableStateFlow(0.0)
    val heading: StateFlow<Double> = _heading

    private val accel = FloatArray(3)
    private val gyro = FloatArray(3)
    private val mag = FloatArray(3)
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var haveAccel = false
    private var haveGyro = false
    private var haveMag = false
    private var haveOrientation = false
    private var rotationVectorAvailable = false
    private var rotationVectorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var accelerometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var gyroscopeAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    private var filteredHeading = 0.0
    private var headingInitialized = false
    private var gravityUp = SensorManager.GRAVITY_EARTH.toDouble()
    private var gravityInitialized = false
    private var gyroBias = storedCalibration.gyroBias
    private var gyroBiasSamples = if (storedCalibration.gyroReady) 64 else 0
    private var gyroBiasCalibrated = storedCalibration.gyroReady
    private var gyroBiasSums = DoubleArray(3)
    private var accelBiasEast = storedCalibration.eastBias
    private var accelBiasNorth = storedCalibration.northBias
    private var accelBiasEastSum = 0.0
    private var accelBiasNorthSum = 0.0
    private var accelBiasSamples = if (storedCalibration.accelerationReady) 64 else 0
    private var accelBiasCalibrated = storedCalibration.accelerationReady
    private var accelerometerAvailable = false
    private var gyroscopeAvailable = false
    private var magnetometerAvailable = false

    fun start() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationVectorAvailable = rotationVector != null
        accelerometerAvailable = accelerometer != null
        gyroscopeAvailable = gyroscope != null
        magnetometerAvailable = magnetometer != null

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } catch (_: SecurityException) { /* Permission may have been revoked. */ }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        try { locationManager.removeUpdates(this) } catch (_: SecurityException) { /* Location permission unavailable. */ }
    }

    fun resetCalibration() {
        calibrationStore.clear()
        gyroBias = DoubleArray(3)
        gyroBiasSamples = 0
        gyroBiasCalibrated = false
        gyroBiasSums = DoubleArray(3)
        accelBiasEast = 0.0
        accelBiasNorth = 0.0
        accelBiasEastSum = 0.0
        accelBiasNorthSum = 0.0
        accelBiasSamples = 0
        accelBiasCalibrated = false
        _sensor.value = _sensor.value.copy(
            gyroBiasCalibrated = false,
            accelerometerBiasCalibrated = false,
            calibrationProgress = 0f,
            orientationStatus = calibrationStatus()
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        var gyroEvent = false
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { event.values.copyInto(accel); haveAccel = true }
            Sensor.TYPE_GYROSCOPE -> {
                event.values.copyInto(gyro); haveGyro = true
                gyroEvent = true
                calibrateGyroBiasIfStationary()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> { event.values.copyInto(mag); haveMag = true }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                haveOrientation = true
                SensorManager.getOrientation(rotation, orientation)
                updateHeading(Math.toDegrees(orientation[0].toDouble()))
            }
        }

        // Fallback for devices without a rotation-vector sensor. Android's
        // matrix combines gravity and magnetic north while accounting for tilt.
        if (!rotationVectorAvailable && haveAccel && haveMag &&
            SensorManager.getRotationMatrix(rotation, null, accel, mag)) {
            haveOrientation = true
            SensorManager.getOrientation(rotation, orientation)
            updateHeading(Math.toDegrees(orientation[0].toDouble()))
        }

        val correctedGyro = DoubleArray(3) { gyro[it].toDouble() - gyroBias[it] }
        val worldEast = rotation[0] * accel[0] + rotation[1] * accel[1] + rotation[2] * accel[2]
        val worldNorth = rotation[3] * accel[0] + rotation[4] * accel[1] + rotation[5] * accel[2]
        val worldUp = rotation[6] * accel[0] + rotation[7] * accel[1] + rotation[8] * accel[2]
        if (gyroEvent && haveOrientation && haveAccel) calibrateAccelerationBiasIfStationary(worldEast.toDouble(), worldNorth.toDouble())
        if (haveOrientation && haveAccel) {
            gravityUp = if (!gravityInitialized) worldUp.toDouble() else 0.96 * gravityUp + 0.04 * worldUp
            gravityInitialized = true
        }
        // In the earth frame gravity is vertical, so subtracting it after
        // rotation removes phone pitch/roll from the horizontal acceleration.
        val linearEast = if (haveOrientation && haveAccel) worldEast.toDouble() - accelBiasEast else 0.0
        val linearNorth = if (haveOrientation && haveAccel) worldNorth.toDouble() - accelBiasNorth else 0.0
        val worldYawRate = if (haveOrientation && haveGyro) {
            rotation[6] * correctedGyro[0] + rotation[7] * correctedGyro[1] + rotation[8] * correctedGyro[2]
        } else 0.0

        _sensor.value = SensorSnapshot(
            ax = accel[0], ay = accel[1], az = accel[2],
            gx = correctedGyro[0].toFloat(), gy = correctedGyro[1].toFloat(), gz = correctedGyro[2].toFloat(),
            mx = mag[0], my = mag[1], mz = mag[2], timestampNanos = event.timestamp,
            eastAccelerationMps2 = linearEast,
            northAccelerationMps2 = linearNorth,
            worldYawRateRadPerSec = worldYawRate,
            orientationValid = haveOrientation && haveAccel,
            orientationStatus = calibrationStatus(),
            gyroBiasCalibrated = gyroBiasCalibrated,
            magnetometerAccuracy = magnetometerAccuracy,
            accelerometerBiasCalibrated = accelBiasCalibrated,
            calibrationProgress = minOf(gyroBiasSamples, accelBiasSamples) / 64f,
            accelerometerAvailable = accelerometerAvailable,
            gyroscopeAvailable = gyroscopeAvailable,
            magnetometerAvailable = magnetometerAvailable,
            rotationVectorAvailable = rotationVectorAvailable
        )
    }

    private fun calibrateGyroBiasIfStationary() {
        if (gyroBiasCalibrated || !haveAccel) return
        val accelerationMagnitude = sqrt(accel.sumOf { it.toDouble() * it })
        val gyroMagnitude = sqrt(gyro.sumOf { it.toDouble() * it })
        if (abs(accelerationMagnitude - SensorManager.GRAVITY_EARTH) > 0.65 || gyroMagnitude > 0.10) {
            gyroBiasSamples = 0
            gyroBiasSums = DoubleArray(3)
            return
        }
        for (axis in 0..2) gyroBiasSums[axis] += gyro[axis]
        gyroBiasSamples++
        if (gyroBiasSamples >= 64) {
            gyroBias = DoubleArray(3) { gyroBiasSums[it] / gyroBiasSamples }
            gyroBiasCalibrated = true
            calibrationStore.saveGyro(gyroBias)
        }
    }

    private fun calibrateAccelerationBiasIfStationary(east: Double, north: Double) {
        if (accelBiasCalibrated) return
        val accelerationMagnitude = sqrt(accel.sumOf { it.toDouble() * it })
        val gyroMagnitude = sqrt(gyro.sumOf { it.toDouble() * it })
        if (abs(accelerationMagnitude - SensorManager.GRAVITY_EARTH) > 0.45 || gyroMagnitude > 0.06) {
            accelBiasSamples = 0
            accelBiasEastSum = 0.0
            accelBiasNorthSum = 0.0
            return
        }
        accelBiasEastSum += east
        accelBiasNorthSum += north
        accelBiasSamples++
        if (accelBiasSamples >= 64) {
            accelBiasEast = accelBiasEastSum / accelBiasSamples
            accelBiasNorth = accelBiasNorthSum / accelBiasSamples
            accelBiasCalibrated = true
            calibrationStore.saveAcceleration(accelBiasEast, accelBiasNorth)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerometerAccuracy = accuracy
            Sensor.TYPE_GYROSCOPE -> gyroscopeAccuracy = accuracy
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerAccuracy = accuracy
            Sensor.TYPE_ROTATION_VECTOR -> rotationVectorAccuracy = accuracy
        }
    }

    override fun onLocationChanged(location: Location) {
        _gnss.value = GnssSnapshot(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            bearingDeg = if (location.hasBearing()) location.bearing else 0f,
            accuracyM = if (location.hasAccuracy()) location.accuracy else Float.NaN,
            timestampMs = location.time
        )
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private fun calibrationStatus(): String {
        val gyroText = if (gyroBiasCalibrated) "gyro bias calibrated" else "hold phone still for gyro calibration ($gyroBiasSamples/64)"
        val accelText = if (accelBiasCalibrated) "accel horizontal bias calibrated" else "accel bias calibration $accelBiasSamples/64"
        val compassText = when {
            !haveMag -> "magnetometer unavailable"
            magnetometerAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "compass accuracy high"
            magnetometerAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "compass accuracy medium"
            magnetometerAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "move phone in a figure-eight for compass calibration"
            else -> "compass calibration not confirmed"
        }
        val orientationText = if (haveOrientation) "orientation available (Android fused sensor)" else "waiting for orientation sensors"
        return "$orientationText • $accelText • $gyroText • $compassText"
    }

    private fun updateHeading(raw: Double) {
        val normalized = ((raw % 360.0) + 360.0) % 360.0
        if (!headingInitialized) {
            filteredHeading = normalized
            headingInitialized = true
        } else {
            val delta = ((normalized - filteredHeading + 540.0) % 360.0) - 180.0
            val alpha = if (abs(delta) > 25.0) 0.30 else 0.12
            filteredHeading = ((filteredHeading + alpha * delta) % 360.0 + 360.0) % 360.0
        }
        _heading.value = filteredHeading
    }
}
