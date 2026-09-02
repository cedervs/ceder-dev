package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.GeographicBounds
import org.maplibre.android.geometry.LatLngBounds

/** Provisional fit padding in raw pixels — not calibrated against any specific device/screen
 * density; a "safe" non-zero value so a fitted country's edge never touches the screen edge. */
internal const val COUNTRY_FOCUS_FIT_PADDING_PX = 96

/**
 * Converts a [GeographicBounds] (this module's own, MapLibre-independent bounds type — see
 * `computeGeographicBounds`'s doc comment for why it's computed antimeridian-safely) into a real
 * MapLibre `LatLngBounds`, ready for [org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds].
 *
 * `LatLngBounds.from`'s real parameter order is **not** the order its name might suggest — verified
 * directly against the real library rather than assumed: `LatLngBounds.from(51.0, 42.0, 8.0, -4.0)`
 * reports back `N:51.0; E:42.0; S:8.0; W:-4.0`, i.e. `from(north, east, south, west)`.
 */
internal fun GeographicBounds.toLatLngBounds(): LatLngBounds =
    LatLngBounds.from(northEastLatitude, northEastLongitude, southWestLatitude, southWestLongitude)
