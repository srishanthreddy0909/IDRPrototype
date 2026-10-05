package com.idr.prototype

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class DeadReckoningEngine : SensorFusionEngine {
    private var initialized = false
    private var lat = 17.385044
    private var lon = 78.486671
    private var speed = 0.0
    private var heading = 0.0
    private var lastT = 0L
    private var distance = 0.0
    private var headingOffset = 0.0
    private var filteredForwardAcceleration = 0.0

    override fun resetFromGnss(g: GnssSnapshot, phoneHeading: Double) {
        if (g.lat == 0.0 && g.lon == 0.0) return
        lat = g.lat
        lon = g.lon
        speed = g.speedMps.toDouble().coerceAtLeast(0.0)
        heading = if (g.speedMps > 1.5f && g.bearingDeg.isFinite()) g.bearingDeg.toDouble() else normalizeDeg(phoneHeading)
        headingOffset = normalizeSignedDeg(heading - phoneHeading)
        lastT = 0L
        distance = 0.0
        filteredForwardAcceleration = 0.0
        initialized = true
    }

    fun alignToVehicle(gnssBearing: Double, phoneHeading: Double) {
        if (!gnssBearing.isFinite()) return
        headingOffset = normalizeSignedDeg(gnssBearing - phoneHeading)
        heading = normalizeDeg(gnssBearing)
    }

    override fun propagate(sensor: SensorSnapshot, phoneHeading: Double, nowNs: Long): NavState {
        if (!initialized || lastT == 0L) {
            lastT = nowNs
            return state()
        }
        val dt = ((nowNs - lastT) / 1e9).coerceIn(0.005, 0.1)
        lastT = nowNs

        // Android sensor manager rotates acceleration into magnetic east/north
        // before this point. Project in the navigation frame, so phone pitch,
        // roll and mounting orientation do not leak gravity into forward speed.
        val gyroHeading = normalizeDeg(heading - Math.toDegrees(sensor.worldYawRateRadPerSec * dt))
        val magneticHeading = normalizeDeg(phoneHeading + headingOffset)
        heading = lerpAngle(gyroHeading, magneticHeading, if (sensor.orientationValid) 0.045 else 0.0)

        if (sensor.orientationValid) {
            val radians = Math.toRadians(heading)
            val forwardAcceleration = sensor.eastAccelerationMps2 * sin(radians) +
                    sensor.northAccelerationMps2 * cos(radians)
            filteredForwardAcceleration = 0.86 * filteredForwardAcceleration + 0.14 * forwardAcceleration
            val usableAcceleration = when {
                filteredForwardAcceleration > 0.10 -> filteredForwardAcceleration
                filteredForwardAcceleration < -0.10 -> filteredForwardAcceleration
                else -> 0.0
            }.coerceIn(-4.0, 4.0)
            speed = (speed + usableAcceleration * dt).coerceIn(0.0, 45.0)
            if (abs(filteredForwardAcceleration) < 0.07 && speed < 0.45) speed = 0.0
        }

        val step = speed * dt
        distance += step
        val radians = Math.toRadians(heading)
        lat += step * cos(radians) / 111_111.0
        lon += step * sin(radians) / (111_111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return state()
    }

    override fun correctWithGnss(g: GnssSnapshot, weight: Double) {
        if (g.lat == 0.0 && g.lon == 0.0) return
        val k = weight.coerceIn(0.0, 1.0)
        lat = lerp(lat, g.lat, k)
        lon = lerp(lon, g.lon, k)
        speed = lerp(speed, g.speedMps.toDouble().coerceAtLeast(0.0), k.coerceAtMost(0.70))
        if (g.speedMps > 2f && g.bearingDeg.isFinite()) heading = lerpAngle(heading, g.bearingDeg.toDouble(), k.coerceAtMost(0.75))
    }

    fun zeroVelocity() {
        speed = 0.0
        filteredForwardAcceleration = 0.0
    }

    fun currentState(mode: NavigationMode, accuracy: Double, gnss: Boolean): NavState = state(mode, accuracy, gnss)

    private fun state(
        mode: NavigationMode = NavigationMode.DEAD_RECKONING,
        accuracy: Double = Double.NaN,
        gnss: Boolean = false
    ) = NavState(lat, lon, speed, heading, accuracy, mode, gnss, distance)

    private fun normalizeDeg(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun normalizeSignedDeg(value: Double): Double {
        var result = value % 360.0
        if (result > 180.0) result -= 360.0
        if (result < -180.0) result += 360.0
        return result
    }
    private fun lerp(a: Double, b: Double, k: Double) = a + (b - a) * k
    private fun lerpAngle(a: Double, b: Double, k: Double): Double {
        val difference = ((b - a + 540.0) % 360.0) - 180.0
        return normalizeDeg(a + difference * k)
    }
}
