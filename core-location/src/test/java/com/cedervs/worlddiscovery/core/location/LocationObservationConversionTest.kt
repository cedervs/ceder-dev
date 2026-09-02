package com.cedervs.worlddiscovery.core.location

import android.location.Location
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    ): Location {
        val location = Location(provider)
        location.latitude = latitude
        location.longitude = longitude
        location.time = timeEpochMillis
        if (accuracy != null) location.accuracy = accuracy
        if (speed != null) location.speed = speed
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
}
