package com.cedervs.worlddiscovery.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Foreground location permissions only — this phase never requests background location
 * (`ACCESS_BACKGROUND_LOCATION`), per docs/discovery-engine.md's staged approach: no
 * continuous/background tracking yet.
 */
object LocationPermissions {

    val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** True if either coarse or fine location is granted — coarse alone is enough to acquire
     * a location, matching the requirement to behave safely with coarse-only grants. */
    fun hasAnyLocationPermission(context: Context): Boolean =
        REQUIRED_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}
