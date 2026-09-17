package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * PHASE 3A CORRECTION ROUND 3 — fixes the final remaining Codex blocker on
 * [OrderedObservationWindowIdentity]: on [buildObservationDedupKey]'s [FixIdentityGuarantee.STRONG]
 * path, dedup identity is `elapsedRealtimeClockDomainId` + `elapsedRealtimeNanos` **alone** — two
 * observations sharing that pair but differing in, say, [TrajectoryObservation.coordinate] could
 * receive the *same* dedup key. Round 2's [OrderedObservationWindowIdentity] stored only the dedup
 * key per observation, so two such observations could produce the same window identity despite
 * genuinely different content — **dedup identity ("is this a redelivery of the same physical fix")
 * and content identity ("what does this observation actually say") are different concepts**, and
 * conflating them was the defect. This type now binds both explicitly, per observation, via
 * [ObservationIdentityEntry].
 *
 * **[buildObservationDedupKey]'s own semantics are unchanged** — this file does not weaken or
 * reinterpret it; it adds a second, independent axis alongside it.
 */
data class ObservationIdentityEntry(
    /** [buildObservationDedupKey]'s own output — unchanged semantics, still the fix-redelivery
     * identity this codebase already established for the trajectory buffer. */
    val dedupKey: String,
    /** See [ObservationContentFingerprint]'s own doc comment for exactly what this does and does not
     * cover. */
    val contentFingerprint: ObservationContentFingerprint,
)

/**
 * A deterministic, immutable, order-independent-per-field canonical fingerprint of one
 * [TrajectoryObservation]'s own safety/reconstruction-relevant content — deliberately **not** a
 * `toString()`-based or hand-concatenated string (no locale formatting anywhere is involved, so
 * there is nothing to be locale-dependent; no field-separator/escaping ambiguity is possible, since
 * each field is its own strongly-typed property compared structurally by Kotlin's generated
 * `equals()`, not parsed back out of a joined string).
 *
 * **Fields included, and why** (Phase 3A Correction Round 3's own §3 inspection of every actual
 * [TrajectoryObservation] field):
 * - [latitude]/[longitude] — the fix's own position; the exact field this round's blocker was found
 *   missing. Kept as raw `Double`s (no rounding) — exact coordinate semantics preserved.
 * - [observedAtEpochSecond]/[observedAtNano] — [Instant.getEpochSecond]/[Instant.getNano], the full-
 *   precision, timezone-free, locale-free decomposition of [TrajectoryObservation.observedAt]
 *   (an `Instant` is always a UTC instant internally; there is no timezone or locale to be
 *   independent *from* — decomposing it this way, rather than formatting it, is what makes that
 *   guarantee concrete rather than merely asserted). Deliberately **not** a single combined
 *   epoch-nanos `Long` (which could silently overflow for values far from the epoch) — two `Long`/
 *   `Int` fields, matching `Instant`'s own real representation exactly.
 * - [elapsedRealtimeClockDomainId]/[elapsedRealtimeNanos] — also part of [ObservationIdentityEntry.dedupKey]'s
 *   own computation, but genuinely relevant *content* in their own right (temporal/kinematic
 *   reasoning, per this round's own §3 category list), so also included directly here rather than
 *   assumed to be "already covered" by the dedup key alone — restating them here is exactly what
 *   closes this round's blocker.
 * - [accuracyMetersBits]/[speedMetersPerSecondBits]/[speedAccuracyMetersPerSecondBits]/
 *   [bearingDegreesBits]/[bearingAccuracyDegreesBits] — GPS-quality/kinematic/heading content.
 *   Stored as the source `Float`'s own [Float.toRawBits] `Int` (exact IEEE-754 bit pattern, including
 *   distinguishing `NaN`/±0.0 exactly as reported) when the source field is non-null, or `null` when
 *   the source field itself is `null` — `null` (never measured) and a genuine `0.0f` reading (whose
 *   bits are a non-null `0`) are never conflated.
 * - [provider] — GPS-quality-relevant diagnostic content (which underlying location provider
 *   produced the fix).
 * - [isMockLocation] — mock/spoof diagnostic content, explicitly called out in this round's own §3
 *   category list.
 *
 * **Fields intentionally excluded, and why** — all of these are *delivery metadata* (how/where/when
 * this app process learned of the fix), never a property of the physical observation itself, mirroring
 * this exact codebase's own already-established `buildObservationDedupKey` rationale for excluding
 * the same kind of field from *that* key:
 * - [TrajectoryObservation.receivedAt] — when *this app process* ingested the fix, not a property of
 *   the fix itself; two independent, genuinely-identical captures of the same real-world observation
 *   could legitimately have different `receivedAt` values depending on delivery timing/batching.
 * - [TrajectoryObservation.source] — acquisition path (foreground/background/one-shot); the same
 *   physical fix redelivered under a different source must not appear as different *content*.
 * - [TrajectoryObservation.processSessionId] — "which app process instance received this"; a process
 *   restart must not change what the observation itself says.
 * - [TrajectoryObservation.batchId]/[TrajectoryObservation.indexInBatch] — background-batch delivery
 *   bookkeeping, not fix content.
 *
 * [TrajectoryObservation] has no altitude field today — this round does not invent one (Phase 3A's
 * own standing "do not invent fields" instruction).
 */
data class ObservationContentFingerprint(
    val latitude: Double,
    val longitude: Double,
    val observedAtEpochSecond: Long,
    val observedAtNano: Int,
    val elapsedRealtimeClockDomainId: String,
    val elapsedRealtimeNanos: Long,
    val accuracyMetersBits: Int?,
    val speedMetersPerSecondBits: Int?,
    val speedAccuracyMetersPerSecondBits: Int?,
    val bearingDegreesBits: Int?,
    val bearingAccuracyDegreesBits: Int?,
    val provider: String?,
    val isMockLocation: Boolean,
) {
    companion object {
        fun of(observation: TrajectoryObservation): ObservationContentFingerprint = ObservationContentFingerprint(
            latitude = observation.coordinate.latitude,
            longitude = observation.coordinate.longitude,
            observedAtEpochSecond = observation.observedAt.epochSecond,
            observedAtNano = observation.observedAt.nano,
            elapsedRealtimeClockDomainId = observation.elapsedRealtimeClockDomainId,
            elapsedRealtimeNanos = observation.elapsedRealtimeNanos,
            accuracyMetersBits = observation.accuracyMeters?.toRawBits(),
            speedMetersPerSecondBits = observation.speedMetersPerSecond?.toRawBits(),
            speedAccuracyMetersPerSecondBits = observation.speedAccuracyMetersPerSecond?.toRawBits(),
            bearingDegreesBits = observation.bearingDegrees?.toRawBits(),
            bearingAccuracyDegreesBits = observation.bearingAccuracyDegrees?.toRawBits(),
            provider = observation.provider,
            isMockLocation = observation.isMockLocation,
        )
    }
}

/**
 * A deterministic, immutable fingerprint of the **exact** [TrajectoryReconstructionResult.AcceptedTrajectory]
 * a piece of [ReconstructionSafetyEvidence] claims to describe — Layer A of the three-layer
 * candidate/evidence association model (see [ReconstructionCandidateIdentity]'s own doc comment for
 * Layers B/C). Computed directly from the ordered raw [TrajectoryObservation] sequence itself
 * ([of]), never reconstructed later from candidate geometry.
 *
 * **Canonical input, explicit:** the ordered list of [TrajectoryObservation]s a
 * [TrajectoryReconstructor] was actually given (an `ObservationWindow.observations` list, in the
 * order presented to the reconstructor). Order is part of the identity —
 * [orderedObservationIdentities] is an ordered `List`, whose `equals` is order-sensitive, so
 * reordering the same observations always changes structural equality.
 *
 * **Binds two independent things per observation** ([ObservationIdentityEntry]): [buildObservationDedupKey]'s
 * own dedup identity (unchanged semantics — reused, not reinterpreted, per this round's own explicit
 * instruction), and [ObservationContentFingerprint] (this round's fix — see that type's own doc
 * comment for the full field-by-field inclusion/exclusion rationale). **Equality is over the full
 * per-observation entry list, never a bare checksum** — [sequenceChecksum] remains a purely
 * supplementary/debug signal, never the sole discriminator, matching this package's own established
 * convention ([ReconstructionCandidateIdentity.geometryChecksum]).
 *
 * **What this type is not, and does not need to be (Phase 3A round's own §12):** it carries no
 * network/matcher information, no policy value, no Safety Gate decision — it represents the ordered
 * input observation window's own identity, nothing more. See [MatcherRunIdentity] (Layer B) for which
 * matcher evaluation produced a candidate, and [ReconstructionCandidateIdentity] (Layer C) for the
 * candidate's own reconstructed geometry/provenance shape.
 */
data class OrderedObservationWindowIdentity(
    val observationCount: Int,
    val orderedObservationIdentities: List<ObservationIdentityEntry>,
    val sequenceChecksum: Long,
) {
    init {
        require(observationCount >= 0) { "observationCount must be >= 0, got $observationCount" }
        require(orderedObservationIdentities.size == observationCount) {
            "orderedObservationIdentities.size (${orderedObservationIdentities.size}) must equal observationCount ($observationCount)"
        }
    }

    companion object {
        private const val CHECKSUM_SEED = 7_919L
        private const val CHECKSUM_MULTIPLIER = 31L

        fun of(observations: List<TrajectoryObservation>): OrderedObservationWindowIdentity {
            val entries = observations.map { observation ->
                ObservationIdentityEntry(
                    dedupKey = buildObservationDedupKey(
                        elapsedRealtimeClockDomainId = observation.elapsedRealtimeClockDomainId,
                        elapsedRealtimeNanos = observation.elapsedRealtimeNanos,
                        providerTimeEpochMillis = observation.observedAt.toEpochMilli(),
                        coordinate = observation.coordinate,
                        accuracyMeters = observation.accuracyMeters,
                    ),
                    contentFingerprint = ObservationContentFingerprint.of(observation),
                )
            }
            var checksum = CHECKSUM_SEED
            for (entry in entries) {
                checksum = checksum * CHECKSUM_MULTIPLIER + entry.dedupKey.hashCode()
                checksum = checksum * CHECKSUM_MULTIPLIER + entry.contentFingerprint.hashCode()
            }
            return OrderedObservationWindowIdentity(
                observationCount = observations.size,
                orderedObservationIdentities = entries,
                sequenceChecksum = checksum,
            )
        }
    }
}
