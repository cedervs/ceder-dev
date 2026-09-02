package com.cedervs.worlddiscovery.feature.map

import android.graphics.Color
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent
import com.cedervs.worlddiscovery.core.discovery.GeographicPolygon
import com.google.gson.JsonObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal const val COUNTRY_OVERLAY_SOURCE_ID = "country-overlay-source"
internal const val COUNTRY_OVERLAY_FILL_LAYER_ID = "country-overlay-fill-layer"
internal const val COUNTRY_OVERLAY_OUTLINE_LAYER_ID = "country-overlay-outline-layer"
internal const val COUNTRY_OVERLAY_AREA_ID_PROPERTY = "areaId"

/**
 * **PHYSICAL PROTOTYPE, Option G — narrow and reversible.** The Liberty style's own water-fill
 * layer id (`type: fill`, `source-layer: water`, confirmed by direct style-JSON and real-tile
 * inspection during the design-review record this was introduced in — its own single `class=ocean`
 * polygon's exterior ring *is* the coastline the basemap visibly renders). Used purely as an
 * insertion anchor: [addCountryOverlayFillLayer] inserts the visited-country fill directly below
 * this layer so the basemap's own already-loaded water geometry (sea and lakes alike, both live in
 * this one source-layer) visually masks whatever part of the fill falls outside the coastline the
 * basemap itself is currently showing — no new source, no polygon clipping, no cached/extracted OSM
 * geometry. A real, explicit, localized coupling to whatever style `DEV_ONLY_DEMO_STYLE_URL`
 * currently loads — same accepted risk category as [BASEMAP_VECTOR_SOURCE_ID] in
 * `BasemapAlignedBorderRendering.kt`. If a future style doesn't declare a layer with this id,
 * [addCountryOverlayFillLayer] falls back to the pre-prototype behavior (fill added on top of
 * everything) rather than silently dropping the fill or crashing.
 */
internal const val BASEMAP_WATER_LAYER_ID = "water"

/** Which of the area's `components()` positional components a rendered feature is — see
 * `CountryOverlayComponentNavigation.kt`'s `resolveClickedCountryComponent` for how a click reads
 * this back to determine exactly which spatially separate piece (e.g. metropolitan France, Corsica,
 * or French Guiana) was tapped, never the area as a whole. */
internal const val COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY = "componentIndex"

/**
 * **PRODUCT CALIBRATION REQUIRED** — provisional zoom range where the Country overlay is shown,
 * chosen only to give a working phone prototype; not derived from any measured on-device
 * legibility threshold yet. As the user approaches fine/local scale the overlay fades out so it
 * never covers the real H3 discoveries it sits above — see [countryOverlayFillLayer]'s opacity
 * expression. No Region/Department transition exists yet — this is the only band this round
 * implements.
 */
internal const val COUNTRY_OVERLAY_MIN_ZOOM = 0f
internal const val COUNTRY_OVERLAY_MAX_ZOOM = 7f
internal const val COUNTRY_OVERLAY_FADE_OUT_START_ZOOM = 5.0
internal const val COUNTRY_OVERLAY_FADE_OUT_END_ZOOM = 7.0

// Provisional experimental color — not final art direction. Deliberately subtle (low opacity, no
// heavy fill) per this round's explicit instruction: this is a VISITED state, not an exploration-
// coverage claim, and must not look like "100% explored".
internal const val COUNTRY_OVERLAY_VISITED_FILL_COLOR = "#FF8C00"
private const val COUNTRY_OVERLAY_FILL_OPACITY = 0.18f
private const val COUNTRY_OVERLAY_OUTLINE_OPACITY = 0.55f
private const val COUNTRY_OVERLAY_OUTLINE_WIDTH = 1.5f

/**
 * Renders the Country-level VISITED overlay — currently only ever France's components, this
 * round's explicit scope. Deliberately **not** the rejected H3-parent-center visualization: this is
 * real geographically-correct polygons (from each [GeographicAreaComponent.polygon], itself derived
 * from a genuine open geographic dataset — see `tools/geo/README.md`), not an H3 parent cell and not
 * a point marker. Entirely additive: never touches [DISCOVERED_CELLS_SOURCE_ID] or its layers (the
 * unchanged fine-H3 rendering) and never adds a `CircleLayer`/aggregate points.
 *
 * **Highlighting follows actual component-level presence, never the whole area.** [visitedComponents]
 * is the caller-filtered list of components that are *themselves* actually visited (see
 * `ObserveMapReadState`'s `franceComponents`/`ClassifyDiscoveredCellsByGeographicAreaComponents`) —
 * one discovery in metropolitan France colors *only* metropolitan France, never Corsica or French
 * Guiana just because they share the same parent `GeographicArea`. The parent area's own
 * `VISITED` status (`ClassifyDiscoveredCellsByGeographicArea`) may still be derived as "at least one
 * component visited" for future hierarchy/statistics use — that derivation never feeds back into
 * which components get colored here. Each visited component is rendered as its **own** `Feature`,
 * tagged with both [COUNTRY_OVERLAY_AREA_ID_PROPERTY] and [COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY]
 * — this is also what makes per-component click navigation possible at all: `queryRenderedFeatures`
 * returns whichever single Feature was actually tapped, and its tagged properties identify exactly
 * which spatially separate piece that is — see `CountryOverlayComponentNavigation.kt`.
 *
 * [visitedComponents] is empty when nothing is visited — the source is then set to an *empty*
 * `FeatureCollection` (see [countryOverlayFeatureCollection]) rather than removed, so the
 * source/layers only need to be created once (mirroring [applyDiscoveredCellGeometries]'s existing
 * "create once, update via setGeoJson thereafter" pattern) and "not visited" never falsely
 * highlights anything, per this round's explicit instruction.
 *
 * Zoom-dependent visibility is handled entirely by MapLibre's own style properties (min/max zoom,
 * an opacity zoom `Expression`) — this function itself is only ever called when the underlying
 * visited-status data changes (see `DiscoveryMapView`'s effect), never on a camera-only zoom/pan.
 */
internal fun applyCountryOverlay(style: Style, visitedComponents: List<GeographicAreaComponent>) {
    // PHYSICAL PROTOTYPE, Option F/G3 -- mainland's rendered shape now comes from the OSM-derived
    // rendering polygon (validated against real basemap tiles, see mainlandFranceRenderingPolygon's
    // own doc comment), not geoBoundaries. Corsica/French Guiana and visited-status itself are
    // entirely unaffected -- see countryOverlayFeatureCollection's doc comment.
    val featureCollection = countryOverlayFeatureCollection(visitedComponents, mainlandFranceRenderingPolygon)

    val existingSource = style.getSourceAs<GeoJsonSource>(COUNTRY_OVERLAY_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setGeoJson(featureCollection)
        return
    }

    style.addSource(GeoJsonSource(COUNTRY_OVERLAY_SOURCE_ID, featureCollection))
    // Fill inserted below the basemap's own water layer when present (Option G masking — see
    // BASEMAP_WATER_LAYER_ID's doc comment); the outline stays added on top of everything, exactly
    // as before this prototype — it's a thin highlight line (currently only ever visible for
    // Corsica/Guyane, mainland's own outline is suppressed, see
    // mainlandFranceOutlineSuppressionFilter), not a wide fill that can visibly overflow into water,
    // so it has no coastline-masking need this round scopes to.
    addCountryOverlayFillLayer(style)
    style.addLayer(countryOverlayOutlineLayer())
}

/**
 * The minimum surface [insertCountryOverlayFillLayer]'s branch needs — kept deliberately narrow (not
 * a general rendering/Style abstraction; nothing else in this file goes through it) so that one
 * decision is unit-testable without a live MapLibre `Style`/native runtime. Never carries an actual
 * `Layer` instance across the seam: constructing a real `FillLayer` (like `Style` and `Layer`
 * subclasses generally — see `BasemapAlignedBorderRendering.kt`'s own doc comments on this) triggers
 * native library loading, so [StyleFillInsertionTarget] closes over the already-built `FillLayer`
 * instead of passing it through an interface method — a test fake never needs to construct one.
 */
internal interface CountryOverlayFillInsertionTarget {
    fun waterAnchorExists(): Boolean
    fun insertBelowWaterAnchor()
    fun insertOnTop()
}

/**
 * The actual insertion decision (Option G masking, see [BASEMAP_WATER_LAYER_ID]'s doc comment) —
 * inserts below the basemap's water layer when present, otherwise falls back to the pre-prototype
 * "add on top of everything" behavior. Exactly one of [CountryOverlayFillInsertionTarget]'s two
 * insertion methods is ever called, never both, never neither — see this function's own tests
 * (`CountryOverlayFillInsertionTest`) for the exact branch-coverage proof.
 */
internal fun insertCountryOverlayFillLayer(target: CountryOverlayFillInsertionTarget) {
    if (target.waterAnchorExists()) {
        target.insertBelowWaterAnchor()
    } else {
        target.insertOnTop()
    }
}

/** The real, thin [CountryOverlayFillInsertionTarget] adapter over an actual `Style`/`FillLayer` —
 * the only place this prototype touches the live MapLibre API for this decision. */
private class StyleFillInsertionTarget(
    private val style: Style,
    private val fillLayer: FillLayer,
) : CountryOverlayFillInsertionTarget {
    override fun waterAnchorExists(): Boolean = style.getLayerAs<Layer>(BASEMAP_WATER_LAYER_ID) != null
    override fun insertBelowWaterAnchor() = style.addLayerBelow(fillLayer, BASEMAP_WATER_LAYER_ID)
    override fun insertOnTop() = style.addLayer(fillLayer)
}

/**
 * Inserts the visited-country fill directly below [BASEMAP_WATER_LAYER_ID] when the currently-loaded
 * style declares a layer with that id, so the fill's apparent coastline matches whatever the basemap
 * itself is showing (Option G). Falls back to the pre-prototype "add on top of everything" behavior
 * when that anchor is absent — the safe, non-crashing, baseline-preserving path, never a fill that
 * silently stops rendering. Delegates the actual decision to [insertCountryOverlayFillLayer] — the
 * only difference from calling that directly is supplying the real [StyleFillInsertionTarget].
 */
private fun addCountryOverlayFillLayer(style: Style) {
    insertCountryOverlayFillLayer(StyleFillInsertionTarget(style, countryOverlayFillLayer()))
}

private fun countryOverlayFillLayer(): FillLayer =
    FillLayer(COUNTRY_OVERLAY_FILL_LAYER_ID, COUNTRY_OVERLAY_SOURCE_ID)
        .withProperties(
            PropertyFactory.fillColor(Color.parseColor(COUNTRY_OVERLAY_VISITED_FILL_COLOR)),
            PropertyFactory.fillOpacity(fadeOutOpacityExpression(COUNTRY_OVERLAY_FILL_OPACITY)),
        )
        .apply {
            minZoom = COUNTRY_OVERLAY_MIN_ZOOM
            maxZoom = COUNTRY_OVERLAY_MAX_ZOOM
        }

private fun countryOverlayOutlineLayer(): LineLayer =
    LineLayer(COUNTRY_OVERLAY_OUTLINE_LAYER_ID, COUNTRY_OVERLAY_SOURCE_ID)
        .withProperties(
            PropertyFactory.lineColor(Color.parseColor(COUNTRY_OVERLAY_VISITED_FILL_COLOR)),
            PropertyFactory.lineWidth(COUNTRY_OVERLAY_OUTLINE_WIDTH),
            PropertyFactory.lineOpacity(fadeOutOpacityExpression(COUNTRY_OVERLAY_OUTLINE_OPACITY)),
        )
        .withFilter(mainlandFranceOutlineSuppressionFilter())
        .apply {
            minZoom = COUNTRY_OVERLAY_MIN_ZOOM
            maxZoom = COUNTRY_OVERLAY_MAX_ZOOM
        }

/**
 * **PROTOTYPE-SCOPED, small and reversible** — see `BasemapAlignedBorderRendering.kt`. Excludes
 * whichever rendered feature has `componentIndex == 0` (mainland France, by the Phase A1 generator's
 * own deliberate component-order contract) from this geoBoundaries-derived outline, so the physical
 * boundary-alignment experiment shows exactly one orange line for mainland France — the new
 * basemap-aligned one — never two competing lines. Corsica and French Guiana are untouched and keep
 * their existing geoBoundaries outline exactly as before.
 *
 * Deliberately filters on `componentIndex` alone, not also `areaId`: only one `GeographicArea`
 * (France) is ever loaded in this prototype, so there is no other area whose own "component 0"
 * could be accidentally caught by this filter. This is a real, acknowledged simplification that
 * would need to reference the actual area id once more than one country can be loaded — not a
 * permanent design decision, matching this whole file's existing "one loaded country" scope.
 * Removing this single `.withFilter(...)` call fully restores the pre-prototype behavior.
 */
internal fun mainlandFranceOutlineSuppressionFilter(): Expression =
    Expression.neq(Expression.get(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY), MAINLAND_FRANCE_COMPONENT_INDEX_PROTOTYPE)

/** A zoom `Expression` ramping from [baseOpacity] down to 0 across the fade-out window, so the
 * overlay disappears smoothly rather than snapping off at [COUNTRY_OVERLAY_MAX_ZOOM]. */
private fun fadeOutOpacityExpression(baseOpacity: Float) =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(COUNTRY_OVERLAY_FADE_OUT_START_ZOOM, baseOpacity),
        Expression.stop(COUNTRY_OVERLAY_FADE_OUT_END_ZOOM, 0f),
    )

/**
 * **PHYSICAL PROTOTYPE, Option F/G3.** [mainlandRenderingPolygon], when supplied, replaces mainland
 * France's (`componentIndex == 0`) rendered *shape* only — see
 * [MainlandFranceRenderingPolygon.kt][mainlandFranceRenderingPolygon]'s own doc comment for why this
 * is deliberately a plain [GeographicPolygon], never a second classification fact. Corsica and
 * French Guiana are never affected, regardless of what's passed here — the substitution below only
 * ever applies to [MAINLAND_FRANCE_COMPONENT_INDEX_PROTOTYPE]. **Visited status itself is untouched**:
 * [visitedComponents] is still exactly the classification-derived list it always was — this
 * parameter can only ever change what shape an *already-visited* mainland renders as, never whether
 * it's considered visited at all. Defaults to `null` (falls back to the component's own
 * classification [GeographicAreaComponent.polygon], today's pre-prototype behavior) so every
 * existing caller/test that doesn't pass this argument is completely unaffected.
 */
internal fun countryOverlayFeatureCollection(
    visitedComponents: List<GeographicAreaComponent>,
    mainlandRenderingPolygon: GeographicPolygon? = null,
): FeatureCollection {
    val features = visitedComponents.map { component ->
        val renderOverride = if (component.componentIndex == MAINLAND_FRANCE_COMPONENT_INDEX_PROTOTYPE) mainlandRenderingPolygon else null
        component.toCountryOverlayFeature(renderOverride)
    }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

/** Renders [renderPolygonOverride] in place of this component's own classification [polygon] when
 * supplied — the `areaId`/`componentIndex` tagged properties always come from the real classification
 * component regardless, so click resolution ([resolveClickedCountryComponent]) is entirely
 * unaffected by which shape actually got drawn. */
internal fun GeographicAreaComponent.toCountryOverlayFeature(renderPolygonOverride: GeographicPolygon? = null): Feature {
    val renderPolygon = renderPolygonOverride ?: polygon
    val geoJsonPolygon = renderPolygon.toMapLibrePolygon()
    val properties = JsonObject().apply {
        addProperty(COUNTRY_OVERLAY_AREA_ID_PROPERTY, area.id)
        addProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY, componentIndex)
    }
    return Feature.fromGeometry(geoJsonPolygon, properties)
}

/** Whether the Country overlay is still meaningfully visible/interactive at [zoomLevel] — `false`
 * once fully faded out (see [COUNTRY_OVERLAY_FADE_OUT_END_ZOOM]), even though the underlying
 * `FillLayer` technically stays within [COUNTRY_OVERLAY_MIN_ZOOM]/[COUNTRY_OVERLAY_MAX_ZOOM] (and
 * so may still be hit-testable by `queryRenderedFeatures`) right up to its own `maxZoom`. Used to
 * make sure an invisible, fully-faded overlay can never trigger country navigation — see
 * `CountryOverlayComponentNavigation.kt`'s `resolveClickedCountryComponent`. */
internal fun isCountryOverlayInteractive(zoomLevel: Double): Boolean = zoomLevel < COUNTRY_OVERLAY_FADE_OUT_END_ZOOM

private fun GeographicPolygon.toMapLibrePolygon(): Polygon {
    val closedRings = rings.map { ring ->
        val rawPoints = ring.map { coordinate -> Point.fromLngLat(coordinate.longitude, coordinate.latitude) }
        // Reuses the same antimeridian-unwrap technique already established for fine H3 cell
        // boundaries (see unwrapAntimeridianRing's own doc comment) — an independent call, not a
        // modification of that existing fine-H3 rendering code.
        val unwrappedPoints = unwrapAntimeridianRing(rawPoints)
        if (unwrappedPoints.isNotEmpty() && unwrappedPoints.first() != unwrappedPoints.last()) {
            unwrappedPoints + unwrappedPoints.first()
        } else {
            unwrappedPoints
        }
    }
    return Polygon.fromLngLats(closedRings)
}
