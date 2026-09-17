package com.cedervs.worlddiscovery.core.discovery

/**
 * One consistent read-side snapshot for the Map feature: [geometries] (fine, canonical
 * resolution-12 cells with their boundaries — close-zoom rendering), [franceVisitedStatus] (this
 * round's Country-level VISITED overlay — see [ClassifyDiscoveredCellsByGeographicArea]), and
 * [franceComponents] (per-component presence — see [ClassifyDiscoveredCellsByGeographicAreaComponents]).
 * `franceComponents` **is** consumed by rendering/navigation: `MapScreen` filters it down to the
 * currently-visited components and passes that straight through to `DiscoveryMapView`'s Country
 * overlay/click-navigation — it is not merely retained for future statistics/hierarchy use, and this
 * comment previously claimed otherwise incorrectly. All fields here are derived from the exact
 * **same**, already-H3-validated [DiscoveredCell] list emitted by [DiscoveredCellRepository.observeAll]
 * — see [ObserveMapReadState] for both the single-subscription and single-validation rationale. A
 * single Room subscription feeding multiple derived read-side views, not one subscription per view
 * (the same principle already applied once in this codebase's history and reapplied here for a
 * genuinely new set of consumers, not a reintroduction of the previously-rejected aggregate-point
 * visualization).
 */
data class MapReadState(
    val geometries: List<DiscoveredCellGeometry>,
    val franceVisitedStatus: GeographicAreaVisitedStatus,
    val franceComponents: List<GeographicAreaComponentVisitedStatus>,
    /** Country -> Region -> Department hierarchy, one level below [franceVisitedStatus] — see
     * [FranceAdministrativeAreas]/[ClassifyDiscoveredCellsByGeographicAreas]. Each level is first
     * classified independently against the exact same [cells] snapshot as everything else in this
     * class (a genuine point-in-polygon test per level, never assumed from a parent/child
     * relationship) — but Country and Region classification use a *different* underlying geometry
     * dataset than Region/Department do (`geoBoundaries` vs OpenStreetMap; see `tools/geo/README.md`),
     * so the two can genuinely disagree by a few meters near a shared border. Without correction, a
     * discovery classified into a child by OSM geometry but just outside the parent's own
     * `geoBoundaries` polygon would leave the parent falsely `not visited` — violating the required
     * "visited child implies visited ancestor" invariant. [ObserveMapReadState] fixes this by
     * **promoting presence upward, never downward**, via [promoteAncestorPresence]: [franceVisitedStatus]
     * and [franceAdmin1Statuses] are each the union of their own raw classification with whatever
     * their real children resolved to, so "Haute-Vienne visited" always implies "Nouvelle-Aquitaine
     * visited" and "France visited" — but "France visited" never implies any region is visited, and
     * "Nouvelle-Aquitaine visited" never implies any department is visited. Nothing here is persisted
     * as a second discovery truth; this is a pure, read-time correction applied fresh on every
     * emission. Covers all 13 loaded metropolitan regions; see [FranceAdministrativeAreas]'s own doc
     * comment for why only Nouvelle-Aquitaine's departments are loaded this round. */
    val franceAdmin1Statuses: List<GeographicAreaVisitedStatus>,
    /** Department-level statuses — currently only ever Nouvelle-Aquitaine's 12 departments, since
     * that's the only region with `ADMIN_2` data loaded this round (see [FranceAdministrativeAreas]).
     * Empty for every other region until its own departments are populated — never an error, exactly
     * like [ClassifyDiscoveredCellsByGeographicAreas] skipping a cell that matches no loaded area.
     * Never itself promoted from anything deeper — Department is the deepest level implemented this
     * round, so this is always raw classification. */
    val franceAdmin2Statuses: List<GeographicAreaVisitedStatus>,
    /** Derived first-discovery corridor segments (never a GPS route, never canonical truth — see
     * [DiscoveredRoute.kt][deriveRouteSegments]'s own file-level doc comment for the full "what this
     * explicitly is NOT" list), recomputed fresh from the exact same validated cell snapshot as every
     * other field here. Globally derived, **not** yet clipped to any particular Department —
     * `feature-map` applies [clipRouteSegmentsToArea] against whichever Department is currently
     * selected, since MapReadState itself carries no notion of "current UI selection". Empty whenever
     * fewer than 2 spatially distinct discovered cells exist, or no consecutive pair passes
     * [deriveRouteSegments]'s own conservative structural/temporal continuity test. */
    val routeSegments: List<RouteSegment>,
)
