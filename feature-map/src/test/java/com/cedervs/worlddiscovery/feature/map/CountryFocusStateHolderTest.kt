package com.cedervs.worlddiscovery.feature.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CountryFocusStateHolderTest {

    @Before
    fun setUp() {
        CountryFocusStateHolder.current = null
    }

    @After
    fun tearDown() {
        CountryFocusStateHolder.current = null
    }

    @Test
    fun `the holder starts empty, meaning no focus is active on a first launch`() {
        assertNull(CountryFocusStateHolder.current)
    }

    @Test
    fun `the holder remembers whatever return camera was last stored, surviving a hypothetical MapView recreation`() {
        val state = MapCameraState(latitude = 45.0, longitude = 4.0, zoom = 10.0, bearing = 0.0, tilt = 0.0)

        CountryFocusStateHolder.current = state

        assertEquals(state, CountryFocusStateHolder.current)
    }

    @Test
    fun `clearing the holder -- as exitCountryFocus does -- leaves no stale focus state behind`() {
        CountryFocusStateHolder.current = MapCameraState(latitude = 45.0, longitude = 4.0, zoom = 10.0, bearing = 0.0, tilt = 0.0)

        CountryFocusStateHolder.current = null

        assertNull(CountryFocusStateHolder.current)
    }
}
