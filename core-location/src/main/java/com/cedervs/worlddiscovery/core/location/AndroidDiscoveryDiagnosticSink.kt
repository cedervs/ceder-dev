package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.DiscoveryDiagnosticSink
import com.cedervs.worlddiscovery.core.discovery.DiscoveryObservationDiagnostics

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — see [CalibrationDiagnosticFileWriter]'s doc
 * comment for the overall removal point. The real, file-backed
 * [com.cedervs.worlddiscovery.core.discovery.DiscoveryDiagnosticSink] — lives in `:core-location`
 * rather than `:core-discovery-engine` because that module must stay a plain-JVM module with no
 * Android dependency (see that interface's own doc comment). Shares the same
 * [CalibrationDiagnosticFileWriter] instance (and so the same NDJSON file/session) as
 * [AndroidCalibrationDiagnosticSink] — one physical file per process, not two.
 */
class AndroidDiscoveryDiagnosticSink(
    private val writer: CalibrationDiagnosticFileWriter,
) : DiscoveryDiagnosticSink {
    override fun record(diagnostics: DiscoveryObservationDiagnostics) {
        writer.appendEvent(
            "DISCOVERY_RESULT",
            mapOf(
                "h3Cell" to diagnostics.h3Cell,
                "trustStatus" to diagnostics.trustStatus.name,
                "provenance" to diagnostics.provenance.name,
                "outcome" to diagnostics.outcome.name,
                "rejectionReason" to diagnostics.rejectionReason,
                "deliveryId" to diagnostics.deliveryId,
            ),
        )
    }
}
