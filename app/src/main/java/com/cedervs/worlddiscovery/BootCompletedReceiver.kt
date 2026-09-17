package com.cedervs.worlddiscovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cedervs.worlddiscovery.core.location.CalibrationLifecycleKind
import com.cedervs.worlddiscovery.core.location.runReceiverWorkWithCriticalDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manifest-declared for `android.intent.action.BOOT_COMPLETED` (see `AndroidManifest.xml`) — one
 * of the implicit broadcasts explicitly exempted from Android 8's manifest-registration
 * restriction. A device reboot does not preserve any `requestLocationUpdates` `PendingIntent`
 * registration; without this, background tracking would silently stay off until the user next
 * opened the app. `goAsync()` keeps the process alive long enough for the actual re-arm attempt
 * (a DataStore read plus, if applicable, a fresh registration call) to complete.
 *
 * Re-arming still only happens if persisted consent is enabled *and* the current OS permission is
 * actually granted — see `AppContainer.rearmBackgroundTrackingAfterBoot()` and
 * `BackgroundLocationController`, which own those two checks; this receiver adds no third one.
 *
 * ## TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — receiver durability
 * Codex review round: same durability concern as `BackgroundLocationReceiver` — this receiver
 * actually running, and the registration outcome `rearmBackgroundTrackingAfterBoot()` may produce,
 * are both lifecycle-critical calibration evidence a process kill shortly after `finish()` could
 * otherwise lose. The ordering — critical record attempt, then the real re-arm, then a bounded
 * diagnostic flush, with diagnostic failure never able to skip the re-arm and never able to mask
 * whatever the re-arm itself threw — is delegated to [runReceiverWorkWithCriticalDiagnostics]
 * (`:core-location`, pure/Android-free and directly unit-tested there). `finish()` stays
 * unconditional via the enclosing `finally`, unaffected by anything that call does or throws.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContainer = (context.applicationContext as WorldDiscoveryApplication).appContainer

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runReceiverWorkWithCriticalDiagnostics(
                    recordCritical = { appContainer.recordCriticalCalibrationEvent(CalibrationLifecycleKind.BOOT_COMPLETED_RECEIVED) },
                    flush = { appContainer.flushCalibrationDiagnostics() },
                    functionalWork = { appContainer.rearmBackgroundTrackingAfterBoot() },
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
