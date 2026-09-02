# Architecture Decisions

## Modular Android architecture
**Decision:** Keep `app`, `core-*` and `feature-*` responsibilities separated.
**Reason:** Location, domain, persistence, auth and UI evolve independently.
**Status:** IMPLEMENTED.
**Do not change unless:** a concrete dependency/maintainability problem justifies restructuring.

## One discovery-domain pipeline
**Decision:** foreground, background and one-shot observations converge on `SubmitDiscoveryObservation`/discovery-engine semantics.
**Reason:** prevent duplicated H3/merge rules.
**Status:** IMPLEMENTED.
**Do not change unless:** a new observation type genuinely requires a versioned domain rule.

## H3 canonical representation
**Decision:** H3 v4.5.0, canonical v1 resolution 12; Android uses `h3-android` and `H3Core.newSystemInstance()`.
**Reason:** stable fine-grained geographic identity; Android native loading issue was solved by the system-instance path.
**Status:** IMPLEMENTED / resolution policy provisional-versioned.
**Do not change unless:** migration/versioning and historical compatibility are designed.

## Local-first persistence
**Decision:** Room stores derived discovery state; raw GPS history is not persisted.
**Reason:** privacy, offline travel and smaller durable data model.
**Status:** PARTIALLY IMPLEMENTED: Room derived-state persistence exists; aggregation/reference layers remain future work.
**Do not change unless:** explicit privacy/data architecture approval.

## Backend
**Decision:** Authentication backend uses FastAPI/PostgreSQL according to the current repository audit; geographic sync/PostGIS discovery backend is future work.
**Reason:** separate account/auth foundation from later geographic synchronization.
**Status:** auth backend IMPLEMENTED; geographic sync DECIDED / NOT IMPLEMENTED.
**Do not change unless:** inspect repository first; code is authoritative.

## Authentication
**Decision:** preserve existing Google Sign-In / email OTP architecture and backend boundary rather than duplicating auth in feature code.
**Reason:** centralized identity/security.
**Status:** IMPLEMENTED for the current Google/OTP foundation; additional account surfaces remain future work.
**Do not change unless:** auth requirements explicitly change.

## Android background location
**Decision:** Fused Location Provider + PendingIntent, persisted explicit consent, real permission check, lifecycle coordination, boot receiver.
**Reason:** battery-conscious background delivery without permanent foreground service.
**Status:** IMPLEMENTED.
**Do not change unless:** measured platform reliability/product requirements demand a different model.

## Offline/online
**Decision:** discovery should work offline locally and synchronize later.
**Reason:** travel frequently has intermittent connectivity.
**Status:** Room-backed local side IMPLEMENTED; server/client geographic sync DECIDED / NOT IMPLEMENTED; concrete protocol ENGINEERING DESIGN REQUIRED.
**Do not change unless:** offline capability remains preserved.

## Trust/security boundary
**Decision:** client observations cannot self-certify.
**Reason:** integrity/anti-cheat.
**Status:** client Non-certified boundary IMPLEMENTED; server certification DECIDED / NOT IMPLEMENTED.
**Do not change unless:** never weaken the authority boundary.

## Derived state, monotonicity and versioning
**Decision:** historical discovery is monotonic; displayed scores/percentages are recalculable projections, never mutable counters. Engine, aggregation, eligibility and geographic references evolve explicitly and versionedly without silently deleting history.
**Reason:** rules and datasets must evolve without corrupting years of user discovery.
**Status:** merge/history foundation IMPLEMENTED; broader aggregation/reference system DECIDED / NOT IMPLEMENTED.

## External geographic data
**Decision:** keep the provider replaceable and use the established hybrid direction (Natural Earth, geoBoundaries, OpenStreetMap, Overture where appropriate, plus a World Discovery correction layer); GADM is not the primary foundation. **Classification geometry and visual rendering geometry are separate concerns and may use different sources** — validated for France (see `PROJECT_STATUS.md` §17 Phase F/G3): classification stays on `geoBoundaries` (offline, canonical for H3 membership tests), while mainland France's visited-country *rendering fill* uses an OSM-derived polygon instead, chosen specifically because it measures far closer (~6-15m median vs up to ~11.7km) to the OpenFreeMap/OpenMapTiles basemap's own boundary geometry than `geoBoundaries` does. The target worldwide shape (not yet built): H3 (canonical) → offline administrative/reference geometry (classification) → basemap-compatible geometry (visual rendering) → basemap-compatible water masking (coastline) → basemap-compatible boundary geometry where available (visible administrative borders).
**Reason:** global coverage, local detail, correct licensing and versioned corrections; the rendering/classification split exists because a single dataset cannot simultaneously be the best fit for both "matches this basemap's pixels" and "stable, offline, worldwide classification truth."
**Status:** DECIDED / NOT IMPLEMENTED for worldwide coverage; France's rendering-only OSM polygon is IMPLEMENTED and PHYSICALLY VALIDATED as a prototype (see `PROJECT_STATUS.md` §17), not yet generalized; ingestion and mapping pipeline for other countries/levels remains ENGINEERING DESIGN REQUIRED.

## Map rendering engine
**Decision:** MapLibre Native is the selected map rendering engine for the discovery/Map feature.
**Reason:** open-source (BSD 2-Clause, forked from Mapbox GL Native before its proprietary license change), no telemetry, no per-user/MAU cost, vector-tile GPU rendering suited to a large number of H3 cell overlays, full style control, and no contractual lock-in to a single tile/data vendor — the best fit for World Discovery's offline-first and provider-replaceability principles (`docs/architecture.md` principle 10).
**Status:** PARTIALLY IMPLEMENTED. This decision covers the rendering engine only, and remains PARTIALLY (not fully) implemented — do not read the physical validations below as completion of this decision. A first increment integrated MapLibre Native (`DiscoveryMapView`, GeoJSON source/FillLayer rendering discovered cells from Room via H3 geometry) and the full Android debug build (`:app:assembleDebug`) plus relevant module tests pass against it — verified with real Gradle. **Physical on-device validation has since happened** (Samsung device, see `PROJECT_STATUS.md` §17's Phase F/G3 record for the exact date and detail) and now covers: MapLibre core rendering, the current `tiles.openfreemap.org/styles/liberty` style loading, H3 cell rendering, the current-position marker, mainland France's OSM-derived visited fill, the OpenMapTiles water-layer coastline masking, the OpenMapTiles FRA terrestrial boundary `LineLayer`, and Android bundled rendering-resource loading (a `feature-map` JVM-style `src/main/resources` artifact, loaded via classloader on real hardware). The style wired in for this validation, `https://tiles.openfreemap.org/styles/liberty`, replaced the earlier temporary `https://demotiles.maplibre.org/style.json` placeholder — it is itself still a temporary, provisional validation style, not a final product/provider decision, and the project's own record already calls it visually too busy for final art direction. **Still genuinely unvalidated, not to be assumed working**: physical on-device antimeridian-crossing geometry rendering (the unwrapping math itself is unit-tested; no physically-tested location in this record crosses ±180°), live foreground/background map behavior beyond what's listed above, and anything else not explicitly named as physically validated in `PROJECT_STATUS.md` §17.
**Explicitly still open, tracked separately (do not infer a choice from this decision or from the physical validations above):** the final vector tile/style provider, any tile hosting or self-hosting choice, the offline tile download/bundling strategy, the final Map art direction (including a future subdued/dark/adventure-oriented custom basemap), and an immersive 3D map mode all remain undecided. See `OPEN_QUESTIONS.md`.
**Do not change unless:** a concrete technical or product problem with MapLibre Native itself — not with a tile/style provider — justifies revisiting.

## Social/engagement separation
**Decision:** private memories, official XP/notable places, community landmarks and geographic discussions are distinct future systems. None may alter exploration percentage or Certified score; participation must not reveal a user's current/precise position.
**Reason:** preserve privacy and the authority of geographic discovery.
**Status:** DECIDED / NOT IMPLEMENTED. See `docs/product-spec.md` §7 and `docs/discovery-engine.md` §§15, 18, 21–22.

## Internationalization and presentation boundary
**Decision:** user-facing text uses localized resources; language/theme/units never affect discovery, H3, scores or Certified semantics.
**Reason:** presentation must not leak into domain truth.
**Status:** English/French resource foundation IMPLEMENTED; synchronized language settings and broader locale behavior DECIDED / NOT IMPLEMENTED. See `docs/architecture.md` §9.
