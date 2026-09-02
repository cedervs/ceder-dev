package com.cedervs.worlddiscovery.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class CountryFocusReturnCameraTest {

    private val originalCamera = MapCameraState(latitude = 48.0, longitude = 2.0, zoom = 3.0, bearing = 0.0, tilt = 0.0)
    private val mainlandFocusCamera = MapCameraState(latitude = 46.5, longitude = 2.5, zoom = 6.0, bearing = 0.0, tilt = 0.0)
    private val corsicaFocusCamera = MapCameraState(latitude = 42.1, longitude = 9.1, zoom = 8.0, bearing = 0.0, tilt = 0.0)

    @Test
    fun `entering focus from an unfocused state adopts the pre-click camera as the return camera`() {
        val result = nextCountryFocusReturnCamera(currentReturnCamera = null, cameraBeforeThisClick = originalCamera)

        assertEquals(originalCamera, result)
    }

    @Test
    fun `a second tap on a different component while already focused does not overwrite the return camera`() {
        // First tap enters focus: return camera becomes `originalCamera`.
        val afterFirstTap = nextCountryFocusReturnCamera(currentReturnCamera = null, cameraBeforeThisClick = originalCamera)

        // Second tap, on a different component, happens while the camera is mid-flight to/at the
        // first component's fit -- its own "camera right before this click" is mainlandFocusCamera,
        // not the original pre-focus camera. The return camera must still be the ORIGINAL one.
        val afterSecondTap = nextCountryFocusReturnCamera(
            currentReturnCamera = afterFirstTap,
            cameraBeforeThisClick = mainlandFocusCamera,
        )

        assertEquals(originalCamera, afterSecondTap)
    }

    @Test
    fun `repeated taps across many components all preserve the same original return camera`() {
        var returnCamera: MapCameraState? = null

        returnCamera = nextCountryFocusReturnCamera(returnCamera, originalCamera)
        returnCamera = nextCountryFocusReturnCamera(returnCamera, mainlandFocusCamera)
        returnCamera = nextCountryFocusReturnCamera(returnCamera, corsicaFocusCamera)

        assertEquals(originalCamera, returnCamera)
    }
}
