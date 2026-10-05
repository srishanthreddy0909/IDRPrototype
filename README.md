
# IDR Prototype — Smart India Hackathon

A locally runnable Android Studio prototype for a GNSS-denied vehicle navigation concept.

## What this prototype demonstrates

- Smartphone accelerometer, gyroscope and magnetometer acquisition
- GNSS acquisition through Android LocationManager
- Basic sensor processing
- Phone-to-vehicle heading alignment using GNSS bearing when moving
- Inertial/dead-reckoning propagation
- GNSS loss detection and simulated GNSS outage
- GNSS recovery and smooth correction
- NHC-inspired forward-only vehicle model
- Zero-velocity reset
- Offline, API-key-free vector map UI
- Navigation mode indicator
- Sensor diagnostics
- Repeatable demo mode for hackathon presentation

## Important prototype scope

This is deliberately a classical-algorithm prototype. It does not claim research-grade lane-level accuracy or replace a trained neural velocity/bias model.

The production/final version can replace the velocity estimator, IMU error correction, GNSS confidence estimator and map matcher with trained ML models while retaining the same app/navigation architecture.

## Open in Android Studio

1. Extract the ZIP.
2. Open the `IDRPrototype` folder in Android Studio.
3. Let Gradle sync.
4. Run on a physical Android phone (recommended).
5. Grant location permission.
6. Tap **Run Demo** for a repeatable GNSS-loss demonstration.

## Live test

For a real vehicle test:
1. Mount the phone securely.
2. Start the app.
3. Drive with GNSS available for a short period so the system can observe GNSS heading/speed.
4. Tap **Simulate Loss** to demonstrate the DR state.
5. Tap **Restore GNSS** to demonstrate recovery.

## Notes

The map in this internal prototype is a lightweight local vector map so the project needs no Google Maps API key. It is intentionally designed to look like a navigation map while keeping the project easy to run locally.

For the next version, replace `PrototypeMap()` with MapLibre/OSM offline tiles and connect `RoadMatcher` to an actual OSM road graph.
