package com.cedervs.worlddiscovery.core.location

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the experimental foreground acquisition-quality profile (see
 * `LocationUpdateConfig.FOREGROUND_PROVISIONAL` and `FusedLocationUpdatesProvider`'s doc
 * comments) — inspects the actual production [foregroundLocationRequest] factory rather than a
 * duplicated builder call, so a future silent revert (e.g. back to
 * `PRIORITY_BALANCED_POWER_ACCURACY`, or dropping the explicit `minUpdateIntervalMillis` floor)
 * fails a test instead of only being caught on a physical walk.
 *
 * Requires [RobolectricTestRunner]: the real `com.google.android.gms.location.LocationRequest`
 * (unlike the pure-Kotlin/H3 types this module otherwise tests) constructs a real
 * `android.os.WorkSource` internally inside `Builder.build()` — a plain JVM unit test against the
 * `android.jar` SDK stub throws `RuntimeException: Stub!` there; Robolectric's shadow layer (this
 * module's existing `testImplementation(libs.robolectric)` dependency) is what makes that
 * construction actually work under `:core-location:testDebugUnitTest`.
 */
@RunWith(RobolectricTestRunner::class)
class FusedLocationUpdatesProviderTest {

    @Test
    fun `the production foreground LocationRequest uses HIGH_ACCURACY, an explicit 7s minimum cadence, waits for an accurate fix, and is not batched`() {
        val request = foregroundLocationRequest(LocationUpdateConfig.FOREGROUND_PROVISIONAL)

        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, request.priority)
        assertEquals(7_000L, request.intervalMillis)
        assertEquals(7_000L, request.minUpdateIntervalMillis)
        assertTrue(request.isWaitForAccurateLocation)
        // maxUpdateDelayMillis stays unwired for this profile — the foreground callback only reads
        // LocationResult.lastLocation, so batching must remain off until that callback is updated
        // to process the full LocationResult.locations list.
        assertFalse(request.isBatched)
    }
}
