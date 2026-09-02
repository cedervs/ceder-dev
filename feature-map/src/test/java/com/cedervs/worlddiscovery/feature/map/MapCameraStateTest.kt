package com.cedervs.worlddiscovery.feature.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

/**
 * `CameraPosition`/`LatLng` are plain value objects (no native/JNI initialization on
 * construction — verified directly against the real MapLibre jar), so this pure-JVM test
 * exercises the actual save/restore conversion logic without needing a real MapLibre native
 * runtime, Robolectric, or a live `MapView`/`MapLibreMap`.
 */
class MapCameraStateTest {

    @Before
    fun setUp() {
        MapCameraStateHolder.current = null
    }

    @After
    fun tearDown() {
        MapCameraStateHolder.current = null
    }

    @Test
    fun `toMapCameraState extracts latitude, longitude, zoom, bearing and tilt`() {
        val position = CameraPosition.Builder()
            .target(LatLng(48.8566, 2.3522))
            .zoom(15.5)
            .bearing(42.0)
            .tilt(30.0)
            .build()

        val state = position.toMapCameraState()

        assertEquals(48.8566, state.latitude, 0.0)
        assertEquals(2.3522, state.longitude, 0.0)
        assertEquals(15.5, state.zoom, 0.0)
        assertEquals(42.0, state.bearing, 0.0)
        assertEquals(30.0, state.tilt, 0.0)
    }

    @Test
    fun `toCameraPosition then toMapCameraState round-trips to the same state`() {
        val original = MapCameraState(latitude = 48.8566, longitude = 2.3522, zoom = 15.5, bearing = 42.0, tilt = 30.0)

        val roundTripped = original.toCameraPosition().toMapCameraState()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `the holder starts empty, meaning a first launch has nothing to restore`() {
        assertNull(MapCameraStateHolder.current)
    }

    @Test
    fun `the holder remembers whatever was last stored`() {
        val state = MapCameraState(latitude = 45.0, longitude = 4.0, zoom = 10.0, bearing = 0.0, tilt = 0.0)

        MapCameraStateHolder.current = state

        assertEquals(state, MapCameraStateHolder.current)
    }
}
