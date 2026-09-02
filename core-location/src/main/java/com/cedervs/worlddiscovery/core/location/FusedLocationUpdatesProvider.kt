package com.cedervs.worlddiscovery.core.location

import android.content.Context
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Continuous foreground location via the fused location provider, for an in-app-session tracking
 * flow. Deliberately never requests background location and never survives past the collecting
 * coroutine being cancelled — no service, no WorkManager, matching this phase's scope.
 *
 * Permission and location-services checks happen once, at subscription time, here — this is the
 * single authoritative check; [LocationTrackingSession] never repeats it. Per this phase's
 * decision to keep the logical session alive across a transient services-disabled window: a
 * disabled state is reported once as an initial signal, but does not stop the underlying update
 * registration — the fused provider itself resumes delivering locations once services return, so
 * no restart is needed. A [SecurityException] (permission revoked) does stop collection.
 *
 * ## Experimental acquisition-quality profile
 * [config] is currently [LocationUpdateConfig.FOREGROUND_PROVISIONAL] — `PRIORITY_HIGH_ACCURACY`
 * at an explicit, controlled ~7s cadence (`intervalMillis`/`minUpdateIntervalMillis` both
 * `7_000L`), physically A/B-tested across three GPS calibration walks (see
 * `docs/ai-context/LOCATION_TRACKING.md` for full results and the exact question this cadence
 * increment is designed to answer). This is best-effort, not a guarantee: `HIGH_ACCURACY` cannot
 * force a fix under genuinely poor GPS sky visibility, and neither it nor
 * `waitForAccurateLocation(true)` nor the explicit cadence (see [foregroundLocationRequest] for
 * exactly what each does and does not guarantee) filters or rejects a degraded fix once acquired —
 * every structurally valid fix still reaches [LocationTrackingSession] exactly as before.
 * Filtering/plausibility thresholds and reconstruction remain **inactive** and unrelated to this
 * change: `DenyAllReconstructionEligibilityPolicy` stays the only wired eligibility policy, and no
 * acceptance/rejection decision exists anywhere in this pipeline.
 *
 * ## Location availability — [LocationAcquisitionResult.LocationUnavailable]
 * [LocationCallback.onLocationAvailability] is FLP's own live, per-subscription, **best-effort**
 * signal about whether it currently believes it can compute a location — distinct from, and
 * reported independently of, [isAnyLocationProviderEnabled]'s one-time-at-subscription OS-level
 * check. **This is not proof of genuine, sustained GPS loss.** It can plausibly reflect true signal
 * loss (indoors, a tunnel, deep obstruction), but physical testing found it also firing as brief,
 * apparently spurious blips even under stable outdoor conditions with otherwise-healthy fixes
 * arriving moments before and after — see `LocationTrackingSession`'s "Staleness grace window" doc
 * comment and `docs/ai-context/LOCATION_TRACKING.md` for the resulting design (a fixed grace
 * window before the live-position marker reacts, rather than an immediate response). A transient
 * FLP availability loss is **not** equivalent to the user disabling Location Services at the OS
 * level: the latter is a system setting this class only checks once, at subscription; the former is
 * momentary and self-healing — the very next [LocationCallback.onLocationResult] naturally signals
 * recovery, so no explicit "available again" emission is needed. Reuses the existing
 * [LocationAcquisitionResult.LocationUnavailable] case (previously only produced by the one-shot
 * [FusedLocationProvider]) rather than introducing a new type. This is a single additional callback
 * method on the same, single, already-registered [LocationCallback] — not a second request, client,
 * or subscription.
 */
class FusedLocationUpdatesProvider(
    context: Context,
    private val config: LocationUpdateConfig = LocationUpdateConfig.FOREGROUND_PROVISIONAL,
) : LocationUpdatesProvider {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun observeLocationUpdates(): Flow<LocationAcquisitionResult> = callbackFlow {
        if (!LocationPermissions.hasAnyLocationPermission(appContext)) {
            trySend(LocationAcquisitionResult.PermissionDenied)
            close()
            return@callbackFlow
        }

        if (!isAnyLocationProviderEnabled()) {
            // Informational only: the request below is still registered so collection resumes
            // on its own once services are re-enabled, with no restart from the caller.
            trySend(LocationAcquisitionResult.LocationServicesDisabled)
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val observation = location.toLocationObservation() ?: return
                trySend(LocationAcquisitionResult.Success(observation))
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                // No explicit "available again" emission — the next onLocationResult (a fresh
                // Success) already communicates recovery; see the class doc comment.
                if (!availability.isLocationAvailable) {
                    trySend(LocationAcquisitionResult.LocationUnavailable)
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(foregroundLocationRequest(config), callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            trySend(LocationAcquisitionResult.PermissionDenied)
            close()
        }

        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    private fun isAnyLocationProviderEnabled(): Boolean {
        val manager = locationManager ?: return false
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Builds the actual production foreground [LocationRequest] — extracted so a test can inspect
 * exactly what [FusedLocationUpdatesProvider] registers, instead of a test duplicating the
 * builder call and silently drifting from it.
 *
 * `setMinUpdateIntervalMillis(config.minUpdateIntervalMillis)`: this is the real, hard per-request
 * floor on callback frequency (apart from normal FLP scheduling jitter) — `intervalMillis` alone
 * is only a *desired* cadence and does not by itself prevent faster delivery. Left unset on the
 * real `LocationRequest`, Play Services applies an *implicit* minimum of roughly half
 * `intervalMillis` — which is why the previous 15s-only request (no explicit minimum) still
 * produced a ~7.45s median callback delta on Trip 3: that was compatible with opportunistic faster
 * FLP production under that implicit ~7.5s floor, not evidence that some other requester was
 * overriding this app's own request. Setting it explicitly here turns the ~7s cadence Trip 3
 * received opportunistically into a deliberate, controlled, reproducible experimental variable —
 * see the class doc comment above and `docs/ai-context/LOCATION_TRACKING.md` for what Trip 4 is
 * specifically testing.
 *
 * `setWaitForAccurateLocation(true)`: per the Play Services API contract, this may briefly delay
 * delivery of an initial low-accuracy location, in case a more accurate initial fix becomes
 * available shortly after. It is best-effort and bounded, not a guarantee — it does **not**
 * ensure the resulting initial fix meets any accuracy threshold, does **not** guarantee rejection
 * of every cached/stale fix, and does **not** filter or reject any later coarse/degraded fix once
 * the session is underway; that remains a separate, still-open, CALIBRATION REQUIRED layer (see
 * the class doc comment above).
 *
 * Deliberately does not set `maxUpdateDelayMillis`/`minUpdateDistanceMeters`/granularity.
 * `maxUpdateDelayMillis` in particular must stay unwired for the foreground profile specifically
 * because [FusedLocationUpdatesProvider]'s callback reads only `LocationResult.lastLocation`, not
 * the full `LocationResult.locations` list (unlike the background path's
 * `extractBackgroundLocationObservations`) — enabling batching here without first updating that
 * callback would silently discard every location in a batch except the newest. Changing any of
 * these remaining fields is a separate issue and must not be conflated with this cadence
 * experiment.
 */
internal fun foregroundLocationRequest(config: LocationUpdateConfig): LocationRequest =
    LocationRequest.Builder(config.intervalMillis)
        .setPriority(config.priority)
        .setWaitForAccurateLocation(true)
        .setMinUpdateIntervalMillis(config.minUpdateIntervalMillis)
        .build()
