package com.idr.prototype

import kotlin.math.*

/** Small deterministic route catalog and planner. All geometry is generated locally. */
class LocalRoutePlanner : RouteProvider {
    val destinations = listOf(
        LocalDestination("city", "City Center", "Local demo destination"),
        LocalDestination("hospital", "Emergency Hospital", "Local demo destination"),
        LocalDestination("fuel", "Fuel Station", "Local demo destination")
    )

    override fun plan(origin: RoutePoint, destination: LocalDestination): LocalRoute {
        // A compact north/east demo route. It is anchored to the current fix,
        // so it remains usable wherever the phone currently has a location.
        val scale = when (destination.id) {
            "hospital" -> 1.25
            "fuel" -> 0.82
            else -> 1.0
        }
        val northA = 75.0 * scale
        val northB = 185.0 * scale
        val eastA = 165.0 * scale
        val northC = 215.0 * scale
        val eastB = 105.0 * scale

        val points = listOf(
            origin,
            offset(origin, north = northA),
            offset(origin, north = northB),
            offset(origin, north = northB, east = eastA),
            offset(origin, north = northB + northC, east = eastA),
            offset(origin, north = northB + northC, east = eastA + eastB)
        )
        val maneuvers = listOf(
            RouteManeuver(1, ManeuverDirection.STRAIGHT, "Continue straight"),
            RouteManeuver(2, ManeuverDirection.RIGHT, "Turn right"),
            RouteManeuver(3, ManeuverDirection.LEFT, "Turn left"),
            RouteManeuver(4, ManeuverDirection.RIGHT, "Turn right"),
            RouteManeuver(5, ManeuverDirection.ARRIVAL, "Destination reached")
        )
        val distance = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
        return LocalRoute(origin, destination, points, maneuvers, distance)
    }

    override fun distanceTo(point: RoutePoint, latitude: Double, longitude: Double): Double =
        distanceMeters(point, RoutePoint(latitude, longitude))

    private fun offset(origin: RoutePoint, north: Double, east: Double = 0.0): RoutePoint {
        val lat = origin.latitude + north / 111_111.0
        val lonScale = (111_111.0 * cos(Math.toRadians(origin.latitude))).coerceAtLeast(1.0)
        return RoutePoint(lat, origin.longitude + east / lonScale)
    }

    private fun distanceMeters(a: RoutePoint, b: RoutePoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
}
