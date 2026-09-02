package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.haversineDistanceMeters
import java.time.Duration

/**
 * The diagnostic payload for one transition between two consecutive [LocationObservation]s —
 * deliberately never carries a coordinate, an H3 cell/ID, or the full path; only aggregated,
 * non-sensitive facts useful for field calibration (see
 * `docs/ai-context/LOCATION_TRACKING.md`). Produced by two independent sources:
 * - [ForegroundReconstructionScheduler], where [eligible] reflects a real
 *   [ReconstructionEligibilityPolicy] evaluation (always `false` today — `DenyAllReconstructionEligibilityPolicy`
 *   is the only policy wired anywhere);
 * - [ForegroundTransitionDiagnostics], which never evaluates eligibility at all and always
 *   reports [eligible] as `false`, computing [pathCellCount]/[pathComputed] unconditionally.
 */
data class ReconstructionTransitionDiagnostics(
    val deltaMillis: Long?,
    val distanceMeters: Double?,
    val impliedSpeedMetersPerSecond: Double?,
    val fromAccuracyMeters: Float?,
    val toAccuracyMeters: Float?,
    val fromSpeedMetersPerSecond: Float?,
    val toSpeedMetersPerSecond: Float?,
    val pathComputed: Boolean,
    val pathCellCount: Int?,
    val eligible: Boolean,
)

/**
 * Diagnostic-only logging seam for a [ReconstructionTransitionDiagnostics] — deliberately a
 * separate abstraction from [LocationDiagnosticLogger] (single-observation diagnostics): a
 * transition's payload is a different shape (a pair + aggregate counts, never a raw coordinate/H3
 * ID/full path). Same non-throwing contract and reasoning as [LocationDiagnosticLogger] — see
 * [logSafely].
 */
interface TransitionDiagnosticLogger {
    fun log(transition: ReconstructionTransitionDiagnostics)
}

/** Default used by [ForegroundReconstructionScheduler]/[ForegroundTransitionDiagnostics]'s
 * constructor parameters. */
class NoOpTransitionDiagnosticLogger : TransitionDiagnosticLogger {
    override fun log(transition: ReconstructionTransitionDiagnostics) = Unit
}

/**
 * Enforces the "never throws" contract at the call site — including a [CancellationException].
 * Unlike a genuine job-cancellation boundary around a `suspend` call, [log] is never `suspend`:
 * nothing here can observe or cooperate with real structured-concurrency cancellation, so a
 * [CancellationException] surfacing from it can only be one a misbehaving, purely synchronous
 * [TransitionDiagnosticLogger] implementation fabricated and threw directly — not a signal that
 * the calling coroutine's job was actually cancelled. Rethrowing it would incorrectly cancel the
 * real tracking collector over a diagnostic-only failure, so it is swallowed identically to any
 * other [Throwable]. Contrast this with [LocationTrackingSession.submitObservationSafely]'s own
 * `catch (e: CancellationException) { throw e }` around the `suspend` call to
 * `submitDiscoveryObservation` — that one *is* a genuine cancellation boundary, because that call
 * really can suspend and really can be cancelled.
 */
fun TransitionDiagnosticLogger.logSafely(transition: ReconstructionTransitionDiagnostics) {
    try {
        log(transition)
    } catch (t: Throwable) {
        // Diagnostic logging must never affect the functional path — see the boundary
        // explanation above for why this deliberately includes CancellationException.
    }
}

/** Pure formatting, no Android dependency — directly unit-testable, matching
 * [formatLocationObservationLogMessage]'s pattern. */
fun formatReconstructionTransitionLogMessage(transition: ReconstructionTransitionDiagnostics): String =
    "eligible=${transition.eligible} deltaMillis=${transition.deltaMillis} " +
        "distanceMeters=${transition.distanceMeters} impliedSpeedMetersPerSecond=${transition.impliedSpeedMetersPerSecond} " +
        "fromAccuracyMeters=${transition.fromAccuracyMeters} toAccuracyMeters=${transition.toAccuracyMeters} " +
        "fromSpeedMetersPerSecond=${transition.fromSpeedMetersPerSecond} toSpeedMetersPerSecond=${transition.toSpeedMetersPerSecond} " +
        "pathComputed=${transition.pathComputed} pathCellCount=${transition.pathCellCount}"

/**
 * Builds a [ReconstructionTransitionDiagnostics] from two consecutive observations plus the
 * [pathCellCount] a caller already computed (or `null` if it wasn't/couldn't be) — shared by
 * [ForegroundReconstructionScheduler] and [ForegroundTransitionDiagnostics] so the
 * delta/distance/implied-speed computation exists in exactly one place, not duplicated and
 * potentially drifting between the two. `deltaMillis` uses the same "omit rather than throw for
 * an extreme Instant" contract as [formatLocationObservationLogMessage]'s age calculation.
 */
internal fun buildReconstructionTransitionDiagnostics(
    from: LocationObservation,
    to: LocationObservation,
    eligible: Boolean,
    pathCellCount: Int?,
): ReconstructionTransitionDiagnostics {
    val deltaMillis = runCatching { Duration.between(from.observedAt, to.observedAt).toMillis() }.getOrNull()
    val distanceMeters = haversineDistanceMeters(from.coordinate, to.coordinate)
    val impliedSpeedMetersPerSecond = deltaMillis?.takeIf { it > 0 }?.let { distanceMeters / (it / 1000.0) }
    return ReconstructionTransitionDiagnostics(
        deltaMillis = deltaMillis,
        distanceMeters = distanceMeters,
        impliedSpeedMetersPerSecond = impliedSpeedMetersPerSecond,
        fromAccuracyMeters = from.accuracyMeters,
        toAccuracyMeters = to.accuracyMeters,
        fromSpeedMetersPerSecond = from.speedMetersPerSecond,
        toSpeedMetersPerSecond = to.speedMetersPerSecond,
        pathComputed = pathCellCount != null,
        pathCellCount = pathCellCount,
        eligible = eligible,
    )
}
