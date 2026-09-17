package com.cedervs.worlddiscovery.core.discovery

/**
 * France's `ADMIN_1` (region) and `ADMIN_2` (department) reference geometry — the first data this
 * app loads below Country level, extending the already-validated `country:FR` prototype
 * ([loadFranceGeographicAreaReference]) one level deeper without changing anything about how it's
 * classified or rendered: every area here is a perfectly ordinary [GeographicArea] with
 * `type = ADMIN_1`/`ADMIN_2` and [GeographicArea.parentId] pointing at its real parent (a region's
 * `parentId` is `"country:FR"`; a department's is its own region's id, e.g. `"admin1:FR-NAQ"`) —
 * see `GeographicArea.kt`'s own doc comment for why `parentId` is a single optional link, not a
 * hard-coded chain, precisely so this extension needs no new domain type.
 *
 * **Scope this round: all 13 metropolitan regions, but only Nouvelle-Aquitaine's 12 departments.**
 * This is a deliberate, documented **data-population** scoping decision, not an architecture limit:
 * [ClassifyDiscoveredCellsByGeographicAreas]/`AdministrativeOverlayRendering.kt`/
 * `AdministrativeAreaNavigation.kt` all operate generically over whatever `List<GeographicArea>` is
 * loaded here and know nothing about which regions/departments exist. Populating the remaining 12
 * regions' departments is a mechanical follow-up — regenerate more artifacts with
 * `tools/geo/GenerateFranceAdministrativeReference.kt` and add their resource paths below — not a
 * code change. See `PROJECT_STATUS.md` for why this round's real-device validation only needs one
 * region proven end-to-end (Nouvelle-Aquitaine / Haute-Vienne, matching this round's required tests).
 *
 * **French overseas regions/departments are NOT included** (Guadeloupe, Martinique, Guyane, La
 * Réunion, Mayotte) — explicitly out of scope this round (`CLAUDE.md`'s France-first, no-Europe/
 * worldwide-yet instruction extends to not inventing overseas administrative coverage either).
 * French Guiana keeps its existing Country-level component-only representation
 * ([loadFranceGeographicAreaReference]'s own `country:FR` MultiPolygon component 2) — it has no
 * `ADMIN_1`/`ADMIN_2` entry here, so it is never offered for Region/Department drill-down.
 *
 * **Corse is included as a region** (`admin1:FR-20R` — Corsica's real ISO 3166-2 code; it is a
 * *collectivité territoriale unique*, not a standard region, but functions as one at this level of
 * the hierarchy) with no departments loaded under it this round (Corse's own two departments,
 * Corse-du-Sud/Haute-Corse, are PLANNED-NOT-IMPLEMENTED, same as every other non-Nouvelle-Aquitaine
 * region's departments).
 */
data class FranceAdministrativeAreas(
    val regions: List<GeographicArea>,
    val departments: List<GeographicArea>,
)

/** All 13 metropolitan France region artifacts generated this round — see this file's own doc
 * comment and `tools/geo/README.md` for the exact OSM relation each was generated from. */
private val FRANCE_ADMIN_1_RESOURCE_PATHS = listOf(
    "/geo/france/regions/idf.json",
    "/geo/france/regions/cvl.json",
    "/geo/france/regions/bfc.json",
    "/geo/france/regions/norm.json",
    "/geo/france/regions/hdf.json",
    "/geo/france/regions/ge.json",
    "/geo/france/regions/pdl.json",
    "/geo/france/regions/bzh.json",
    "/geo/france/regions/na.json",
    "/geo/france/regions/occ.json",
    "/geo/france/regions/ara.json",
    "/geo/france/regions/paca.json",
    "/geo/france/regions/corse.json",
)

/** Nouvelle-Aquitaine's 12 department artifacts — the only region with department-level data
 * populated this round. See this file's own doc comment for why, and how to extend it. */
private val FRANCE_ADMIN_2_RESOURCE_PATHS = listOf(
    "/geo/france/departments/charente.json",
    "/geo/france/departments/charente-maritime.json",
    "/geo/france/departments/correze.json",
    "/geo/france/departments/creuse.json",
    "/geo/france/departments/dordogne.json",
    "/geo/france/departments/gironde.json",
    "/geo/france/departments/landes.json",
    "/geo/france/departments/lot-et-garonne.json",
    "/geo/france/departments/pyrenees-atlantiques.json",
    "/geo/france/departments/deux-sevres.json",
    "/geo/france/departments/vienne.json",
    "/geo/france/departments/haute-vienne.json",
)

/**
 * Loads and parses every bundled France `ADMIN_1`/`ADMIN_2` artifact — reads from this class's own
 * classloader, mirroring [loadFranceGeographicAreaReference]'s established trick (works identically
 * from a plain JVM test and the real Android app). Called once per process, like the country-level
 * reference — see `AppContainer`'s own doc comment for why a small, checked-in, versioned geographic
 * artifact set is never reloaded or reclassified per session.
 *
 * [franceCountryArea] is [loadFranceGeographicAreaReference]'s own return value, passed in rather
 * than reloaded here — this function never loads the country artifact itself, both to avoid a second,
 * redundant parse and because [validateGeographicAreaHierarchy] (called below) needs the real
 * `COUNTRY`-typed area every region's `parentId` is expected to resolve to; a single-file parser
 * ([loadGeographicAreaReference]) has no way to know that area exists at all, so this set-level
 * loader is where hierarchy validation belongs — see that function's own doc comment. A caller that
 * only wants regions/departments without validating against a specific country area (e.g. a future
 * multi-country loader) can still call [validateGeographicAreaHierarchy] itself over a differently
 * assembled area list; this function's own use of it is not the only valid way to call it.
 */
fun loadFranceAdministrativeAreas(franceCountryArea: GeographicArea): FranceAdministrativeAreas {
    val regions = FRANCE_ADMIN_1_RESOURCE_PATHS.map { path -> loadGeographicAreaReference(path) }
    val departments = FRANCE_ADMIN_2_RESOURCE_PATHS.map { path -> loadGeographicAreaReference(path) }

    validateGeographicAreaHierarchy(listOf(franceCountryArea) + regions + departments)

    return FranceAdministrativeAreas(regions = regions, departments = departments)
}
