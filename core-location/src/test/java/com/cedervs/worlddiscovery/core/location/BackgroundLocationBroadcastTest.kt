package com.cedervs.worlddiscovery.core.location

import android.content.Intent
import android.location.Location
import com.google.android.gms.location.LocationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for [extractBackgroundLocationObservations] against a real
 * [LocationResult]-carrying [Intent], not a hand-rolled substitute — the same "test the real
 * production artifact" principle as [FusedBackgroundLocationRegistrarTest] and
 * [FusedLocationUpdatesProviderTest]. Confirms every observation in a batch is extracted exactly
 * once, in chronological order by each fix's own timestamp, and that a multi-location batch never
 * collapses to only the last one — the exact failure mode this class's doc comment warns against
 * (`LocationResult.lastLocation` silently dropping every earlier fix in a batch).
 *
 * [LocationResult] does not expose a public helper for building a test [Intent] — [hasResult] and
 * [extractResult] read `Intent.getParcelableExtra("com.google.android.gms.location.EXTRA_LOCATION_RESULT")`
 * internally (confirmed by disassembling the real `play-services-location` classes, not guessed).
 * This test supplies that same key by name, via [Intent.putExtra], exactly as a real FLP-delivered
 * broadcast would populate it — this is the smallest way to exercise the real [extractResult] path
 * rather than reimplementing its extraction logic in the test.
 *
 * Requires [RobolectricTestRunner]: a bare `Intent`/`Location` and real Parcelable round-tripping
 * through it need Robolectric's shadow layer outside a real Android runtime, matching every other
 * Robolectric-backed test in this module.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundLocationBroadcastTest {

    private fun fakeLocation(provider: String, lat: Double, lon: Double, timeMillis: Long): Location =
        Location(provider).apply {
            latitude = lat
            longitude = lon
            time = timeMillis
        }

    private fun intentCarrying(locations: List<Location>): Intent {
        val intent = Intent()
        intent.putExtra(LOCATION_RESULT_EXTRA_KEY, LocationResult.create(locations))
        return intent
    }

    @Test
    fun `every location in a real batch intent is extracted, not just the last`() {
        val intent = intentCarrying(
            listOf(
                fakeLocation("fused", 48.8566, 2.3522, 1_000L),
                fakeLocation("fused", 45.7640, 4.8357, 2_000L),
                fakeLocation("fused", 43.6961, 7.2716, 3_000L),
            ),
        )

        val observations = extractBackgroundLocationObservations(intent)

        assertEquals(3, observations.size)
    }

    @Test
    fun `observations are processed exactly once each, no duplication or loss`() {
        val intent = intentCarrying(
            listOf(
                fakeLocation("fused", 48.8566, 2.3522, 1_000L),
                fakeLocation("fused", 45.7640, 4.8357, 2_000L),
            ),
        )

        val observations = extractBackgroundLocationObservations(intent)

        assertEquals(listOf(1_000L, 2_000L), observations.map { it.observedAt.toEpochMilli() })
    }

    @Test
    fun `an out-of-order batch is returned sorted chronologically by each fix's own timestamp`() {
        val intent = intentCarrying(
            listOf(
                fakeLocation("fused", 43.6961, 7.2716, 3_000L),
                fakeLocation("fused", 48.8566, 2.3522, 1_000L),
                fakeLocation("fused", 45.7640, 4.8357, 2_000L),
            ),
        )

        val observations = extractBackgroundLocationObservations(intent)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), observations.map { it.observedAt.toEpochMilli() })
    }

    @Test
    fun `a single-location delivery is not affected by batch handling`() {
        val intent = intentCarrying(listOf(fakeLocation("fused", 48.8566, 2.3522, 1_000L)))

        val observations = extractBackgroundLocationObservations(intent)

        assertEquals(1, observations.size)
        assertEquals(1_000L, observations.single().observedAt.toEpochMilli())
    }

    @Test
    fun `an intent carrying no LocationResult extracts to an empty list rather than throwing`() {
        val observations = extractBackgroundLocationObservations(Intent())

        assertTrue(observations.isEmpty())
    }

    companion object {
        private const val LOCATION_RESULT_EXTRA_KEY = "com.google.android.gms.location.EXTRA_LOCATION_RESULT"
    }
}
