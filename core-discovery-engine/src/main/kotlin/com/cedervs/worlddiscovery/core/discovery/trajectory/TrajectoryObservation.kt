package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant

/**
 * PHASE 1 FOUNDATION — trajectory reconstruction / map matching. See
 * `docs/ai-context/LOCATION_TRACKING.md`'s "Trajectory reconstruction (Phase 1 foundations)"
 * section for the full status. **No reconstruction runs yet** — this package only defines the
 * durable domain boundary a future [TrajectoryReconstructor] implementation will consume.
 *
 * Deliberately separate from `com.cedervs.worlddiscovery.core.discovery`'s existing H3/canonical
 * types (`CanonicalCell`, `H3CellConverter`, `DiscoveredCell`...): H3 is the canonical *output*
 * format of discovery, never the internal working representation a future road-network map
 * matcher should reason in. Nothing in this package imports an H3 type, and nothing should —
 * nothing here even imports [Coordinate] beyond this file's convenience reuse (see below).
 *
 * Also deliberately independent of Android/Room/MapLibre and of any specific map-matching engine
 * (Valhalla/OSRM/GraphHopper/...) — see [TrajectoryReconstructor] and
 * [TrajectoryReconstructionResult] for why their shapes stay generic rather than modeled after one
 * engine's own API.
 */

/** Which acquisition path produced an observation — mirrors `core-location`'s own
 * `CalibrationLocationSource` distinction (foreground callback vs. background `PendingIntent`),
 * plus the one-shot debug/manual path, since a reconstruction window may need to reason about
 * observations that crossed a foreground/background transition. */
enum class TrajectoryObservationSource {
    FOREGROUND,
    BACKGROUND,
    ONE_SHOT,
}

/**
 * One observation as a future [TrajectoryReconstructor] would consume it — the pure, in-memory
 * projection of a buffered record (see [com.cedervs.worlddiscovery.core.discovery.trajectory]'s
 * own `BufferedObservationRecord` in this same package for the persisted shape this is derived
 * from).
 *
 * [observedAt] is the fix's own reported wall-clock time (`Location.time`) — **not** a reliable
 * monotonic ordering key on its own (device clock adjustments, NTP correction, timezone changes
 * can all move it non-monotonically). [elapsedRealtimeNanos], scoped to
 * [elapsedRealtimeClockDomainId], is the more trustworthy *relative* ordering signal within one
 * clock domain — see that field's own doc comment and `BufferedObservationRecord`'s for the full
 * reasoning. [receivedAt] is when this process actually learned about the fix (app-side, may lag
 * [observedAt] under background batching).
 */
data class TrajectoryObservation(
    val coordinate: Coordinate,
    val observedAt: Instant,
    val receivedAt: Instant,
    val source: TrajectoryObservationSource,
    /** Opaque identifier for "this app process's own lifetime" — never a claim of a real Android
     * boot identity (Android exposes no stable, permission-free per-boot UUID). Two observations
     * sharing the same [processSessionId] were captured by the same running process; a process
     * restart (swipe, crash, cold start) always gets a fresh one. See
     * [elapsedRealtimeClockDomainId] for why this is tracked as a *separate* axis from the raw
     * clock-domain concept, even though today's real implementation assigns them the same value. */
    val processSessionId: String,
    /** Opaque identifier for "the [elapsedRealtimeNanos] values in this observation are directly
     * comparable to any other observation sharing this same id." `elapsedRealtimeNanos` itself is
     * only meaningful *relative to other readings from the same underlying clock domain*
     * (conventionally, the same Android boot) — comparing it across a genuine reboot is meaningless.
     * Kept as a field distinct from [processSessionId] specifically so a future implementation that
     * *can* detect a real reboot boundary (as opposed to only a process restart) can assign it
     * independently, without needing to change this model's shape.
     *
     * **Production status: NOT WIRED / ENGINEERING DESIGN REQUIRED.** Nothing in this codebase
     * calls `core-location`'s `buildBufferedObservationRecord` yet (see that function's own doc
     * comment), so there is no "real capture path" to describe today — any earlier claim that one
     * exists and conservatively equates this with [processSessionId] was inaccurate and has been
     * corrected. Android exposes no stable, permission-free per-boot identifier, so a future
     * implementation must either (a) find and wire a genuine boot-scoped signal, or (b) explicitly
     * decide, as a reviewed engineering decision, to treat every process session as its own clock
     * domain (the conservative choice: it never wrongly merges two domains, at the cost of treating
     * a mere process restart as a new one even when the real Android boot has not changed). Neither
     * (a) nor (b) is decided or wired here — do not assume this field is ever populated from a real
     * clock-domain source until that decision is made and implemented. Test code in this codebase
     * that sets this equal to a `processSessionId`-shaped value is exercising the dedup/ordering
     * logic only, not asserting anything about production behavior. */
    val elapsedRealtimeClockDomainId: String,
    /** `Location.getElapsedRealtimeNanos()` — a monotonic nanosecond counter since the device's
     * current boot, always populated by a real Fused Location Provider fix. Only ever compare two
     * readings that share the same [elapsedRealtimeClockDomainId]. */
    val elapsedRealtimeNanos: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val bearingAccuracyDegrees: Float?,
    val provider: String?,
    val isMockLocation: Boolean,
    /** Groups observations delivered together in one background `PendingIntent`/batch — `null` for
     * foreground/one-shot deliveries, which are always singleton. Mirrors `core-location`'s
     * existing `CalibrationDiagnosticEvent.LocationDelivered` batch/index pair. */
    val batchId: String?,
    val indexInBatch: Int?,
)

/**
 * A bounded, ordered slice of [TrajectoryObservation]s a future [TrajectoryReconstructor] would
 * evaluate together — the "fenêtre de trajectoire" step of the conceptual pipeline (buffer →
 * normalization/dedup → window → reconstructor → result). Deliberately just an ordered list with
 * a non-empty invariant in Phase 1: **no windowing/segmentation policy is decided or implemented
 * here** — how a future component decides where one window starts/ends (gap-based trip
 * boundaries, a fixed observation count, a fixed time span, ...) is explicitly out of scope for
 * this phase; see `docs/ai-context/LOCATION_TRACKING.md`.
 */
data class ObservationWindow(val observations: List<TrajectoryObservation>) {
    init {
        require(observations.isNotEmpty()) { "An ObservationWindow must contain at least one observation" }
    }
}
