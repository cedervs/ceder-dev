package com.cedervs.worlddiscovery.core.location

import android.location.Location
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exercises the real `android.location.Location -> LocationObservation` conversion via
 * Robolectric (like `RoomDiscoveredCellRepositoryTest` in `:core-database`) — `Location`'s
 * getters/setters are stubbed to throw under a plain JVM unit test (AGP's default unit-test
 * android.jar), so genuinely verifying `hasAccuracy()`/`hasSpeed()` semantics needs Robolectric's
 * real shadow implementation, not just a fake/hand-written double.
 */
@RunWith(RobolectricTestRunner::class)
class LocationObservationConversionTest {

    private val fixTimeEpochMillis = 1_700_000_000_000L

    private fun realLocation(
        provider: String = "gps",
        latitude: Double = 48.8566,
        longitude: Double = 2.3522,
        timeEpochMillis: Long = fixTimeEpochMillis,
        accuracy: Float? = null,
        speed: Float? = null,
        speedAccuracy: Float? = null,
        bearing: Float? = null,
        bearingAccuracy: Float? = null,
        elapsedRealtimeNanos: Long? = null,
        isMock: Boolean = false,
    ): Location {
        val location = Location(provider)
        location.latitude = latitude
        location.longitude = longitude
        location.time = timeEpochMillis
        if (accuracy != null) location.accuracy = accuracy
        if (speed != null) location.speed = speed
        if (speedAccuracy != null) location.speedAccuracyMetersPerSecond = speedAccuracy
        if (bearing != null) location.bearing = bearing
        if (bearingAccuracy != null) location.bearingAccuracyDegrees = bearingAccuracy
        if (elapsedRealtimeNanos != null) location.elapsedRealtimeNanos = elapsedRealtimeNanos
        if (isMock) shadowOf(location).setIsFromMockProvider(true)
        return location
    }

    @Test
    fun `observedAt is exactly the fix's own epoch time, not the conversion time`() {
        val observation = realLocation(timeEpochMillis = fixTimeEpochMillis).toLocationObservation()

        assertEquals(Instant.ofEpochMilli(fixTimeEpochMillis), observation?.observedAt)
    }

    @Test
    fun `an old fix time is preserved exactly, never replaced by the conversion time`() {
        // Exercises the "never silently corrected" contract: a fix from years ago, or Location's
        // own default of epoch 0, must come through completely unchanged.
        val observation = realLocation(timeEpochMillis = 0L).toLocationObservation()

        assertEquals(Instant.EPOCH, observation?.observedAt)
    }

    @Test
    fun `accuracy is captured when Location reports it`() {
        val observation = realLocation(accuracy = 12.5f).toLocationObservation()

        assertEquals(12.5f, observation?.accuracyMeters)
    }

    @Test
    fun `accuracy is null when Location never reported one`() {
        val observation = realLocation(accuracy = null).toLocationObservation()

        assertNull(observation?.accuracyMeters)
    }

    @Test
    fun `speed is captured when Location reports it`() {
        val observation = realLocation(speed = 1.4f).toLocationObservation()

        assertEquals(1.4f, observation?.speedMetersPerSecond)
    }

    @Test
    fun `speed is null when Location never reported one`() {
        val observation = realLocation(speed = null).toLocationObservation()

        assertNull(observation?.speedMetersPerSecond)
    }

    @Test
    fun `provider passes through unchanged`() {
        val observation = realLocation(provider = "network").toLocationObservation()

        assertEquals("network", observation?.provider)
    }

    @Test
    fun `coordinate matches the Location's own latitude and longitude`() {
        val observation = realLocation(latitude = 45.7640, longitude = 4.8357).toLocationObservation()

        assertEquals(Coordinate(latitude = 45.7640, longitude = 4.8357), observation?.coordinate)
    }

    @Test
    fun `a structurally invalid coordinate converts to null rather than throwing`() {
        // Location itself doesn't validate lat/lng range; Coordinate's constructor does — the
        // conversion must surface that as null, not propagate an exception from deep inside a
        // location callback.
        val observation = realLocation(latitude = 200.0, longitude = 0.0).toLocationObservation()

        assertNull(observation)
    }

    // ==============================================================================================
    // Trajectory reconstruction Phase 1 -- newly captured metadata. See LocationObservation's own
    // doc comment: capturing these is not itself a new filter, nothing about acceptance/rejection
    // changes here.
    // ==============================================================================================

    @Test
    fun `bearing is captured when Location reports it`() {
        val observation = realLocation(bearing = 87.5f).toLocationObservation()

        assertEquals(87.5f, observation?.bearingDegrees)
    }

    @Test
    fun `bearing is null when Location never reported one`() {
        val observation = realLocation(bearing = null).toLocationObservation()

        assertNull(observation?.bearingDegrees)
    }

    @Test
    fun `bearingAccuracyDegrees is captured when Location reports it`() {
        val observation = realLocation(bearingAccuracy = 15.0f).toLocationObservation()

        assertEquals(15.0f, observation?.bearingAccuracyDegrees)
    }

    @Test
    fun `bearingAccuracyDegrees is null when Location never reported one`() {
        val observation = realLocation(bearingAccuracy = null).toLocationObservation()

        assertNull(observation?.bearingAccuracyDegrees)
    }

    @Test
    fun `speedAccuracyMetersPerSecond is captured when Location reports it`() {
        val observation = realLocation(speedAccuracy = 0.8f).toLocationObservation()

        assertEquals(0.8f, observation?.speedAccuracyMetersPerSecond)
    }

    @Test
    fun `speedAccuracyMetersPerSecond is null when Location never reported one`() {
        val observation = realLocation(speedAccuracy = null).toLocationObservation()

        assertNull(observation?.speedAccuracyMetersPerSecond)
    }

    @Test
    fun `elapsedRealtimeNanos is captured directly, with no presence gate`() {
        val observation = realLocation(elapsedRealtimeNanos = 123_456_789_000L).toLocationObservation()

        assertEquals(123_456_789_000L, observation?.elapsedRealtimeNanos)
    }

    @Test
    fun `elapsedRealtimeNanos defaults to 0 when Location never set it, matching the documented sentinel`() {
        val observation = realLocation(elapsedRealtimeNanos = null).toLocationObservation()

        assertEquals(0L, observation?.elapsedRealtimeNanos)
    }

    @Test
    fun `isMockLocation is true when the Location came from a mock provider`() {
        val observation = realLocation(isMock = true).toLocationObservation()

        assertTrue(observation?.isMockLocation == true)
    }

    @Test
    fun `isMockLocation is false for an ordinary real Location`() {
        val observation = realLocation(isMock = false).toLocationObservation()

        assertFalse(observation?.isMockLocation == true)
    }
}
