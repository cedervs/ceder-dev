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
 * **Full metropolitan coverage: all 13 metropolitan regions and all 96 of their departments.**
 * [ClassifyDiscoveredCellsByGeographicAreas]/`AdministrativeOverlayRendering.kt`/
 * `AdministrativeAreaNavigation.kt` all operate generically over whatever `List<GeographicArea>` is
 * loaded here and know nothing about which regions/departments exist — completing the remaining 11
 * regions' departments (Phase FH-1) was a mechanical follow-up of regenerating more artifacts with
 * `tools/geo/GenerateFranceAdministrativeReference.kt` and adding their resource paths below, exactly
 * as this doc comment already predicted; no hierarchy/classification/rendering/navigation code
 * changed. See `PROJECT_STATUS.md` for physical-validation status (still partial — data completion is
 * not itself on-device validation).
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
 * the hierarchy) with its own two departments loaded like any other region's — Corse-du-Sud
 * (`admin2:FR-2A`) and Haute-Corse (`admin2:FR-2B`) — no special-cased architecture.
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

/** All 96 metropolitan department artifacts, grouped by region in the same order as
 * [FRANCE_ADMIN_1_RESOURCE_PATHS] above — see this file's own doc comment and `tools/geo/README.md`
 * for the exact OSM relation each was generated from. */
private val FRANCE_ADMIN_2_RESOURCE_PATHS = listOf(
    // Île-de-France
    "/geo/france/departments/paris.json",
    "/geo/france/departments/seine-et-marne.json",
    "/geo/france/departments/yvelines.json",
    "/geo/france/departments/essonne.json",
    "/geo/france/departments/hauts-de-seine.json",
    "/geo/france/departments/seine-saint-denis.json",
    "/geo/france/departments/val-de-marne.json",
    "/geo/france/departments/val-doise.json",
    // Centre-Val de Loire
    "/geo/france/departments/cher.json",
    "/geo/france/departments/eure-et-loir.json",
    "/geo/france/departments/indre.json",
    "/geo/france/departments/indre-et-loire.json",
    "/geo/france/departments/loir-et-cher.json",
    "/geo/france/departments/loiret.json",
    // Bourgogne-Franche-Comté
    "/geo/france/departments/cote-dor.json",
    "/geo/france/departments/doubs.json",
    "/geo/france/departments/jura.json",
    "/geo/france/departments/nievre.json",
    "/geo/france/departments/haute-saone.json",
    "/geo/france/departments/saone-et-loire.json",
    "/geo/france/departments/yonne.json",
    "/geo/france/departments/territoire-de-belfort.json",
    // Normandie
    "/geo/france/departments/calvados.json",
    "/geo/france/departments/eure.json",
    "/geo/france/departments/manche.json",
    "/geo/france/departments/orne.json",
    "/geo/france/departments/seine-maritime.json",
    // Hauts-de-France
    "/geo/france/departments/aisne.json",
    "/geo/france/departments/nord.json",
    "/geo/france/departments/oise.json",
    "/geo/france/departments/pas-de-calais.json",
    "/geo/france/departments/somme.json",
    // Grand Est
    "/geo/france/departments/ardennes.json",
    "/geo/france/departments/aube.json",
    "/geo/france/departments/marne.json",
    "/geo/france/departments/haute-marne.json",
    "/geo/france/departments/meurthe-et-moselle.json",
    "/geo/france/departments/meuse.json",
    "/geo/france/departments/moselle.json",
    "/geo/france/departments/bas-rhin.json",
    "/geo/france/departments/haut-rhin.json",
    "/geo/france/departments/vosges.json",
    // Pays de la Loire
    "/geo/france/departments/loire-atlantique.json",
    "/geo/france/departments/maine-et-loire.json",
    "/geo/france/departments/mayenne.json",
    "/geo/france/departments/sarthe.json",
    "/geo/france/departments/vendee.json",
    // Bretagne
    "/geo/france/departments/cotes-darmor.json",
    "/geo/france/departments/finistere.json",
    "/geo/france/departments/ille-et-vilaine.json",
    "/geo/france/departments/morbihan.json",
    // Nouvelle-Aquitaine
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
    // Occitanie
    "/geo/france/departments/ariege.json",
    "/geo/france/departments/aude.json",
    "/geo/france/departments/aveyron.json",
    "/geo/france/departments/gard.json",
    "/geo/france/departments/haute-garonne.json",
    "/geo/france/departments/gers.json",
    "/geo/france/departments/herault.json",
    "/geo/france/departments/lot.json",
    "/geo/france/departments/lozere.json",
    "/geo/france/departments/hautes-pyrenees.json",
    "/geo/france/departments/pyrenees-orientales.json",
    "/geo/france/departments/tarn.json",
    "/geo/france/departments/tarn-et-garonne.json",
    // Auvergne-Rhône-Alpes
    "/geo/france/departments/ain.json",
    "/geo/france/departments/allier.json",
    "/geo/france/departments/ardeche.json",
    "/geo/france/departments/cantal.json",
    "/geo/france/departments/drome.json",
    "/geo/france/departments/isere.json",
    "/geo/france/departments/loire.json",
    "/geo/france/departments/haute-loire.json",
    "/geo/france/departments/puy-de-dome.json",
    "/geo/france/departments/rhone.json",
    "/geo/france/departments/savoie.json",
    "/geo/france/departments/haute-savoie.json",
    // Provence-Alpes-Côte d'Azur
    "/geo/france/departments/alpes-de-haute-provence.json",
    "/geo/france/departments/hautes-alpes.json",
    "/geo/france/departments/alpes-maritimes.json",
    "/geo/france/departments/bouches-du-rhone.json",
    "/geo/france/departments/var.json",
    "/geo/france/departments/vaucluse.json",
    // Corse
    "/geo/france/departments/corse-du-sud.json",
    "/geo/france/departments/haute-corse.json",
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
