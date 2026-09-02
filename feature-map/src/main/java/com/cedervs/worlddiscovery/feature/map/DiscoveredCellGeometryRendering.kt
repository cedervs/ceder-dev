package com.cedervs.worlddiscovery.feature.map

import android.graphics.Color
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellGeometry
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import com.google.gson.JsonObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal const val DISCOVERED_CELLS_SOURCE_ID = "discovered-cells-source"
internal const val DISCOVERED_CELLS_FILL_LAYER_ID = "discovered-cells-fill-layer"
internal const val DISCOVERED_CELLS_OUTLINE_LAYER_ID = "discovered-cells-outline-layer"
internal const val TRUST_STATUS_PROPERTY = "trustStatus"

// Provisional, non-final colors — only to keep Certified/Non-certified visually distinguishable
// for this increment. Final art direction is still open (docs/ai-context/OPEN_QUESTIONS.md).
internal const val NON_CERTIFIED_FILL_COLOR = "#3B82F6"
internal const val CERTIFIED_FILL_COLOR = "#F59E0B"

// Provisional physical-validation styling only, not final art direction — `fill-outline-color`
// (see git history) turned out to be an unreliable, unwidthable antialiased stroke, invisible in
// practice for a resolution-12 cell (confirmed by Codex's diagnosis: correctly configured, but
// FillLayer's outline has no configurable width). A dedicated LineLayer with an explicit width is
// the standard, reliable way to render a visible cell boundary.
internal const val CELL_OUTLINE_COLOR = "#000000"
internal const val CELL_OUTLINE_WIDTH = 2f

/**
 * Pure geometry/rendering logic, deliberately kept free of any Jetpack Compose dependency (unlike
 * `DiscoveryMapView.kt`, which owns the Composable/lifecycle wiring) so it stays testable without
 * a Compose test environment — this module has none.
 */
internal fun applyDiscoveredCellGeometries(style: Style, geometries: List<DiscoveredCellGeometry>) {
    val featureCollection = FeatureCollection.fromFeatures(geometries.map { it.toFeature() })

    val existingSource = style.getSourceAs<GeoJsonSource>(DISCOVERED_CELLS_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setGeoJson(featureCollection)
        return
    }

    style.addSource(GeoJsonSource(DISCOVERED_CELLS_SOURCE_ID, featureCollection))
    style.addLayer(
        FillLayer(DISCOVERED_CELLS_FILL_LAYER_ID, DISCOVERED_CELLS_SOURCE_ID).withProperties(
            PropertyFactory.fillColor(
                Expression.match(
                    Expression.get(TRUST_STATUS_PROPERTY),
                    Expression.color(Color.parseColor(NON_CERTIFIED_FILL_COLOR)),
                    Expression.stop(TrustStatus.CERTIFIED.code, Expression.color(Color.parseColor(CERTIFIED_FILL_COLOR))),
                ),
            ),
            PropertyFactory.fillOpacity(0.5f),
        ),
    )
    // Same source as the fill layer above — no second geometry/data path. Added immediately
    // after the fill layer so it renders on top of it. A resolution-12 cell is a few meters
    // across; a dedicated, explicitly-widthed line is what actually makes its boundary
    // identifiable against a busy basemap, unlike `fill-outline-color`'s unwidthable antialiased
    // stroke. Provisional physical-validation styling only, not final art direction.
    style.addLayer(
        LineLayer(DISCOVERED_CELLS_OUTLINE_LAYER_ID, DISCOVERED_CELLS_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor(CELL_OUTLINE_COLOR)),
            PropertyFactory.lineWidth(CELL_OUTLINE_WIDTH),
        ),
    )
}

internal fun DiscoveredCellGeometry.toFeature(): Feature {
    val rawRing = boundary.map { coordinate -> Point.fromLngLat(coordinate.longitude, coordinate.latitude) }
    val unwrappedRing = unwrapAntimeridianRing(rawRing)
    // GeoJSON polygon rings must be closed (first vertex repeated at the end); H3's boundary
    // does not repeat it, so close it here rather than in the domain/H3 layer. Compared after
    // unwrapping, using coordinate equality rather than reference equality.
    val closedRing = if (unwrappedRing.isNotEmpty() && unwrappedRing.first() != unwrappedRing.last()) {
        unwrappedRing + unwrappedRing.first()
    } else {
        unwrappedRing
    }
    val polygon = Polygon.fromLngLats(listOf(closedRing))
    val properties = JsonObject().apply {
        addProperty(TRUST_STATUS_PROPERTY, cell.trustStatus.code)
    }
    return Feature.fromGeometry(polygon, properties)
}

/**
 * H3's `cellToBoundary` does not unwrap longitudes for a cell straddling the antimeridian: two
 * geometrically adjacent vertices can come back as, e.g., `179.9999` and `-179.9999` — verified
 * against the real library (H3 resolution-12 cell `8c7eb57221a2bff`, near +180°). Rendered
 * as-is, a `Polygon` built from that ring draws an edge that spans nearly the full width of the
 * map instead of a small cell near the dateline.
 *
 * The minimal correct fix: walk the ring in order, and whenever consecutive raw longitudes jump
 * by more than 180°, apply a cumulative ±360° offset to the rest of the ring so it stays
 * geometrically contiguous — the standard technique for this class of problem (the unwrapped
 * longitude may end up slightly outside [-180, 180], which MapLibre/GL rendering handles
 * correctly). A ring nowhere near the antimeridian is returned unchanged.
 */
internal fun unwrapAntimeridianRing(points: List<Point>): List<Point> {
    if (points.isEmpty()) return points

    var previousRawLongitude = points.first().longitude()
    var offset = 0.0
    return points.map { point ->
        val rawLongitude = point.longitude()
        val delta = rawLongitude - previousRawLongitude
        if (delta > 180.0) {
            offset -= 360.0
        } else if (delta < -180.0) {
            offset += 360.0
        }
        previousRawLongitude = rawLongitude
        Point.fromLngLat(rawLongitude + offset, point.latitude())
    }
}
