package com.idr.prototype

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin
import java.util.UUID

class NavigationViewModel(app: Application) : AndroidViewModel(app) {
    private val sensors = DeviceSensorManager(app)
    private val dr = DeadReckoningEngine()
    private val routePlanner = LocalRoutePlanner()
    private val contactsStore = EmergencyContactsStore(app)

    val sensor = sensors.sensor
    val gnss = sensors.gnss
    val phoneHeading = sensors.heading
    val destinations = routePlanner.destinations

    private val _nav = MutableStateFlow(NavState())
    val nav: StateFlow<NavState> = _nav
    private val _demoOutage = MutableStateFlow(false)
    val demoOutage: StateFlow<Boolean> = _demoOutage
    private val _recovering = MutableStateFlow(false)
    val recovering: StateFlow<Boolean> = _recovering
    private val _isDemo = MutableStateFlow(false)
    val isDemo: StateFlow<Boolean> = _isDemo
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _route = MutableStateFlow<LocalRoute?>(null)
    val route: StateFlow<LocalRoute?> = _route
    private val _guidance = MutableStateFlow(RouteGuidance())
    val guidance: StateFlow<RouteGuidance> = _guidance
    private val _contacts = MutableStateFlow(contactsStore.load())
    val contacts: StateFlow<List<EmergencyContact>> = _contacts
    private val _outageTelemetry = MutableStateFlow(OutageTelemetry())
    val outageTelemetry: StateFlow<OutageTelemetry> = _outageTelemetry

    private var demoJob: Job? = null
    private var lastGnss = GnssSnapshot()
    private var drInitialized = false
    private var maneuverIndex = 0
    private var outageStartedAtMs = 0L
    private var outageDistanceAtStart = 0.0
    private var lastTrailSampleAtMs = 0L

    init {
        viewModelScope.launch {
            combine(sensors.gnss, sensors.heading) { g, h -> Pair(g, h) }.collect { (g, h) ->
                if (g.lat == 0.0 && g.lon == 0.0) return@collect
                lastGnss = g
                if (!drInitialized) {
                    dr.resetFromGnss(g, h)
                    drInitialized = true
                }
                // Keep receiving and caching real fixes during a simulated
                // outage/recovery, but never paint them over the DR state.
                if (_demoOutage.value || _recovering.value || _isDemo.value) return@collect

                if (_nav.value.mode == NavigationMode.DEAD_RECKONING && drInitialized) {
                    _recovering.value = true
                    dr.correctWithGnss(g, 0.08)
                    _nav.value = dr.currentState(NavigationMode.RECOVERY, Double.NaN, true)
                    updateGuidance(_nav.value)
                    viewModelScope.launch {
                        repeat(12) {
                            delay(100)
                            dr.correctWithGnss(lastGnss, 0.08)
                            publishDr(dr.currentState(NavigationMode.RECOVERY, Double.NaN, true), NavigationMode.RECOVERY, true)
                        }
                        _recovering.value = false
                        val fix = lastGnss
                        _nav.value = NavState(
                            lat = fix.lat, lon = fix.lon, speedMps = fix.speedMps.toDouble(),
                            headingDeg = if (fix.hasUsefulBearing()) fix.bearingDeg.toDouble() else phoneHeading.value,
                            accuracyM = fix.accuracyM.toDouble(),
                            mode = NavigationMode.GNSS_INS, gnssAvailable = true, drDistanceM = _nav.value.drDistanceM
                        )
                        _outageTelemetry.value = _outageTelemetry.value.copy(trajectory = emptyList())
                        updateGuidance(_nav.value)
                    }
                    return@collect
                }

                if (g.speedMps > 1.5f && g.bearingDeg.isFinite()) dr.alignToVehicle(g.bearingDeg.toDouble(), h)
                dr.correctWithGnss(g)
                val updated = NavState(
                    lat = g.lat,
                    lon = g.lon,
                    speedMps = g.speedMps.toDouble(),
                    headingDeg = if (g.hasUsefulBearing()) g.bearingDeg.toDouble() else h,
                    accuracyM = g.accuracyM.toDouble(),
                    mode = NavigationMode.GNSS_INS,
                    gnssAvailable = true,
                    drDistanceM = _nav.value.drDistanceM
                )
                _nav.value = updated
                updateGuidance(updated)
            }
        }

        viewModelScope.launch {
            sensors.sensor.collect { sample ->
                if (!_running.value || _isDemo.value) return@collect
                val propagated = dr.propagate(sample, phoneHeading.value, sample.timestampNanos)
                val realFixExpired = lastGnss.timestampMs > 0L &&
                        System.currentTimeMillis() - lastGnss.timestampMs > GNSS_STALE_AFTER_MS
                when {
                    _demoOutage.value -> publishDr(propagated, NavigationMode.DEAD_RECKONING, false)
                    _recovering.value -> {
                        if (lastGnss.lat != 0.0) dr.correctWithGnss(lastGnss, 0.0015)
                        publishDr(dr.currentState(NavigationMode.RECOVERY, Double.NaN, true), NavigationMode.RECOVERY, true)
                    }
                    lastGnss.lat != 0.0 && realFixExpired -> {
                        if (_nav.value.mode != NavigationMode.DEAD_RECKONING) beginOutageTelemetry()
                        publishDr(propagated, NavigationMode.DEAD_RECKONING, false)
                    }
                    lastGnss.lat != 0.0 -> {
                        val normal = _nav.value.copy(
                            mode = NavigationMode.GNSS_INS,
                            gnssAvailable = true,
                            drDistanceM = propagated.drDistanceM
                        )
                        _nav.value = normal
                        updateGuidance(normal)
                    }
                    else -> publishDr(propagated, NavigationMode.DEAD_RECKONING, false)
                }
            }
        }
    }

    private fun publishDr(state: NavState, mode: NavigationMode, gnssAvailable: Boolean) {
        val updated = state.copy(
            mode = mode,
            gnssAvailable = gnssAvailable,
            accuracyM = if (mode == NavigationMode.DEAD_RECKONING) Double.NaN else state.accuracyM
        )
        _nav.value = updated
        if (_demoOutage.value || (mode == NavigationMode.DEAD_RECKONING && !_isDemo.value)) updateOutageTelemetry(updated)
        updateGuidance(updated)
    }

    private fun beginOutageTelemetry() {
        outageStartedAtMs = SystemClock.elapsedRealtime()
        outageDistanceAtStart = _nav.value.drDistanceM
        lastTrailSampleAtMs = 0L
        _outageTelemetry.value = OutageTelemetry(trajectory = listOf(RoutePoint(_nav.value.lat, _nav.value.lon)))
    }

    private fun updateOutageTelemetry(state: NavState) {
        val now = SystemClock.elapsedRealtime()
        val old = _outageTelemetry.value
        val trajectory = if (lastTrailSampleAtMs == 0L || now - lastTrailSampleAtMs >= 1_000L) {
            lastTrailSampleAtMs = now
            (old.trajectory + RoutePoint(state.lat, state.lon)).takeLast(MAX_TRAJECTORY_POINTS)
        } else old.trajectory
        _outageTelemetry.value = OutageTelemetry(
            durationMs = (now - outageStartedAtMs).coerceAtLeast(0L),
            distanceM = (state.drDistanceM - outageDistanceAtStart).coerceAtLeast(0.0),
            trajectory = trajectory
        )
    }

    fun start() {
        if (_running.value) return
        _running.value = true
        sensors.start()
    }

    fun stop() {
        _running.value = false
        sensors.stop()
    }

    fun toggleOutage() {
        if (_isDemo.value) return
        if (!_demoOutage.value) {
            beginOutageTelemetry()
            _demoOutage.value = true
            _recovering.value = false
            _nav.value = _nav.value.copy(mode = NavigationMode.DEAD_RECKONING, gnssAvailable = false)
        } else {
            _demoOutage.value = false
            _recovering.value = true
            if (lastGnss.lat != 0.0) dr.correctWithGnss(lastGnss, 0.08)
            _nav.value = dr.currentState(NavigationMode.RECOVERY, Double.NaN, true)
            viewModelScope.launch {
                repeat(12) {
                    delay(100)
                    if (lastGnss.lat != 0.0) dr.correctWithGnss(lastGnss, 0.08)
                }
                _recovering.value = false
                if (lastGnss.lat != 0.0) {
                    val g = lastGnss
                    _nav.value = NavState(
                        lat = g.lat, lon = g.lon, speedMps = g.speedMps.toDouble(),
                        headingDeg = if (g.hasUsefulBearing()) g.bearingDeg.toDouble() else phoneHeading.value,
                        accuracyM = g.accuracyM.toDouble(),
                        mode = NavigationMode.GNSS_INS, gnssAvailable = true, drDistanceM = _nav.value.drDistanceM
                    )
                    updateGuidance(_nav.value)
                }
            }
        }
    }

    fun startDemo() {
        stop()
        _isDemo.value = true
        _running.value = false
        _demoOutage.value = false
        _recovering.value = false
        demoJob?.cancel()
        demoJob = viewModelScope.launch {
            var lat = 17.385044
            var lon = 78.486671
            var heading = 40.0
            var speed = 11.0
            var t = 0
            while (isActive) {
                val outage = t in 12..28
                if (t == 12) _demoOutage.value = true
                if (t == 12) beginOutageTelemetry()
                if (t == 29) {
                    _demoOutage.value = false
                    _recovering.value = true
                }
                speed += sin(t / 5.0) * 0.15
                heading += if (t in 20..28) 0.8 else 0.15
                val d = speed * 0.25
                val radians = Math.toRadians(heading)
                lat += d * cos(radians) / 111111.0
                lon += d * sin(radians) / (111111.0 * cos(Math.toRadians(lat)))
                val state = NavState(
                    lat = lat, lon = lon, speedMps = speed, headingDeg = heading,
                    accuracyM = Double.NaN,
                    mode = when { outage -> NavigationMode.DEAD_RECKONING; t == 29 -> NavigationMode.RECOVERY; else -> NavigationMode.GNSS_INS },
                    gnssAvailable = !outage, drDistanceM = if (outage) (t - 12) * d else 0.0
                )
                _nav.value = state
                if (outage) updateOutageTelemetry(state)
                updateGuidance(state)
                if (t == 29) {
                    delay(1200)
                    _recovering.value = false
                    _nav.value = _nav.value.copy(mode = NavigationMode.GNSS_INS, accuracyM = Double.NaN)
                }
                t = (t + 1) % 45
                delay(250)
            }
        }
    }

    fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        _isDemo.value = false
        _demoOutage.value = false
        _recovering.value = false
        _nav.value = _nav.value.copy(mode = if (lastGnss.lat != 0.0) NavigationMode.GNSS_INS else NavigationMode.DEAD_RECKONING,
            gnssAvailable = lastGnss.lat != 0.0)
        start()
    }

    fun startNavigation(destinationId: String) {
        val destination = destinations.firstOrNull { it.id == destinationId } ?: return
        val current = _nav.value
        val origin = RoutePoint(current.lat, current.lon)
        _route.value = routePlanner.plan(origin, destination)
        maneuverIndex = 0
        updateGuidance(current, advance = false)
    }

    fun clearRoute() {
        _route.value = null
        _guidance.value = RouteGuidance()
        maneuverIndex = 0
    }

    private fun updateGuidance(state: NavState, advance: Boolean = true) {
        val route = _route.value ?: return
        if (route.maneuvers.isEmpty()) return
        if (advance) {
            while (maneuverIndex < route.maneuvers.lastIndex) {
                val point = route.points[route.maneuvers[maneuverIndex].pointIndex]
                if (routePlanner.distanceTo(point, state.lat, state.lon) > ARRIVAL_RADIUS_M) break
                maneuverIndex++
            }
        }
        val maneuver = route.maneuvers[maneuverIndex]
        val target = route.points[maneuver.pointIndex]
        val distance = routePlanner.distanceTo(target, state.lat, state.lon)
        val arrived = maneuver.direction == ManeuverDirection.ARRIVAL && distance <= ARRIVAL_RADIUS_M
        val remaining = distance + route.points.drop(maneuver.pointIndex).zipWithNext().sumOf { (a, b) -> routePlanner.distanceTo(b, a.latitude, a.longitude) }
        _guidance.value = RouteGuidance(
            instruction = if (arrived) "Destination reached" else maneuver.instruction,
            direction = if (arrived) ManeuverDirection.ARRIVAL else maneuver.direction,
            distanceToManeuverM = if (arrived) 0.0 else distance,
            destinationName = route.destination.name,
            arrived = arrived,
            remainingDistanceM = remaining,
            etaSeconds = if (state.speedMps > 0.5) remaining / state.speedMps else null
        )
    }

    fun saveEmergencyContact(id: String?, name: String, phone: String) {
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        if (cleanName.isEmpty() || cleanPhone.isEmpty()) return
        val contact = EmergencyContact(id ?: UUID.randomUUID().toString(), cleanName, cleanPhone)
        val updated = if (id == null) _contacts.value + contact else _contacts.value.map { if (it.id == id) contact else it }
        contactsStore.save(updated)
        _contacts.value = updated
    }

    fun removeEmergencyContact(id: String) {
        val updated = _contacts.value.filterNot { it.id == id }
        contactsStore.save(updated)
        _contacts.value = updated
    }

    fun resetSensorCalibration() = sensors.resetCalibration()

    fun manualZeroVelocity() {
        dr.zeroVelocity()
        _nav.value = _nav.value.copy(speedMps = 0.0)
    }

    override fun onCleared() {
        stop()
        demoJob?.cancel()
        super.onCleared()
    }

    private fun GnssSnapshot.hasUsefulBearing() = speedMps > 1.5f && bearingDeg.isFinite()

    companion object {
        private const val ARRIVAL_RADIUS_M = 22.0
        private const val MAX_TRAJECTORY_POINTS = 240
        private const val GNSS_STALE_AFTER_MS = 15_000L
    }
}
