package com.cedervs.worlddiscovery.core.location

import java.time.Duration
import java.time.Instant

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — see [CalibrationDiagnosticFileWriter]'s doc
 * comment for the overall removal point. Pure Kotlin, no Android dependency — directly
 * unit-testable, matching every other diagnostic event shape in this module.
 *
 * One-off process/app/tracking transitions, all otherwise indistinguishable from each other in a
 * plain Logcat capture that cannot survive a real physical calibration trip (screen lock, task
 * swipe, process death) — see `docs/ai-context/LOCATION_TRACKING.md`.
 */
enum class CalibrationLifecycleKind {
    /** Logged once, unconditionally, by [CalibrationDiagnosticFileWriter] itself at construction —
     * makes a process restart visible in the file even if nothing else fires afterward. */
    PROCESS_INIT,

    /** [AppForegroundTrackingController.onStart] — the app gained a STARTED Activity. In this
     * architecture this *is* "foreground tracking start": the same call site starts
     * [LocationTrackingSession] immediately after. */
    APP_FOREGROUND_START,

    /** [AppForegroundTrackingController.onStop] — the app lost its last STARTED Activity (not
     * necessarily the screen locking — see [SCREEN_OFF] for that distinct signal). Also "foreground
     * tracking stop" in this architecture, for the same reason as [APP_FOREGROUND_START]. */
    APP_FOREGROUND_STOP,

    /** [BackgroundLocationController.arm] was called — a background registration attempt is about
     * to be requested (subject to consent, checked afterward). */
    BACKGROUND_ARM_REQUESTED,

    /** [BackgroundLocationController.disarm] was called. */
    BACKGROUND_DISARM_REQUESTED,

    /** `BootCompletedReceiver.onReceive` ran (`:app`, cannot depend on this module the other way
     * around — recorded via `AppContainer.recordCalibrationReceiverEvent`). */
    BOOT_COMPLETED_RECEIVED,

    /** `BackgroundLocationReceiver.onReceive` ran — the single most important signal for
     * distinguishing "the PendingIntent registration survived a task swipe / process death and
     * still delivered" from "it did not." Recorded even when the delivered intent carried zero
     * usable locations. */
    BACKGROUND_LOCATION_RECEIVER_INVOKED,

    /** `Intent.ACTION_SCREEN_ON` — see [ScreenStateCalibrationReceiver]. The only source in this
     * whole pipeline able to distinguish "backgrounded, screen still on" from "backgrounded,
     * locked" — [APP_FOREGROUND_STOP] alone cannot, since the app leaves the foreground for both. */
    SCREEN_ON,

    /** `Intent.ACTION_SCREEN_OFF` — see [SCREEN_ON]. */
    SCREEN_OFF,
}

/** Which of the two independent delivery paths produced a [CalibrationDiagnosticEvent.LocationDelivered] —
 * see `docs/architecture.md`'s background-tracking notes for why these are structurally distinct
 * (an in-process callback vs. a `PendingIntent`-targeted manifest receiver, possibly in a
 * cold-started process). */
enum class CalibrationLocationSource {
    FOREGROUND_CALLBACK,
    BACKGROUND_PENDING_INTENT,
}

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE. See [CalibrationDiagnosticSink] for the logging
 * seam this is recorded through, and [CalibrationDiagnosticFileWriter] for the overall removal
 * point.
 */
sealed class CalibrationDiagnosticEvent {
    data class Lifecycle(val kind: CalibrationLifecycleKind) : CalibrationDiagnosticEvent()

    /** Mirrors [BackgroundRegistrationOutcome] — see that enum's doc comment for what each value
     * means. Recorded from [performBackgroundLocationRegistration] alongside (not instead of) the
     * existing [BackgroundLocationDiagnosticLogger] Logcat line. */
    data class BackgroundRegistration(
        val config: LocationUpdateConfig,
        val outcome: BackgroundRegistrationOutcome,
    ) : CalibrationDiagnosticEvent()

    /**
     * One delivered fix — [batchId] groups every fix delivered together in the same callback/
     * broadcast (always size 1 for [CalibrationLocationSource.FOREGROUND_CALLBACK], since
     * [FusedLocationUpdatesProvider] only reads `LocationResult.lastLocation`; can be >1 for
     * [CalibrationLocationSource.BACKGROUND_PENDING_INTENT], which batches — see
     * `BACKGROUND_PROVISIONAL`'s `maxUpdateDelayMillis`). Never carries a raw coordinate or H3
     * cell — accuracy/speed/provider/timestamps only, matching [LocationObservation]'s own
     * existing diagnostic-logging privacy stance.
     */
    data class LocationDelivered(
        val source: CalibrationLocationSource,
        val batchId: String,
        val batchSize: Int,
        val indexInBatch: Int,
        val observedAt: Instant,
        val receivedAt: Instant,
        val accuracyMeters: Float?,
        val speedMetersPerSecond: Float?,
        val provider: String?,
    ) : CalibrationDiagnosticEvent() {
        /** Omitted (never guessed) if the duration can't be represented as a `Long` of
         * milliseconds — same contract as `formatLocationObservationLogMessage`'s `fixAgeMillis`. */
        val fixAgeMillis: Long? = runCatching { Duration.between(observedAt, receivedAt).toMillis() }.getOrNull()
    }
}
