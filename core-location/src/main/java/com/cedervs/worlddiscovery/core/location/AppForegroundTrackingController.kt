package com.cedervs.worlddiscovery.core.location

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Owns both the foreground [LocationTrackingSession] and background tracking
 * ([BackgroundLocationController]) in step with the *application's* foreground state — not any
 * single screen or Activity. Registered against `ProcessLifecycleOwner.get().lifecycle` by the
 * composition root (`AppContainer`), so foreground tracking runs whenever World Discovery is in
 * the foreground regardless of which tab is visible.
 *
 * A single class owns both transitions specifically so foreground and background tracking are
 * never simultaneously active *by construction*: `onStart` always tears background down before
 * starting foreground; `onStop` always stops foreground before arming background. Two independent
 * observers reacting to the same lifecycle event would each be individually correct but would
 * rely on relative ordering to avoid overlap — this doesn't.
 *
 * Takes a [LifecycleOwner] rather than hardcoding `ProcessLifecycleOwner` so this stays testable
 * with any [LifecycleOwner] test double (e.g. one backed by `LifecycleRegistry`).
 *
 * [calibrationDiagnosticSink] is TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE (see
 * [CalibrationDiagnosticFileWriter]'s doc comment) — best-effort, never affects the transition
 * logic above.
 */
class AppForegroundTrackingController(
    private val foregroundSession: LocationTrackingSession,
    private val backgroundController: BackgroundLocationController,
    private val calibrationDiagnosticSink: CalibrationDiagnosticSink = NoOpCalibrationDiagnosticSink(),
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        calibrationDiagnosticSink.recordSafely(
            CalibrationDiagnosticEvent.Lifecycle(CalibrationLifecycleKind.APP_FOREGROUND_START),
        )
        backgroundController.disarm()
        foregroundSession.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        calibrationDiagnosticSink.recordSafely(
            CalibrationDiagnosticEvent.Lifecycle(CalibrationLifecycleKind.APP_FOREGROUND_STOP),
        )
        foregroundSession.stop()
        backgroundController.arm()
    }
}
