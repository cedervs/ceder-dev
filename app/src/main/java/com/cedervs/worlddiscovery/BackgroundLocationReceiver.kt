package com.cedervs.worlddiscovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cedervs.worlddiscovery.core.location.extractBackgroundLocationObservations
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
 */
class BackgroundLocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val observations = extractBackgroundLocationObservations(intent)
        if (observations.isEmpty()) return
        val appContainer = (context.applicationContext as WorldDiscoveryApplication).appContainer

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                appContainer.submitBackgroundLocationObservations(observations)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
