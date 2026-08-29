package com.cedervs.worlddiscovery.core.location

import android.content.Context
import android.location.LocationManager
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * One-shot foreground location via Google Play services' fused location provider. Deliberately
 * does not start any tracking, service, or repeated polling — a single explicit request per
 * call, matching this phase's scope.
 *
 * Holds only an application-scoped [Context]; no Activity/UI context is needed since this
 * makes no UI-affecting calls of its own.
 */
class FusedLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override suspend fun getCurrentLocation(): LocationAcquisitionResult {
        if (!LocationPermissions.hasAnyLocationPermission(appContext)) {
            return LocationAcquisitionResult.PermissionDenied
        }
        if (!isAnyLocationProviderEnabled()) {
            return LocationAcquisitionResult.LocationServicesDisabled
        }

        return try {
            val cancellationTokenSource = CancellationTokenSource()
            val location = fusedClient
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationTokenSource.token)
                .await()
                ?: return LocationAcquisitionResult.LocationUnavailable

            val coordinate = runCatching { Coordinate(location.latitude, location.longitude) }.getOrNull()
                ?: return LocationAcquisitionResult.LocationUnavailable

            LocationAcquisitionResult.Success(coordinate)
        } catch (e: SecurityException) {
            LocationAcquisitionResult.PermissionDenied
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log the underlying exception message here: on some providers it can echo
            // back location-derived data. The class name alone is enough for debugging.
            LocationAcquisitionResult.Error(e.javaClass.simpleName)
        }
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
