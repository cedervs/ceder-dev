package com.cedervs.worlddiscovery.core.location

import android.content.Context
import android.location.LocationManager
import android.os.Looper
import com.cedervs.worlddiscovery.core.discovery.Coordinate
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
 */
class FusedLocationUpdatesProvider(
    context: Context,
    private val config: LocationUpdateConfig = LocationUpdateConfig.PROVISIONAL,
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
                val coordinate = runCatching { Coordinate(location.latitude, location.longitude) }.getOrNull()
                    ?: return
                trySend(LocationAcquisitionResult.Success(coordinate))
            }
        }

        val locationRequest = LocationRequest.Builder(config.intervalMillis)
            .setPriority(config.priority)
            .build()

        try {
            fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
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
