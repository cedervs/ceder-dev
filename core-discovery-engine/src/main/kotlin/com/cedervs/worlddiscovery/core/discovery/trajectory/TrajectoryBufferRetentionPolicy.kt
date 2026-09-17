package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.time.Duration

/**
 * Injectable, versionable retention policy for the local trajectory buffer — **the exact
 * numbers are CALIBRATION REQUIRED, not decided here** (`docs/ai-context/LOCATION_TRACKING.md`
 * mirrors this module's own established convention: see `DiscoveredRoute.kt`'s
 * `*_CALIBRATION_REQUIRED` constants for the same pattern applied to the corridor). [maxAge] and
 * [maxObservationCount] are independently optional — either or both may apply; `null` means "no
 * bound on this axis".
 *
 * **Correction (second Codex review round): no implicit unbounded construction.** An earlier
 * version exposed a public no-arg-friendly constructor (`TrajectoryBufferRetentionPolicy()`, both
 * parameters defaulting to `null`), which meant a call site could silently end up with an unbounded
 * policy without ever intending to — nothing about that call site's code would visibly say
 * "unbounded". The primary constructor is now private; every instance is built through one of the
 * named factories below, so the caller's *intent* is always visible at the call site:
 * - [boundedByAge] — an age bound only.
 * - [boundedByCount] — a count bound only.
 * - [bounded] — both bounds.
 * - [unboundedForTestingOnly] — explicitly no bound on either axis, for tests only (see its own doc
 *   comment); a future production wiring point must never reach for this one.
 *
 * No byte-size bound is modeled here: precisely enforcing on-disk storage size from within Room/
 * SQLite (accounting for page overhead, WAL content, index size) cannot be done reliably without
 * disproportionate complexity for a bounded, short-lived buffer whose row size is already small
 * and roughly fixed — [maxObservationCount] is the practical, testable proxy for bounding storage
 * growth. A true byte-size bound remains a possible future refinement, not attempted here.
 */
@ConsistentCopyVisibility
data class TrajectoryBufferRetentionPolicy private constructor(
    val maxAge: Duration?,
    val maxObservationCount: Int?,
) {
    companion object {
        /** An age bound only — no count bound. */
        fun boundedByAge(maxAge: Duration): TrajectoryBufferRetentionPolicy {
            requirePositiveAge(maxAge)
            return TrajectoryBufferRetentionPolicy(maxAge = maxAge, maxObservationCount = null)
        }

        /** A count bound only — no age bound. */
        fun boundedByCount(maxObservationCount: Int): TrajectoryBufferRetentionPolicy {
            requirePositiveCount(maxObservationCount)
            return TrajectoryBufferRetentionPolicy(maxAge = null, maxObservationCount = maxObservationCount)
        }

        /** Both an age bound and a count bound, applied independently (see
         * [TrajectoryObservationBufferRepository.purgeAccordingTo]'s own doc comment). */
        fun bounded(maxAge: Duration, maxObservationCount: Int): TrajectoryBufferRetentionPolicy {
            requirePositiveAge(maxAge)
            requirePositiveCount(maxObservationCount)
            return TrajectoryBufferRetentionPolicy(maxAge = maxAge, maxObservationCount = maxObservationCount)
        }

        /**
         * No expiration at all — **exists only for tests**, so a test can express "retention
         * disabled" explicitly via a name that says so, rather than relying on any default. A future
         * production wiring point must never reach for this function — it must always call
         * [boundedByAge]/[boundedByCount]/[bounded] with its own explicit, calibrated values.
         */
        fun unboundedForTestingOnly(): TrajectoryBufferRetentionPolicy =
            TrajectoryBufferRetentionPolicy(maxAge = null, maxObservationCount = null)

        private fun requirePositiveAge(maxAge: Duration) {
            require(!maxAge.isNegative && !maxAge.isZero) { "maxAge must be positive, got $maxAge" }
        }

        private fun requirePositiveCount(maxObservationCount: Int) {
            require(maxObservationCount > 0) { "maxObservationCount must be positive, got $maxObservationCount" }
        }
    }
}
