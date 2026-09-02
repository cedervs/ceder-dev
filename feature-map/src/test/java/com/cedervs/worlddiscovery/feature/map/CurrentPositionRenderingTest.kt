package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.haversineDistanceMeters
import com.cedervs.worlddiscovery.core.location.LocationObservation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Pure-JVM coverage for the live current-position marker's geometry logic
 * ([renderableAccuracyMetersOrNull], [positionFeatureCollection], [accuracyFeatureCollection],
 * [accuracyPolygon]) — the same `org.maplibre.geojson.*`-only boundary
 * [DiscoveredCellGeometryRenderingTest] already relies on. [applyCurrentPosition] itself
 * constructs real MapLibre `Style`/`GeoJsonSource`/`FillLayer`/`CircleLayer` objects, which need a
 * live Android/native runtime and can't be unit-tested here — same established boundary as
 * `applyDiscoveredCellGeometries` (see `DiscoveredCellLayerConfigurationTest`'s doc comment): source
 * reuse, layer ordering (accuracy below marker), style-reload recreation, and non-interference with
 * the discovered-cell source are all real, deliberate properties of [applyCurrentPosition]'s
 * implementation (see its own doc comment) but are not verifiable by a test in this module.
 */
class CurrentPositionRenderingTest {

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)

    private fun observation(accuracyMeters: Float?) = LocationObservation(
        coordinate = paris,
        observedAt = Instant.parse("2026-01-01T10:00:00Z"),
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = null,
        provider = null,
    )

    // ---- renderableAccuracyMetersOrNull ----

    @Test
    fun `renderableAccuracyMetersOrNull is null for a null accuracy`() {
        assertNull(renderableAccuracyMetersOrNull(null))
    }

    @Test
    fun `renderableAccuracyMetersOrNull is null for NaN`() {
        assertNull(renderableAccuracyMetersOrNull(Float.NaN))
    }

    @Test
    fun `renderableAccuracyMetersOrNull is null for positive infinity`() {
        assertNull(renderableAccuracyMetersOrNull(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `renderableAccuracyMetersOrNull is null for negative infinity`() {
        assertNull(renderableAccuracyMetersOrNull(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `renderableAccuracyMetersOrNull is null for a negative value`() {
        assertNull(renderableAccuracyMetersOrNull(-5.0f))
    }

    @Test
    fun `renderableAccuracyMetersOrNull is null for zero`() {
        assertNull(renderableAccuracyMetersOrNull(0.0f))
    }

    @Test
    fun `renderableAccuracyMetersOrNull returns the value for a valid finite positive accuracy`() {
        assertEquals(12.5, renderableAccuracyMetersOrNull(12.5f)!!, 1e-6)
    }

    // ---- positionFeatureCollection ----

    @Test
    fun `positionFeatureCollection is empty for a null observation`() {
        val collection = positionFeatureCollection(null)

        assertTrue(collection.features()!!.isEmpty())
    }

    @Test
    fun `positionFeatureCollection contains exactly one point at the observation's coordinate`() {
        val collection = positionFeatureCollection(observation(accuracyMeters = 10.0f))

        val feature = collection.features()!!.single()
        val point = feature.geometry() as Point
        assertEquals(paris.longitude, point.longitude(), 0.0)
        assertEquals(paris.latitude, point.latitude(), 0.0)
    }

    // ---- accuracyFeatureCollection ----

    @Test
    fun `accuracyFeatureCollection is empty for a null observation`() {
        val collection = accuracyFeatureCollection(null)

        assertTrue(collection.features()!!.isEmpty())
    }

    @Test
    fun `accuracyFeatureCollection is empty for every malformed accuracy value`() {
        for (malformed in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.0f, 0.0f)) {
            val collection = accuracyFeatureCollection(observation(accuracyMeters = malformed))

            assertTrue("expected no accuracy polygon for accuracyMeters=$malformed", collection.features()!!.isEmpty())
        }
    }

    @Test
    fun `accuracyFeatureCollection contains exactly one polygon for a valid finite positive accuracy`() {
        val collection = accuracyFeatureCollection(observation(accuracyMeters = 10.0f))

        val feature = collection.features()!!.single()
        assertTrue(feature.geometry() is Polygon)
    }

    // ---- accuracyPolygon ----

    @Test
    fun `accuracyPolygon closes its ring — first vertex repeated at the end`() {
        val ring = accuracyPolygon(paris, radiusMeters = 25.0).coordinates().single()

        assertEquals(ring.first(), ring.last())
    }

    @Test
    fun `accuracyPolygon vertices are approximately the requested radius from the center`() {
        val radiusMeters = 25.0
        val ring = accuracyPolygon(paris, radiusMeters).coordinates().single()

        // Every vertex (the closing duplicate included) must sit close to the requested radius —
        // cross-checked with the independently-tested haversineDistanceMeters, the same symmetry
        // used in GeodesicDistanceTest/the destinationPoint round-trip tests.
        for (vertex in ring) {
            val vertexCoordinate = Coordinate(latitude = vertex.latitude(), longitude = vertex.longitude())
            val distance = haversineDistanceMeters(paris, vertexCoordinate)
            assertTrue(
                "expected ~${radiusMeters}m, got $distance",
                distance in (radiusMeters - 0.5)..(radiusMeters + 0.5),
            )
        }
    }

    @Test
    fun `accuracyPolygon at a small radius still produces a non-degenerate ring`() {
        // Sub-meter accuracy is a real, reachable value (Trip 3's best fix was ~3.37m) — the ring
        // must not collapse to a single point or otherwise degenerate at small radii.
        val ring = accuracyPolygon(paris, radiusMeters = 3.0).coordinates().single()

        val longitudeSpan = ring.maxOf { it.longitude() } - ring.minOf { it.longitude() }
        val latitudeSpan = ring.maxOf { it.latitude() } - ring.minOf { it.latitude() }
        assertTrue("expected a non-degenerate ring, got longitude span $longitudeSpan", longitudeSpan > 0.0)
        assertTrue("expected a non-degenerate ring, got latitude span $latitudeSpan", latitudeSpan > 0.0)
    }

    // ---- layer/source identity ----

    @Test
    fun `the accuracy and position sources use distinct ids`() {
        assertNotEquals(CURRENT_POSITION_ACCURACY_SOURCE_ID, CURRENT_POSITION_SOURCE_ID)
    }

    @Test
    fun `the accuracy and position layers use distinct ids`() {
        assertNotEquals(CURRENT_POSITION_ACCURACY_LAYER_ID, CURRENT_POSITION_DOT_LAYER_ID)
    }

    @Test
    fun `the current-position source and layer ids never collide with the discovered-cell ones`() {
        val currentPositionIds = setOf(
            CURRENT_POSITION_ACCURACY_SOURCE_ID,
            CURRENT_POSITION_ACCURACY_LAYER_ID,
            CURRENT_POSITION_SOURCE_ID,
            CURRENT_POSITION_DOT_LAYER_ID,
        )
        val discoveredCellIds = setOf(
            DISCOVERED_CELLS_SOURCE_ID,
            DISCOVERED_CELLS_FILL_LAYER_ID,
            DISCOVERED_CELLS_OUTLINE_LAYER_ID,
        )

        assertTrue(currentPositionIds.intersect(discoveredCellIds).isEmpty())
    }
}
