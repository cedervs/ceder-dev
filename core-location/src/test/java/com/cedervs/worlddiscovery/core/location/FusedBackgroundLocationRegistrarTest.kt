package com.cedervs.worlddiscovery.core.location

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the background acquisition calibration experiment (see
 * `LocationUpdateConfig.BACKGROUND_PROVISIONAL`'s doc comment and
 * `docs/ai-context/LOCATION_TRACKING.md`) — inspects the actual production
 * [backgroundLocationRequest] factory, mirroring [FusedLocationUpdatesProviderTest]'s approach for
 * the foreground request, so a future silent revert of the experimental cadence fails a test
 * instead of only being caught on a physical trip.
 *
 * Requires [RobolectricTestRunner] for the same reason as [FusedLocationUpdatesProviderTest]: the
 * real `LocationRequest.Builder.build()` constructs a real `android.os.WorkSource` internally,
 * which throws `RuntimeException: Stub!` against the plain `android.jar` SDK stub outside
 * Robolectric's shadow layer.
 */
@RunWith(RobolectricTestRunner::class)
class FusedBackgroundLocationRegistrarTest {

    @Test
    fun `the production background LocationRequest uses BALANCED_POWER, the experimental 1-minute cadence, and stays batched`() {
        val request = backgroundLocationRequest(LocationUpdateConfig.BACKGROUND_PROVISIONAL)

        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
        assertEquals(60_000L, request.intervalMillis)
        assertEquals(30_000L, request.minUpdateIntervalMillis)
        assertEquals(15 * 60 * 1000L, request.maxUpdateDelayMillis)
        // Batching must remain on: maxUpdateDelayMillis (15 min) stays well above intervalMillis
        // (1 min) specifically so this experiment can still distinguish sparse computation from
        // dense-but-batched delivery — see LocationUpdateConfig.BACKGROUND_PROVISIONAL's doc
        // comment for why collapsing this ratio would confound the experiment.
        assertTrue(request.isBatched)
    }
}
