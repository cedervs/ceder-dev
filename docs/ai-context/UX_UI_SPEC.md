# UX / UI Specification

## Navigation — IMPLEMENTED
Compose navigation between **Map, Journey, Progress and Profile** is implemented in `WorldDiscoveryApp`, including the bottom navigation bar and destination routes. This status covers navigation infrastructure, not feature completeness.

## Map
**PARTIALLY IMPLEMENTED, physically validated where noted (Samsung device; see `PROJECT_STATUS.md` §17 and `ARCHITECTURE_DECISIONS.md`'s "Map rendering engine" entry for the itemized record):** `MapScreen` renders MapLibre Native with the user's discovered H3 cells, consumed reactively from Room (`DiscoveredCellRepository.observeAll()` → derived cell geometries), alongside the existing user-triggered current-location test action, which is preserved unchanged. Physically validated on-device: MapLibre core rendering; the current `https://tiles.openfreemap.org/styles/liberty` style loading (a temporary validation style, not a final provider/product choice — it replaces the earlier `demotiles.maplibre.org` placeholder); H3 cell rendering; the current-position marker; mainland France's OSM-derived visited-country fill; OpenMapTiles water-layer coastline masking; the OpenMapTiles FRA terrestrial boundary line; and Android bundled rendering-resource loading. Still genuinely pending/not final: the final tile/style provider, final style, hosting strategy, offline packaging strategy, final visual direction, and physical antimeridian-crossing rendering (the unwrapping math itself is unit-tested; no physically-validated location crosses ±180°) — these remain **ENGINEERING DESIGN REQUIRED** or **NEEDS USER CONFIRMATION** as already tracked, not silently assumed working. The map is otherwise read-only for this increment: no clustering, camera system beyond country-level focus, Progress overlays, or ELIGIBLE/RESTRICTED_EXCLUDED/UNKNOWN handling. **DECIDED / NOT IMPLEMENTED:** Map is the primary surface and visualizes discovered territory rather than a raw GPS breadcrumb trace; Certified and Non-certified remain distinguishable where exposed — currently via a provisional fill color, not final art direction; labels/borders are contextual only; visited/present country styling means **VISITED / PRESENCE, never fully explored, 100% completed, or full geographic coverage** — exact exploration remains derived solely from canonical H3 discovery data. **Future, not implemented:** worldwide geographic generalization beyond this France prototype; the World → Country → Admin1 → Admin2 → local discovery → H3 navigation hierarchy (country-aware, since administrative structures differ worldwide); stronger far-zoom visited-country visual prominence; a future custom subdued/dark/adventure-oriented basemap; an immersive 3D map mode. **NEEDS USER CONFIRMATION:** final art direction and product-specific interaction treatment.

## Progress
**PARTIALLY IMPLEMENTED:** destination/module placeholder exists. **DECIDED / NOT IMPLEMENTED:** show recalculable exploration depth derived from canonical history, with Standard as default/reference and Easy/Hard interpretations. Widgets/achievement UX may need confirmation when scheduled; aggregation design is an engineering task and coefficients require calibration.

## Journey
**PARTIALLY IMPLEMENTED:** destination/module placeholder exists. **DECIDED / NOT IMPLEMENTED:** present discovery as understandable journeys/trips, not permanent raw GPS traces. **NEEDS USER CONFIRMATION:** automatic segmentation, merging, editing, naming, overrides and timeline UX.

## Profile
**PARTIALLY IMPLEMENTED:** profile contains Google/OTP authentication, logout and background-discovery consent UI/disclosure. Consent is distinct from Android OS permission. When foreground permission is absent, the toggle is disabled and an explanatory error is shown. Broader account/settings/privacy/profile surfaces are not implemented.

## Permissions and consent
IMPLEMENTED: background discovery is opt-in and disclosed. Android permission remains the OS authority; persisted consent alone cannot enable tracking. Revocation/downgrade must safely stop effective background tracking. Force-stop is respected.

## Authentication / onboarding
Existing Google Sign-In and email OTP flows exist; consult code/`PROJECT_STATUS.md` for exact current screens. Broader onboarding, first-run education and final account-selection UX are **NEEDS USER CONFIRMATION** when that work is scheduled.

## Empty/error states
**PARTIALLY IMPLEMENTED:** current auth, location-test and permission flows have localized states/copy. No complete final cross-feature specification exists. Use existing resource conventions and request user confirmation only when an unresolved product behavior—not routine UX engineering—matters.
