package com.cedervs.worlddiscovery.core.location

import java.time.Duration
import java.time.Instant

/**
 * Diagnostic-only logging seam for a [LocationObservation] — deliberately an injectable
 * abstraction, not a direct `android.util.Log` call, for two reasons:
 * 1. [formatLocationObservationLogMessage] below (the actual formatting/computation logic) stays
 *    pure Kotlin with zero Android dependency, so it's unit-testable without Robolectric.
 * 2. Every implementation's [log] must be guaranteed never to throw — logging is best-effort and
 *    diagnostic-only; it must never be able to affect whether a caller goes on to submit an
 *    observation to [com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation]. See
 *    `AndroidLocationDiagnosticLogger` for the real, Android-backed implementation and its
 *    non-throwing contract, and [logSafely] for how that contract is actually enforced at the call
 *    site — including for a fabricated [kotlinx.coroutines.CancellationException], which [log]
 *    (never `suspend`) cannot produce as a signal of genuine job cancellation.
 */
interface LocationDiagnosticLogger {
    fun log(observation: LocationObservation)
}

/** Default used by every call site's constructor parameter — logging is opt-in, wired explicitly
 * in production (see `AppContainer`); tests that don't care about logging need not pass anything. */
class NoOpLocationDiagnosticLogger : LocationDiagnosticLogger {
    override fun log(observation: LocationObservation) = Unit
}

/**
 * Calls [LocationDiagnosticLogger.log], guaranteed never to propagate any exception from it —
 * this is the actual enforcement point for the "never throws" contract described on
 * [LocationDiagnosticLogger] itself, applied uniformly at the call site rather than trusted to
 * every implementation individually. [LocationTrackingSession], `SubmitCurrentLocationUseCase`,
 * and `SubmitBackgroundLocationObservations` all call this instead of [LocationDiagnosticLogger.log]
 * directly, so even a misbehaving logger implementation (not just a well-behaved one) can never
 * prevent the caller from going on to submit an observation to the discovery engine.
 *
 * This includes a [kotlinx.coroutines.CancellationException]: [log] is never `suspend`, so nothing
 * here can observe or cooperate with real structured-concurrency cancellation — a
 * [kotlinx.coroutines.CancellationException] surfacing from it can only be one a misbehaving,
 * purely synchronous [LocationDiagnosticLogger] implementation fabricated and threw directly, not
 * a signal that the calling coroutine's job was actually cancelled. Rethrowing it would incorrectly
 * cancel the real caller (e.g. [LocationTrackingSession]'s tracking collector) over a
 * diagnostic-only failure, so it is swallowed identically to any other [Throwable] — exactly like
 * [TransitionDiagnosticLogger.logSafely]. Contrast this with the genuine cancellation boundary
 * around the `suspend` call to `SubmitDiscoveryObservation` in [LocationTrackingSession] (and the
 * one-shot/background submission use cases) — that one *is* real, because that call really can
 * suspend and really can be cancelled, and must keep propagating.
 */
fun LocationDiagnosticLogger.logSafely(observation: LocationObservation) {
    try {
        log(observation)
    } catch (t: Throwable) {
        // Diagnostic logging must never affect the functional submission path — see the boundary
        // explanation above for why this deliberately includes CancellationException.
    }
}

/**
 * Builds the diagnostic log line for one [LocationObservation] — deliberately excludes the raw
 * coordinate and any derived H3 cell (see `docs/ai-context/LOCATION_TRACKING.md`). Pure Kotlin,
 * no Android dependency, no I/O — directly unit-testable.
 *
 * `fixAgeMillis` is *omitted* (never computed as `0` or any other guessed value) rather than
 * thrown from if the duration between [LocationObservation.observedAt] and [loggedAt] can't be
 * represented as a `Long` of milliseconds (`Duration.between`/`.toMillis()` can throw
 * `ArithmeticException` for a representable-but-extreme `Instant`) — an edge case in the data
 * must never crash a caller that only wanted a log line.
 */
fun formatLocationObservationLogMessage(observation: LocationObservation, loggedAt: Instant): String {
    val fixAgeMillis = runCatching { Duration.between(observation.observedAt, loggedAt).toMillis() }.getOrNull()
    return "observedAt=${observation.observedAt} fixAgeMillis=$fixAgeMillis " +
        "accuracyMeters=${observation.accuracyMeters} speedMetersPerSecond=${observation.speedMetersPerSecond} " +
        "provider=${observation.provider}"
}
