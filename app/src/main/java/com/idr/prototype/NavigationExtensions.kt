package com.idr.prototype

/** Extension seams for replacing classical estimation without tying it to Compose or a backend. */
interface VelocityEstimator {
    fun estimate(previousSpeedMps: Double, forwardAccelerationMps2: Double, deltaSeconds: Double): Double
}

interface ImuCorrectionModel {
    fun correct(sample: SensorSnapshot): SensorSnapshot
}

interface SensorFusionEngine {
    fun resetFromGnss(snapshot: GnssSnapshot, phoneHeading: Double)
    fun propagate(sample: SensorSnapshot, phoneHeading: Double, nowNs: Long): NavState
    fun correctWithGnss(snapshot: GnssSnapshot, weight: Double = 0.65)
}

interface RouteProvider {
    fun plan(origin: RoutePoint, destination: LocalDestination): LocalRoute
    fun distanceTo(point: RoutePoint, latitude: Double, longitude: Double): Double
}
