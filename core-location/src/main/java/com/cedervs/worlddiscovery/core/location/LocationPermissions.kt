package com.cedervs.worlddiscovery.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Location permission checks. [REQUIRED_PERMISSIONS] covers foreground access only, requested
 * together as today. [BACKGROUND_LOCATION_PERMISSION] is deliberately kept out of that array and
 * checked separately: Android silently denies *both* permissions if foreground and background
 * are ever requested bundled together (API 30+), and background access must never be inferred
 * from foreground being granted — see [hasBackgroundLocationPermission], which is the single
 * authoritative check used by [FusedBackgroundLocationRegistrar].
 */
object LocationPermissions {

    val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    const val BACKGROUND_LOCATION_PERMISSION: String = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    /** True if either coarse or fine location is granted — coarse alone is enough to acquire
     * a location, matching the requirement to behave safely with coarse-only grants. */
    fun hasAnyLocationPermission(context: Context): Boolean =
        REQUIRED_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    fun hasBackgroundLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, BACKGROUND_LOCATION_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
}
