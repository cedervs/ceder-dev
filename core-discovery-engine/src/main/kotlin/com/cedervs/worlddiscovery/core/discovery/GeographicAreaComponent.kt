package com.cedervs.worlddiscovery.core.discovery

/**
 * One independently navigable, spatially separate piece of a [GeographicArea] — e.g. France's real
 * geometry is three components: metropolitan France, Corsica, and French Guiana. [area] may still
 * know it is a single administrative entity (for statistics, hierarchy, country profiles, etc. —
 * future work), but **map navigation must never force all of an area's components into one camera
 * fit**: this is the product rule behind this type existing at all. See `docs/discovery-engine.md`
 * for the broader hierarchy this sits under.
 *
 * Deliberately **generic**, not France-specific: [componentIndex] is a plain positional index into
 * [GeographicArea.geometry]'s own `polygons` list — there is no hard-coded name or index anywhere
 * that assumes "France has exactly 3 parts" or "index 2 is French Guiana". The same mechanism
 * transparently supports any other geographically fragmented entity (continental USA/Alaska/Hawaii,
 * archipelagos, island territories, ...) the moment its reference geometry is loaded, with zero
 * additional code.
 *
 * One polygon in a `GeographicMultiPolygon` is treated as one component. This is deliberately the
 * *simplest* generically-correct rule available this round: a real-world open geographic dataset
 * (Natural Earth, and any plausible future replacement — see `docs/ai-context/OPEN_QUESTIONS.md`)
 * already encodes "this is a spatially separate landmass" as a separate `Polygon` entry within a
 * feature's `MultiPolygon`, so no additional clustering/adjacency computation is needed to recover
 * that structure here. This is **not** the same concept as the future H3-connected-components local
 * presence experiment (`docs/discovery-engine.md`'s multi-scale notes) — that is about grouping the
 * user's own *discoveries*, not decomposing a *reference boundary*, and is explicitly out of scope
 * for this round.
 */
data class GeographicAreaComponent(
    val area: GeographicArea,
    val componentIndex: Int,
    val polygon: GeographicPolygon,
    /** This component's own bounds — computed from *only* [polygon], never [area]'s full bounds.
     * This is precisely what makes "fit to the clicked component" possible instead of "fit to the
     * whole area": tapping metropolitan France must never pull French Guiana into the camera fit. */
    val bounds: GeographicBounds,
) {
    init {
        require(componentIndex >= 0) { "componentIndex must not be negative: $componentIndex" }
    }
}

/** Decomposes [GeographicArea.geometry] into its independently navigable [GeographicAreaComponent]s
 * — one per polygon, in the same order as [GeographicArea.geometry]'s `polygons` list, so
 * [GeographicAreaComponent.componentIndex] is stable and reproducible from the area's geometry alone
 * (the same index a renderer tags onto a feature can always be looked back up here — see
 * `feature-map`'s `CountryOverlayRendering.kt`/`CountryOverlayComponentNavigation.kt`). */
fun GeographicArea.components(): List<GeographicAreaComponent> =
    geometry.polygons.mapIndexed { index, polygon ->
        GeographicAreaComponent(
            area = this,
            componentIndex = index,
            polygon = polygon,
            bounds = computeGeographicBounds(GeographicMultiPolygon(listOf(polygon))),
        )
    }
