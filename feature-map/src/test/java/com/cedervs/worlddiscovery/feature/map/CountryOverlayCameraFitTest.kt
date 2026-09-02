package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.GeographicBounds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `LatLngBounds` is a plain value object (no native/JNI initialization on construction — verified
 * directly against the real MapLibre jar, same as `CameraPosition`/`LatLng` in
 * `MapCameraStateTest`), so this pure-JVM test exercises the actual conversion without needing a
 * real MapLibre native runtime.
 */
class CountryOverlayCameraFitTest {

    @Test
    fun `toLatLngBounds maps each corner to the correct LatLngBounds field`() {
        val bounds = GeographicBounds(
            southWestLatitude = 2.0,
            southWestLongitude = -54.0,
            northEastLatitude = 51.0,
            northEastLongitude = 9.0,
        )

        val latLngBounds = bounds.toLatLngBounds()

        assertEquals(51.0, latLngBounds.getLatNorth(), 0.0)
        assertEquals(9.0, latLngBounds.getLonEast(), 0.0)
        assertEquals(2.0, latLngBounds.getLatSouth(), 0.0)
        assertEquals(-54.0, latLngBounds.getLonWest(), 0.0)
    }
}
