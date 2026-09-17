package com.cedervs.worlddiscovery.feature.map

import android.graphics.Color
import com.cedervs.worlddiscovery.core.discovery.RouteSegment
import com.cedervs.worlddiscovery.core.discovery.chunkRouteSegmentForRendering
import com.cedervs.worlddiscovery.core.discovery.sampleRouteNodesBounded
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * **Department-level derived first-discovery corridor visualization — not a GPS route, not canonical
 * discovery truth.** Renders [com.cedervs.worlddiscovery.core.discovery.RouteSegment]s — themselves a
 * pure, read-time derivation from the exact same canonical
 * [com.cedervs.worlddiscovery.core.discovery.DiscoveredCell] data H3 rendering already uses, see
 * `DiscoveredRoute.kt`'s own file-level doc comment for the full "what this explicitly is NOT" list —
 * as a thin, luminous-blue line with sampled node markers, designed to stay recognizable even once
 * individual H3 cells are too small to perceive at Department zoom.
 *
 * **Every point is an H3 resolution-12 cell center, never a raw GPS fix.** A straight connector
 * between two centers is a schematic connector between cells the underlying data shows real evidence
 * for (see `deriveRouteSegments`'s own structural-continuity requirement), never a claim about actual
 * road/terrain geometry, and the corridor as a whole represents *first-discovery progression*, not a
 * complete or exact travelled path — a corridor gap can simply mean "already discovered on an earlier
 * visit," not "never visited."
 *
 * **Deliberately Department-scoped, not shown at Region/Country/World scale.** `DiscoveryMapView`
 * only ever calls [applyRouteOverlay] with a non-empty, already-clipped segment list while an
 * `ADMIN_2` is the actual current selection (see [clipRouteSegmentsToArea][com.cedervs.worlddiscovery
 * .core.discovery.clipRouteSegmentsToArea] and `DiscoveryMapView`'s own wiring) — an empty list at
 * Region/Country/World scale, exactly like every other "set to an empty FeatureCollection rather than
 * removing" overlay in this module (see [CountryOverlayRendering]'s own precedent), never a blue
 * "spaghetti" of every route recorded in France.
 *
 * **No zoom-threshold gate of its own, unlike [AdministrativeOverlayRendering]'s Country/Region/
 * Department fills.** Visibility is controlled entirely by whether the caller supplies non-empty
 * segment data (a UI-selection-driven decision, made once in `DiscoveryMapView`), not by a `minZoom`
 * property on the layer itself — deliberately avoiding the exact class of bug this app's own physical-
 * validation Round 5 fixed (a generic zoom floor slightly above the camera's own automatic Department-
 * fit zoom, hiding data that should already be visible). The route is visible starting the instant
 * the Region-fit... Department-fit camera animation completes, and remains visible through any
 * subsequent manual pan/zoom within that Department context, per this round's own explicit
 * requirement.
 *
 * **Halo and core always render identical chunk geometry, by construction.** Both
 * [routeHaloLayer]/[routeCoreLayer] source from the exact same [ROUTE_SOURCE_ID] `GeoJsonSource`,
 * populated by exactly one call to [routeCoreFeatureCollection] — there is no second, independent
 * conversion path either layer could silently diverge from. See [routeCoreFeatureCollection]'s own doc
 * comment for how a long segment is safely chunked (never vertex-dropped) for this shared source.
 *
 * **Read-only, never intercepts geographic hierarchy taps.** None of [ROUTE_HALO_FILL_LAYER_ID]/
 * [ROUTE_CORE_LAYER_ID]/[ROUTE_NODES_LAYER_ID] is ever passed to `queryRenderedFeatures` in
 * `DiscoveryMapView`'s click handler — Country/Region/Department click resolution is structurally
 * unaware these layers even exist, not merely conventionally so.
 *
 * **Certification is not yet reflected in route styling** — see `DiscoveredRoute.kt`'s own doc
 * comment on [RouteSegment]/`RoutePoint` for why: no sound Certified/Non-certified -> route-segment
 * color mapping exists yet, so every segment renders in the same single provisional blue, regardless
 * of the trust status of the discovered cells it passes through. Flagged as an explicit open product
 * question (`PROJECT_STATUS.md`), never a fabricated trust distinction.
 *
 * **Layering** (bottom to top, verified against this file's own insertion calls, not assumed):
 * Country/Region/Department fills (inserted below the basemap's own water layer, see
 * [BASEMAP_WATER_LAYER_ID]) -> route halo -> route core -> route nodes -> H3 discovered-cell fill
 * (inserted first via a plain `addLayer`, so everything inserted here via
 * `addLayerBelow(_, DISCOVERED_CELLS_FILL_LAYER_ID)` lands directly beneath it) -> current-position
 * marker (added last, on top of everything). Falls back to a plain `addLayer` (top of the whole
 * style) if the H3 fill layer does not exist yet at call time — the same defensive anchor-may-be-
 * absent pattern [insertCountryOverlayFillLayer] already established, reused here via the same
 * [CountryOverlayFillInsertionTarget] seam rather than a new one.
 */
internal const val ROUTE_SOURCE_ID = "route-overlay-source"
internal const val ROUTE_HALO_FILL_LAYER_ID = "route-overlay-halo-layer"
internal const val ROUTE_CORE_LAYER_ID = "route-overlay-core-layer"
internal const val ROUTE_NODES_SOURCE_ID = "route-overlay-nodes-source"
internal const val ROUTE_NODES_LAYER_ID = "route-overlay-nodes-layer"

// PRODUCT CALIBRATION REQUIRED -- provisional starting values matching this round's approved
// reference direction (thin electric/light blue core, subtle wider luminous halo underneath, small
// circular nodes). Orange remains the hierarchy/discovery-area language everywhere else in this
// module; blue is reserved exclusively for this derived first-discovery corridor visualization.
internal const val ROUTE_CORE_COLOR = "#2F9BFF"
internal const val ROUTE_HALO_COLOR = "#2F9BFF"
private const val ROUTE_CORE_OPACITY = 0.85f
private const val ROUTE_HALO_OPACITY = 0.22f
internal const val ROUTE_NODE_COLOR = "#2F9BFF"
private const val ROUTE_NODE_OPACITY = 0.9f
private const val ROUTE_NODE_STROKE_COLOR = "#FFFFFF"
private const val ROUTE_NODE_STROKE_WIDTH = 1f

// Zoom-dependent detail (this round's own "no abrupt disappearance around the Department fit"
// requirement) -- widths/radii only ever grow with zoom, via a smooth Expression.interpolate, never
// jump or vanish. The interpolation window is anchored around this app's own typical Department-fit
// zoom range (see AdministrativeOverlayRendering.kt's ADMIN1_OVERLAY_MIN_ZOOM/effectiveAdmin2MinZoom),
// not an arbitrary pair of numbers.
private const val ROUTE_DETAIL_ZOOM_NEAR = 8.0
private const val ROUTE_DETAIL_ZOOM_FAR = 15.0

/**
 * Creates (once) and updates the Department-level route overlay from [segments] — already
 * Department-clipped by the caller (see [com.cedervs.worlddiscovery.core.discovery
 * .clipRouteSegmentsToArea]); this function itself performs no filtering of its own, only rendering.
 * [segments] empty clears the overlay to an empty `FeatureCollection` (never removed — same
 * established "create once, update via `setGeoJson` thereafter" pattern as every other overlay in
 * this module), which is exactly how Region/Country/World scale hides the route entirely.
 */
internal fun applyRouteOverlay(style: Style, segments: List<RouteSegment>) {
    val lineFeatureCollection = routeCoreFeatureCollection(segments)
    val nodeFeatureCollection = routeNodeFeatureCollection(segments)

    val existingLineSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
    val existingNodeSource = style.getSourceAs<GeoJsonSource>(ROUTE_NODES_SOURCE_ID)
    if (existingLineSource != null && existingNodeSource != null) {
        existingLineSource.setGeoJson(lineFeatureCollection)
        existingNodeSource.setGeoJson(nodeFeatureCollection)
        return
    }

    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, lineFeatureCollection))
    style.addSource(GeoJsonSource(ROUTE_NODES_SOURCE_ID, nodeFeatureCollection))

    // Insertion order matters: each addLayerBelow(_, DISCOVERED_CELLS_FILL_LAYER_ID) call places its
    // layer directly beneath the H3 fill, displacing whatever was already there further down -- so
    // inserting halo, then core, then nodes, in that order, yields the desired final stack (top to
    // bottom): H3 fill -> nodes -> core -> halo -> (Country/Region/Department fills, already anchored
    // below the water layer, untouched by any of this).
    addRouteLayerBelowH3(style, routeHaloLayer())
    addRouteLayerBelowH3(style, routeCoreLayer())
    addRouteLayerBelowH3(style, routeNodesLayer())
}

/** Reuses [insertCountryOverlayFillLayer]'s own generic decision (already independent of Country
 * specifically) with a fresh [CountryOverlayFillInsertionTarget] adapter anchored on
 * [DISCOVERED_CELLS_FILL_LAYER_ID] instead of the water layer -- falls back to a plain `addLayer` (top
 * of the whole style) if that anchor is absent, the same safe, non-crashing, "never silently fail to
 * render" behavior [addAdministrativeOverlayFillLayer] already relies on. */
private fun addRouteLayerBelowH3(style: Style, layer: org.maplibre.android.style.layers.Layer) {
    val target = object : CountryOverlayFillInsertionTarget {
        override fun waterAnchorExists(): Boolean = style.getLayerAs<org.maplibre.android.style.layers.Layer>(DISCOVERED_CELLS_FILL_LAYER_ID) != null
        override fun insertBelowWaterAnchor() = style.addLayerBelow(layer, DISCOVERED_CELLS_FILL_LAYER_ID)
        override fun insertOnTop() = style.addLayer(layer)
    }
    insertCountryOverlayFillLayer(target)
}

private fun routeWidthExpression(nearWidth: Float, farWidth: Float): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(ROUTE_DETAIL_ZOOM_NEAR, nearWidth),
        Expression.stop(ROUTE_DETAIL_ZOOM_FAR, farWidth),
    )

private fun routeHaloLayer(): LineLayer =
    LineLayer(ROUTE_HALO_FILL_LAYER_ID, ROUTE_SOURCE_ID)
        .withProperties(
            PropertyFactory.lineColor(Color.parseColor(ROUTE_HALO_COLOR)),
            PropertyFactory.lineOpacity(ROUTE_HALO_OPACITY),
            PropertyFactory.lineWidth(routeWidthExpression(6f, 9f)),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
        )

private fun routeCoreLayer(): LineLayer =
    LineLayer(ROUTE_CORE_LAYER_ID, ROUTE_SOURCE_ID)
        .withProperties(
            PropertyFactory.lineColor(Color.parseColor(ROUTE_CORE_COLOR)),
            PropertyFactory.lineOpacity(ROUTE_CORE_OPACITY),
            PropertyFactory.lineWidth(routeWidthExpression(2f, 3f)),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
        )

private fun routeNodesLayer(): CircleLayer =
    CircleLayer(ROUTE_NODES_LAYER_ID, ROUTE_NODES_SOURCE_ID)
        .withProperties(
            PropertyFactory.circleColor(Color.parseColor(ROUTE_NODE_COLOR)),
            PropertyFactory.circleOpacity(ROUTE_NODE_OPACITY),
            PropertyFactory.circleRadius(routeWidthExpression(3f, 5f)),
            PropertyFactory.circleStrokeColor(Color.parseColor(ROUTE_NODE_STROKE_COLOR)),
            PropertyFactory.circleStrokeWidth(ROUTE_NODE_STROKE_WIDTH),
        )

/** One or more `LineString` `Feature`s per [RouteSegment] — different SEGMENTS are never joined into
 * one `MultiLineString`, so two genuinely non-continuous runs can never visually read as one connected
 * path (see `DiscoveredRoute.kt`'s own splitting rationale). Each segment is first passed through
 * [chunkRouteSegmentForRendering] — a rendering-only point-VOLUME bound that splits an over-long
 * segment into multiple boundary-overlapping chunks, **never drops or reorders a single point** (Codex
 * review correction round, blocking fix: the previous round's vertex-dropping "simplification" could
 * manufacture a straight chord between two points that were never actually adjacent in the validated
 * corridor — chunking cannot do that by construction, since every point either chunk *N* or chunk
 * *N+1* renders was already exactly where it is in [segment]'s own real sequence). Consecutive chunks
 * from the same segment share their boundary point exactly, so the rendered corridor stays visually
 * continuous across the chunk split despite being drawn as separate `Feature`s — never affecting the
 * domain [RouteSegment] itself, only how many `LineString` `Feature`s its real point sequence is split
 * across. */
internal fun routeCoreFeatureCollection(segments: List<RouteSegment>): FeatureCollection {
    val features = segments.flatMap { segment -> chunkRouteSegmentForRendering(segment) }
        .map { chunk ->
            val points = chunk.points.map { point -> Point.fromLngLat(point.coordinate.longitude, point.coordinate.latitude) }
            Feature.fromGeometry(LineString.fromLngLats(points))
        }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

/** Sampled node `Feature`s across every segment, hard-bounded overlay-wide — see
 * [sampleRouteNodesBounded]'s own doc comment for why this is a deterministic, capped subset, never
 * one marker per raw discovered cell and never an unbounded count for a Department with an unusually
 * large first-discovery history. */
internal fun routeNodeFeatureCollection(segments: List<RouteSegment>): FeatureCollection {
    val features = sampleRouteNodesBounded(segments)
        .map { point -> Feature.fromGeometry(Point.fromLngLat(point.coordinate.longitude, point.coordinate.latitude)) }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}
