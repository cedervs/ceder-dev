# PROJECT_STATUS.md

> This file mixes two distinct kinds of information, deliberately kept
> apart below:
>
> -   **Phases 1--4 (§6--§16, §18):** describe the approved project state
>     at the historical Phase 4 code baseline, commit `7a906a9` ---
>     `feat: add background location tracking`. That code state has not
>     changed since; do not assume phase 1--4 behavior differs from what
>     is described there.
> -   **Documentation/governance commits already in Git, on top of
>     `7a906a9`, with no further code change:** `1e22ed4`, `ce7732d`,
>     `7b9294d`, and `d222fd7` (`docs: adopt MapLibre as map rendering
>     engine`). `d222fd7` is the current, pushed `HEAD` of `main` as of
>     this document's last update (verify with `git log`/`git rev-parse
>     HEAD` rather than assuming this stays true later).
> -   **§17 only:** describes a first Map/MapLibre implementation
>     increment that exists **solely in the current working tree, on top
>     of `d222fd7`, and has NOT been committed or pushed.** Do not assume
>     it exists in a fresh checkout, and do not treat it as part of the
>     committed/pushed history until repository history says otherwise.
>
> This file is intended as context for Codex and Claude Code. Do not
> assume work beyond what is explicitly and currently described here
> exists unless repository history, or an explicit newer instruction,
> establishes it.

## 1. General objective

**World Discovery** is an Android application for building a long-term
personal map of places physically discovered by the user.

It is not intended to be a conventional continuous GPS tracker. The
product goal is automatic discovery over months and years while
minimizing battery use and privacy exposure.

Core principles:

-   automatic foreground and background discovery;
-   local/offline-first discovery recording;
-   derive geographic cells quickly instead of persisting raw GPS
    history;
-   battery-conscious sampling rather than continuous high-accuracy
    tracking;
-   future illustrated/minimal discovery map;
-   future hierarchy: World → Continent → Country → sublevels;
-   visible distinction between certified and non-certified discoveries;
-   certification must eventually depend on server-side validation,
    never client GPS alone.

The implemented Compose navigation contains Map, Journey, Progress and
Profile. The destination content is still incomplete as described below.

## 2. Technical stack

-   Kotlin
-   Jetpack Compose
-   Gradle 9.7.1
-   Android Gradle Plugin 9.3.2
-   Kotlin / Compose plugin 2.4.10
-   Compose BOM 2026.08.00
-   Java/JVM 17
-   `compileSdk 37`
-   `targetSdk 36`
-   `minSdk 26`
-   Room for local persistence
-   H3 4.5.0 / `h3-android`
-   canonical H3 resolution: **12**
-   MapLibre Native (Android SDK, OpenGL variant) as the map rendering
    engine — tile/style provider still undecided
-   Google Play services Fused Location Provider
-   Android DataStore for persisted background-tracking consent
-   existing authentication layer with Google Sign-In and email OTP
-   Python
-   FastAPI
-   SQLAlchemy
-   PostgreSQL
-   Alembic
-   pytest
-   Git / GitHub

## 3. Package / application ID

``` text
com.cedervs.worlddiscovery
```

Treat this as the canonical Android identifier.

## 4. Architecture

The project is modular:

``` text
app
core-auth
core-database
core-discovery-engine
core-location
core-network
feature-map
feature-journey
feature-progress
feature-profile
```

### Responsibilities

`app` owns application composition and Android-level wiring, including
`WorldDiscoveryApplication`, `MainActivity`, `AppContainer`, `WorldDiscoveryApp`,
`BackgroundLocationReceiver`, `BootCompletedReceiver` and the manifest.

`core-discovery-engine` contains discovery-domain logic: H3 conversion,
discovery models, merge rules, repository abstraction and
`SubmitDiscoveryObservation`. It is intentionally kept independent from
Android where possible.

`core-database` contains Room persistence for derived discovery state.

`core-location` owns foreground/background location orchestration,
provider abstractions, Fused Location implementations, permissions,
consent, PendingIntent registration and background observation handling.

`backend` contains the existing authentication backend implemented with
FastAPI, SQLAlchemy and PostgreSQL. It owns Google ID-token verification,
email OTP authentication, access/refresh sessions, Alembic migrations and
the associated pytest suite. It does not yet contain geographic discovery
synchronization or PostGIS-backed discovery tables.

The `feature-*` modules own the Map, Journey, Progress and Profile UI
areas.

## 5. External services / platform dependencies

Current dependencies include Google Play services for fused Android
location, Google authentication, H3 and GitHub source control.

An authentication backend is already implemented at `7a906a9` with
Python, FastAPI, SQLAlchemy, PostgreSQL and Alembic. It supports Google
authentication, email OTP, access/refresh sessions and logout.

The geographic discovery synchronization backend is **not implemented at
`7a906a9`**. PostGIS and the future geographic/discovery synchronization
tables and endpoints are also not implemented. Those remain future
architectural work distinct from the existing authentication backend.

MapLibre/OpenStreetMap are part of the intended mapping direction; do
not infer that the final discovery map is already implemented at this
commit. A first Map increment integrating MapLibre Native now exists in
the working tree, uncommitted — see §17 for its actual scope and
pending on-device validation.

## 6. Completed phases

### Phase 1 --- local discovery engine foundation

Reference commit:

``` text
a731e50  feat: add local discovery engine foundation
```

Completed:

-   canonical H3 conversion at resolution 12;
-   discovery events/cells and domain models;
-   merge logic;
-   repository abstraction;
-   submission use case;
-   Room-backed local persistence;
-   discovery-engine and Room tests;
-   no persistent raw GPS history.

Room identity distinguishes `(h3Index, trustStatus)`, so the same H3
cell under different trust states is not silently merged.

### Phase 2 --- foreground one-shot location discovery

Reference commit:

``` text
fff2cb5  feat: add foreground location discovery
```

Completed:

-   user-triggered foreground location acquisition;
-   Fused Location Provider integration;
-   submission through the discovery engine;
-   H3 conversion;
-   Room persistence.

Android H3 loading uses `h3-android` 4.5.0 with:

``` kotlin
H3Core.newSystemInstance()
```

### Phase 3 --- automatic foreground tracking

Reference commit:

``` text
63f2d96  feat: add foreground location tracking
```

Completed:

-   automatic updates while the app is foregrounded;
-   lifecycle-aware start/stop;
-   idempotent tracking-session startup;
-   recovery after foreground permission is granted;
-   observations routed through the existing discovery pipeline.

Conceptual flow:

``` text
Process ON_START
  → foreground session start
  → Fused Location updates
  → SubmitDiscoveryObservation
  → H3 res 12
  → Room

Process ON_STOP
  → foreground session stop
```

### Phase 4 --- background location tracking

Stable reference commit:

``` text
7a906a9  feat: add background location tracking
```

Completed:

-   PendingIntent-based background location updates;
-   explicit persisted user consent;
-   separation of application consent and Android permission;
-   actual background permission rechecked when registering;
-   coordinated foreground/background transitions;
-   reboot re-arm via `BOOT_COMPLETED`;
-   processing of every location in a batched `LocationResult`;
-   preservation of each location's real `Location.time`;
-   same discovery-domain pipeline as foreground observations;
-   automatic background discoveries remain `OBSERVED + NON_CERTIFIED`;
-   no raw GPS persistence;
-   no foreground service;
-   no WorkManager GPS polling;
-   no battery-optimization exemption request.

Current provisional background configuration:

``` text
priority: balanced power
interval: 20 minutes
minimum interval: 10 minutes
maximum update delay: 30 minutes
```

Because `maxUpdateDelay` permits batching, **all** received locations
must be processed.

## 7. Current Google Sign-In

Google authentication was implemented before the discovery/location
phases and remains part of the existing auth architecture. Email OTP
authentication also exists from earlier work.

Relevant earlier authentication commits include:

``` text
1f62598  feat: add Google authentication foundation
c999c95  feat: add email OTP authentication
```

Phases 1--4 did not replace the authentication architecture.

Before modifying Google Sign-In, inspect the actual current code in
`core-auth`, `core-network` and application wiring. Do not duplicate or
bypass the existing flow.

Never add Google credentials, OAuth secrets, access/refresh tokens,
signing secrets, private keys or other credentials to this document or
source control.

## 8. Current geolocation behavior

### Foreground

When the app enters the foreground, background registration is disarmed
as appropriate and the foreground session starts. Fused Location updates
are submitted to `SubmitDiscoveryObservation`, converted to H3
resolution 12 and stored/merged through Room.

Foreground automatic tracking has been physically validated on a Samsung
device. Reopening the app resumes it. Foreground permission
revoke/re-grant recovery was also validated without requiring an app
restart. These are historically communicated device-validation results;
they are consistent with the code but are not independently demonstrable
from the repository alone at this commit.

### Background

When the app leaves the foreground, foreground tracking stops and the
background controller may arm background updates.

Effective background tracking requires both:

-   persisted application consent;
-   sufficient current Android location permission.

The Fused Location Provider uses a `PendingIntent`. The receiver uses
`goAsync()` for asynchronous work.

A `LocationResult` may contain multiple locations. Every location is
converted into a background observation and submitted. Each observation
preserves its own `Location.time`; do not replace batched timestamps
with one `Instant.now()`.

### Reboot

`BootCompletedReceiver` re-arms background tracking after reboot only
when consent and permission conditions allow it. This was physically
validated without manually reopening the app. This is a historically
communicated device-validation result and is not independently
demonstrable from the repository alone at this commit.

### Permission downgrade

Persisted consent and Android OS permission are deliberately separate.
If consent remains enabled but OS background permission is downgraded,
registration is blocked by the real permission check. This was
physically validated. This is a historically communicated
device-validation result and is not independently demonstrable from the
repository alone at this commit.

### Force-stop

Force-stop semantics are accepted. The app does not try to defeat
Android force-stop behavior. Tracking remains stopped until manual
relaunch.

## 9. Trust / provenance rules

Current automatic client-side location observations must remain:

``` text
trustStatus = NON_CERTIFIED
provenance = OBSERVED
```

Never promote an automatic client observation to certified status solely
because it came from GPS.

The product model distinguishes certified/non-certified discoveries and
observed/reconstructed/imported/manual provenance. Official rankings are
intended to use certified discoveries only.

## 10. Persistence and merge rules

Raw latitude/longitude is not persisted as long-term discovery history.
Coordinates are transient inputs used to derive H3 cells.

Established merge rules:

1.  no existing discovery → create;
2.  `firstDiscoveredAt` = earliest observation;
3.  `lastObservedAt` = latest observation;
4.  provenance follows the latest chronological observation; exact
    timestamp tie → incoming wins;
5.  `engineVersion` and `h3Resolution` remain frozen from record
    creation;
6.  same H3 with different trust status remains separate;
7.  cross-trust merging is not allowed.

Do not alter these rules incidentally from UI/location work.

## 11. Principal files/classes

### Application

``` text
app/src/main/AndroidManifest.xml
app/src/main/java/com/cedervs/worlddiscovery/MainActivity.kt
app/src/main/java/com/cedervs/worlddiscovery/WorldDiscoveryApplication.kt
app/src/main/java/com/cedervs/worlddiscovery/BackgroundLocationReceiver.kt
app/src/main/java/com/cedervs/worlddiscovery/BootCompletedReceiver.kt
app/src/main/java/com/cedervs/worlddiscovery/di/AppContainer.kt
app/src/main/java/com/cedervs/worlddiscovery/ui/WorldDiscoveryApp.kt
```

`WorldDiscoveryApplication` creates and owns the process-wide
`AppContainer`; activities and broadcast receivers reuse that same
composition root.

### Location

``` text
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/AppForegroundTrackingController.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/FusedLocationUpdatesProvider.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/LocationPermissions.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/LocationUpdateConfig.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/BackgroundLocationBroadcast.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/BackgroundLocationController.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/BackgroundLocationObservation.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/BackgroundLocationRegistrar.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/BackgroundTrackingConsent.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/DataStoreBackgroundTrackingConsent.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/FusedBackgroundLocationRegistrar.kt
core-location/src/main/java/com/cedervs/worlddiscovery/core/location/SubmitBackgroundLocationObservations.kt
```

### Profile UI

``` text
feature-profile/src/main/java/com/cedervs/worlddiscovery/feature/profile/ProfileScreen.kt
feature-profile/src/main/res/values/strings.xml
feature-profile/src/main/res/values-fr/strings.xml
```

### Phase-4 tests

``` text
core-location/src/test/java/com/cedervs/worlddiscovery/core/location/AppForegroundTrackingControllerTest.kt
core-location/src/test/java/com/cedervs/worlddiscovery/core/location/BackgroundLocationControllerTest.kt
core-location/src/test/java/com/cedervs/worlddiscovery/core/location/SubmitBackgroundLocationObservationsTest.kt
```

For discovery persistence/domain changes, inspect
`core-discovery-engine` and `core-database`; do not duplicate their
rules in `app` or `core-location`.

## 12. Important technical decisions

-   H3 resolution 12 is canonical for the current engine version.
-   Do not silently change H3 resolution from location/UI code.
-   Do not persist raw GPS history by default.
-   Foreground, background and one-shot observations converge on one
    discovery-domain pipeline.
-   Automatic client observations remain non-certified.
-   Consent and Android OS permission are separate states.
-   Registration must verify actual Android permission.
-   Foreground and background tracking must remain coordinated.
-   Background batching is intentional; never regress to
    `LocationResult.lastLocation`.
-   Preserve each batched location's real timestamp.
-   Battery efficiency is a product requirement.
-   Android H3 uses `h3-android` with `H3Core.newSystemInstance()`.

## 13. Solutions abandoned / do not reintroduce

Do not casually reintroduce:

-   permanent foreground service for normal background discovery;
-   WorkManager used as a periodic GPS polling loop;
-   battery-optimization exemption prompts;
-   continuous high-accuracy background GPS;
-   persistent raw GPS point history;
-   client-side certification;
-   the previously problematic Android H3 initialization instead of the
    working `newSystemInstance()` path;
-   processing only `LocationResult.lastLocation`;
-   replacing a batch's individual timestamps with `Instant.now()`.

Any future change to these decisions requires an explicit new
architecture/product justification.

## 14. Test/build commands

From repository root in Windows PowerShell:

### Historically executed/validated for phase 4

The following commands are the commands reported as executed and validated
for phase 4:

``` powershell
.\gradlew :core-discovery-engine:test --console=plain
```

``` powershell
.\gradlew :core-location:testDebugUnitTest --console=plain
```

``` powershell
.\gradlew :app:assembleDebug --console=plain
```

``` powershell
git diff --check
```

### Additional test commands available in the repository

The repository also contains test suites for Room persistence and Android
authentication. The corresponding supported Gradle commands are:

``` powershell
.\gradlew :core-database:testDebugUnitTest --console=plain
```

``` powershell
.\gradlew :core-auth:testDebugUnitTest --console=plain
```

The backend pytest suite requires the backend Python dependencies and its
environment configuration, plus the test PostgreSQL service. From the
repository root, the supported commands are:

``` powershell
docker compose up -d postgres_test
```

``` powershell
Push-Location backend
python -m pytest
Pop-Location
```

These additional Room, authentication and backend commands are available
from the repository configuration. Their presence does not by itself prove
that they were executed as part of the phase-4 validation.

JDK 17 is required.

The development machine used during phase 4 sometimes required:

``` powershell
$env:JAVA_HOME="C:\Users\van Slobbe\.jdks\temurin-17.0.20.1"
```

That path is machine-specific and must not be hard-coded into project
configuration.

## 15. Phase-4 validation completed

The following validations were historically communicated as completed
before `7a906a9`. The repository contains code and automated tests
consistent with them, but physical-device execution and past command
execution are not independently provable from the repository alone at the
current commit:

-   discovery-engine tests;
-   location unit tests;
-   full Android debug build;
-   automatic foreground tracking on physical Samsung;
-   automatic background tracking on physical Samsung;
-   foreground → background transition;
-   background → foreground transition;
-   explicit background consent;
-   consent OFF preventing later background observations;
-   reboot re-arm;
-   background permission downgrade preventing background observations;
-   foreground permission revoke/re-grant recovery;
-   batched background submission tests;
-   per-location timestamp preservation;
-   empty-batch behavior;
-   full build after batching fix;
-   `git diff --check`;
-   clean working tree before push.

## 16. Known bugs / limitations / platform behavior

### Native-library strip warning

Earlier full Android builds emitted non-blocking warnings that some
native libraries could not be stripped. Builds still completed
successfully. Do not treat this warning alone as a phase failure unless
it becomes a packaging/runtime problem.

### Background cadence is not exact

Android controls background scheduling and may delay or batch updates. A
configured 20-minute interval is not a promise of an observation exactly
every 20 minutes.

### Force-stop

Android force-stop prevents automatic tracking until manual relaunch.
This is accepted behavior.

### Consent vs permission UI

Persisted consent can remain true while the OS permission changes.
Effective tracking still depends on the real permission. Future UI may
make this distinction clearer; the permission enforcement must remain.

### Certification

Secure certification/validation is not implemented client-side. Current
automatic discoveries remain non-certified.

### Final map

The discovery/location foundation exists, but the full visual World
Discovery map and geographic hierarchy are not yet the completed
product.

## 17. Map/discovery visualization layer --- first increment (uncommitted)

**Status: PARTIALLY IMPLEMENTED, not yet committed.** A first Map
increment exists in the working tree on top of `d222fd7`, the current
pushed `HEAD` of `main` (itself documentation-only on top of the
`7a906a9` Phase 1--4 code baseline). Neither `7a906a9` nor `d222fd7` has
moved (see §19) --- this increment has not been committed or pushed.

Implemented pipeline:

``` text
Room `discovered_cells`
  → RoomDiscoveredCellRepository.observeAll() (Flow)
  → ObserveDiscoveredCellGeometries (H3 cell → boundary geometry)
  → MapScreen
  → DiscoveryMapView
  → MapLibre GeoJsonSource / FillLayer
```

-   `DiscoveredCellRepository` now exposes a reactive
    `observeAll(): Flow<List<DiscoveredCell>>`, backed by Room's own
    invalidation tracking (`DiscoveredCellDao.observeAll()`), in
    addition to the existing `find`/`upsert`.
-   Rendered geometries are derived on the fly from the existing
    `discovered_cells` rows via H3 (`H3CellConverter.cellBoundary`);
    there is no second persisted source of truth for geometry.
-   MapLibre Native (OpenGL Android SDK variant) is integrated as the
    rendering engine and consumes that Flow through `DiscoveryMapView`,
    styled with a data-driven fill color keyed on Certified/Non-certified
    --- a provisional visual distinction, not final art direction.
-   The current map style, `https://demotiles.maplibre.org/style.json`,
    is a temporary, keyless, isolated development style used only to
    unblock technical integration. It is not a product/provider
    decision. The vector tile/style provider, tile hosting, and offline
    packaging strategy remain undecided (see `ARCHITECTURE_DECISIONS.md`,
    `OPEN_QUESTIONS.md`); final art direction remains NEEDS USER
    CONFIRMATION.
-   The map is read-only for this increment: no clustering, no camera
    system, no Progress/percentage overlays, no
    ELIGIBLE/RESTRICTED_EXCLUDED/UNKNOWN handling, no backend sync.
-   The existing user-triggered one-shot location test button is
    preserved unchanged alongside the map.
-   `MapView`'s lifecycle is explicitly managed
    (`MapViewLifecycleController`), including teardown paths not covered
    by a bare `DisposableEffect` (Navigation-Compose disposal without a
    host lifecycle transition) and `onLowMemory()` forwarding.
-   Invalid/malformed H3 cells are detected via
    `H3CellConverter.isValidCell(...)` and skipped individually before
    geometry conversion; any other, genuinely unexpected exception is not
    caught and propagates normally.
-   Antimeridian (±180°) ring unwrapping is implemented and covered by
    unit tests using real H3-captured boundary data.
-   Canonical H3 resolution 12 is preserved; no raw-GPS persistence was
    added for rendering.

Validated locally (JDK 17, real Gradle, on the developer's machine):

``` powershell
.\gradlew :core-discovery-engine:test --console=plain
.\gradlew :core-database:testDebugUnitTest --console=plain
.\gradlew :core-location:testDebugUnitTest --console=plain
.\gradlew :app:assembleDebug --console=plain
git diff --check
```

All reported BUILD SUCCESSFUL.

**State at completion of this initial increment (historical — see the
time-scoping note immediately below before relying on this list):**

**Explicitly PENDING --- not yet physically validated on a device, as of
this increment:**

-   real MapLibre rendering on a physical phone;
-   correct style loading on-device;
-   discovered cells actually rendering at the correct location;
-   live map update while a discovery happens;
-   real foreground/background behavior with the map on screen;
-   Certified/Non-certified visual distinction on-device with real data;
-   MapLibre Native's actual runtime interpretation of
    antimeridian-crossing geometry (the unwrapping math itself is
    unit-tested; on-device rendering of it is not).

Do not treat this increment as a physically validated Map feature until
that on-device validation happens and is recorded here.

**Time-scoping note (do not confuse this historical state with the current
one):** the "PENDING" list above describes this increment's own state at
the time it was written, before any physical device testing had occurred.
It was **later superseded**: real MapLibre core rendering, real style
loading, and correct on-screen placement of rendered geometry (among
other items) were subsequently physically validated on a Samsung device —
see the Phase F/G3 section below and `docs/ai-context/ARCHITECTURE_DECISIONS.md`'s
"Map rendering engine" entry for the authoritative, current, itemized
physical-validation record. Live map update during a fresh discovery,
Certified/Non-certified visual distinction with real data, and physical
antimeridian-crossing rendering remain genuinely unvalidated as of the
Phase F/G3 record — do not assume those specific items are covered just
because other items on this list have since been validated.

Remaining future work beyond this increment: full clustering, camera
system, Progress/percentage overlays, ELIGIBLE/RESTRICTED_EXCLUDED/UNKNOWN
handling, backend sync, community features, souvenirs, POI, final
political borders, full Certified mode, final visual design, and the
World → Continent → Country → sublevel exploration hierarchy. Avoid
prematurely introducing backend certification/synchronization unless a
future phase is explicitly re-scoped for it.

### Phase F/G3 — France country-fill rendering (OSM-derived), PHYSICALLY VALIDATED

**Status: IMPLEMENTED and PHYSICALLY VALIDATED** on a real Samsung device
(2026-09-02), on top of the increment above, still uncommitted.

**IMPLEMENTED / PHYSICALLY VALIDATED:**

-   mainland France's visited-country **fill** now renders from an
    OSM-derived polygon (OSM relation `1403916`, retrieved via
    `polygons.openstreetmap.fr`, bundled as
    `feature-map/src/main/resources/geo/france-mainland-osm-render.json` —
    see `tools/geo/README.md`), replacing `geoBoundaries` for *rendering*
    only. Measured (point-to-segment, real deployed OpenFreeMap/OpenMapTiles
    tiles) at ~6-15m median divergence at Geneva/Spain/Italy/Andorra/Monaco,
    versus up to ~11.7km for `geoBoundaries` at the same locations.
-   the existing OpenMapTiles `water`-layer masking (fill inserted below the
    basemap's own water layer) continues to handle coastline appearance —
    unchanged this phase, confirmed still correct on-device (Brittany).
-   the existing OpenMapTiles `admin_level=2` boundary `LineLayer`
    (`basemap-aligned-france-border-prototype`) continues to render the
    visible terrestrial outline — unchanged this phase, confirmed still
    correct on-device.
-   rendering geometry and classification geometry are now explicitly
    separate for this prototype: classification (`geoBoundaries`, whether a
    discovery counts as "in France") is untouched and lives in
    `core-discovery-engine`; the new OSM rendering polygon lives in
    `feature-map`, carries no classification meaning (no area id, no
    component index), and only ever changes what shape an *already-visited*
    mainland draws as.
-   Android resource packaging/loading of a `feature-map`
    (`com.android.library`) module's `src/main/resources` JVM-style
    resource, via plain classloader `getResourceAsStream` (the same
    technique `core-discovery-engine` already used for its own
    classification resource) — previously an open risk, now **physically
    validated**: the real Samsung build loaded and rendered the bundled
    polygon correctly.
-   Corsica and French Guiana are unaffected — still render their own
    `geoBoundaries` component geometry, confirmed on-device.
-   H3 rendering and the current-position marker are unaffected, confirmed
    on-device.

**Known, documented, accepted minor limitations (not redesigned this
round):**

-   `queryRenderedFeatures` click-hit-testing on the country-overlay fill
    layer can theoretically still register a hit on a visually
    water-masked part of the underlying polygon (the mask is a *visual*
    layer-order trick, not a geometric clip) — no problem observed in
    physical testing; not redesigned.
-   the click/focus camera-fit (`CountryOverlayCameraFit.kt`) still fits to
    the *classification* component's bounds, not the new rendering
    polygon's own bounds — assessed PASS for this prototype (no reported
    navigation regression); not redesigned.
-   at far country/world zoom, the visited-country orange fill is
    currently considered visually too subtle — a real, tracked UX gap
    (opacity/color unchanged this round; a future round should
    increase visual prominence so visited countries read as immediately
    distinguishable from unvisited ones).
-   the current OpenFreeMap Liberty basemap style is provisional and
    considered visually busy — a future basemap/style redesign remains
    open, not attempted here.

**DECIDED / NOT IMPLEMENTED — target architecture for later rounds, not
built now:**

-   **Worldwide generalization.** The France OSM-relation-fill approach is
    a validated *prototype*, not a worldwide-final architecture. The
    target shape for every country:
    canonical discovery truth (H3) → geographic visited classification
    (offline administrative/reference geometry) → visual administrative
    rendering (basemap-compatible geometry) → coastline
    (basemap-compatible water geometry/masking where appropriate) →
    visible administrative boundaries (basemap-compatible boundary
    geometry where available). No worldwide data or pipeline is built yet.
-   **Multi-level geographic navigation.** The product must eventually
    support World → Country → Administrative Level 1 → Administrative
    Level 2 → real discovered local areas → precise H3 (e.g. for France:
    World → France → Nouvelle-Aquitaine → visited département(s) → actual
    discovered areas → H3). Only actually-visited administrative areas
    receive visited styling at each level; administrative hierarchy is
    country-aware since structures differ worldwide. Not implemented this
    round — see `docs/ai-context/OPEN_QUESTIONS.md`'s existing "hybrid
    geographic ingestion and per-country hierarchy mapping" entry.
-   **Orange means VISITED/PRESENCE, never "fully explored," "100%
    completed," or "full geographic coverage."** Exact exploration
    percentage remains derived exclusively from canonical H3 discovery
    data — this rule is unchanged and must be preserved by any future
    admin-hierarchy work.

## 18. Constraints for Codex / Claude Code

1.  Treat `7a906a9` as the historical Phase 1--4 code baseline (§6--§16)
    and `d222fd7` as the current pushed `HEAD` of `main` (documentation
    only on top of `7a906a9`, no further code change). Treat §17's
    Map/MapLibre increment as uncommitted working-tree state layered on
    top of `d222fd7`, not yet part of either baseline — verify with
    `git log`/`git rev-parse HEAD` rather than assuming this stays
    current.
2.  Inspect existing code before replacing architecture.
3.  Do not rewrite working phase 1--4 behavior without a concrete
    reason.
4.  Keep H3 resolution 12 unless a deliberate versioned decision changes
    it.
5.  Never persist raw GPS history by default.
6.  Never certify discoveries solely on the client.
7.  Preserve `OBSERVED + NON_CERTIFIED` for current automatic tracking.
8.  Process every location in batched background results.
9.  Preserve per-location timestamps.
10. Preserve explicit background consent.
11. Re-check actual Android permissions before background registration.
12. Keep foreground/background lifecycle coordinated.
13. Respect Android force-stop semantics.
14. Optimize for low battery impact.
15. Do not add FGS, WorkManager GPS polling or battery exemption without
    newly justified requirements.
16. Reuse `SubmitDiscoveryObservation` and existing discovery-engine
    rules.
17. Preserve Room/data semantics unless a deliberate migration is
    designed.
18. Keep authentication concerns separate from discovery/location work.
19. Never place secrets, passwords, tokens, OAuth credentials, private
    keys or sensitive credentials in docs, commits, logs or generated
    code.
20. Run relevant module tests plus the full Android debug build before
    declaring a phase complete.
21. Physically validate changes affecting permissions, lifecycle,
    location delivery, reboot or other Android platform behavior.
22. Do not commit or push automatically unless explicitly instructed.

## 19. Stable baseline

Phase 1--4 code baseline (historical reference for §6--§16):

``` text
branch: main
commit: 7a906a9
commit message: feat: add background location tracking
```

`7a906a9` was pushed successfully to `main` and remains the reference
commit for the completed Phases 1--4. No code changed between `7a906a9`
and the current pushed `HEAD` below — only documentation/governance
commits landed in between.

Current pushed `HEAD` of `main` (documentation only):

``` text
branch: main
commit: d222fd7
commit message: docs: adopt MapLibre as map rendering engine
```

Verify this is still accurate with `git log`/`git rev-parse HEAD` rather
than assuming it stays current as the repository evolves.

Working tree beyond `d222fd7`: the Map/MapLibre implementation increment
described in §17 exists only in the current working tree. It is **not**
part of `d222fd7` or any pushed commit, and must not be assumed to exist
in a fresh checkout until repository history establishes a newer pushed
baseline that supersedes this section.
