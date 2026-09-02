package com.cedervs.worlddiscovery.core.discovery

/**
 * Generic internal area type — matches the already-validated product hierarchy
 * (`docs/discovery-engine.md` §19: World → Continent → Country is fixed; below Country the
 * hierarchy is country-dependent). The UI shows a locally-appropriate display name
 * ([GeographicArea.displayName]), never this generic type name.
 */
enum class GeographicAreaType {
    COUNTRY,
    ADMIN_1,
    ADMIN_2,
    LOCALITY,
    ZONE,
}

/**
 * Where [GeographicArea]'s geometry came from — [EXTERNAL_REFERENCE_DATASET] for a third-party
 * open geographic dataset (e.g. Natural Earth — see `tools/geo/README.md`) or [WORLD_DISCOVERY_ZONE]
 * for a World-Discovery-authored supplement (`docs/discovery-engine.md` §19's "zones World
 * Discovery", used where official subdivisions don't support useful progression).
 *
 * Deliberately **not** named `OFFICIAL`: a dataset like Natural Earth is a genuine, licensed,
 * versioned open geographic source, but it is not an official government boundary authority (e.g.
 * INSEE/IGN for France) — calling it "official" would overclaim its authority. Neither value here
 * makes any claim about Certified-mode authority — that remains exclusively server-side validation
 * events, per `docs/discovery-engine.md`/CLAUDE.md's Certified-authority rule.
 */
enum class GeographicAreaProvenance {
    EXTERNAL_REFERENCE_DATASET,
    WORLD_DISCOVERY_ZONE,
}

/**
 * One node in the (eventually versioned, worldwide) geographic-area hierarchy — a country, an
 * administrative subdivision, or a World-Discovery-authored zone. This is a **derived,
 * reconstructible read-side concept**, never a second discovery truth: nothing here is
 * authoritative about what the user discovered — [ClassifyDiscoveredCellsByGeographicArea] derives
 * `VISITED` status by testing canonical [DiscoveredCell]s against [geometry], never the reverse.
 *
 * Deliberately does not encode a fixed `country -> admin1 -> admin2` schema: [parentId] is a
 * single optional link, not a required chain, because the real-world hierarchy has variable depth
 * per country/territory (`docs/discovery-engine.md` §19) — a future `ADMIN_1` area sets
 * `parentId` to its `COUNTRY`'s [id]; a country itself has `parentId = null`. This class doesn't
 * assume or enforce how many levels exist below any given area.
 */
data class GeographicArea(
    /** A stable World Discovery ID, independent of any one source's own identifiers — e.g.
     * `"country:FR"`. Must survive a source/version change (see [sourceVersion]) so history tied
     * to this ID is never silently invalidated by a data refresh. */
    val id: String,
    val type: GeographicAreaType,
    /** The name actually shown to the user — already localized/display-ready for this prototype
     * (a single string); a real localization table is future work, not decided here. */
    val displayName: String,
    val geometry: GeographicMultiPolygon,
    /** Precomputed once from [geometry] (see [computeGeographicBounds]) so callers never need to
     * walk the full polygon set just to fit a camera to this area. */
    val bounds: GeographicBounds,
    /** Which dataset this area's geometry came from — e.g. `"natural-earth"`. */
    val sourceId: String,
    /** The exact version/commit of [sourceId] this geometry was extracted from — see
     * `tools/geo/README.md` for the real, reproducible extraction this app's bundled reference
     * artifact was generated from. Any `(h3Index, area)` classification is only ever valid for a
     * specific [sourceVersion]; a future source refresh is a new version, not a silent overwrite —
     * matching `docs/architecture.md`'s "toute référence géographique importée doit être
     * versionnée" principle. */
    val sourceVersion: String,
    val sourceProvenance: GeographicAreaProvenance,
    /** The parent area's [id], or `null` for a top-level area (a country). See the class doc
     * comment for why this is a single optional link, not a fixed-depth chain. */
    val parentId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "GeographicArea.id must not be blank" }
        require(displayName.isNotBlank()) { "GeographicArea.displayName must not be blank" }
        require(sourceId.isNotBlank()) { "GeographicArea.sourceId must not be blank" }
        require(sourceVersion.isNotBlank()) { "GeographicArea.sourceVersion must not be blank" }
    }
}
