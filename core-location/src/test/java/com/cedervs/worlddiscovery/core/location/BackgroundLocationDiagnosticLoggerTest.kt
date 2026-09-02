package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundLocationDiagnosticLoggerTest {

    private val config = LocationUpdateConfig.BACKGROUND_PROVISIONAL

    private fun observation(lat: Double, lon: Double, observedAt: Instant, accuracy: Float?, speed: Float?) =
        LocationObservation(
            coordinate = Coordinate(latitude = lat, longitude = lon),
            observedAt = observedAt,
            accuracyMeters = accuracy,
            speedMetersPerSecond = speed,
            provider = "fused",
        )

    @Test
    fun `the registration message includes outcome and the configured cadence`() {
        val message = formatBackgroundRegistrationLogMessage(config, BackgroundRegistrationOutcome.REGISTERED)

        assertTrue(message.contains("outcome=REGISTERED"))
        assertTrue(message.contains("intervalMillis=${config.intervalMillis}"))
        assertTrue(message.contains("minUpdateIntervalMillis=${config.minUpdateIntervalMillis}"))
        assertTrue(message.contains("maxUpdateDelayMillis=${config.maxUpdateDelayMillis}"))
    }

    @Test
    fun `a skipped registration is distinguishable from a registered or failed one`() {
        val skipped = formatBackgroundRegistrationLogMessage(config, BackgroundRegistrationOutcome.SKIPPED_NO_PERMISSION)
        val failed = formatBackgroundRegistrationLogMessage(config, BackgroundRegistrationOutcome.FAILED_SECURITY_EXCEPTION)

        assertTrue(skipped.contains("outcome=SKIPPED_NO_PERMISSION"))
        assertTrue(failed.contains("outcome=FAILED_SECURITY_EXCEPTION"))
    }

    @Test
    fun `the delivery message includes batch size and receipt time`() {
        val receivedAt = Instant.parse("2026-01-01T10:00:00Z")

        val message = formatBackgroundDeliveryLogMessage(emptyList(), receivedAt)

        assertTrue(message.contains("batchSize=0"))
        assertTrue(message.contains("receivedAt=2026-01-01T10:00:00Z"))
    }

    @Test
    fun `every observation in the batch gets its own line with timestamp, age, accuracy, speed and provider`() {
        val receivedAt = Instant.parse("2026-01-01T10:00:05Z")
        val batch = listOf(
            observation(48.8566, 2.3522, Instant.parse("2026-01-01T10:00:00Z"), 12.5f, 1.4f),
            observation(45.7640, 4.8357, Instant.parse("2026-01-01T10:00:02Z"), null, null),
        )

        val message = formatBackgroundDeliveryLogMessage(batch, receivedAt)

        assertTrue(message.contains("batchSize=2"))
        assertTrue(message.contains("[0] observedAt=2026-01-01T10:00:00Z fixAgeMillis=5000 accuracyMeters=12.5 speedMetersPerSecond=1.4 provider=fused"))
        assertTrue(message.contains("[1] observedAt=2026-01-01T10:00:02Z fixAgeMillis=3000 accuracyMeters=null speedMetersPerSecond=null provider=fused"))
    }

    @Test
    fun `the delivery message never includes a raw coordinate`() {
        val batch = listOf(observation(48.8566, 2.3522, Instant.now(), null, null))

        val message = formatBackgroundDeliveryLogMessage(batch, Instant.now())

        assertTrue(!message.contains("48.8566"))
        assertTrue(!message.contains("2.3522"))
    }

    @Test
    fun `logRegistrationSafely swallows any exception from a misbehaving logger`() {
        val logger = object : BackgroundLocationDiagnosticLogger {
            override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) {
                error("simulated diagnostic logger failure")
            }

            override fun logDelivery(observations: List<LocationObservation>) = Unit
        }

        // Must not throw — registration must never be affected by a broken diagnostic logger.
        logger.logRegistrationSafely(config, BackgroundRegistrationOutcome.REGISTERED)
    }

    @Test
    fun `logRegistrationSafely also swallows a fabricated CancellationException`() {
        val logger = object : BackgroundLocationDiagnosticLogger {
            override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) {
                throw CancellationException("fabricated by a misbehaving synchronous logger, not real cancellation")
            }

            override fun logDelivery(observations: List<LocationObservation>) = Unit
        }

        logger.logRegistrationSafely(config, BackgroundRegistrationOutcome.REGISTERED)
    }

    @Test
    fun `logDeliverySafely swallows any exception from a misbehaving logger`() {
        val logger = object : BackgroundLocationDiagnosticLogger {
            override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) = Unit
            override fun logDelivery(observations: List<LocationObservation>) {
                error("simulated diagnostic logger failure")
            }
        }

        // Must not throw — the batch submission path must never be affected by a broken logger.
        logger.logDeliverySafely(emptyList())
    }

    @Test
    fun `logDeliverySafely also swallows a fabricated CancellationException`() {
        val logger = object : BackgroundLocationDiagnosticLogger {
            override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) = Unit
            override fun logDelivery(observations: List<LocationObservation>) {
                throw CancellationException("fabricated by a misbehaving synchronous logger, not real cancellation")
            }
        }

        logger.logDeliverySafely(emptyList())
    }
}
