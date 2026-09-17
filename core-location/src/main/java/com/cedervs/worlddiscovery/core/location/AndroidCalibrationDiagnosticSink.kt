package com.cedervs.worlddiscovery.core.location

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — see [CalibrationDiagnosticFileWriter]'s doc
 * comment for the overall removal point. The real, file-backed [CalibrationDiagnosticSink] —
 * converts each [CalibrationDiagnosticEvent] into the flat field map [CalibrationDiagnosticFileWriter.appendEvent]
 * expects. All Android-independent formatting logic ([calibrationEventToFields]) is a separate,
 * pure top-level function so it's directly unit-testable without constructing a real writer.
 */
class AndroidCalibrationDiagnosticSink(
    private val writer: CalibrationDiagnosticFileWriter,
) : CalibrationDiagnosticSink {
    override fun record(event: CalibrationDiagnosticEvent) {
        val (eventType, fields) = calibrationEventToFields(event)
        writer.appendEvent(eventType, fields)
    }

    override fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long): Boolean {
        val (eventType, fields) = calibrationEventToFields(event)
        return writer.appendEventCritical(eventType, fields, timeoutMillis)
    }
}

/** Pure, no Android dependency, no I/O — directly unit-testable. */
internal fun calibrationEventToFields(event: CalibrationDiagnosticEvent): Pair<String, Map<String, Any?>> =
    when (event) {
        is CalibrationDiagnosticEvent.Lifecycle ->
            "LIFECYCLE" to mapOf("kind" to event.kind.name)

        is CalibrationDiagnosticEvent.BackgroundRegistration ->
            "BACKGROUND_REGISTRATION" to mapOf(
                "outcome" to event.outcome.name,
                "priority" to event.config.priority,
                "intervalMillis" to event.config.intervalMillis,
                "minUpdateIntervalMillis" to event.config.minUpdateIntervalMillis,
                "maxUpdateDelayMillis" to event.config.maxUpdateDelayMillis,
            )

        is CalibrationDiagnosticEvent.LocationDelivered ->
            "LOCATION_DELIVERED" to mapOf(
                "source" to event.source.name,
                "batchId" to event.batchId,
                "batchSize" to event.batchSize,
                "indexInBatch" to event.indexInBatch,
                "observedAt" to event.observedAt.toString(),
                "receivedAt" to event.receivedAt.toString(),
                "fixAgeMillis" to event.fixAgeMillis,
                "accuracyMeters" to event.accuracyMeters,
                "speedMetersPerSecond" to event.speedMetersPerSecond,
                "provider" to event.provider,
            )
    }
