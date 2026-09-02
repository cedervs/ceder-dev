package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDiagnosticLoggerTest {

    private val observation = LocationObservation(
        coordinate = Coordinate(latitude = 48.8566, longitude = 2.3522),
        observedAt = Instant.parse("2026-01-01T10:00:00Z"),
        accuracyMeters = 12.5f,
        speedMetersPerSecond = 1.4f,
        provider = "gps",
    )

    @Test
    fun `the formatted message includes timestamp, age, accuracy, speed and provider`() {
        val loggedAt = Instant.parse("2026-01-01T10:00:05Z")

        val message = formatLocationObservationLogMessage(observation, loggedAt)

        assertTrue(message.contains("observedAt=2026-01-01T10:00:00Z"))
        assertTrue(message.contains("fixAgeMillis=5000"))
        assertTrue(message.contains("accuracyMeters=12.5"))
        assertTrue(message.contains("speedMetersPerSecond=1.4"))
        assertTrue(message.contains("provider=gps"))
    }

    @Test
    fun `the formatted message never includes the raw coordinate`() {
        val message = formatLocationObservationLogMessage(observation, Instant.now())

        assertTrue(!message.contains("48.8566"))
        assertTrue(!message.contains("2.3522"))
    }

    @Test
    fun `null accuracy and speed are represented as null, not a guessed default`() {
        val noMetadata = observation.copy(accuracyMeters = null, speedMetersPerSecond = null)

        val message = formatLocationObservationLogMessage(noMetadata, Instant.now())

        assertTrue(message.contains("accuracyMeters=null"))
        assertTrue(message.contains("speedMetersPerSecond=null"))
    }

    @Test
    fun `an extreme observedAt that overflows the age calculation omits the age rather than throwing`() {
        // Instant.MIN to Instant.now() is a duration whose seconds fit in a Long, but whose
        // milliseconds do not (Duration.toMillis() overflows) — a real, reachable case, not a
        // contrived one, for a representable-but-extreme Instant.
        val extremeObservation = observation.copy(observedAt = Instant.MIN)

        val message = formatLocationObservationLogMessage(extremeObservation, Instant.now())

        assertTrue(message.contains("fixAgeMillis=null"))
    }

    @Test
    fun `logSafely swallows any exception from a misbehaving logger`() {
        val logger = object : LocationDiagnosticLogger {
            override fun log(observation: LocationObservation) {
                error("simulated diagnostic logger failure")
            }
        }

        // Must not throw — this is the actual guarantee every submission path relies on.
        logger.logSafely(observation)
    }

    @Test
    fun `logSafely also swallows a CancellationException — log() never suspends, so it cannot be genuine job cancellation`() {
        val logger = object : LocationDiagnosticLogger {
            override fun log(observation: LocationObservation) {
                throw CancellationException("fabricated by a misbehaving synchronous logger, not real cancellation")
            }
        }

        // Must not throw — unlike the genuine cancellation boundary around the suspend
        // SubmitDiscoveryObservation call in LocationTrackingSession (and the one-shot/background
        // use cases), nothing here suspends, so this can only be a fabricated exception, never
        // real structured-concurrency cancellation. See logSafely's doc comment for the full
        // boundary explanation.
        logger.logSafely(observation)
    }
}
