package com.cedervs.worlddiscovery.feature.map

import android.graphics.Color
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

/**
 * **PHYSICAL PROTOTYPE — narrow, reversible, not a settled architecture decision.** See the
 * design-review record this was introduced in (not yet transcribed into `/docs`): the geoBoundaries
 * overlay's own border, however precise, is a *different* survey/digitization than the basemap's
 * own OpenStreetMap-derived border, so the two visibly diverge on physical devices (confirmed:
 * France/Switzerland/Italy, Brittany, Marseille). This file adds an *additional* line, drawn from
 * the basemap's own already-loaded vector data, so it can never visually diverge from what the
 * basemap itself shows — but it draws **only mainland France's `admin_level == 2` boundary line**,
 * nothing else, and only while that specific component is visited.
 *
 * **Deliberately references the existing `openmaptiles` vector source by its known style-JSON
 * source ID, rather than adding a new source.** No new geometry is downloaded, bundled, or owned by
 * this app — this is a real coupling to whatever `DEV_ONLY_DEMO_STYLE_URL` currently loads (see
 * `DiscoveryMapView.kt`'s own doc comment for why that style is itself provisional/not decided). If
 * that style is ever swapped for one that doesn't declare an `openmaptiles`-schema vector source
 * with a `boundary` source-layer, this specific layer simply stops rendering anything (MapLibre does
 * not throw for a layer referencing a currently-absent source) — an accepted, documented risk for a
 * physical-test prototype, not something engineered around here.
 *
 * **Does not attempt coastline.** The basemap has no separate coastline layer — see
 * `CountryOverlayRendering.kt`'s sibling doc comments and the design-review record's own
 * "Coastline uncertainty" section: whether OSM's own France boundary relation traces coastal
 * segments as `boundary` features at all is genuinely unverified in this codebase. This filter only
 * ever asks for `admin_level == 2` lines identified via `adm0_l`/`adm0_r` — if the underlying tile
 * data happens to include coastal segments there, they render; if not, nothing is invented to fill
 * the gap. The physical test itself is what answers this question, not this code.
 */
internal const val BASEMAP_VECTOR_SOURCE_ID = "openmaptiles"
internal const val BASEMAP_BOUNDARY_SOURCE_LAYER = "boundary"
internal const val BASEMAP_ALIGNED_FRANCE_BORDER_LAYER_ID = "basemap-aligned-france-border-prototype"

/** ISO 3166-1 **alpha-3** code used by OpenMapTiles' `boundary` layer's `adm0_l`/`adm0_r` fields —
 * confirmed by directly decoding a real, deployed OpenFreeMap vector tile (z8/x132/y90, covering the
 * France/Switzerland border near Geneva): every real `admin_level == 2` boundary feature carries
 * `adm0_l`/`adm0_r` values of `"FRA"`/`"CHE"`, never the alpha-2 `"FR"`/`"CH"` this constant
 * previously (and incorrectly) assumed. That mismatch was the confirmed root cause of the prototype
 * line never rendering on a physical device: the filter's exact-string comparison could never match
 * any real feature. */
private const val MAINLAND_FRANCE_ADM0_CODE = "FRA"

/** Mirrors [mainlandFranceOutlineSuppressionFilter] in `CountryOverlayRendering.kt` — see that
 * function's own doc comment for why this is a prototype-scoped, single-loaded-area simplification,
 * not a permanent worldwide identity. Component 0 is mainland France by the Phase A1 generator's own
 * deliberate, documented order contract. */
internal const val MAINLAND_FRANCE_COMPONENT_INDEX_PROTOTYPE = 0

// Same provisional orange as the existing fill/outline (COUNTRY_OVERLAY_VISITED_FILL_COLOR) --
// deliberately not a new color, so the basemap-aligned line still reads as "the same World
// Discovery visited-border language", not a second, different-looking feature.
private const val BASEMAP_ALIGNED_BORDER_WIDTH = 2.0f

/**
 * Whether [visitedComponents] includes mainland France (component 0) — the sole gate for this
 * prototype's line. A pure predicate, independent of any MapLibre native runtime, so this specific
 * decision is directly unit-testable even though the actual layer mutation below is not (matches
 * this module's established "extract the pure decision, leave the Style/Layer call untestable"
 * pattern — see `CountryOverlayRendering.kt`'s `isCountryOverlayInteractive` for the precedent).
 */
internal fun isMainlandFranceVisited(visitedComponents: List<GeographicAreaComponent>): Boolean =
    visitedComponents.any { component -> component.componentIndex == MAINLAND_FRANCE_COMPONENT_INDEX_PROTOTYPE }

/** Maps the visited gate to a MapLibre visibility property value — a pure, trivial, directly
 * testable mapping, kept separate from the actual `Layer.setProperties` call. */
internal fun basemapAlignedBorderVisibility(mainlandFranceVisited: Boolean): String =
    if (mainlandFranceVisited) Property.VISIBLE else Property.NONE

/**
 * The static filter selecting mainland France's own `admin_level == 2` boundary line segments from
 * the basemap's `boundary` source-layer — never changes based on visited status (visibility handles
 * that separately, see [basemapAlignedBorderVisibility]), so it's set once at layer-creation time.
 * Mirrors the exclusions the Liberty style's own `boundary_2` layer already applies (`maritime`,
 * `disputed`, `claimed_by`) — verified directly against the real style JSON during the investigation
 * this prototype follows up on — so this prototype's line only ever draws the same *kind* of
 * segment the basemap's own country-border line already draws, not a superset that could include
 * disputed/maritime segments the basemap itself doesn't render as a solid line.
 */
internal fun mainlandFranceAdmin2BorderFilter(): Expression =
    Expression.all(
        Expression.eq(Expression.get("admin_level"), 2),
        Expression.any(
            Expression.eq(Expression.get("adm0_l"), MAINLAND_FRANCE_ADM0_CODE),
            Expression.eq(Expression.get("adm0_r"), MAINLAND_FRANCE_ADM0_CODE),
        ),
        Expression.neq(Expression.get("maritime"), 1),
        Expression.neq(Expression.get("disputed"), 1),
        Expression.not(Expression.has("claimed_by")),
    )

/**
 * Creates (once) and updates the visibility of the basemap-aligned mainland-France border line.
 * Never touches the `openmaptiles` source itself (owned entirely by the currently-loaded basemap
 * style, not by this app) — only adds one new `LineLayer` referencing it by source id/source-layer.
 *
 * Called from the same effect as [applyCountryOverlay] (see `DiscoveryMapView.kt`), so it runs
 * exactly when the visited-components snapshot changes — never on a camera-only zoom/pan, matching
 * every other rendering function in this module.
 */
internal fun applyBasemapAlignedFranceBorder(style: Style, visitedComponents: List<GeographicAreaComponent>) {
    val visibility = basemapAlignedBorderVisibility(isMainlandFranceVisited(visitedComponents))

    val existingLayer = style.getLayerAs<Layer>(BASEMAP_ALIGNED_FRANCE_BORDER_LAYER_ID)
    if (existingLayer != null) {
        existingLayer.setProperties(PropertyFactory.visibility(visibility))
        return
    }

    val layer = LineLayer(BASEMAP_ALIGNED_FRANCE_BORDER_LAYER_ID, BASEMAP_VECTOR_SOURCE_ID)
        .withSourceLayer(BASEMAP_BOUNDARY_SOURCE_LAYER)
        .withFilter(mainlandFranceAdmin2BorderFilter())
        .withProperties(
            PropertyFactory.lineColor(Color.parseColor(COUNTRY_OVERLAY_VISITED_FILL_COLOR)),
            PropertyFactory.lineWidth(BASEMAP_ALIGNED_BORDER_WIDTH),
            PropertyFactory.visibility(visibility),
        )
    // Added on top, exactly like the existing country-overlay fill/outline (see
    // CountryOverlayRendering.kt's applyCountryOverlay) -- consistent z-order behavior, not a new
    // concern this prototype introduces: the existing outline already sits above basemap labels
    // today, and a thin line has the same limited practical impact on label legibility.
    style.addLayer(layer)
}
