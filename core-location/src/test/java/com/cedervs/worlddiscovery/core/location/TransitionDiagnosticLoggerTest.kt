package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionDiagnosticLoggerTest {

    private val transition = ReconstructionTransitionDiagnostics(
        deltaMillis = 5_000L,
        distanceMeters = 42.5,
        impliedSpeedMetersPerSecond = 1.4,
        fromAccuracyMeters = 8.0f,
        toAccuracyMeters = null,
        fromSpeedMetersPerSecond = 1.2f,
        toSpeedMetersPerSecond = null,
        pathComputed = true,
        pathCellCount = 12,
        eligible = true,
    )

    @Test
    fun `the formatted message includes every aggregated field`() {
        val message = formatReconstructionTransitionLogMessage(transition)

        assertTrue(message.contains("eligible=true"))
        assertTrue(message.contains("deltaMillis=5000"))
        assertTrue(message.contains("distanceMeters=42.5"))
        assertTrue(message.contains("impliedSpeedMetersPerSecond=1.4"))
        assertTrue(message.contains("fromAccuracyMeters=8.0"))
        assertTrue(message.contains("toAccuracyMeters=null"))
        assertTrue(message.contains("fromSpeedMetersPerSecond=1.2"))
        assertTrue(message.contains("toSpeedMetersPerSecond=null"))
        assertTrue(message.contains("pathComputed=true"))
        assertTrue(message.contains("pathCellCount=12"))
    }

    @Test
    fun `null fields are represented as null, not a guessed default`() {
        val incomplete = transition.copy(
            deltaMillis = null,
            distanceMeters = null,
            impliedSpeedMetersPerSecond = null,
            pathCellCount = null,
        )

        val message = formatReconstructionTransitionLogMessage(incomplete)

        assertTrue(message.contains("deltaMillis=null"))
        assertTrue(message.contains("distanceMeters=null"))
        assertTrue(message.contains("impliedSpeedMetersPerSecond=null"))
        assertTrue(message.contains("pathCellCount=null"))
    }

    @Test
    fun `logSafely swallows any exception from a misbehaving logger`() {
        val logger = object : TransitionDiagnosticLogger {
            override fun log(transition: ReconstructionTransitionDiagnostics) {
                error("simulated diagnostic logger failure")
            }
        }

        logger.logSafely(transition)
    }

    @Test
    fun `logSafely also swallows a CancellationException — log() never suspends, so it cannot be genuine job cancellation`() {
        val logger = object : TransitionDiagnosticLogger {
            override fun log(transition: ReconstructionTransitionDiagnostics) {
                throw CancellationException("fabricated by a misbehaving synchronous logger, not real cancellation")
            }
        }

        // Must not throw — unlike LocationTrackingSession.submitObservationSafely's genuine
        // cancellation boundary around the suspend submitDiscoveryObservation call, nothing here
        // suspends, so this can only be a fabricated exception, never real structured-concurrency
        // cancellation. See logSafely's doc comment for the full boundary explanation.
        logger.logSafely(transition)
    }

    private fun observation(coordinate: Coordinate, observedAt: Instant, accuracyMeters: Float? = null, speedMetersPerSecond: Float? = null) =
        LocationObservation(
            coordinate = coordinate,
            observedAt = observedAt,
            accuracyMeters = accuracyMeters,
            speedMetersPerSecond = speedMetersPerSecond,
            provider = null,
        )

    @Test
    fun `buildReconstructionTransitionDiagnostics computes delta, distance and implied speed`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val nearby = Coordinate(latitude = 48.8576, longitude = 2.3522) // ~111m north
        val from = observation(paris, Instant.parse("2026-01-01T10:00:00Z"))
        val to = observation(nearby, Instant.parse("2026-01-01T10:00:10Z"))

        val diagnostics = buildReconstructionTransitionDiagnostics(from, to, eligible = false, pathCellCount = 7)

        assertEquals(10_000L, diagnostics.deltaMillis)
        assertNotNull(diagnostics.distanceMeters)
        assertTrue("expected ~111m, got ${diagnostics.distanceMeters}", diagnostics.distanceMeters!! in 100.0..120.0)
        assertNotNull(diagnostics.impliedSpeedMetersPerSecond)
        // ~111m over 10s ~= ~11 m/s.
        assertTrue(
            "expected ~11 m/s, got ${diagnostics.impliedSpeedMetersPerSecond}",
            diagnostics.impliedSpeedMetersPerSecond!! in 9.0..13.0,
        )
        assertEquals(true, diagnostics.pathComputed)
        assertEquals(7, diagnostics.pathCellCount)
    }

    @Test
    fun `buildReconstructionTransitionDiagnostics carries the real accuracy and speed values, not booleans`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val from = observation(paris, Instant.parse("2026-01-01T10:00:00Z"), accuracyMeters = 5.5f, speedMetersPerSecond = 1.1f)
        val to = observation(paris, Instant.parse("2026-01-01T10:00:05Z"), accuracyMeters = null, speedMetersPerSecond = null)

        val diagnostics = buildReconstructionTransitionDiagnostics(from, to, eligible = false, pathCellCount = null)

        assertEquals(5.5f, diagnostics.fromAccuracyMeters)
        assertNull(diagnostics.toAccuracyMeters)
        assertEquals(1.1f, diagnostics.fromSpeedMetersPerSecond)
        assertNull(diagnostics.toSpeedMetersPerSecond)
    }

    @Test
    fun `buildReconstructionTransitionDiagnostics represents a missing path as pathComputed=false`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val from = observation(paris, Instant.parse("2026-01-01T10:00:00Z"))
        val to = observation(paris, Instant.parse("2026-01-01T10:00:05Z"))

        val diagnostics = buildReconstructionTransitionDiagnostics(from, to, eligible = false, pathCellCount = null)

        assertEquals(false, diagnostics.pathComputed)
        assertNull(diagnostics.pathCellCount)
    }

    @Test
    fun `identical timestamps produce a zero delta and a null implied speed, not a division by zero`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val nearby = Coordinate(latitude = 48.8576, longitude = 2.3522)
        val sameInstant = Instant.parse("2026-01-01T10:00:00Z")
        val from = observation(paris, sameInstant)
        val to = observation(nearby, sameInstant)

        val diagnostics = buildReconstructionTransitionDiagnostics(from, to, eligible = false, pathCellCount = null)

        assertEquals(0L, diagnostics.deltaMillis)
        assertNull(diagnostics.impliedSpeedMetersPerSecond)
    }

    @Test
    fun `an inverted timestamp pair keeps the negative delta as-is and still reports a null implied speed`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val nearby = Coordinate(latitude = 48.8576, longitude = 2.3522)
        // "from" observed after "to" — e.g. two fixes arriving out of order.
        val from = observation(paris, Instant.parse("2026-01-01T10:00:10Z"))
        val to = observation(nearby, Instant.parse("2026-01-01T10:00:00Z"))

        val diagnostics = buildReconstructionTransitionDiagnostics(from, to, eligible = false, pathCellCount = null)

        assertEquals(-10_000L, diagnostics.deltaMillis)
        assertNull(diagnostics.impliedSpeedMetersPerSecond)
    }
}
