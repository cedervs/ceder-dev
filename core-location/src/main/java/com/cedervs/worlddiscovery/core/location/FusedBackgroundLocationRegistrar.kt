package com.cedervs.worlddiscovery.core.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices

/**
 * [BackgroundLocationRegistrar] backed by `FusedLocationProviderClient.requestLocationUpdates`'s
 * `PendingIntent` form — the registration lives at the Play-services layer, independent of this
 * process, and can cold-start the app via the manifest-declared receiver it targets even after
 * process death (see `docs/architecture.md`'s background-tracking notes).
 *
 * The target receiver is identified by fully-qualified class name only (no action/intent-filter)
 * — it must match exactly the `<receiver>` entry in `app/src/main/AndroidManifest.xml` and the
 * actual `BackgroundLocationReceiver` class in `:app`. `:core-location` cannot depend on `:app`,
 * so this string is the one place that link is expressed from this side.
 */
class FusedBackgroundLocationRegistrar(
    context: Context,
    private val config: LocationUpdateConfig = LocationUpdateConfig.BACKGROUND_PROVISIONAL,
) : BackgroundLocationRegistrar {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent().setClassName(appContext.packageName, RECEIVER_CLASS_NAME)
        PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            // Mutable: Play services must be able to add the LocationResult extras to the intent
            // it delivers — an immutable PendingIntent is rejected for this use.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    override fun register() {
        if (!LocationPermissions.hasBackgroundLocationPermission(appContext)) return

        val locationRequest = LocationRequest.Builder(config.intervalMillis)
            .setPriority(config.priority)
            .setMinUpdateIntervalMillis(config.minUpdateIntervalMillis)
            .setMaxUpdateDelayMillis(config.maxUpdateDelayMillis)
            .build()

        try {
            // Same PendingIntent identity (matching request code + component) on every call, so
            // a repeated register() replaces rather than duplicates the standing request.
            fusedClient.requestLocationUpdates(locationRequest, pendingIntent)
        } catch (e: SecurityException) {
            // Permission was just checked above but can still race with a concurrent revocation;
            // fail safely — nothing to submit, the next attempt re-checks from scratch.
        }
    }

    override fun unregister() {
        // Documented as safe to call even when nothing is currently registered.
        fusedClient.removeLocationUpdates(pendingIntent)
    }

    companion object {
        private const val RECEIVER_CLASS_NAME = "com.cedervs.worlddiscovery.BackgroundLocationReceiver"
        private const val REQUEST_CODE = 1001
    }
}
