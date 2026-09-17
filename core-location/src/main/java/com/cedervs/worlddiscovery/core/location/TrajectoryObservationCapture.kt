package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationProcessingState
import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationRecord
import com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationSource
import com.cedervs.worlddiscovery.core.discovery.trajectory.buildObservationDedupKey
import java.time.Instant

/**
 * PHASE 1 FOUNDATION — bridges this module's existing [LocationObservation] (produced by
 * [Location.toLocationObservation], already used by all three tracking paths) into the pure
 * `core-discovery-engine.trajectory` domain's [BufferedObservationRecord] shape, ready for
 * [com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationBufferRepository.insert].
 *
 * **Nothing in this codebase calls this function yet.** No tracking path writes to the trajectory
 * buffer in this phase — see `docs/ai-context/LOCATION_TRACKING.md` for why that wiring (which
 * call sites, what threading/performance guarantees on the hot location-callback path, any consent
 * gating beyond the tracking consent that already governs whether location processing happens at
 * all) is deliberately deferred past Phase 1's own scope. This function exists now, tested now, so
 * that future wiring only has to call it, not design it.
 *
 * Pure and total: never throws for a structurally valid [LocationObservation] (mirrors
 * [Location.toLocationObservation]'s own "no rejection logic here" contract) — [id] is always `0`
 * (storage assigns the real row id on insert) and [BufferedObservationRecord.processingState] is
 * always [BufferedObservationProcessingState.PENDING] for a freshly captured observation.
 */
fun buildBufferedObservationRecord(
    observation: LocationObservation,
    source: TrajectoryObservationSource,
    processSessionId: String,
    elapsedRealtimeClockDomainId: String,
    receivedAt: Instant,
    batchId: String? = null,
    indexInBatch: Int? = null,
): BufferedObservationRecord {
    val providerTimeEpochMillis = observation.observedAt.toEpochMilli()
    val dedupKey = buildObservationDedupKey(
        elapsedRealtimeClockDomainId = elapsedRealtimeClockDomainId,
        elapsedRealtimeNanos = observation.elapsedRealtimeNanos,
        providerTimeEpochMillis = providerTimeEpochMillis,
        coordinate = observation.coordinate,
        accuracyMeters = observation.accuracyMeters,
    )
    return BufferedObservationRecord(
        id = 0L, // assigned by storage on insert -- also doubles as the buffer's own monotonic order
        dedupKey = dedupKey,
        source = source,
        processSessionId = processSessionId,
        elapsedRealtimeClockDomainId = elapsedRealtimeClockDomainId,
        elapsedRealtimeNanos = observation.elapsedRealtimeNanos,
        coordinate = observation.coordinate,
        providerTimeEpochMillis = providerTimeEpochMillis,
        receivedAt = receivedAt,
        accuracyMeters = observation.accuracyMeters,
        speedMetersPerSecond = observation.speedMetersPerSecond,
        speedAccuracyMetersPerSecond = observation.speedAccuracyMetersPerSecond,
        bearingDegrees = observation.bearingDegrees,
        bearingAccuracyDegrees = observation.bearingAccuracyDegrees,
        provider = observation.provider,
        isMockLocation = observation.isMockLocation,
        batchId = batchId,
        indexInBatch = indexInBatch,
        processingState = BufferedObservationProcessingState.PENDING,
        claimToken = null,
        processingStartedAt = null,
        processedAt = null,
    )
}
