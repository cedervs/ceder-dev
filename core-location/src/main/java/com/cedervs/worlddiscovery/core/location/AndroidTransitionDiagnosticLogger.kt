package com.cedervs.worlddiscovery.core.location

import android.util.Log

private const val RECONSTRUCTION_DIAGNOSTIC_LOG_TAG = "LocationQuality"

/**
 * The real, Android-backed [TransitionDiagnosticLogger] — mirrors [AndroidLocationDiagnosticLogger]
 * exactly: same Logcat tag (so `adb logcat -s LocationQuality:D` already covers both), same
 * debug-gating pattern, same non-throwing guarantee (best-effort diagnostic only).
 *
 * Wired into `AppContainer` for [ForegroundTransitionDiagnostics] (debug-only field-calibration
 * instrumentation — see `docs/ai-context/LOCATION_TRACKING.md`). [ForegroundReconstructionScheduler]
 * itself is a separate, still-unwired consumer of the same [TransitionDiagnosticLogger] interface —
 * nothing calls it in production today (see its own doc comment); wiring that in is part of
 * actually activating reconstruction, not this increment.
 */
class AndroidTransitionDiagnosticLogger(private val isEnabled: Boolean) : TransitionDiagnosticLogger {
    override fun log(transition: ReconstructionTransitionDiagnostics) {
        if (!isEnabled) return
        try {
            Log.d(RECONSTRUCTION_DIAGNOSTIC_LOG_TAG, formatReconstructionTransitionLogMessage(transition))
        } catch (t: Throwable) {
            // Best-effort diagnostic only — must never affect the functional path.
        }
    }
}
