package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellGeometry
import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Polygon

/**
 * Regression guard for the exact symptom reported from physical device testing: a discovered
 * cell rendering as a huge polygon covering most of the viewport instead of a small H3
 * resolution-12 cell. Investigation found no defect here — the coordinate order, ring closing,
 * and antimeridian handling below are all correct; the huge shape was the temporary MapLibre
 * demo basemap's own per-country fill (`https://demotiles.maplibre.org/style.json`'s
 * `countries-fill` layer paints unmatched countries, France included, with `#EAB38F`), not this
 * code — see `DiscoveryMapView`'s doc comment and the implementation report for the full
 * diagnosis. These tests exist so a genuine future regression in `toFeature()` (a lat/lng swap, a
 * ring-closing bug, or an antimeridian-unwrap bug inflating the ring) fails loudly here instead of
 * only being caught by eye on a physical device.
 */
class DiscoveredCellGeometryRenderingTest {

    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

    // A small synthetic boundary at the same tens-of-meters scale as a real resolution-12 cell.
    // H3JavaCellConverterTest already covers the real H3 boundary values; this file stays focused
    // on toFeature()'s own behavior given a boundary, not H3 itself.
    private val smallBoundary = listOf(
        Coordinate(latitude = 48.8570, longitude = 2.3520),
        Coordinate(latitude = 48.8565, longitude = 2.3525),
        Coordinate(latitude = 48.8560, longitude = 2.3520),
    )

    private val discoveredCell = DiscoveredCell(
        cell = parisCell,
        trustStatus = TrustStatus.NON_CERTIFIED,
        firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
        lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    @Test
    fun `toFeature preserves longitude-latitude order, not the reverse`() {
        val geometry = DiscoveredCellGeometry(cell = discoveredCell, boundary = smallBoundary)

        val ring = (geometry.toFeature().geometry() as Polygon).coordinates().single()

        assertEquals(smallBoundary[0].longitude, ring[0].longitude(), 0.0)
        assertEquals(smallBoundary[0].latitude, ring[0].latitude(), 0.0)
    }

    @Test
    fun `toFeature closes the ring without changing its extent`() {
        val geometry = DiscoveredCellGeometry(cell = discoveredCell, boundary = smallBoundary)

        val ring = (geometry.toFeature().geometry() as Polygon).coordinates().single()

        assertEquals(smallBoundary.size + 1, ring.size)
        assertEquals(ring.first(), ring.last())
    }

    @Test
    fun `toFeature never inflates a small cell boundary into a country-sized polygon`() {
        // The exact class of bug reported from physical testing: a resolution-12 cell (a few
        // meters across) rendering as a polygon covering most of a country (multiple degrees
        // across). A real cell spans well under 0.001 degrees; this asserts the output stays on
        // that order of magnitude, generously bounded at 1 degree (~111km) — several orders of
        // magnitude below "covers France".
        val geometry = DiscoveredCellGeometry(cell = discoveredCell, boundary = smallBoundary)

        val ring = (geometry.toFeature().geometry() as Polygon).coordinates().single()

        val longitudeSpan = ring.maxOf { it.longitude() } - ring.minOf { it.longitude() }
        val latitudeSpan = ring.maxOf { it.latitude() } - ring.minOf { it.latitude() }
        assertTrue("longitude span $longitudeSpan degrees is country-sized, not cell-sized", longitudeSpan < 1.0)
        assertTrue("latitude span $latitudeSpan degrees is country-sized, not cell-sized", latitudeSpan < 1.0)
    }

    @Test
    fun `toFeature stays small even for a real antimeridian-crossing cell`() {
        // Real H3 resolution-12 boundary near +180 degrees (H3Core.cellToBoundary on cell
        // "8c7eb57221a2bff") — same fixture as AntimeridianUnwrappingTest, but run through the
        // full toFeature() pipeline (unwrap + ring-close + Polygon construction) rather than just
        // the unwrap function in isolation.
        val antimeridianCell = CanonicalCell(h3Index = "8c7eb57221a2bff", resolution = 12)
        val antimeridianBoundary = listOf(
            Coordinate(latitude = 1.1272600941060491E-4, longitude = 179.99989408795096),
            Coordinate(latitude = 4.2529943245653316E-5, longitude = 179.99985295677513),
            Coordinate(latitude = -3.99415330551343E-5, longitude = 179.99989240464174),
            Coordinate(latitude = -5.2217063085225254E-5, longitude = 179.99997298374083),
            Coordinate(latitude = 1.797898450522571E-5, longitude = -179.99998588496643),
            Coordinate(latitude = 1.0045058073046189E-4, longitude = 179.99997466711034),
        )
        val geometry = DiscoveredCellGeometry(
            cell = discoveredCell.copy(cell = antimeridianCell),
            boundary = antimeridianBoundary,
        )

        val ring = (geometry.toFeature().geometry() as Polygon).coordinates().single()

        val longitudeSpan = ring.maxOf { it.longitude() } - ring.minOf { it.longitude() }
        assertTrue("longitude span $longitudeSpan degrees is not cell-sized even after unwrapping", longitudeSpan < 1.0)
    }
}
