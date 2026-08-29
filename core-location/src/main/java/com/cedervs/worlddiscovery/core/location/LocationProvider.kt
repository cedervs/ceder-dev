package com.cedervs.worlddiscovery.core.location

/** Obtains a single foreground location on explicit request — never continuous tracking, never
 * a background/foreground service. Kept as an interface so a fake can stand in for tests
 * (docs/discovery-engine.md: keep Android-specific location code out of the pure engine, and
 * out of anything that needs to run without a real device/GPS signal). */
interface LocationProvider {
    suspend fun getCurrentLocation(): LocationAcquisitionResult
}
