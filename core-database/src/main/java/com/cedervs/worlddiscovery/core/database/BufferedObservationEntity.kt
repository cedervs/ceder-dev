package com.cedervs.worlddiscovery.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for one buffered raw observation — the trajectory-reconstruction Phase 1 foundation
 * (see `docs/ai-context/LOCATION_TRACKING.md`). Lives in its **own separate database**
 * ([TrajectoryBufferDatabase]), never in [WorldDiscoveryDatabase] alongside `discovered_cells` —
 * see that database's own doc comment for why (backup-exclusion targeting, and keeping the
 * canonical-discovery database's own file completely untouched by this feature).
 *
 * **This table stores a raw latitude/longitude — a deliberate, bounded, documented exception** to
 * this codebase's general "never persist raw GPS" stance (see
 * `docs/ai-context/REJECTED_APPROACHES.md`'s "Persistent raw GPS history" entry, and this round's
 * explicit product approval recorded in `docs/ai-context/LOCATION_TRACKING.md`). It exists solely
 * to make future trajectory reconstruction possible: short-lived, private, app-only, never
 * synchronized, never exposed to `feature-map`/`feature-journey`, purged by
 * [com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryBufferRetentionPolicy] — see
 * that class's own doc comment for what "purged" does and does not guarantee at the SQLite level.
 *
 * [id] is `AUTOINCREMENT` (via `autoGenerate = true`) specifically so it never gets reused after a
 * purge empties the table — this is what lets [id] double as the buffer's own monotonic retrieval
 * order (see `BufferedObservationRecord.id`'s own doc comment in `:core-discovery-engine`), not
 * just a row identity.
 */
@Entity(
    tableName = "buffered_observations",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["processingState", "id"]),
    ],
)
data class BufferedObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupKey: String,
    val source: String,
    val processSessionId: String,
    val elapsedRealtimeClockDomainId: String,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val providerTimeEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val bearingAccuracyDegrees: Float?,
    val provider: String?,
    val isMockLocation: Boolean,
    val batchId: String?,
    val indexInBatch: Int?,
    val processingState: String,
    /** Opaque owner token of the current `PROCESSING` lease, or `null` when not leased. See
     * `com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationBufferRepository.claimPendingObservations`
     * for the full claim/lease contract this backs — this column is what lets `markProcessed`
     * refuse to terminate a lease it does not currently own, and what lets a reclaim invalidate a
     * stale owner by clearing it. */
    val claimToken: String?,
    val processingStartedAtEpochMillis: Long?,
    val processedAtEpochMillis: Long?,
)
