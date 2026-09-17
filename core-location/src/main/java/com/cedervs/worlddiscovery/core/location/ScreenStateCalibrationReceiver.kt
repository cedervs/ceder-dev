package com.cedervs.worlddiscovery.core.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — see [CalibrationDiagnosticFileWriter]'s doc
 * comment for the overall removal point.
 *
 * `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` cannot be declared in the manifest (not among Android 8's
 * implicit-broadcast exemptions for statically-declared receivers) — this registers dynamically
 * instead, purely to log a [CalibrationLifecycleKind.SCREEN_ON]/[CalibrationLifecycleKind.SCREEN_OFF]
 * calibration event. It never reads any extra from the intent and never affects tracking behavior
 * in any way — see [CalibrationDiagnosticSink]'s non-throwing contract.
 *
 * This is the only signal in this whole pipeline able to distinguish "app backgrounded, screen
 * still on" from "app backgrounded, screen locked" — [AppForegroundTrackingController] alone
 * cannot, since the app leaves the foreground identically in both cases.
 *
 * Registered once, for the process lifetime, from `AppContainer` — debug builds only (see
 * `CalibrationDiagnosticFileWriter.forContext`'s `isEnabled` gate); never registered at all in a
 * release build, and never unregistered since it is meant to live exactly as long as the process,
 * matching every other process-lifetime object `AppContainer` owns.
 */
class ScreenStateCalibrationReceiver(
    private val calibrationDiagnosticSink: CalibrationDiagnosticSink,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> CalibrationLifecycleKind.SCREEN_ON
            Intent.ACTION_SCREEN_OFF -> CalibrationLifecycleKind.SCREEN_OFF
            else -> return
        }
        calibrationDiagnosticSink.recordSafely(CalibrationDiagnosticEvent.Lifecycle(kind))
    }

    /** Dynamically registers this receiver against [context]'s application context. Safe to call
     * even if calibration logging turns out to be disabled — callers only construct this class at
     * all when debug calibration logging is enabled (see `AppContainer`). */
    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            this,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
