package com.cedervs.worlddiscovery.core.location

import java.time.Duration
import java.time.Instant

/**
 * What happened when [FusedBackgroundLocationRegistrar.register] was called — logged alongside
 * every registration attempt so a later Logcat capture can distinguish "we never actually asked"
 * from "we asked and it failed" without guessing.
 */
enum class BackgroundRegistrationOutcome {
    /** `requestLocationUpdates`'s [com.google.android.gms.tasks.Task] **completed successfully**
     * — not merely submitted. This confirms Play Services accepted the standing
     * [android.app.PendingIntent] request; it is still not proof Android will actually honor the
     * requested cadence, or that any location will ever be delivered — only that registration
     * itself succeeded. See [FusedBackgroundLocationRegistrar.register]'s doc comment for why
     * this is only logged from the Task's `addOnSuccessListener`, never right after the
     * (synchronous, void-returning-in-effect) call that starts it. */
    REGISTERED,

    /** [LocationPermissions.hasBackgroundLocationPermission] was false, so no request was made at
     * all — the single authoritative permission gate, unchanged by this diagnostic. */
    SKIPPED_NO_PERMISSION,

    /** `requestLocationUpdates` itself threw [SecurityException] synchronously, despite the
     * permission check above — a race with a concurrent revocation. */
    FAILED_SECURITY_EXCEPTION,

    /** `requestLocationUpdates`'s [com.google.android.gms.tasks.Task] completed **unsuccessfully**
     * — an asynchronous Play Services failure distinct from the synchronous
     * [FAILED_SECURITY_EXCEPTION] case. Deliberately carries no exception detail: only this
     * outcome tag is ever logged, never the [Exception] itself, so a diagnostic Logcat capture
     * can never carry arbitrary or sensitive exception content. */
    FAILED_TASK,
}

/**
 * Debug-only diagnostic logging seam for the **background acquisition calibration experiment**
 * (see `docs/ai-context/LOCATION_TRACKING.md`'s "BACKGROUND ACQUISITION CALIBRATION —
 * EXPERIMENTAL" section) — entirely separate from [LocationDiagnosticLogger], which already
 * covers per-fix quality logging shared by all three submission paths. This one exists
 * specifically to answer the calibration question: does a substantially shorter background
 * request actually produce denser location computation, denser batched delivery, neither, or a
 * registration/delivery problem? That needs registration-attempt outcomes and per-delivery batch
 * shape, which [LocationDiagnosticLogger] was never designed to carry.
 *
 * Deliberately never logs [LocationObservation.coordinate], any derived H3 cell, or any persisted
 * trajectory — see [formatBackgroundDeliveryLogMessage].
 *
 * Same non-throwing contract as [LocationDiagnosticLogger]: [logRegistrationSafely] and
 * [logDeliverySafely] are the actual enforcement points, guaranteeing a misbehaving
 * implementation — including one that fabricates a [kotlinx.coroutines.CancellationException] —
 * can never affect registration or submission. Neither [logRegistration] nor [logDelivery] is
 * `suspend`, so any such exception is necessarily fabricated, never genuine job cancellation; see
 * [LocationDiagnosticLogger.logSafely]'s doc comment for the identical reasoning applied there.
 */
interface BackgroundLocationDiagnosticLogger {
    fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome)
    fun logDelivery(observations: List<LocationObservation>)
}

/** Default used by every call site's constructor parameter — logging is opt-in, wired explicitly
 * in production (see `AppContainer`); tests that don't care about it need not pass anything. */
class NoOpBackgroundLocationDiagnosticLogger : BackgroundLocationDiagnosticLogger {
    override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) = Unit
    override fun logDelivery(observations: List<LocationObservation>) = Unit
}

/** See the interface doc comment's "non-throwing contract" paragraph. */
fun BackgroundLocationDiagnosticLogger.logRegistrationSafely(
    config: LocationUpdateConfig,
    outcome: BackgroundRegistrationOutcome,
) {
    try {
        logRegistration(config, outcome)
    } catch (t: Throwable) {
        // Diagnostic-only — must never affect whether registration proceeded.
    }
}

/** See the interface doc comment's "non-throwing contract" paragraph. */
fun BackgroundLocationDiagnosticLogger.logDeliverySafely(observations: List<LocationObservation>) {
    try {
        logDelivery(observations)
    } catch (t: Throwable) {
        // Diagnostic-only — must never affect whether the batch goes on to be submitted.
    }
}

/**
 * Pure Kotlin, no Android dependency, no I/O — directly unit-testable. One line per registration
 * attempt: what was requested (so a Logcat capture is self-contained even without cross-referencing
 * source) and what actually happened.
 */
fun formatBackgroundRegistrationLogMessage(
    config: LocationUpdateConfig,
    outcome: BackgroundRegistrationOutcome,
): String =
    "outcome=$outcome priority=${config.priority} intervalMillis=${config.intervalMillis} " +
        "minUpdateIntervalMillis=${config.minUpdateIntervalMillis} maxUpdateDelayMillis=${config.maxUpdateDelayMillis}"

/**
 * Pure Kotlin, no Android dependency, no I/O — directly unit-testable. One line per delivered
 * batch (size + wall-clock receipt time, for correlating against a physical trip afterward) plus
 * one line per location in it (its own timestamp, age at receipt computed the same way
 * [formatLocationObservationLogMessage] already does, accuracy, speed, provider) — enough to
 * distinguish sparse computation, dense-but-batched delivery, and stale/cached fixes from each
 * other, per the calibration experiment's stated goal. Never includes
 * [LocationObservation.coordinate] or any derived H3 cell — accuracy/speed/provider/timestamps
 * only.
 */
fun formatBackgroundDeliveryLogMessage(observations: List<LocationObservation>, receivedAt: Instant): String {
    val header = "batchSize=${observations.size} receivedAt=$receivedAt"
    val lines = observations.mapIndexed { index, observation ->
        val fixAgeMillis = runCatching { Duration.between(observation.observedAt, receivedAt).toMillis() }.getOrNull()
        "  [$index] observedAt=${observation.observedAt} fixAgeMillis=$fixAgeMillis " +
            "accuracyMeters=${observation.accuracyMeters} speedMetersPerSecond=${observation.speedMetersPerSecond} " +
            "provider=${observation.provider}"
    }
    return (listOf(header) + lines).joinToString(separator = "\n")
}
