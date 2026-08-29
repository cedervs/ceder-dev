package com.cedervs.worlddiscovery.core.location

import com.google.android.gms.location.Priority

/**
 * Parameters for the continuous location-updates request used by [FusedLocationUpdatesProvider].
 *
 * **Provisional, not a calibrated World Discovery product decision.** `discovery-engine.md` §1/§8
 * leave movement/sampling thresholds explicitly open ([OUVERT — à calibrer]); these values are a
 * conservative placeholder for foreground, application-session tracking only, chosen to match the
 * priority already used by the one-shot [FusedLocationProvider]. Revisit once a real calibration
 * pass happens (battery behavior, resolution-12 coverage during a walk, etc.) and record the
 * outcome in `discovery-engine.md` before treating any value here as final.
 */
data class LocationUpdateConfig(
    val priority: Int,
    val intervalMillis: Long,
) {
    companion object {
        val PROVISIONAL = LocationUpdateConfig(
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMillis = 15_000L,
        )
    }
}
