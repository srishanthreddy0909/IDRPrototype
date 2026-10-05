package com.idr.prototype

data class SensorSnapshot(
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f,
    val mx: Float = 0f,
    val my: Float = 0f,
    val mz: Float = 0f,
    val timestampNanos: Long = 0L,
    val eastAccelerationMps2: Double = 0.0,
    val northAccelerationMps2: Double = 0.0,
    val worldYawRateRadPerSec: Double = 0.0,
    val orientationValid: Boolean = false,
    val orientationStatus: String = "Waiting for sensors",
    val gyroBiasCalibrated: Boolean = false,
    val magnetometerAccuracy: Int = 0,
    val accelerometerBiasCalibrated: Boolean = false,
    val calibrationProgress: Float = 0f,
    val accelerometerAvailable: Boolean = false,
    val gyroscopeAvailable: Boolean = false,
    val magnetometerAvailable: Boolean = false,
    val rotationVectorAvailable: Boolean = false
)

data class GnssSnapshot(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val accuracyM: Float = Float.NaN,
    val timestampMs: Long = 0L
)

enum class NavigationMode {
    GNSS_INS, DEAD_RECKONING, RECOVERY, DEMO
}

data class NavState(
    val lat: Double = 17.385044,
    val lon: Double = 78.486671,
    val speedMps: Double = 0.0,
    val headingDeg: Double = 0.0,
    val accuracyM: Double = Double.NaN,
    val mode: NavigationMode = NavigationMode.GNSS_INS,
    val gnssAvailable: Boolean = false,
    val drDistanceM: Double = 0.0,
    val sensorHz: Double = 0.0
)

data class LocalDestination(
    val id: String,
    val name: String,
    val description: String
)

enum class ManeuverDirection { STRAIGHT, LEFT, RIGHT, ARRIVAL }

data class RoutePoint(val latitude: Double, val longitude: Double)

data class RouteManeuver(
    val pointIndex: Int,
    val direction: ManeuverDirection,
    val instruction: String
)

data class LocalRoute(
    val origin: RoutePoint,
    val destination: LocalDestination,
    val points: List<RoutePoint>,
    val maneuvers: List<RouteManeuver>,
    val totalDistanceM: Double
)

data class RouteGuidance(
    val instruction: String = "Choose a destination to begin",
    val direction: ManeuverDirection = ManeuverDirection.STRAIGHT,
    val distanceToManeuverM: Double = 0.0,
    val destinationName: String = "",
    val arrived: Boolean = false,
    val remainingDistanceM: Double = 0.0,
    val etaSeconds: Double? = null
)

enum class GnssStatus { AVAILABLE, DEGRADED, LOST }

data class OutageTelemetry(
    val durationMs: Long = 0L,
    val distanceM: Double = 0.0,
    val trajectory: List<RoutePoint> = emptyList()
)

data class EmergencyContact(
    val id: String,
    val name: String,
    val phoneNumber: String
)
