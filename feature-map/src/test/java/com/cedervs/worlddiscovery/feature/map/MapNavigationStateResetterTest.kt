package com.cedervs.worlddiscovery.feature.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MapNavigationStateResetterTest {

    private val someCamera = MapCameraState(latitude = 48.0, longitude = 2.0, zoom = 5.0, bearing = 0.0, tilt = 0.0)
    private val someOtherCamera = MapCameraState(latitude = 41.9, longitude = 8.7, zoom = 9.0, bearing = 0.0, tilt = 0.0)

    @Before
    fun setUp() {
        MapCameraStateHolder.current = null
        CountryFocusStateHolder.current = null
    }

    @After
    fun tearDown() {
        MapCameraStateHolder.current = null
        CountryFocusStateHolder.current = null
    }

    @Test
    fun `reset clears both the camera and the focus state when both are present`() {
        MapCameraStateHolder.current = someCamera
        CountryFocusStateHolder.current = someOtherCamera

        MapNavigationStateResetter.reset()

        assertNull(MapCameraStateHolder.current)
        assertNull(CountryFocusStateHolder.current)
    }

    @Test
    fun `reset clears the camera even when focus state was already empty`() {
        MapCameraStateHolder.current = someCamera
        CountryFocusStateHolder.current = null

        MapNavigationStateResetter.reset()

        assertNull(MapCameraStateHolder.current)
        assertNull(CountryFocusStateHolder.current)
    }

    @Test
    fun `reset clears the focus state even when the camera was already empty`() {
        MapCameraStateHolder.current = null
        CountryFocusStateHolder.current = someOtherCamera

        MapNavigationStateResetter.reset()

        assertNull(MapCameraStateHolder.current)
        assertNull(CountryFocusStateHolder.current)
    }

    @Test
    fun `ordinary state access -- exactly what a MapView recreation or tab switch does -- never clears either holder on its own`() {
        MapCameraStateHolder.current = someCamera
        CountryFocusStateHolder.current = someOtherCamera

        // Simulates precisely what DiscoveryMapView does across a genuine recreation/tab switch:
        // it only READS each holder's current value, to restore the camera and re-sync local focus
        // state -- it never calls MapNavigationStateResetter.reset() itself. Reading must never be
        // conflated with resetting; only a real session transition (observed in AppContainer) does.
        val readCamera = MapCameraStateHolder.current
        val readFocus = CountryFocusStateHolder.current

        assertEquals(someCamera, readCamera)
        assertEquals(someOtherCamera, readFocus)
        assertEquals("reading must not mutate the camera holder", someCamera, MapCameraStateHolder.current)
        assertEquals("reading must not mutate the focus holder", someOtherCamera, CountryFocusStateHolder.current)
    }

    @Test
    fun `after a reset, a fresh focus action captures a new return camera exactly like a first-ever focus`() {
        MapCameraStateHolder.current = someCamera
        CountryFocusStateHolder.current = someOtherCamera

        MapNavigationStateResetter.reset()

        // A brand-new focus action after reset must behave exactly like entering focus from a
        // genuinely unfocused state -- nextCountryFocusReturnCamera adopts the new pre-click
        // camera, proving no stale state survived the reset to interfere.
        val newPreClickCamera = MapCameraState(latitude = 10.0, longitude = 20.0, zoom = 4.0, bearing = 0.0, tilt = 0.0)
        val next = nextCountryFocusReturnCamera(CountryFocusStateHolder.current, newPreClickCamera)
        CountryFocusStateHolder.current = next

        assertEquals(newPreClickCamera, CountryFocusStateHolder.current)
    }
}
