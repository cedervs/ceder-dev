package com.cedervs.worlddiscovery.core.database

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationProcessingState
import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationRecord
import com.cedervs.worlddiscovery.core.discovery.trajectory.ClaimToken
import com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationSource
import java.time.Instant

/** Pure mapping between the Room row and the [BufferedObservationRecord] domain model — no Room/
 * Android dependency, matching this module's existing `DiscoveredCellMapper` pattern. */

fun BufferedObservationEntity.toDomain(): BufferedObservationRecord = BufferedObservationRecord(
    id = id,
    dedupKey = dedupKey,
    source = TrajectoryObservationSource.valueOf(source),
    processSessionId = processSessionId,
    elapsedRealtimeClockDomainId = elapsedRealtimeClockDomainId,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    coordinate = Coordinate(latitude = latitude, longitude = longitude),
    providerTimeEpochMillis = providerTimeEpochMillis,
    receivedAt = Instant.ofEpochMilli(receivedAtEpochMillis),
    accuracyMeters = accuracyMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
    bearingDegrees = bearingDegrees,
    bearingAccuracyDegrees = bearingAccuracyDegrees,
    provider = provider,
    isMockLocation = isMockLocation,
    batchId = batchId,
    indexInBatch = indexInBatch,
    processingState = BufferedObservationProcessingState.valueOf(processingState),
    claimToken = claimToken?.let { ClaimToken(it) },
    processingStartedAt = processingStartedAtEpochMillis?.let { Instant.ofEpochMilli(it) },
    processedAt = processedAtEpochMillis?.let { Instant.ofEpochMilli(it) },
)

fun BufferedObservationRecord.toEntity(): BufferedObservationEntity = BufferedObservationEntity(
    id = id,
    dedupKey = dedupKey,
    source = source.name,
    processSessionId = processSessionId,
    elapsedRealtimeClockDomainId = elapsedRealtimeClockDomainId,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    latitude = coordinate.latitude,
    longitude = coordinate.longitude,
    providerTimeEpochMillis = providerTimeEpochMillis,
    receivedAtEpochMillis = receivedAt.toEpochMilli(),
    accuracyMeters = accuracyMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
    bearingDegrees = bearingDegrees,
    bearingAccuracyDegrees = bearingAccuracyDegrees,
    provider = provider,
    isMockLocation = isMockLocation,
    batchId = batchId,
    indexInBatch = indexInBatch,
    processingState = processingState.name,
    claimToken = claimToken?.value,
    processingStartedAtEpochMillis = processingStartedAt?.toEpochMilli(),
    processedAtEpochMillis = processedAt?.toEpochMilli(),
)
