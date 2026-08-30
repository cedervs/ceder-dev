package com.cedervs.worlddiscovery.core.location

import com.google.android.gms.location.Priority

/**
 * Parameters for a continuous location-updates request, used by both
 * [FusedLocationUpdatesProvider] (foreground) and [FusedBackgroundLocationRegistrar]
 * (background).
 *
 * **Provisional, not a calibrated World Discovery product decision.** `discovery-engine.md` §1/§8
 * leave movement/sampling thresholds explicitly open ([OUVERT — à calibrer]). Revisit once a real
 * calibration pass happens (battery behavior, resolution-12 coverage during a walk/trip, etc.)
 * and record the outcome in `discovery-engine.md` before treating any value here as final.
 */
data class LocationUpdateConfig(
    val priority: Int,
    val intervalMillis: Long,
    val minUpdateIntervalMillis: Long,
    val maxUpdateDelayMillis: Long,
) {
    companion object {
        /** Foreground, in-app-session tracking (unchanged values from the previous phase; only
         * the name changed, from `PROVISIONAL`, now that a second profile exists). Only
         * [priority]/[intervalMillis] are actually read by [FusedLocationUpdatesProvider] today —
         * the other two fields exist purely so both profiles share one shape. */
        val FOREGROUND_PROVISIONAL = LocationUpdateConfig(
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMillis = 15_000L,
            minUpdateIntervalMillis = 15_000L,
            maxUpdateDelayMillis = 15_000L,
        )

        /**
         * Background, `PendingIntent`-based tracking. Android already throttles background
         * delivery to roughly a few updates per hour regardless of what's requested here (see
         * `docs/architecture.md`'s background-tracking notes) — these values sit close to that
         * natural ceiling rather than far below it, to avoid spending battery asking for a
         * cadence the OS won't honor anyway. `PRIORITY_BALANCED_POWER_ACCURACY`, not
         * low-power/coarse-only, since H3 resolution-12 cells are small enough that a
         * cell-tower-only fix risks landing in the wrong one.
         */
        val BACKGROUND_PROVISIONAL = LocationUpdateConfig(
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMillis = 20 * 60 * 1000L,
            minUpdateIntervalMillis = 10 * 60 * 1000L,
            maxUpdateDelayMillis = 30 * 60 * 1000L,
        )
    }
}
