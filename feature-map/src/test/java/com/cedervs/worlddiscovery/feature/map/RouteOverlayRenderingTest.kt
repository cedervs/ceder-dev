package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.RoutePoint
import com.cedervs.worlddiscovery.core.discovery.RouteSegment
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Covers required scenario E ("route rendering FeatureCollection contains expected
 * LineString/MultiLineString properties") and the empty/no-selection case (D) at the rendering-
 * conversion layer -- `deriveRouteSegments`/`clipRouteSegmentsToArea` themselves are covered
 * exhaustively in `core-discovery-engine`'s `DiscoveredRouteTest.kt`; this file only proves the
 * MapLibre `Feature`/`FeatureCollection` conversion is correct.
 */
class RouteOverlayRenderingTest {

    private fun point(lat: Double, lon: Double) = RoutePoint(Coordinate(latitude = lat, longitude = lon), Instant.parse("2026-01-01T10:00:00Z"))

    private val segmentA = RouteSegment(listOf(point(45.83, 1.26), point(45.80, 1.25), point(45.76, 1.23)))
    private val segmentB = RouteSegment(listOf(point(45.72, 1.20), point(45.68, 1.18)))

    @Test
    fun `E -- routeCoreFeatureCollection is empty for an empty segment list -- the no-selection case`() {
        val collection = routeCoreFeatureCollection(emptyList())

        assertTrue(collection.features()!!.isEmpty())
    }

    @Test
    fun `E -- routeCoreFeatureCollection produces one LineString Feature per RouteSegment, points in order`() {
        val collection = routeCoreFeatureCollection(listOf(segmentA, segmentB))

        val features = collection.features()!!
        assertEquals(2, features.size)
        assertTrue("every feature must be a genuine LineString geometry", features.all { it.geometry() is LineString })

        val lineA = features[0].geometry() as LineString
        assertEquals(
            listOf(Point.fromLngLat(1.26, 45.83), Point.fromLngLat(1.25, 45.80), Point.fromLngLat(1.23, 45.76)),
            lineA.coordinates(),
        )
    }

    @Test
    fun `E -- two genuinely non-continuous segments are never joined into one LineString`() {
        val collection = routeCoreFeatureCollection(listOf(segmentA, segmentB))

        val features = collection.features()!!
        val lineB = features[1].geometry() as LineString
        assertEquals(2, lineB.coordinates().size)
        // segmentB's own last point must never appear inside segmentA's LineString.
        val lineA = features[0].geometry() as LineString
        assertTrue(Point.fromLngLat(1.18, 45.68) !in lineA.coordinates())
    }

    @Test
    fun `E -- routeNodeFeatureCollection is empty for an empty segment list`() {
        assertTrue(routeNodeFeatureCollection(emptyList()).features()!!.isEmpty())
    }

    @Test
    fun `routeNodeFeatureCollection samples nodes across all segments, always including each segment's own endpoints`() {
        val collection = routeNodeFeatureCollection(listOf(segmentA, segmentB))

        val nodePoints = collection.features()!!.map { it.geometry() as Point }
        assertTrue(Point.fromLngLat(1.26, 45.83) in nodePoints) // segmentA's first point
        assertTrue(Point.fromLngLat(1.23, 45.76) in nodePoints) // segmentA's last point
        assertTrue(Point.fromLngLat(1.20, 45.72) in nodePoints) // segmentB's first point
        assertTrue(Point.fromLngLat(1.18, 45.68) in nodePoints) // segmentB's last point
    }

    // ==============================================================================================
    // N -- node count has an absolute bound, enforced at the actual rendering conversion layer.
    // ==============================================================================================

    @Test
    fun `N -- routeNodeFeatureCollection never exceeds the overlay-wide node budget for a Department with a very large first-discovery history`() {
        val manySegments = (0 until 10).map { segIndex ->
            RouteSegment((0 until 60).map { i -> point(45.0 + segIndex * 0.1, 1.0 + i * 0.0005) })
        }

        val collection = routeNodeFeatureCollection(manySegments)

        val totalRawPoints = manySegments.sumOf { it.points.size }
        assertTrue(
            "the bound must actually engage -- otherwise this test proves nothing",
            collection.features()!!.size < totalRawPoints,
        )
        assertTrue(
            "must never exceed the documented ROUTE_MAX_NODES_PER_OVERLAY_CALIBRATION_REQUIRED default",
            collection.features()!!.size <= com.cedervs.worlddiscovery.core.discovery.ROUTE_MAX_NODES_PER_OVERLAY_CALIBRATION_REQUIRED,
        )
    }

    // ==============================================================================================
    // Line-point VOLUME is bounded by CHUNKING (see chunkRouteSegmentForRendering), never by dropping
    // vertices -- Codex review correction round, blocking fix. A long segment now produces MULTIPLE
    // LineString Features, each within budget, whose edges are exactly the original segment's own
    // edges -- see DiscoveredRouteTest.kt for the exhaustive edge-preservation proof of the pure
    // chunking function itself; this file only proves the Feature/FeatureCollection conversion wires
    // it correctly.
    // ==============================================================================================

    private fun zigzagPoint(i: Int) = point(45.0 + i * 0.0001, 1.0 + if (i % 2 == 0) 0.0 else 0.0005)

    @Test
    fun `routeCoreFeatureCollection produces MULTIPLE LineString Features for a single very long segment, each within budget`() {
        val longSegment = RouteSegment((0 until 1200).map { i -> zigzagPoint(i) })

        val collection = routeCoreFeatureCollection(listOf(longSegment))
        val lines = collection.features()!!.map { it.geometry() as LineString }

        assertTrue("a 1200-point segment must be split into more than one chunk at the default 500-point budget", lines.size > 1)
        assertTrue(
            "must never exceed the documented ROUTE_MAX_LINE_POINTS_PER_SEGMENT_CALIBRATION_REQUIRED default",
            lines.all { it.coordinates().size <= com.cedervs.worlddiscovery.core.discovery.ROUTE_MAX_LINE_POINTS_PER_SEGMENT_CALIBRATION_REQUIRED },
        )
        // Chunking is rendering-only -- the domain segment itself is never mutated/truncated.
        assertEquals(1200, longSegment.points.size)
    }

    @Test
    fun `routeCoreFeatureCollection -- successive chunk Features overlap exactly at the boundary point, so the corridor stays visually continuous`() {
        val longSegment = RouteSegment((0 until 1200).map { i -> zigzagPoint(i) })

        val lines = routeCoreFeatureCollection(listOf(longSegment)).features()!!.map { it.geometry() as LineString }

        for (i in 0 until lines.size - 1) {
            assertEquals(
                "chunk Feature $i's own last coordinate must equal chunk Feature ${i + 1}'s own first coordinate -- no visible gap",
                lines[i].coordinates().last(),
                lines[i + 1].coordinates().first(),
            )
        }
    }

    @Test
    fun `routeCoreFeatureCollection never introduces a coordinate pair that was not consecutive in the original segment`() {
        val longSegment = RouteSegment((0 until 1200).map { i -> zigzagPoint(i) })
        val originalPairs = longSegment.points.zipWithNext { a, b ->
            Point.fromLngLat(a.coordinate.longitude, a.coordinate.latitude) to Point.fromLngLat(b.coordinate.longitude, b.coordinate.latitude)
        }.toSet()

        val lines = routeCoreFeatureCollection(listOf(longSegment)).features()!!.map { it.geometry() as LineString }
        val renderedPairs = lines.flatMap { it.coordinates().zipWithNext() }

        for (pair in renderedPairs) {
            assertTrue("rendered coordinate pair $pair was never consecutive in the original segment -- a synthetic chord", pair in originalPairs)
        }
    }

    @Test
    fun `routeCoreFeatureCollection leaves a short segment as exactly one LineString Feature`() {
        val collection = routeCoreFeatureCollection(listOf(segmentA))

        val features = collection.features()!!
        assertEquals(1, features.size)
        val line = features.single().geometry() as LineString
        assertEquals(3, line.coordinates().size)
    }

    // ==============================================================================================
    // I -- halo and core render identical chunk geometry, by construction: both layers source from
    // the same GeoJsonSource (ROUTE_SOURCE_ID), populated by exactly one call to
    // routeCoreFeatureCollection -- there is no second, independent conversion path either layer could
    // diverge from. This test proves that shared conversion is itself deterministic, so re-applying
    // the overlay (e.g. on a selection change) can never produce a mismatch between what halo and core
    // would render.
    // ==============================================================================================

    @Test
    fun `I -- routeCoreFeatureCollection is deterministic -- repeated calls with the same segments produce identical chunk geometry`() {
        val longSegment = RouteSegment((0 until 1200).map { i -> zigzagPoint(i) })

        val first = routeCoreFeatureCollection(listOf(longSegment, segmentA, segmentB))
        val second = routeCoreFeatureCollection(listOf(longSegment, segmentA, segmentB))

        val firstLines = first.features()!!.map { (it.geometry() as LineString).coordinates() }
        val secondLines = second.features()!!.map { (it.geometry() as LineString).coordinates() }
        assertEquals(firstLines, secondLines)
    }
}
