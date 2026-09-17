package com.cedervs.worlddiscovery.core.location

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [calibrationEventToFields] — the pure mapping [AndroidCalibrationDiagnosticSink] relies
 * on to turn each typed [CalibrationDiagnosticEvent] into the flat field map
 * [CalibrationDiagnosticFileWriter.appendEvent] writes. A silent typo/drift here would make a
 * physical calibration capture quietly lose a field, so this is worth pinning down directly. */
class AndroidCalibrationDiagnosticSinkTest {

    @Test
    fun `a Lifecycle event maps to LIFECYCLE with its kind`() {
        val (eventType, fields) = calibrationEventToFields(
            CalibrationDiagnosticEvent.Lifecycle(CalibrationLifecycleKind.SCREEN_OFF),
        )

        assertEquals("LIFECYCLE", eventType)
        assertEquals(mapOf("kind" to "SCREEN_OFF"), fields)
    }

    @Test
    fun `a BackgroundRegistration event maps to BACKGROUND_REGISTRATION with the outcome and full config`() {
        val (eventType, fields) = calibrationEventToFields(
            CalibrationDiagnosticEvent.BackgroundRegistration(
                config = LocationUpdateConfig.BACKGROUND_PROVISIONAL,
                outcome = BackgroundRegistrationOutcome.REGISTERED,
            ),
        )

        assertEquals("BACKGROUND_REGISTRATION", eventType)
        assertEquals("REGISTERED", fields["outcome"])
        assertEquals(LocationUpdateConfig.BACKGROUND_PROVISIONAL.intervalMillis, fields["intervalMillis"])
        assertEquals(LocationUpdateConfig.BACKGROUND_PROVISIONAL.maxUpdateDelayMillis, fields["maxUpdateDelayMillis"])
    }

    @Test
    fun `a LocationDelivered event maps to LOCATION_DELIVERED and never includes a coordinate field`() {
        val event = CalibrationDiagnosticEvent.LocationDelivered(
            source = CalibrationLocationSource.BACKGROUND_PENDING_INTENT,
            batchId = "batch-1",
            batchSize = 2,
            indexInBatch = 1,
            observedAt = Instant.parse("2026-01-01T10:00:00Z"),
            receivedAt = Instant.parse("2026-01-01T10:00:05Z"),
            accuracyMeters = 12.5f,
            speedMetersPerSecond = null,
            provider = "fused",
        )

        val (eventType, fields) = calibrationEventToFields(event)

        assertEquals("LOCATION_DELIVERED", eventType)
        assertEquals("BACKGROUND_PENDING_INTENT", fields["source"])
        assertEquals("batch-1", fields["batchId"])
        assertEquals(2, fields["batchSize"])
        assertEquals(1, fields["indexInBatch"])
        assertEquals(5000L, fields["fixAgeMillis"])
        assertEquals("fused", fields["provider"])
        assertEquals(null, fields["speedMetersPerSecond"])
        assertTrue(
            "must never carry a raw coordinate/lat/lon field",
            fields.keys.none { it.contains("lat", ignoreCase = true) || it.contains("lon", ignoreCase = true) },
        )
    }

    @Test
    fun `recordCritical delegates through to the writer's appendEventCritical and reports success`() {
        val dir = java.nio.file.Files.createTempDirectory("android-calibration-sink-test").toFile()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)
        val sink = AndroidCalibrationDiagnosticSink(writer)

        val acknowledged = sink.recordCritical(CalibrationDiagnosticEvent.Lifecycle(CalibrationLifecycleKind.BACKGROUND_LOCATION_RECEIVER_INVOKED))

        assertTrue("a real write with no contention must succeed", acknowledged)
        assertTrue(
            File(dir, "tracking-calibration.ndjson").readText().contains("BACKGROUND_LOCATION_RECEIVER_INVOKED"),
        )
    }
}
