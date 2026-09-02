# Geographic reference artifacts — generation

`core-discovery-engine/src/main/resources/geo/france-reference.json` is the France entry of this
app's first `GeographicArea` reference artifact — a small, checked-in, versioned geometry file
(see `GeographicArea.kt`/`GeographicAreaReference.kt` for how it's parsed and used), **not** a
hand-drawn polygon.

**As of 2026-09-02 this artifact is generated from `geoBoundaries`, replacing the original
Natural Earth 1:110m version.** Physical-device testing found the Natural Earth artifact's borders
visibly too coarse — long straight segments that didn't track the real basemap coastline/border.
`geoBoundaries` was evaluated in a dedicated spike (real data downloaded and measured, not assumed)
before this switch; see the "Why geoBoundaries, and why two source files" section below for what
that spike found, including a real problem with French Guiana's raw data that required a
dedicated filtering step.

## Source

Two separate `geoBoundaries` `gbOpen` ADM0 files, **both pinned to the same commit
`9469f09`** on the `wmgeolab/geoBoundaries` GitHub mirror — never `master`, so this artifact's exact
source content is reproducible indefinitely regardless of later upstream changes:

- **France (`FRA`)** — metropolitan France + Corsica:
  ```
  https://github.com/wmgeolab/geoBoundaries/raw/9469f09/releaseData/gbOpen/FRA/ADM0/geoBoundaries-FRA-ADM0.geojson
  ```
- **French Guiana (`GUF`)** — see "Why two source files" below for why this is a separate fetch:
  ```
  https://github.com/wmgeolab/geoBoundaries/raw/9469f09/releaseData/gbOpen/GUF/ADM0/geoBoundaries-GUF-ADM0.geojson
  ```

Retrieved: 2026-09-02.

## Licensing is not uniform across components — read this before assuming one blanket license

Unlike Natural Earth (a single, uniformly public-domain dataset), `geoBoundaries` is an aggregator:
**each country's boundary carries the license of its own underlying national source**, confirmed
directly against the `geoBoundaries` API (`https://www.geoboundaries.org/api/current/gbOpen/{ISO3}/ADM0/`),
not assumed from the project's own README (which oversimplifies this — see below):

- **`FRA` (mainland France + Corsica): CC0 1.0 Universal** (Public Domain Dedication) — sourced from
  Wikipedia by `geoBoundaries`. No attribution legally required.
- **`GUF` (French Guiana): Creative Commons Attribution 4.0 International (CC BY 4.0)** — sourced
  from Sentinel-2 (ESA) satellite land-cover data, processed by `geoBoundaries`/IMB. Attribution
  **is** legally required for this component.

Because the two licenses differ and CC BY 4.0 is the stricter, **the combined bundled artifact as a
whole must be treated as requiring attribution** — the `license` field in the generated JSON states
both licenses explicitly, in full, rather than collapsing them into one falsely-uniform string. See
"Architectural limitation: no per-component provenance" below for why this lives in one shared
free-text field instead of a structured per-component field.

`geoBoundaries`' own top-level README states "the only requirement for use is acknowledgement" —
this is a simplification that does not match ODbL's actual share-alike terms for the (not used
here) ODbL-licensed entries in its corpus, and should not be trusted over each license's own text.
For `FRA`/`GUF` specifically, both licenses used here (CC0, CC BY 4.0) are straightforward —
attribution-only at most, no share-alike obligation — so this particular pair does not carry the
ODbL risk flagged for other countries during the broader dataset spike.

## Provenance is not "official"

Every generated artifact records `sourceProvenance: "EXTERNAL_REFERENCE_DATASET"`, not `"OFFICIAL"`.
`geoBoundaries` is a genuine, licensed, versioned open geographic dataset, but it is **not** an
official government boundary authority (e.g. INSEE/IGN for France) — see `GeographicArea.kt`'s
`GeographicAreaProvenance` doc comment for why this distinction is recorded explicitly rather than
overclaimed. (Unchanged reasoning from the prior Natural-Earth-based artifact — this is a property
of "external reference dataset" in general, not specific to which one is used.)

## Why geoBoundaries, and why two source files

A dedicated spike (see the design-review record this switch was made in, not yet transcribed into
`/docs`) downloaded and measured `geoBoundaries` gbOpen ADM0 for France, Luxembourg, Japan, and
Indonesia before committing to this switch. Relevant findings for France specifically:

- **`FRA`'s own ADM0 geometry does not include French Guiana** — only 2 polygons (mainland +
  Corsica), confirmed by direct bounds inspection (never crosses into South American longitudes).
  This is a genuine gap relative to the prior Natural-Earth-based artifact, which had French Guiana
  bundled into the same feature. `GUF`'s own separate ADM0 entry is used to fill that gap.
- **`GUF`'s raw data is not safe to use directly.** Its source is not a cartographic/administrative
  boundary at all — the `geoBoundaries` API's own `boundarySource` field describes it as
  `"raster2polygon from Sentinel-2 10m Land Cover ... exclude gridcode = 1 (water) / dissolve ..."`,
  i.e. an auto-vectorized satellite land-cover classification, not a surveyed coastline. Direct
  measurement of the raw file found:
  - **1,812 exterior polygons**, of which exactly **1** exceeds 100 km² (the real landmass,
    ~83,079 km² in this generator's own crude flat-degree area approximation — matching French
    Guiana's known real area, ~83,534 km², to within 0.5%) and accounts for 99.78% of total polygon
    area. Every other polygon is raster/classification noise (cloud-cover artifacts, misclassified
    pixels, tiny sandbar/wetland slivers from the dissolve step) — the next-largest is only
    29.48 km² (in the same approximation).
  - **3,815 interior holes** on those polygons (3,784 on the main landmass alone), summing to only
    **272.3 km² — 0.33% of the main landmass's own area**. The single largest hole is 45.71 km²
    (same approximation). None of the measured real landmasses or noise polygons/holes fall
    anywhere near the 46–11,694 km² gap between "clearly noise" and "clearly real geography".
  - A right-angle/turning-angle heuristic (fraction of consecutive-edge turns near exactly 90°/180°,
    a signature of pixel-grid-following geometry) measured 24% on the raw main landmass's outer
    ring — confirming visible "staircase" artifacts pre-simplification — dropping to ~3% after
    Douglas-Peucker simplification at the tolerance this generator uses.

## Filtering and simplification rule

`GenerateFranceReference.kt` applies **one shared area threshold, `MIN_COMPONENT_AREA_KM2 = 50.0`**
(in the generator's own crude flat-degree approximation), to *both* exterior polygons and interior
holes, for *both* source files — not two independently guessed numbers, and not a blind
"always keep polygons / always drop holes" special case:

- A polygon survives only if its own outer-ring area is `>= 50.0`.
- A hole survives only if its own area is `>= 50.0`.

At this pinned source version, this keeps exactly 2 `FRA` polygons (mainland + Corsica) and exactly
1 `GUF` polygon (the real landmass) with **zero** surviving holes on any of them — not because holes
are unconditionally discarded, but because every measured hole (max 45.71 in this approximation)
falls strictly under the threshold. If a future regeneration's largest hole or a smaller-but-real
exterior feature ever exceeds 50.0, it survives.

After filtering, every component is simplified with the same Douglas-Peucker tolerance,
`SIMPLIFY_TOLERANCE_DEGREES = 0.001` (≈111m), and coordinates are rounded to 6 decimal places
(≈11cm — vastly finer than anything visually relevant, discarding upstream floating-point noise
rather than real surveyed precision) before being written out.

## Component order is a deliberate artifact contract, not inherited from either source

`FRA`'s 2 significant polygons are told apart by **area**, never by position in the source file's
own polygon list (mainland is ~70x Corsica's size in this generator's approximation — an
unambiguous gap). The final `polygons` array is assembled in an explicit order:

```
polygons[0] = mainland France
polygons[1] = Corsica
polygons[2] = French Guiana
```

This is a contract of *this generated artifact*, re-established fresh on every regeneration — never
a claim that positional indices are a stable, permanent, worldwide component identity. See
`GeographicAreaComponent.kt`'s own doc comment for the same point made from the consuming side.

## Architectural limitation: no per-component provenance

`GeographicArea` has exactly one `sourceId`/`sourceVersion`/`sourceProvenance`/(and the reference
JSON's own) `license` field for the *entire* area — there is no way to record "this specific
component came from this specific source under this specific license" structurally. This is not
worked around by inventing a new schema field or persistence change in this round (out of scope);
instead, the single `license` field's text explicitly names both sources and both licenses in full
(see above), so nothing is silently lost — but a caller that only reads `sourceId`/`sourceVersion`
programmatically (rather than displaying the full `license` string somewhere) cannot currently
distinguish "this component is CC0" from "this component is CC BY 4.0" without parsing that free
text. **Future fix, not built now**: if a truly worldwide, many-source reference model is built
(per the "Future sources" section below), `GeographicAreaComponent` — which already exists as the
per-polygon unit — would be the natural place to carry its own provenance fields, rather than
`GeographicArea` alone.

## Regenerating the artifact

1. Download both pinned source files:

   ```bash
   curl -o geoBoundaries-FRA-ADM0.geojson \
     https://github.com/wmgeolab/geoBoundaries/raw/9469f09/releaseData/gbOpen/FRA/ADM0/geoBoundaries-FRA-ADM0.geojson
   curl -o geoBoundaries-GUF-ADM0.geojson \
     https://github.com/wmgeolab/geoBoundaries/raw/9469f09/releaseData/gbOpen/GUF/ADM0/geoBoundaries-GUF-ADM0.geojson
   ```

2. Compile and run `GenerateFranceReference.kt` (a plain Kotlin program — no Gradle module wraps it
   deliberately, since it's a one-off/occasional generation step, not part of the regular build)
   against `core-discovery-engine`'s compiled output (for the shared `GeographicAreaReferenceJson`
   schema) and the `kotlinx-serialization-json` runtime + compiler plugin (must match your
   `kotlinc`'s exact version — see `gradle/libs.versions.toml`'s `kotlin` entry for the version
   this project targets):

   ```bash
   kotlinc -cp "<core-discovery-engine-classes>;<kotlinx-serialization-core-jvm.jar>;<kotlinx-serialization-json-jvm.jar>" \
     -Xplugin=<kotlin-serialization-compiler-plugin-embeddable.jar matching your kotlinc version> \
     -Xfriend-paths=<core-discovery-engine-classes> \
     -d out tools/geo/GenerateFranceReference.kt

   java -cp "out;<core-discovery-engine-classes>;<kotlinx-serialization-*.jar>;<kotlin-stdlib.jar>;<h3.jar>;<kotlinx-coroutines-core.jar>" \
     GenerateFranceReferenceKt geoBoundaries-FRA-ADM0.geojson geoBoundaries-GUF-ADM0.geojson \
     core-discovery-engine/src/main/resources/geo/france-reference.json
   ```

3. Re-run this project's relevant `core-discovery-engine` tests (`GeographicAreaReferenceTest`,
   `GeographicAreaComponentTest`, `PointInPolygonClassifierTest`, `ClassifyDiscoveredCellsByGeographicAreaTest`,
   `ClassifyDiscoveredCellsByGeographicAreaComponentsTest`, and friends) to confirm the regenerated
   artifact still parses and behaves as expected — several of these tests are explicitly pinned to
   this artifact version's real measured values and must be re-verified (not blindly re-approved)
   against any future regeneration.

## Rendering-only artifact — `feature-map/src/main/resources/geo/france-mainland-osm-render.json`

**A second, deliberately separate artifact — not a replacement for anything above.** Everything in
this file so far concerns France's *classification* geometry (still `geoBoundaries`, unchanged).
This section documents a second, narrower artifact introduced in the Phase F/G3 design-review
record (real device testing, not yet transcribed into `/docs`): mainland France's *rendering-only*
polygon — the shape drawn for the visited-country fill — sourced from OpenStreetMap instead of
`geoBoundaries`, because on-device measurement found `geoBoundaries` diverges from the actual
OpenFreeMap/OpenMapTiles basemap by kilometers in places (Geneva: up to ~11.7km), while a real OSM
administrative relation, measured the same way, diverges by only ~6–15m (median, point-to-segment)
against the same real deployed tiles at Geneva/Spain/Italy/Andorra/Monaco.

**Lives in `feature-map`, not `core-discovery-engine` — on purpose.** Classification geometry has no
reason to know about basemaps or rendering; rendering geometry has no reason to carry classification
semantics (an area id, a component index, a parent link). Keeping them in separate modules with
separate schemas ([`MainlandFranceRenderingPolygonJson`] vs. [`GeographicAreaReferenceJson`]) makes
this a structural guarantee, not just a naming convention — see `MainlandFranceRenderingPolygon.kt`'s
own doc comment in `feature-map` for the full reasoning.

**Mainland only.** Corsica and French Guiana still render from their own `geoBoundaries` component
geometry, unchanged — this artifact never claims to cover them.

### Source

OSM relation `1403916` ("France métropolitaine"), fetched via the community `polygons.openstreetmap.fr`
tool:

```
https://polygons.openstreetmap.fr/get_geojson.py?id=1403916&params=0
```

Retrieved: 2026-09-02. License: OpenStreetMap contributors, **ODbL v1.0**
(https://www.openstreetmap.org/copyright) — attribution required. Bundling this processed extract in
a released build makes it a **Derivative Database** under ODbL: the extracted/processed polygon
itself must be made available under ODbL to whoever requests it (see the Phase G design-review
record's licensing analysis for the full reasoning — commercial use itself is not restricted).

### Regenerating the artifact

1. Fetch the source relation (same URL as above).
2. Compile and run `GenerateFranceOsmRenderingPolygon.kt` the same way as `GenerateFranceReference.kt`
   above (same `kotlinx-serialization-json` classpath/plugin requirements — no dependency on
   `core-discovery-engine`'s own compiled output this time, since this tool defines its own,
   deliberately separate JSON schema):

   ```bash
   kotlinc -cp "<kotlinx-serialization-core-jvm.jar>;<kotlinx-serialization-json-jvm.jar>" \
     -Xplugin=<kotlin-serialization-compiler-plugin-embeddable.jar matching your kotlinc version> \
     -d out tools/geo/GenerateFranceOsmRenderingPolygon.kt

   java -cp "out;<kotlinx-serialization-*.jar>;<kotlin-stdlib.jar>" \
     GenerateFranceOsmRenderingPolygonKt france-osm-relation-1403916.geojson \
     feature-map/src/main/resources/geo/france-mainland-osm-render.json
   ```

3. Re-run `feature-map`'s relevant tests (`MainlandFranceRenderingPolygonTest`,
   `CountryOverlayRenderingTest`) to confirm the regenerated artifact still parses and behaves as
   expected.

## Future sources — not implemented, not decided here

`geoBoundaries` gbOpen ADM0 is now used for France's Country-level boundary specifically — this
does **not** itself decide the worldwide/subdivision strategy. Per the current architecture
direction (see the conversation/design-review record, not yet transcribed into `/docs`):
administrative subdivisions (region/department-equivalent) are expected to evaluate Overture Maps'
Divisions dataset first, with OpenStreetMap as a local/detail/gap-filling source — none of this is
implemented yet, and no provider below Country level is locked in. A real, evidence-based licensing
concern (ODbL share-alike) was found for *other* countries' `geoBoundaries` gbOpen entries (e.g.
Luxembourg, Indonesia) during the broader spike — not relevant to `FRA`/`GUF` (both CC-family,
no share-alike obligation), but must be resolved with a real legal review before `geoBoundaries` is
adopted for any ODbL-sourced country.
