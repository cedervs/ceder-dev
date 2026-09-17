package com.cedervs.worlddiscovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cedervs.worlddiscovery.core.location.CalibrationLifecycleKind
import com.cedervs.worlddiscovery.core.location.extractBackgroundLocationObservations
import com.cedervs.worlddiscovery.core.location.runReceiverWorkWithCriticalDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manifest-declared (see `AndroidManifest.xml`) so Play services can invoke this even after the
 * app process was killed — the `PendingIntent` this class is targeted by (see
 * `FusedBackgroundLocationRegistrar`) is an explicit broadcast aimed at this exact component, so
 * it isn't subject to Android 8's implicit-broadcast manifest-registration restriction.
 *
 * Thin glue only: extracts every location in the delivery (a single broadcast can carry a batch —
 * see `BACKGROUND_PROVISIONAL`'s `maxUpdateDelayMillis`), then submits each through the exact
 * same `SubmitDiscoveryObservation` pipeline foreground tracking uses (`AppContainer`'s
 * `submitBackgroundLocationObservations`) — OBSERVED / NON_CERTIFIED, H3 resolution 12, Room. No
 * raw coordinate is persisted anywhere beyond that existing pipeline. `goAsync()` keeps the
 * process alive just long enough for those quick Room writes.
 *
 * ## TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — receiver durability
 * Codex review round: this receiver actually running is itself lifecycle-critical calibration
 * evidence — Android may kill the process moments after `onReceive` returns, so recording that
 * fact through the ordinary fire-and-forget [com.cedervs.worlddiscovery.core.location.CalibrationDiagnosticSink.record]
 * path (enqueue-and-return) could leave the write never actually reaching disk. `goAsync()` covers
 * *every* invocation, not just non-empty ones. The actual ordering — critical record attempt,
 * then the real submission, then a bounded diagnostic flush — is delegated to
 * [runReceiverWorkWithCriticalDiagnostics] (`:core-location`, pure/Android-free and directly
 * unit-tested there — see its own doc comment for the full guarantee), so this class stays a thin
 * wrapper around `goAsync()`/`PendingResult.finish()`, which stay unconditional regardless of what
 * that call does or throws. [com.cedervs.worlddiscovery.core.location.SubmitBackgroundLocationObservations]
 * already no-ops safely on an empty list (see its own tests), so this always calls it unconditionally
 * rather than branching on emptiness — one less thing to get subtly wrong, and it also means an
 * empty delivery still gets a chance to flush any pending overflow-loss evidence.
 */
class BackgroundLocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContainer = (context.applicationContext as WorldDiscoveryApplication).appContainer
        val observations = extractBackgroundLocationObservations(intent)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runReceiverWorkWithCriticalDiagnostics(
                    recordCritical = {
                        appContainer.recordCriticalCalibrationEvent(CalibrationLifecycleKind.BACKGROUND_LOCATION_RECEIVER_INVOKED)
                    },
                    flush = { appContainer.flushCalibrationDiagnostics() },
                    functionalWork = { appContainer.submitBackgroundLocationObservations(observations) },
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
