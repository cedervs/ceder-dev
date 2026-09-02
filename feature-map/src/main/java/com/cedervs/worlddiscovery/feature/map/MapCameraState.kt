package com.cedervs.worlddiscovery.feature.map

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

/**
 * The subset of MapLibre's [CameraPosition] this app preserves across a genuine
 * [org.maplibre.android.maps.MapView] recreation (see [DiscoveryMapView]'s doc comment for when
 * that happens) — latitude, longitude, zoom, bearing and tilt. Deliberately not the full
 * [CameraPosition] (which also carries `roll`, `fov`, `centerAltitude`, `padding`) — only what
 * was asked for. A plain data class, independent of any MapLibre native runtime, so the
 * save/restore conversion logic is unit-testable.
 */
internal data class MapCameraState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double,
    val tilt: Double,
)

internal fun CameraPosition.toMapCameraState(): MapCameraState = MapCameraState(
    // `target` is nullable in MapLibre's own type (a `CameraPosition` could in principle be built
    // without one) but a real position read back from a live `MapLibreMap` always has one — 0.0
    // is only ever a fallback that can't happen in practice here, not a deliberate location.
    latitude = target?.latitude ?: 0.0,
    longitude = target?.longitude ?: 0.0,
    zoom = zoom,
    bearing = bearing,
    tilt = tilt,
)

internal fun MapCameraState.toCameraPosition(): CameraPosition = CameraPosition.Builder()
    .target(LatLng(latitude, longitude))
    .zoom(zoom)
    .bearing(bearing)
    .tilt(tilt)
    .build()

/**
 * Holds the most recently known camera position for the lifetime of the process — deliberately
 * in-memory only (no Room/DataStore persistence was asked for or needed here), and independent of
 * any particular [org.maplibre.android.maps.MapView] instance, so it survives exactly the kind of
 * MapView recreation that otherwise loses camera state (Navigation-Compose disposing and later
 * recomposing `DiscoveryMapView` — see its doc comment) without introducing a ViewModel or any
 * other new architectural layer. `null` means no camera position has been observed yet in this
 * process (a first-ever launch) — nothing to restore, so the map keeps MapLibre's normal default
 * camera.
 */
internal object MapCameraStateHolder {
    var current: MapCameraState? = null
}
