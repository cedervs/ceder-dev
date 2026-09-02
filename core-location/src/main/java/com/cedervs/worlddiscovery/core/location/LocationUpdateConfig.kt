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
 *
 * ## Field semantics (per the real Play Services `LocationRequest` contract)
 * - [intervalMillis]: the *desired* cadence — not a strict period. Delivery may be faster (down to
 *   [minUpdateIntervalMillis]), slower, or occasionally absent; it is a target, not a guarantee.
 * - [minUpdateIntervalMillis]: the fastest callbacks *this request* will accept — a hard per-request
 *   floor (apart from normal FLP scheduling jitter). When left unset on the real `LocationRequest`
 *   (not this data class — see [FusedLocationUpdatesProvider]), Play Services applies an *implicit*
 *   default of roughly half of [intervalMillis], not the full [intervalMillis] value.
 * - [maxUpdateDelayMillis]: batching control — how long the system may hold multiple computed
 *   locations before delivering them together in one `LocationResult`. Deliberately kept unwired
 *   for the foreground profile: [FusedLocationUpdatesProvider]'s callback reads only
 *   `LocationResult.lastLocation`, so enabling batching without also updating that callback to
 *   process the full `LocationResult.locations` list would silently discard every location in a
 *   batch except the newest.
 */
data class LocationUpdateConfig(
    val priority: Int,
    val intervalMillis: Long,
    val minUpdateIntervalMillis: Long,
    val maxUpdateDelayMillis: Long,
) {
    companion object {
        /**
         * Foreground, in-app-session tracking. **Experimental acquisition-quality profile,
         * physically A/B-tested across three GPS calibration walks** (see
         * `docs/ai-context/LOCATION_TRACKING.md` for full trip-by-trip results):
         * - Trips 1–2 used `PRIORITY_BALANCED_POWER_ACCURACY` and showed hundreds-of-meters
         *   accuracy, false excursions of several kilometers, and long frozen-position/
         *   degrading-accuracy sequences.
         * - Trip 3 switched to `PRIORITY_HIGH_ACCURACY` (interval still 15s, no explicit
         *   `minUpdateIntervalMillis`) and was dramatically better (median accuracy ~10.4m, zero
         *   fixes >100m, no catastrophic excursions) — but callbacks arrived with a median delta of
         *   ~7.45s, not ~15s. This is consistent with Play Services' documented implicit minimum of
         *   roughly half the requested interval when no explicit [minUpdateIntervalMillis] is set —
         *   i.e. it is compatible with opportunistic faster FLP production under that implicit
         *   ~7.5s floor, not evidence of some other requester overriding our request (our own
         *   implicit floor already permits this cadence on its own).
         * - This profile now requests that same ~7s cadence **explicitly and deliberately** —
         *   `intervalMillis`/[minUpdateIntervalMillis] both `7_000L` — turning what Trip 3 received
         *   opportunistically into a controlled, reproducible experimental variable (Trip 4). This
         *   is not a claim that 7s is a final calibrated threshold — see
         *   `docs/ai-context/LOCATION_TRACKING.md` for the specific question Trip 4 is designed to
         *   answer.
         *
         * `HIGH_ACCURACY` remains best-effort, not a guarantee: it heavily prioritizes GPS but
         * cannot force a fix under genuinely poor sky visibility (dense canopy), and does not by
         * itself filter/reject a degraded fix once acquired — that remains a separate, still-open,
         * CALIBRATION REQUIRED layer (see [ForegroundTransitionDiagnostics]).
         *
         * [priority]/[intervalMillis]/[minUpdateIntervalMillis] are all read by
         * [FusedLocationUpdatesProvider] as of this increment. [maxUpdateDelayMillis] remains
         * unread — see this class's "Field semantics" doc above for why.
         */
        val FOREGROUND_PROVISIONAL = LocationUpdateConfig(
            priority = Priority.PRIORITY_HIGH_ACCURACY,
            intervalMillis = 7_000L,
            minUpdateIntervalMillis = 7_000L,
            maxUpdateDelayMillis = 15_000L,
        )

        /**
         * Background, `PendingIntent`-based tracking. **BACKGROUND ACQUISITION CALIBRATION —
         * EXPERIMENTAL** (round 2, see `docs/ai-context/LOCATION_TRACKING.md` for the full
         * writeup and physical results this is designed to gather).
         *
         * **Previous configuration** (target 20 min / minimum 10 min / max batching delay 30
         * min): a physical vehicle-return trip with background tracking enabled produced
         * observations spaced roughly 10–20 minutes apart, some with accuracy as poor as ~1700 m
         * — consistent with, and largely explained by, that request itself (`BALANCED_POWER`
         * priority can and does fall back to network-tier fixes; the 10–20 min spacing sits right
         * where a 10-min floor / 20-min target would predict). **This does not by itself prove
         * the previous request was the *only* contributor** — Android background-execution
         * throttling and OEM (Samsung) battery/process management remain plausible additional
         * factors this configuration alone cannot isolate from.
         *
         * **This experiment's question**: when World Discovery requests a substantially shorter
         * `BALANCED_POWER` background cadence, does FLP produce useful intermediate observations
         * that are later delivered in batches, or does Android/OEM background behavior still
         * leave the observation history too sparse for meaningful discovery? `priority` stays
         * `PRIORITY_BALANCED_POWER_ACCURACY` and the `PendingIntent`/batching mechanism is
         * unchanged — only cadence is varied, to isolate that one variable.
         *
         * - [intervalMillis] drops a full order of magnitude, 20 min → **1 min** — the actual
         *   experimental lever.
         * - [minUpdateIntervalMillis] stays at half of [intervalMillis] (**30 s**), the same
         *   ratio Play Services applies implicitly when left unset — not itself the variable
         *   under test, just kept from becoming an artificial secondary constraint.
         * - [maxUpdateDelayMillis] (**15 min**) is deliberately kept a full 15× larger than the
         *   new [intervalMillis], not shrunk proportionally with it — real batching headroom is
         *   the second thing this experiment needs to observe (see class doc's "batching
         *   behavior" question). Shrinking it down close to [intervalMillis] would collapse every
         *   batch to ~1 location and make "FLP only computes sparsely" indistinguishable from
         *   "FLP computes densely but batching got disabled" — exactly the confound this
         *   experiment exists to avoid. Also shorter than the previous 30 min cap, so a physical
         *   trip sees results sooner without losing that headroom.
         *
         * **Not a calibrated product value** — see `docs/discovery-engine.md` §1/§8's
         * `[OUVERT — à calibrer]` movement/sampling thresholds. Revisit once this experiment's
         * physical results are recorded in `docs/ai-context/LOCATION_TRACKING.md`.
         */
        val BACKGROUND_PROVISIONAL = LocationUpdateConfig(
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMillis = 60 * 1000L,
            minUpdateIntervalMillis = 30 * 1000L,
            maxUpdateDelayMillis = 15 * 60 * 1000L,
        )
    }
}
