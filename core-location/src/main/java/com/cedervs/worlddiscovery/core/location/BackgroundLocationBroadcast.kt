package com.cedervs.worlddiscovery.core.location

import android.content.Intent
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.google.android.gms.location.LocationResult
import java.time.Instant

/**
 * Extracts every valid [BackgroundLocationObservation] from a background-location broadcast
 * [Intent] delivered by [FusedBackgroundLocationRegistrar]'s `PendingIntent`, oldest first.
 *
 * [LocationUpdateConfig.BACKGROUND_PROVISIONAL]'s `maxUpdateDelayMillis` lets Play services batch
 * several fixes into a single delivery — [LocationResult.getLocations] (not the single
 * [LocationResult.lastLocation]) is the only way to see all of them; reading just the last one
 * would silently drop every earlier fix in a batch. Each entry keeps its own `Location.getTime()`
 * rather than being stamped with a shared "now" — locations in a batch can genuinely be tens of
 * minutes apart. Sorted defensively by that timestamp rather than assumed to already arrive in
 * order. Returns an empty list if the intent carries no usable location.
 */
fun extractBackgroundLocationObservations(intent: Intent): List<BackgroundLocationObservation> {
    val result = LocationResult.extractResult(intent) ?: return emptyList()
    return result.locations
        .sortedBy { it.time }
        .mapNotNull { location ->
            val coordinate = runCatching { Coordinate(location.latitude, location.longitude) }.getOrNull()
                ?: return@mapNotNull null
            BackgroundLocationObservation(coordinate, Instant.ofEpochMilli(location.time))
        }
}
