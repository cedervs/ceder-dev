# PROJECT_STATUS.md

> This file mixes several distinct kinds of information, deliberately kept
> apart below:
>
> -   **Phases 1--4 (§6--§16, §18):** describe the approved project state
>     at the historical Phase 4 code baseline, commit `7a906a9` ---
>     `feat: add background location tracking`. That code state has not
>     changed since; do not assume phase 1--4 behavior differs from what
>     is described there.
> -   **Documentation/governance commits, then five stabilization commits,
>     landed on top of `7a906a9`:** `1e22ed4`, `ce7732d`, `7b9294d`,
>     `d222fd7` (`docs: adopt MapLibre as map rendering engine`), then
>     `b2ec52e`/`30c0320`/`8c477f2` (the pre-stabilization Map/discovery
>     visualization work §17 up to Phase F/G3 already describes), then the
>     five-commit stabilization series: `531fa354` (chore: protect private
>     benchmark and calibration artifacts), `43594f96` (feat: add France
>     administrative hierarchy and derived discovery corridor --- lands
>     §17's Phase H section below), `5d98269e` (feat: add debug-only
>     tracking calibration diagnostics), `fcc8de9a` (feat: add trajectory
>     buffering and reconstruction safety foundation --- lands §20/§21
>     below), and `d54f0c77` (test: add reproducible map-matching
>     benchmark infrastructure). `d54f0c77` is the current, pushed `HEAD`
>     of `main` as of this document's last update (verify with `git log`/
>     `git rev-parse HEAD` rather than assuming this stays true later).
> -   **§17's Phase H/corridor, §20, and §21 are now committed and pushed**
>     (see the commit mapping immediately above and §19's own detail) ---
>     any "(uncommitted)"/"still uncommitted" wording remaining inside
>     those sections describes what was true at the historical moment it
>     was written (mid-review-round), not current Git state; §19 is the
>     authoritative current-baseline record.
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
not infer that the final discovery map is already implemented at the
`7a906a9` Phase 1--4 baseline this section describes. A Map increment
integrating MapLibre Native is now committed and pushed (see §19) — see
§17 for its actual scope and remaining pending on-device validation.

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

### Google Sign-In fails after account selection

Physically observed on the Samsung SM-G998U1 during Phase FH-1's physical
validation rounds (see §17's own "second physical validation" entry):
Google Sign-In fails after the account picker's account selection step.
Not yet diagnosed or investigated -- tracked here as a known, separate,
open issue. Do not modify authentication to address this without first
inspecting the actual current code in `core-auth`/`core-network` per §7
above.

### Map tab re-entry performance

Physically observed noticeable delay when leaving and returning to the
Map tab. Not yet precisely diagnosed (no profiling/measurement performed
yet) -- do not claim this is fixed, and do not optimize speculatively
without first measuring where the actual delay is.

## 17. Map/discovery visualization layer --- first increment (now committed and pushed)

**Status: PARTIALLY IMPLEMENTED.** This section's own sub-entries are now
committed and pushed as part of the history described in §19 (this first
increment's own pipeline shape landed via `b2ec52e`/`30c0320`/`8c477f2`,
predating the stabilization series; Phase H hierarchy/corridor further
below in this section landed via `43594f96`). What follows describes
each increment's own historical state at the time it was written —
"uncommitted"/"not yet committed" language deeper in this section reflects
that increment's state mid-development, not current Git status; see §19
for the authoritative current baseline.

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
    country-aware since structures differ worldwide. **Country → Region →
    Department is now IMPLEMENTED for France with full metropolitan
    coverage (all 13 regions, all 96 departments; see Phase H and Phase
    FH-1 below) — not implemented: below Department (local discovered
    areas as their own navigable level), overseas France, and every other
    country** — see `docs/ai-context/OPEN_QUESTIONS.md`'s existing "hybrid
    geographic ingestion and per-country hierarchy mapping" entry.
-   **Orange means VISITED/PRESENCE, never "fully explored," "100%
    completed," or "full geographic coverage."** Exact exploration
    percentage remains derived exclusively from canonical H3 discovery
    data — this rule is unchanged and must be preserved by any future
    admin-hierarchy work.

### Phase H — France Country → Region (ADMIN_1) → Department (ADMIN_2) hierarchy, IMPLEMENTED, NOT YET PHYSICALLY VALIDATED

**Status: IMPLEMENTED, on top of Phase F/G3 above, still uncommitted. NOT physically validated on
device** — validated only via the manual `kotlinc`/JBR JUnit toolchain (Gradle's standing
`Unable to establish loopback connection` failure persisted; attempted once, not retried, per
established practice).
Real device validation (Samsung) is still required before this can be marked physically validated.

**IMPLEMENTED:**

-   Domain model extended, not replaced: `ADMIN_1`/`ADMIN_2` `GeographicArea`s reuse the existing
    generic type/`parentId` link exactly as designed (`GeographicArea.kt`'s own doc comment) — no
    `FranceRegion`/`FranceDepartment` types were introduced.
-   `core-discovery-engine/src/main/resources/geo/france/{regions,departments}/*.json`: 13
    metropolitan regions (all of France's regions except overseas) + Nouvelle-Aquitaine's 12
    departments, generated from real OSM administrative relations via
    `polygons.openstreetmap.fr/get_geojson.py` (the same proven mechanism Phase F/G3 already used),
    by a new `tools/geo/GenerateFranceAdministrativeReference.kt` generator — see
    `tools/geo/README.md`'s new "France Region/Department reference data" section for every relation
    ID, ISO 3166-2/INSEE code, and the licensing/provenance text.
-   Classification: a new `ClassifyDiscoveredCellsByGeographicAreas` (generic over any list of
    sibling `GeographicArea`s, single pass per cell) drives `MapReadState.franceAdmin1Statuses`/
    `franceAdmin2Statuses`, wired through `ObserveMapReadState`/`AppContainer` from the exact same
    validated-cell snapshot as Country-level and fine-H3 rendering — one Room subscription, still.
    Presence at a deeper level is never separately marked at shallower levels; each level's own
    `visited` is independently derived from the same canonical cells against its own real (nested)
    geometry, which is what makes "Haute-Vienne visited implies Nouvelle-Aquitaine and France visited"
    true without any propagation code.
-   Rendering: new `feature-map/.../AdministrativeOverlayRendering.kt` — separate source/layers per
    level (never touches the Country overlay's own source/layers), one Feature per visited area (its
    full `MultiPolygon`, including any real islands, never decomposed per-component), lighter orange
    per level (Country `#FF8C00` unchanged; Region `#FFA733`; Department `#FFC670`), provisional
    zoom bands for a progressive drill-down handoff (Region 3–10, Department 6–13, Country unchanged
    0–7).
-   Navigation: new `AdministrativeFocusStateHolder` (a small, additive Region/Department focus
    stack, max depth 2) composed *beside*, never merged into, the existing single-slot
    `CountryFocusStateHolder`. The underlying Country-component functions
    (`resolveClickedCountryComponent`/`nextCountryFocusReturnCamera`) are themselves unchanged, but
    which *click-eligibility state* gets to call them has changed twice across two correction rounds
    — see "Codex review correction rounds" below for the exact current behavior (Region takes
    priority; Country-component switching is a fallback, not the byte-for-byte-identical first
    attempt it was before this feature existed). Click resolution is **hierarchy-aware and
    parent-scoped** (`eligibleClickLevels`/`resolveGeographicClick` in
    `AdministrativeAreaNavigation.kt`): only the level(s) actually eligible from the *current* focus
    state are ever attempted, and candidates are filtered by their own real `parentId` against the
    actually-focused ancestor, so an ancestor can never intercept a descendant's click and a
    Region/Department belonging to an unfocused parent can never resolve as if it belonged to the
    focused one. Back is unified (`goBackOneGeographicFocusLevel`): pops one Region/Department frame
    if present, otherwise falls through to the existing `exitCountryFocus()`.
    `MapNavigationStateResetter` now also clears the new stack on a real session transition.
-   `MapScreen`/`DiscoveryMapView` pass `visitedAdmin1Areas`/`visitedAdmin2Areas` (already filtered
    to `visited == true`, mirroring the existing `visitedFranceComponents` convention) through.

**Scope, deliberately not "full France" this round (documented data-population limitation, not an
architecture limit):**

-   **Only Nouvelle-Aquitaine has Department-level (`ADMIN_2`) data.** All 13 metropolitan regions
    have Region-level (`ADMIN_1`) data. Populating the remaining 12 regions' departments is a
    mechanical follow-up (rerun the same generator against more OSM relations), not a code change —
    `ClassifyDiscoveredCellsByGeographicAreas`/the rendering/navigation code already work generically
    over however many areas are loaded.
-   **French overseas regions/departments are not included** (Guadeloupe, Martinique, Guyane, La
    Réunion, Mayotte) — French Guiana keeps its existing Country-level-component-only representation;
    it has no `ADMIN_1`/`ADMIN_2` entry.
-   **Corse is included as a region** (`admin1:FR-20R`) with no departments loaded under it this round.
-   Regions/departments with real coastal islands (e.g. Bretagne, Charente-Maritime) render/navigate
    as ONE shape covering every component together — a deliberate scoping decision distinct from
    Country level's own per-component (mainland/Corsica/Guiana) navigation; `GeographicAreaComponent`
    still works unchanged on any of this round's areas, so per-island navigation remains available to
    a future round with zero data regeneration.

### Phase H, Codex review correction rounds

Two rounds of independent Codex review against the uncommitted Phase H implementation, each fully
addressed before the next began. **Status after both: FIXED, on top of Phase H above, still
uncommitted, still NOT physically validated on device.**

#### Round 1 — 4 correctness fixes + 2 minor items

-   **Hierarchy-aware click eligibility (was Blocking).** The previous click handler tried
    Country resolution unconditionally first, then Department, then Region — an ancestor (Country)
    could intercept a descendant's click (e.g. Region) once both overlays were simultaneously
    interactive. Fixed with `eligibleClickLevels(currentFocusLevel)`, a pure function returning
    exactly the level(s) eligible to be clicked next given the current focus state. **Superseded by
    Round 2 below** — the exact table this round shipped (`COUNTRY -> [ADMIN_1]` only, permanently
    removing Country-component sibling switching while Country-focused) was itself corrected in
    Round 2 after Codex flagged it as an unintended behavior change; see Round 2's own entry for the
    current, real table.
-   **Index-independent admin focus stack (was Blocking).** `AdminFocusFrame` now carries its own
    real `GeographicAreaType` (`ADMIN_1`/`ADMIN_2`), never inferred from stack position — the
    previous implementation assumed "index 0 is always Region, index 1 is always Department," which
    a direct Department selection from an empty stack (storing Department at index 0) could corrupt
    on a second Department tap. `nextAdminFocusStack` now looks up existing frames by type everywhere.
-   **Parent hierarchy validation (was Important).** New `validateGeographicAreaHierarchy` (generic,
    not France-specific — no `FranceRegion`/`FranceDepartment` rules) checks, at the loaded-set
    level (never inside a single-file parser, which cannot know about other files): unique ids,
    `COUNTRY` areas have no parent, `ADMIN_1`'s parent resolves and is a `COUNTRY`, `ADMIN_2`'s
    parent resolves and is an `ADMIN_1`, every non-null `parentId` resolves to something real.
    `loadFranceAdministrativeAreas` now takes the France `COUNTRY` area as a parameter and validates
    the combined set against it.
-   **Child → ancestor presence invariant (was Important).** Country classification uses
    `geoBoundaries` geometry; Region/Department classification uses OpenStreetMap geometry — the two
    can genuinely disagree by a few meters near a shared border, which could otherwise leave a
    visited child's own ancestor falsely unvisited. New `promoteAncestorPresence` (pure, generic,
    reusable for any adjacent level pair) unions each parent's raw classification with its real
    children's presence — **child → parent only, never parent → child**: a visited France does not
    mark any region visited, and a visited region does not mark any department visited. Wired into
    `ObserveMapReadState` for both Department→Region and Region→Country. Never persisted — a
    read-time-only correction recomputed fresh on every emission.
-   **Classification bounds prefilter (Minor).** `GeographicBounds.contains` (antimeridian-safe, in
    `GeographicGeometry.kt`) added as a cheap prefilter before the real point-in-polygon test in
    `ClassifyDiscoveredCellsByGeographicAreas` — a safe narrowing only, documented as insufficient on
    its own before a genuine Europe/worldwide rollout (would need a real spatial index instead of
    this linear per-area bounds scan).
-   **Stale KDoc (Minor).** `MapReadState`'s own doc comment incorrectly claimed `franceComponents`
    was "currently unconsumed by rendering" — `MapScreen` has always consumed it. Corrected.

**Round 1 tests:** `PromoteAncestorPresenceTest` (including the exact Codex-specified synthetic
scenario: child geometry contains a discovered cell, parent's own raw classification geometry does
not, ancestor still resolves VISITED), `GeographicAreaHierarchyValidationTest` (every
malformed-hierarchy case Codex listed, plus the real bundled France set proven backward-compatible),
new bounds-prefilter coverage in `GeographicGeometryTest`, and an `AdministrativeAreaNavigationTest`
rewrite covering the required regression list at that time, plus the hierarchy-aware resolution
sequence itself driven through `resolveGeographicClick`. `ObserveMapReadStateTest` gained end-to-end
tests proving the promotion invariant through the real `ObserveMapReadState` pipeline.

**`DiscoveryMapView.kt`/`MapScreen.kt` compile status — corrected from the round-1-prior report.**
Round 1 assembled a real (if partial) Jetpack Compose classpath in the manual toolchain (the bundled
`compose-compiler-plugin.jar`, extracted `classes.jar` from the relevant Compose/AndroidX AARs,
`-jvm-target 11`, plus a minimal 3-file subset of `core-location` — `LocationObservation.kt`/
`LocationPermissions.kt`/`LocationTestOutcome.kt`, the exact production types these two files
reference — compiled alongside them) and **successfully compiled `DiscoveryMapView.kt`, `MapScreen.kt`,
`CurrentPositionRendering.kt`, and `MapViewLifecycleController.kt` with zero errors** — this classpath
was reused and re-verified in Round 2 too (see below). `AppContainer.kt` was **not** compiled in
either round (its own dependency surface — Room, Tink, Google Identity/Credentials, Ktor — is a
disproportionate expansion for what remains a small, mechanically-verified edit) and remains
code-reviewed only.

#### Round 2 — Codex re-review, 2 remaining findings

Codex re-reviewed Round 1's fix and found it incomplete in two ways: click eligibility was
hierarchy-aware by *type* but not *parent-scoped* (a Region/Department could still resolve as a
child of the wrong, unfocused ancestor purely from geographic overlap), and the child→ancestor
presence promotion fixed the aggregate whole-Country status but not the per-COMPONENT statuses that
actually drive Country-level rendering/navigation. A third, minor finding: Round 1's strict
eligibility change had an unintended side effect — it silently removed the pre-existing ability to
switch between a fragmented country's own components (mainland ↔ Corsica ↔ French Guiana) while
Country-focused, contradicting other documentation's "Country behavior is unchanged" claims. All
three fixed:

-   **Parent-scoped click resolution.** `resolveGeographicClick` now filters every Region/Department
    candidate list by its own real `GeographicArea.parentId` against the actually-focused ancestor's
    real id, BEFORE testing hit features — never relying on geographic overlap alone. Concretely:
    `ADMIN_1` candidates are filtered to `parentId == focusedCountryId`; `ADMIN_2` candidates to
    `parentId == focusedAdmin1Id`. `GeographicClickContext` gained `focusedCountryId`/
    `focusedAdmin1Id`/`focusedAdmin2Id` — a generic, id-based representation of "where in the
    hierarchy focus currently is," not a France-specific concept, computed fresh per click in
    `DiscoveryMapView` from `CountryFocusStateHolder`/`AdministrativeFocusStateHolder`'s own state. A
    Department candidate that fails the parent-scoped filter simply isn't offered to
    `resolveClickedAdministrativeArea` at all for that level; the next eligible level (typically the
    Region layer, at the same point) is tried next — which is exactly what makes "tap a Department
    belonging to a different, unfocused Region" read as a **Region switch**, never a corrupted
    attachment. `nextAdminFocusStack` was changed to take the real, already-resolved `GeographicArea`
    (not a bare id string) specifically so it always knows the target's own real `parentId`, and now
    `require()`s (fails loudly, never silently) that an existing Region frame's id matches the
    target Department's `parentId` — a defense-in-depth internal-precondition check that should never
    actually fire through the real, now-parent-scoped click path.
-   **Country-component promotion, at the correct component.** New `promoteAncestorComponentPresence`
    (in `PromoteAncestorPresence.kt`) — the per-COMPONENT counterpart to Round 1's
    `promoteAncestorPresence`. Without it, `MapReadState.franceComponents` (which actually drives
    Country-level rendering/click-navigation, not just the aggregate `franceVisitedStatus`) could stay
    entirely unvisited even when the aggregate Country and a real visited Region both correctly read
    `visited == true` — France would be logically visited yet render nothing and accept no clicks.
    The fix promotes each visited Region/Department into the ONE real Country component its own
    geometry actually falls within, tested via `PointInPolygonClassifier` against each component's
    polygon — **never all components, and never a hardcoded index** (no `if region ==
    "Nouvelle-Aquitaine" then componentIndex = 0`); a region matching no loaded component promotes
    nothing. Certified/non-certified presence are promoted independently at this level too, exactly
    like Round 1's aggregate version. Wired into `ObserveMapReadState` immediately after
    `franceComponents`' own raw classification. **Round 2's own first implementation of the
    representative point used the raw arithmetic average of the region's largest polygon's outer-ring
    vertices, unverified — a real defect a further Codex review caught (not a guaranteed interior
    point for a concave polygon, a polygon with a hole, or an antimeridian-crossing ring) and Round 3
    below replaced.**
-   **Country-component sibling fallback restored (Minor — a real, documented behavior change, not
    silently reverted).** `eligibleClickLevels(COUNTRY)` is now `[ADMIN_1, COUNTRY]` (was `[ADMIN_1]`
    only in Round 1): Region resolution is still always tried FIRST while Country-focused, so it can
    never be intercepted by the Country-component fallback — but if Region resolution finds nothing
    at all, Country-component resolution is now tried as a fallback, restoring mainland ↔ Corsica ↔
    French Guiana switching while Country-focused. This is the accurate current behavior; any earlier
    text in this document claiming Country-component behavior is "byte-for-byte unchanged" refers only
    to the underlying `resolveClickedCountryComponent`/`nextCountryFocusReturnCamera` functions
    themselves (genuinely untouched across both rounds), not to which click-eligibility state gets to
    call them.

**Round 2 tests (real, run via the manual toolchain — `core-discovery-engine`: 209/209 passed;
`feature-map`: 159/159 passed; same `CurrentPositionRenderingTest`/`MapViewLifecycleControllerTest`
exclusion as Round 1, for the same reason):** new `PromoteAncestorComponentPresenceTest` (including
Codex's own exact synthetic scenario: child/admin geometry contains a discovered cell, the parent
Country's own raw component geometry does not, exactly one real component is promoted, an unrelated
component stays unvisited, certified/non-certified promoted independently); `ObserveMapReadStateTest`
gained an assertion that a Limoges discovery also visits the correct France *component*, not just the
aggregate status. `AdministrativeAreaNavigationTest` gained the full Codex-specified parent-scoping
regression list (Region A vs. a Department whose real parent is Region B; a focused Department vs. a
Department in a different Region; a Region's own valid child Department; sibling Region switching
with no stale child; sibling Department reselection within the same Region; a Department with a
malformed/null `parentId`) plus the restored Country-component-fallback behavior (fallback fires only
after a Region miss; a valid Region hit always still wins over the fallback) — all driven through the
real `resolveGeographicClick`/`nextAdminFocusStack` functions `DiscoveryMapView` itself calls, not
isolated helpers. `DiscoveryMapView.kt`/`MapScreen.kt` were re-compiled successfully with the new
signatures (`nextAdminFocusStack(stack, camera, targetArea: GeographicArea)`,
`GeographicClickContext`'s three new `focused*Id` fields) using the same manual Compose classpath
Round 1 assembled.

#### Round 3 — Codex re-review, 1 remaining defect: unsafe representative-point geometry

Codex's latest review passed everything from Rounds 1–2 (parent-scoped click navigation, Country
fallback priority, focus-stack invariants, hierarchy validation, aggregate child→ancestor promotion,
trust-state behavior, rendering/navigation wiring) and found exactly one remaining Important defect:
Round 2's own `representativeAreaPoint` computed the plain arithmetic average of a region's largest
polygon's outer-ring vertices and trusted it, unverified, as "a point inside the region." That is not
a guaranteed interior point — it can land outside a concave polygon, inside a hole, or (for a naively
longitude-averaged antimeridian-crossing ring) somewhere on the wrong side of the globe entirely.
Fixed:

-   **New `findVerifiedInteriorPoint` (in `InteriorPointFinder.kt`), replacing `representativeAreaPoint`
    entirely.** Never trusts a candidate point without checking: for each polygon component of the
    area (searched in order — **never assumes the largest, `polygons[0]`, is correct**, per Codex's
    own explicit instruction), three increasingly-robust candidate strategies are tried in order —
    (1) the polygon's own antimeridian-safe bounding-box midpoint, (2) the true signed-area polygon
    centroid (the standard formula, genuinely different from and more robust than a vertex average),
    (3) several antimeridian-agnostic horizontal-scanline interior-span midpoints (the robust fallback
    for concave shapes and shapes with holes) — and every single candidate, from every strategy, is
    verified against `PointInPolygonClassifier` (the same authoritative containment test used
    everywhere else in this module) before being trusted. If no candidate for any polygon component
    verifies, the function returns `null` — **explicit failure, never a guess** —
    `promoteAncestorComponentPresence` treats a `null` result as "that child contributes no promotion
    for any component," never a crash, never a wrong-but-plausible fallback point.
-   **`promoteAncestorComponentPresence` updated accordingly** — computes each visited child's
    verified interior point once per call (not once per component), and a child whose point is `null`
    simply matches no component. Semantics otherwise unchanged: exactly one real component promoted
    per matching child, never all components, never a hardcoded index/name; certified/non-certified
    presence still promoted independently.
-   **The "one Admin1 maps to exactly one Country component" property is now a real-artifact-verified
    fact about this app's own bundled data, not an assumption.** `InteriorPointFinderRealDataTest`
    loads the real bundled France reference and, for all 13 metropolitan `ADMIN_1` regions, proves:
    a verified interior point exists; it is genuinely inside that region's own real geometry; it
    matches exactly one real France Country component (never zero, never more than one); Corse maps
    to the Corsica component and the other 12 map to the mainland component. **This is a verified
    France-prototype property, not a worldwide guarantee** — a future genuinely fragmented `ADMIN_1`
    (a region itself split across two disjoint Country components, which does not occur among
    France's own current regions) would need explicit component-ancestry metadata or
    discovery-location-aware evidence that does not exist yet; nothing in this code silently assumes
    it already works for that case.

**Round 3 tests (real, run via the manual toolchain — `core-discovery-engine`: 223/223 passed; no
`feature-map` changes this round, so its own suite was not re-run — Round 2's 159/159 stands, and a
grep confirms no `feature-map` file ever referenced the removed `representativeAreaPoint`):** new
`InteriorPointFinderTest` (convex polygon; a strongly concave/crescent polygon where the naive vertex
average genuinely falls outside — confirmed as a test premise, not merely asserted; a polygon with a
hole where the naive centroid/bbox-center genuinely falls inside the hole — the search must move past
it; an antimeridian-crossing polygon; a fragmented `MultiPolygon` where the first, largest component
is degenerate and a later, smaller one is valid; a fully degenerate/collinear polygon that must return
`null`, never a guess; determinism across repeated calls) and `InteriorPointFinderRealDataTest` (the
real-artifact regression above). `PromoteAncestorComponentPresenceTest` gained a null-safe-handling
test (a degenerate child promotes nothing) and a genuine classification-driven end-to-end fixture (a
real discovered cell classified via `ClassifyDiscoveredCellsByGeographicArea` into a real region,
whose real, derived — not hand-constructed — `visited` status then correctly promotes the matching
Country component), per Codex's own preference for exercising the real
discovery→classification→promotion path over hand-built statuses alone.

**Not implemented this round (see `docs/ai-context/`'s own status-label conventions):** worldwide/
Europe generalization beyond France; nationwide department coverage beyond Nouvelle-Aquitaine;
overseas French administrative areas; Admin3/communes; local discovery-area aggregation; "fully
explored"/percentage semantics at Region/Department level (VISITED/PRESENCE only, unchanged rule);
final visual identity for the three-level orange hierarchy (values above are provisional, same
`PRODUCT CALIBRATION REQUIRED` status as the existing Country-level constants); a real spatial index
for classification (documented as required before a genuine Europe/worldwide rollout, not attempted
this round beyond the small bounds prefilter above); worldwide fragmented-`ADMIN_1`-to-multiple-
Country-components support (verified only for France's own current, non-fragmented regions — see
Round 3's own entry above).

#### Round 4 — physical validation FAILED, corrections in progress: selection-relative color hierarchy + ancestor-selection camera bug

**Physical validation status: FAILED / CORRECTIONS IN PROGRESS — hierarchy is NOT yet marked
physically validated.** Testing on the Samsung device surfaced two problems against Rounds 1–3's own
(logically correct, Codex-passed) implementation: (1) the fixed per-level orange rule ("Country
darkest, Region medium, Department lightest, permanently") did not match the approved reference
design — the currently-selected level needs to read as the lightest/richest orange, not whichever
level happens to be Country; (2) a real camera bug — after drilling into a Region or Department and
then manually zooming out, tapping France changed nothing, because Country was never even eligible
to resolve a click once focus had moved past it.

**1. Selection-relative color hierarchy, replacing the fixed per-level rule.** New
`GeographicHierarchyStyling.kt`: `GeographicAreaStyleRole` (`SELECTED` / `DIRECT_SUBLEVEL` /
`ANCESTOR_CONTEXT`) and the pure `resolveGeographicAreaStyleRole(areaType, areaId, areaParentId,
selection)` decision — depth-relative to whatever `GeographicFocusSelection` names as currently
selected (`null`/`null` = the implicit World view, where Country alone reads as the next-selectable
`DIRECT_SUBLEVEL` tier). `DIRECT_SUBLEVEL` additionally requires genuine parentage (`areaParentId ==
selection.selectedId`), not depth alone — an unrelated sibling region's own department never
misreads as "direct sublevel of the selected region" just because both are one level below Country.
Every rendered Country/Region/Department `Feature` (`CountryOverlayRendering.kt`,
`AdministrativeOverlayRendering.kt`) is now tagged with this role (`GEOGRAPHIC_STYLE_ROLE_PROPERTY`),
and the FillLayer/LineLayer paint properties read it via one shared `Expression.match` (built once at
layer-creation time; a focus change only ever needs a fresh `FeatureCollection` with updated role
tags, never a new layer). `DiscoveryMapView`'s visited-data `LaunchedEffect` is now additionally keyed
on the current focus state (`adminFocusStack`/`countryFocusReturnCamera`), so a pure selection change
(no new discovery) still re-colors. The basemap-aligned mainland-France border line
(`applyBasemapAlignedFranceBorder`) has no per-feature source to tag (it sources from the basemap's
own vector tiles), so its color is resolved once per call from the same `GeographicFocusSelection` and
applied as a literal `lineColor`, exactly like its existing `visibility` property already was.
Richer, more opaque starting palette per the physical review's reference image:
`SELECTED = #FFA23A`, `DIRECT_SUBLEVEL = #C96A16`, `ANCESTOR_CONTEXT = #7A3D16` (fill opacity
0.18 → 0.45, outline 0.55 → 0.85) — **`PRODUCT CALIBRATION REQUIRED`, starting values, not final art
direction**, same status as every zoom-band constant already in this file. Discovered H3 cell
rendering (Certified/Non-certified blue/amber) is deliberately **untouched** by this rework: the
round's own instruction text described H3 as "its own distinct vivid discovery orange," which would
mean discarding the already-validated Certified/Non-certified color distinction section 9's own
"preserve existing validated work" list explicitly requires keeping — an unresolved tension between
two instructions in the same round, resolved conservatively (preserve validated behavior) rather than
silently picked either way. Flagged here as a genuine open question, not decided.

**2. Ancestor-selection / camera-refocus fix.** Root cause of the physical camera bug: `resolveGeographicClick`'s eligibility table (`eligibleClickLevels`) never included `COUNTRY` as a
reachable resolution once focus had moved to `ADMIN_1`/`ADMIN_2` — a tap on France while Region- or
Department-focused simply never resolved to anything, at any zoom. Fixed:
`eligibleClickLevels(ADMIN_1)` now tries `[ADMIN_2, ADMIN_1, COUNTRY]` (added the `COUNTRY` fallback)
and `eligibleClickLevels(ADMIN_2)` now tries `[ADMIN_2, ADMIN_1, COUNTRY]` (added BOTH `ADMIN_1` and
`COUNTRY` — selecting the parent Region directly from Department depth, without Back-ing out first, is
also now possible). Ancestor levels are always tried **last**, after every more specific level, so a
genuinely eligible descendant hit can never be shadowed by an ancestor fallback. Second, independent
fix: the click handler used to call `enterOrKeepCountryFocus`/`enterOrKeepAdminFocus` inline per
resolution branch, and the `CountryComponent` branch never cleared `adminFocusStack` — so even once
Country became reachable, selecting it while a Region/Department was focused would leave a stale
child frame behind. Replaced with one new pure decision function, `nextGeographicSelectionOutcome`
(`AdministrativeAreaNavigation.kt`): given the resolved click and the current focus state, it returns
the next `countryFocusReturnCamera`, the next `adminFocusStack` (always `emptyList()` for a
`CountryComponent` resolution — ancestor selection now unconditionally clears descendant focus — and
`nextAdminFocusStack`'s own already-validated collapse-to-one-frame behavior for a `Region`
resolution), and the camera target bounds — applied unconditionally on every successful click,
regardless of any manual pan/zoom beforehand. `DiscoveryMapView`'s three near-duplicate click branches
collapsed into one `when`-free dispatch through this single function.

**Round 4 tests (real, run via the manual toolchain — `feature-map`: 217/217 passed; `core-discovery-
engine` untouched this round, 223/223 from Round 3 stands):** `eligibleClickLevels` tests updated for
the new ancestor-fallback table; new `resolveGeographicClick` regression tests (Country reachable from
Region and Department focus; Region reachable directly from Department focus; an ancestor fallback
never shadows a genuinely eligible descendant hit); new `nextGeographicSelectionOutcome` tests
covering all 7 of this round's required camera scenarios (World→France, France→Region, Region→
Department, Department-focused-then-France-selected-with-admin-focus-cleared, Department-focused-
then-parent-Region-selected-with-Department-cleared, Back still restores the exact stored camera —
already covered by the existing `adminFocusBack` tests, and manual camera movement never overwrites an
existing return camera). New `GeographicHierarchyStylingTest.kt`: pure, MapLibre-independent coverage
of `resolveGeographicAreaStyleRole` for exactly the three required scenarios (France selected: Country
SELECTED/Region DIRECT_SUBLEVEL; Region selected: Region SELECTED/Department DIRECT_SUBLEVEL/Country
ANCESTOR_CONTEXT; Department selected: Department SELECTED/Region and Country both ANCESTOR_CONTEXT)
plus parent-scoping and no-focus edge cases; `geographicAreaStyleRoleColorExpression()` itself is
deliberately NOT unit-tested here — it calls `android.graphics.Color.parseColor`, a real Android
framework method with a `Stub!`-throwing body on this manual toolchain's stub `android.jar` (confirmed
empirically), consistent with this whole module's established pattern of keeping real Style/Layer/
Color construction behind an untested seam rather than unit-testing it directly.

**Not implemented this round:** final art direction / calibrated palette and opacity values (still
provisional); discovered-H3 color scheme unchanged pending resolution of the H3-orange-vs-preserve-
Certified-distinction tension flagged above; per-region/department zoom-band recalibration (out of
scope, not requested); worldwide/deeper-hierarchy generalization beyond what Round 3 already scoped.
**Physical validation must be re-run on the Samsung device before this round's fixes can be marked
validated** — nothing here claims device confirmation, only manual-toolchain compilation and test
evidence.

#### Round 5 — physical validation PARTIAL PASS, one correction: Admin2 not immediately visible after Admin1 selection

**Physical validation result for Round 4's own fixes: PARTIAL PASS.** Confirmed physically correct:
World→France, France→Region, Region→Department (once the Department overlay is actually visible),
ancestor selection after manual pan/zoom, descendant-focus clearing, and the relative orange hierarchy
in principle. Palette remains deliberately uncalibrated pending the future basemap redesign (see
`docs/ai-context/OPEN_QUESTIONS.md`'s "Final Map art direction" entry, extended this round with the
specific future-basemap notes the physical test surfaced — darker/simpler basemap, discovered/
selected areas popping, progressive label reveal, likely Dark/Light modes — **PLANNED / NOT
IMPLEMENTED**, explicitly not designed or built this round). One real defect: after selecting
Nouvelle-Aquitaine, Haute-Vienne (a visited Department) was not immediately visible — the camera
correctly fit the Region, but the Department overlay only appeared after an extra manual zoom-in.

**Root cause, verified against the real `Layer` API before changing anything** (`javap` against the
actual bundled MapLibre `android-sdk-opengl` AAR's `Layer.class`): `ADMIN2_OVERLAY_MIN_ZOOM` (6f) is a
single, generic, level-only zoom floor set once on the Department `FillLayer`/`LineLayer` at creation
time and never revisited — MapLibre hides a layer entirely below its own `minZoom` (confirmed:
`getMinZoom`/`setMinZoom` are ordinary, always-callable instance methods, not construction-only
values). `CameraUpdateFactory.newLatLngBounds(nouvelleAquitaine.bounds, ...)`'s own resulting fit zoom
— confirmed on the physical device — lands slightly below that floor for a Region the size of
Nouvelle-Aquitaine, so the Department layer stayed invisible until the user's own manual zoom pushed
past 6, even though the Region-fit camera had already landed exactly where Departments should already
be visible. The zoom-fade opacity expression was ruled out as a cause: `Expression.interpolate`
clamps to its first stop's value below that stop, so opacity was never the blocker — only the layer's
own `minZoom` property was.

**Fix: the Department layer's effective minimum zoom is now selection-relative, not a single
constant.** New `effectiveAdmin2MinZoom(selection)` (`AdministrativeOverlayRendering.kt`): once an
`ADMIN_1` (Region) — or its own `ADMIN_2` (Department) child, so the floor doesn't snap back up one
level deeper — is the actually-selected level, the floor drops to `ADMIN1_OVERLAY_MIN_ZOOM` (Region's
own render floor: a safe, non-arbitrary lower bound, not `0f`, since Departments can never usefully
appear before their own parent Region does). Whenever `COUNTRY` is selected or nothing is selected at
all (the World view), the ordinary `ADMIN2_OVERLAY_MIN_ZOOM` generic threshold is unchanged —
Departments do NOT become visible just because France itself is selected, satisfying this round's own
explicit "do not simply make Departments visible everywhere at World/Country scale" requirement.
`applyAdministrativeOverlayLevel` now re-applies this effective floor on every call (`style
.getLayerAs<FillLayer>(...)?.minZoom = minZoom`, mirroring `applyBasemapAlignedFranceBorder`'s own
established "re-apply a literal property on every call" pattern), so a pure focus change (no new
discovery data) still lowers/raises it — this reuses the exact same `DiscoveryMapView` `LaunchedEffect`
that Round 4 already keyed on `adminFocusStack`/`countryFocusReturnCamera`, no new wiring needed.
**Known, accepted limitation, documented rather than silently fixed**: this is a layer-level (not
per-feature) zoom gate — a visited-but-unrelated Department elsewhere in France also becomes visible
once any Region is selected, since `applyAdministrativeOverlay` still renders every visited Department
in one shared source/layer pair (a pre-existing Round 0 scoping decision). Fixing that would need
per-feature zoom gating, a materially larger change than this round's own physically-observed defect
(Admin2 invisibility) calls for.

**Round 5 tests (real, run via the manual toolchain — `feature-map`: 221/221 passed; `core-discovery-
engine` untouched, 223/223 from Round 3 stands):** new tests in `AdministrativeOverlayRenderingTest.kt`
covering exactly this round's required scenarios — Region (and Department) selected lowers the
effective floor below the generic threshold and keeps it lowered one level deeper; Country selected
does NOT lower it (no global Department visibility just because France is selected); no selection
(World view) leaves the generic threshold untouched (no new clutter). All pre-existing tests preserved
unchanged.

**Not implemented this round:** per-feature (parent-scoped) Department visibility filtering (documented
limitation above); palette/opacity calibration (explicitly deferred to the future basemap phase);
final basemap redesign (documented as PLANNED / NOT IMPLEMENTED in `OPEN_QUESTIONS.md`, not built).
**Physical validation must be re-run on the Samsung device before this specific fix is marked
validated** — nothing here claims device confirmation beyond the manual-toolchain evidence above.

#### Round 6 — Department-level derived first-discovery corridor, IMPLEMENTED, NOT PHYSICALLY VALIDATED

**This entry describes the CORRECTED implementation after a Codex review (CHANGES REQUIRED) found the
first implementation's evidence model insufficient and its own documentation overclaiming what the
data actually supports.** The corrected model, terminology, and evidence requirements below are what
is actually implemented; the original entry's own language ("connected by real chronology,"
"credibly-travelled run," same-instant ties "treated as continuous") is retracted — see the correction
notes inline below for exactly what changed and why.

**Data-model investigation (unchanged from the original round, still holds):** No ordered, timestamped,
per-fix sequence of coordinates is persisted anywhere in this app — raw GPS is deliberately transient by
design (`docs/ai-context/REJECTED_APPROACHES.md`'s "Persistent raw GPS history" entry: rejected for
privacy exposure; the only Room table, `discovered_cells`, "Never stores a raw latitude/longitude").
**No new persistence was added, this round or the correction round** — nothing here reopens the
rejected raw-GPS-history decision. The one real, already-persisted ordering signal is
`DiscoveredCell.firstDiscoveredAt` — genuinely coarse (cell-level, not GPS-fix-level), which the
correction round now takes far more seriously than the original implementation did (see below).

**What the corridor explicitly is NOT (the corrected, authoritative framing — see
`DiscoveredRoute.kt`'s own file-level doc comment for the full list):** not a GPS breadcrumb trail; not
the exact road/path travelled (every point is an H3 resolution-12 cell center, ~9-19 m across, never a
raw coordinate); not the complete journey (only *first discovery* is represented — a later revisit of
an already-known cell leaves no trace, so a corridor gap can mean "already discovered earlier," not
"never visited"); not proof that every connector was physically traversed. Preferred terminology:
**"derived first-discovery corridor"** or **"approximate first-discovery progression"** — never "travel
route," never "the route travelled."

**Correction 1 — same-timestamp cells (BLOCKING, fixed).** The original implementation treated a
same-instant tie between two distinct cells as automatically continuous, relying on the
`h3Index`-based sort tie-break as if it carried chronological meaning — it does not; batch-processed
cells commonly share an identical timestamp with genuinely unknown internal order. Fixed: a connection
now requires `dt` (the time gap) to be **strictly positive** — `dt <= 0` (a tie, or, defensively, a
negative gap) is rejected unconditionally, before any distance/structural check ever runs. New
adversarial tests (`DiscoveredRouteTest.kt`, letters B/C/D) prove three same-timestamp real H3
grid-neighbor cells produce **no** connected path in any input order, and that a same-timestamp pair a
huge distance apart is never connected either.

**Correction 2 — duplicate H3 identities (fixed).** `DiscoveredCellRepository`'s own persistence
identity is `(cell, trustStatus)` (see `DiscoveredCell`'s own doc comment) — the same physical H3 cell
can legitimately exist as both a `CERTIFIED` and a `NON_CERTIFIED` row. New
`consolidateBySpatialCell(discoveredCells)`: groups by the real `CanonicalCell` identity and collapses
every trust-status row for the same cell into one `(cell, timestamp)` pair, using the **earliest**
`firstDiscoveredAt` across all of that cell's own rows — documented as the deliberate conservative rule
("the cell was genuinely first reached, under *some* trust status, at that earlier time"). Never alters
any persisted `DiscoveredCell` row (letter H tests).

**Correction 3 — structural spatial continuity (IMPORTANT, fixed).** Time and implied average speed
alone are never sufficient evidence two first-discovered cells should be connected: a cell physically
crossed *between* two later first-discoveries, but itself already discovered on an earlier pass (a
genuine revisit), is invisible to `firstDiscoveredAt` chronology, and the old model would still draw a
corridor across that gap purely from plausible timing. Fixed: every candidate connection now also
requires real H3 grid-adjacency evidence via the existing `H3GridTraversal.pathBetween` (already used
by `ForegroundReconstructionScheduler`'s dormant reconstruction infrastructure, reused here rather than
duplicated) — `path.size - 1` (the real grid distance) must be at most
`ROUTE_MAX_GRID_DISTANCE_CALIBRATION_REQUIRED` (provisional `2`: direct neighbors, plus tolerance for
exactly one GPS-sampling-skipped cell, per `docs/ai-context/LOCATION_TRACKING.md`'s own Trip 3/4 field
cadence data — **not** chosen "to make the visualization prettier"). Time-gap (`maxGap`, unchanged 20
min) and implied-speed (`maxPlausibleSpeedMetersPerSecond`, unchanged 55 m/s) checks are retained
**only as additional rejection guards**, never described as proving continuity, and are checked after
the (cheaper) time-gap check but the speed check is computed only once `dt` is already known bounded,
making its own millisecond conversion overflow-safe by construction. `AndroidH3GridTraversal` is now
constructed **unconditionally** in `AppContainer` (previously only debug-gated for
`ForegroundTransitionDiagnostics`) — the corridor's own structural check needs it in every build. Real
H3 fixtures (not invented) prove: real grid-neighbors connect (letter A); a real distance-2 pair
connects (the default tolerance boundary); a real distance-3 pair does not; a temporally- and
speed-plausible but structurally-distant real pair (grid distance 116) is still rejected (letter G — the
core proof that time+speed alone is insufficient); a revisit scenario (letter I) proves an old,
already-known cell's timestamp never bridges two later, structurally-unrelated new discoveries.

**Correction 4 — Department containment for the connecting chord, not just its endpoints (fixed).**
The original `clipRouteSegmentsToArea` only checked each individual point's own containment
(point-run filtering) — Codex correctly flagged that two points can each individually be inside a
concave Department, or inside on either side of a hole, while the straight chord connecting them
briefly leaves the polygon or crosses the hole. Fixed: the connecting chord between two already-kept
points is now independently sampled at 3 interior fractions (25/50/75%), and every sample must also
test inside the Department's geometry before the connection is kept — a conservative approximation
(**not** true Sutherland–Hodgman line/polygon clipping, a documented, deliberate scoping decision), but
strictly more conservative than endpoint-only containment. New tests (letters K/L) prove a concave
"crescent" Department and a Department with a hole both correctly reject a chord that would otherwise
cross outside/through them, while a genuinely-interior chord is still accepted.

**Correction 5 — node/line volume bounds (fixed).** The original per-segment node sampling
(`sampleRouteNodes`, unchanged) reduced density but never bounded the *total* across many segments.
New `sampleRouteNodesBounded(segments, maxTotalNodes)` (provisional
`ROUTE_MAX_NODES_PER_OVERLAY_CALIBRATION_REQUIRED = 250`) applies one further deterministic
uniform-stride reduction across the combined node set only if still over budget — a hard, absolute cap
regardless of first-discovery history size (letter N tests). Symmetrically, a very long single
`RouteSegment`'s own `LineString` point count is now bounded at the rendering layer only (never the
domain `RouteSegment` itself) via new `simplifyRouteSegmentForRendering(segment, maxPoints)`
(provisional `ROUTE_MAX_LINE_POINTS_PER_SEGMENT_CALIBRATION_REQUIRED = 500`, same uniform-stride
technique, documented as a conservative approximation rather than shape-preserving Douglas-Peucker
simplification).

**Architecture (`DiscoveredRoute.kt`, `core-discovery-engine`, pure domain logic, no MapLibre
dependency)**: `RoutePoint`/`RouteSegment` (constructor-enforced `>= 2` points) unchanged;
`consolidateBySpatialCell` (new, Correction 2); `deriveRouteSegments(discoveredCells, cellConverter,
gridTraversal, maxGap, maxGridDistance, maxPlausibleSpeedMetersPerSecond)` (now requires
`H3GridTraversal`, Correction 3, and rejects non-positive `dt` unconditionally, Correction 1);
`clipRouteSegmentsToArea(segments, area)` (chord-sampling, Correction 4); `sampleRouteNodes`/
`sampleRouteNodesBounded`/`simplifyRouteSegmentForRendering` (Correction 5). `MapReadState.routeSegments`
/ `ObserveMapReadState` (now takes `gridTraversal` too) unchanged in shape: globally-derived, not yet
Department-clipped, computed fresh from the same validated `validCells` snapshot every other field uses,
never persisted as a second discovery truth.

**Rendering** (`RouteOverlayRendering.kt`, `feature-map`) — layering, z-order, no-`minZoom`-gate
rationale, and read-only click-avoidance are unchanged from the original round (verified via `javap`
against the real `Style` class); `routeCoreFeatureCollection` now applies
`simplifyRouteSegmentForRendering` per segment before conversion, and `routeNodeFeatureCollection` now
calls `sampleRouteNodesBounded` (overlay-wide) instead of per-segment-only sampling.

**Provisional palette** (**PRODUCT CALIBRATION REQUIRED**, unchanged starting values): core `#2F9BFF`
(~2-3px, opacity 0.85), halo same hue (~6-9px, opacity 0.22), nodes `#2F9BFF` with a white stroke
(~3-5px radius, opacity 0.9). Orange remains hierarchy/discovery-area language; blue is reserved
exclusively for this corridor visualization.

**Certification**: unchanged — not fabricated. No sound Certified/Non-certified → corridor-segment
color mapping exists yet; every segment renders in the same single provisional blue regardless of trust
status, an explicit open product question. Existing H3 Certified/Non-certified rendering untouched.

**Performance**: unchanged principle (no expensive recomputation on camera movement, only on
data/selection change), now additionally hard-bounded (Correction 5) rather than only density-reduced.

**Round 6 correction-round tests (real, run via the manual toolchain — `core-discovery-engine`:
257/257 passed; `feature-map`: 229/229 passed):** `DiscoveredRouteTest.kt` rewritten with real H3
fixtures (captured via `H3Core.gridDisk`/`gridDistance`/`cellToLatLng`, the same discipline
`H3JavaGridTraversalTest` already established) covering the full adversarial letter list this
correction round requires: A (real neighbors connect; distance-2 tolerance boundary connects,
distance-3 does not), B/C (same-timestamp ties never connect, in any input order, regardless of
distance), D (zero/negative `dt` never connects), E (large time gap splits), F (implausible speed
rejects even a real structurally-adjacent pair), G (a temporally-and-speed-plausible but
structurally-distant real pair — grid distance 116 — is still rejected, the core structural-continuity
proof), H (duplicate H3 identity across Certified/Non-certified consolidates to one point, earliest
timestamp), I (a revisit scenario proves an old cell's timestamp never bridges two structurally-
unrelated later discoveries), J (inside/outside/inside splits correctly), K/L (a concave Department and
a Department with a hole both reject a chord that would cross outside/through, while a genuinely
interior chord is accepted), M (empty/single-cell/degenerate-`RouteSegment` all fail safe), N (node and
line-point counts are both hard-bounded, proven to actually engage for large inputs), O (extreme
`Instant.MIN`/`MAX` timestamps never crash derivation). Extended `RouteOverlayRenderingTest.kt` (node
and line-point bounds proven at the actual rendering-conversion layer) and `ObserveMapReadStateTest.kt`
(wiring now includes a fake `H3GridTraversal`; the Haute-Vienne urban-to-rural wiring test is now
explicit that adjacency is faked for wiring purposes, real H3 topology being
`DiscoveredRouteTest.kt`'s own job).

**Documentation**: this `PROJECT_STATUS.md` entry rewritten in place (see the retraction note at the
top); `docs/ai-context/UX_UI_SPEC.md`'s Map section corrected to "derived first-discovery corridor"
terminology throughout, with the same explicit "what this is NOT" list; the future dark/light basemap
direction remains PLANNED / NOT IMPLEMENTED, carried in `OPEN_QUESTIONS.md`.

**Not implemented this round:** true Sutherland–Hodgman polygon/line geometric clipping at the
Department border (chord-sampling only, a documented, deliberate conservative approximation);
Region-level corridor preview (deliberately out of scope, Department-only); corridor-segment
Certified/Non-certified styling (open question); gap/speed/grid-distance/sampling/budget calibration
against real field data (all remain provisional starting values); shape-preserving line simplification
(uniform-stride only); the future dark/light basemap itself.

**Future true journey/location-history architecture — explicitly NOT implemented, a separate future
question from this corridor.** If World Discovery later wants to show "the actual route you travelled"
with GPS-level fidelity, that requires a genuinely new, privacy-conscious, explicitly-designed ordered
location-history representation — a real product/privacy architecture decision (reopening, deliberately
and explicitly, what `REJECTED_APPROACHES.md`'s "Persistent raw GPS history" entry currently forecloses
by default), which must remain architecturally separate from canonical H3 discovery truth the same way
this corridor already does. Status: **ENGINEERING DESIGN REQUIRED / NOT IMPLEMENTED** — not started,
not scoped, not decided this round or any prior round.

**Physical validation status: still NOT PHYSICALLY VALIDATED, and explicitly not ready to be marked
validated until a further Codex re-review of this correction confirms the fixes above are sufficient**
— the primary real-world target (a Haute-Vienne urban-to-rural validation route) requires a Samsung
device test only after that re-review; nothing here claims device confirmation beyond the
manual-toolchain compilation/test evidence above.

##### Round 6 micro-fix — unsafe rendering "simplification" replaced with safe chunking (BLOCKING, fixed)

Codex's second review of Round 6 accepted every correction above (same-timestamp safety, duplicate H3
consolidation, structural continuity, time/speed guards, Department containment, corridor semantics,
certification, app wiring) but found one remaining blocking defect: the rendering-volume control for
very long segments, `simplifyRouteSegmentForRendering`, reduced a validated segment's point count by
uniform-stride **dropping intermediate vertices**, then let MapLibre draw a straight edge between
whichever points survived. That silently manufactured brand-new chords (`P0 -> P4 -> P8 -> ...`) that
had **never themselves passed** `deriveRouteSegments`'s own H3-grid-adjacency/time/speed evidence
checks — exactly the class of unvalidated connector this whole feature otherwise refuses to draw, for
the sole reason of keeping a single `LineString` small.

**Fix: replaced with deterministic CHUNKING, never vertex dropping.** New
`chunkRouteSegmentForRendering(segment, maxPointsPerChunk)` (`DiscoveredRoute.kt`) splits an
over-long, already-validated `RouteSegment` into multiple smaller `RouteSegment`s, where **consecutive
chunks share their boundary point exactly** (chunk *N*'s own last point equals chunk *N+1*'s own first
point). No point is ever skipped, reordered, or connected to a non-adjacent point — every edge any
chunk ever renders is a genuine, already-validated original edge from the segment's own real sequence.
The domain `RouteSegment` passed in is never mutated. `simplifyRouteSegmentForRendering` is removed
entirely, not merely deprecated. `RouteOverlayRendering.kt`'s `routeCoreFeatureCollection` now calls
`segments.flatMap { chunkRouteSegmentForRendering(it) }`, so one long segment can produce multiple
`LineString` `Feature`s from the same shared `GeoJsonSource` — halo and core both read that same
source, so they render identical chunk geometry by construction, with no second, independently-derived
conversion path either could diverge from. Node sampling (`sampleRouteNodesBounded`, the previously-
accepted 250 cap) is untouched and, by construction, safe from this same class of bug: dropping a
*node marker* never manufactures a false edge the way dropping a *line vertex* does, since a marker is
a single rendered point, not a chord between two points — documented explicitly in
`uniformStrideDownsample`'s own doc comment, which now states it is used only for nodes, never for
line geometry.

**Tests (real, run via the manual toolchain — `core-discovery-engine`: 267/267 passed; `feature-map`:
232/232 passed):** `DiscoveredRouteTest.kt`'s simplification tests replaced with an adversarial
**750-point zigzag fixture** (never a straight line, so a "skip points and connect what's left" bug
would produce a visibly different, checkable edge set) proving: every output chunk respects the
maximum point count; every consecutive pair in every rendered chunk was genuinely consecutive in the
original segment (no synthetic adjacency); successive chunks overlap exactly at the boundary point;
the complete original edge sequence is represented exactly once, in order, across all chunks; chunking
never mutates the input `RouteSegment`; a small segment stays exactly one chunk; exact-boundary cases
(`maxPoints - 1`, `maxPoints`, `maxPoints + 1`, and a substantially larger 2137-point segment) all
preserve every original edge with correct overlap. `RouteOverlayRenderingTest.kt` extended to prove
the same at the `Feature`/`FeatureCollection` conversion layer (multiple `LineString` Features for one
long segment, each within budget; boundary coordinates match exactly between successive chunk
Features; no rendered coordinate pair was ever non-consecutive in the original segment; the shared
`routeCoreFeatureCollection` conversion is deterministic, proving halo and core can never diverge).

**Documentation**: `DiscoveredRoute.kt`/`RouteOverlayRendering.kt` doc comments rewritten to describe
chunking, not "conservative simplification" or "a purely visual operation that cannot affect
geometry" — the whole point of this fix is that the earlier framing was false: dropping vertices
*does* affect rendered geometry, by creating new edges. This `PROJECT_STATUS.md` entry documents the
fix in place rather than leaving the earlier "uniform-stride point dropping... sufficient to bound
worst-case rendering cost" framing standing uncorrected.

**Not implemented this round:** shape-preserving (Douglas-Peucker-style) line simplification remains
explicitly future work — if a future round needs it for visual fidelity reasons, any replacement chord
it would introduce requires its own independent geometric/continuity revalidation, never assumed safe
by default the way this round's fix explicitly avoided assuming.

**Physical validation status: still NOT PHYSICALLY VALIDATED** — remains blocked on a further Codex
re-review of this micro-fix before any Samsung device test is warranted.

### Phase FH-1 — Full metropolitan Department coverage (data-completion phase, NOT committed)

**Status: IMPLEMENTED, uncommitted.** Phase H above shipped all 13 metropolitan regions but only
Nouvelle-Aquitaine's 12 departments, by deliberate scoping. This phase completes the remaining 11
regions' departments plus Corse's 2, using the exact same generator/loader/hierarchy architecture —
no new data format, no hierarchy/classification/rendering/navigation code changed.

**Data:** all 84 remaining metropolitan departments fetched from real OSM administrative relations
(structured Nominatim query, cross-verified against each relation's own `ref:INSEE` tag, geometry via
`polygons.openstreetmap.fr/get_geojson.py`) — the same methodology `tools/geo/README.md` already
documents, reproduced without modifying `tools/geo/GenerateFranceAdministrativeReference.kt`. Every
one of the 84 was INSEE-cross-checked before acceptance; none were taken on a first Nominatim hit
alone. Corse's two departments (Corse-du-Sud `admin2:FR-2A`, Haute-Corse `admin2:FR-2B`) are included
under `admin1:FR-20R` with no special-cased architecture — consistent with `FranceAdministrativeAreas.kt`'s
own prior doc comment explicitly calling Corse's departments "PLANNED-NOT-IMPLEMENTED, same as every
other non-Nouvelle-Aquitaine region's departments," not a new decision.

**Files:** 84 new `core-discovery-engine/src/main/resources/geo/france/departments/*.json` artifacts
(same `GeographicAreaReferenceJson` schema as the existing 12); `FranceAdministrativeAreas.kt`'s
`FRANCE_ADMIN_2_RESOURCE_PATHS` extended from 12 to 96 entries (grouped by region, same order as
`FRANCE_ADMIN_1_RESOURCE_PATHS`) and its doc comment updated to describe full coverage instead of the
old NAQ-only scoping. No generator code changed.

**Generic-engine check (as required before touching any of this):** confirmed by direct code reading
before making any change — `GeographicAreaHierarchyValidation.kt`'s `validateGeographicAreaHierarchy`
is already fully generic (map-keyed-by-id, no hardcoded counts); `ObserveMapReadState.kt` is the only
production file referencing `.departments` and does so generically
(`classifyDiscoveredCellsByGeographicAreas(validCells, franceAdministrativeAreas.departments)`);
`feature-map`'s hierarchy/navigation/rendering tests use synthetic fixtures, not the real department
count. **No hierarchy, classification, Map click-resolver, selection-relative styling, or camera/focus
code was modified for this phase** — exactly the expected outcome the phase was designed to prove.

**Tests updated:** `FranceAdministrativeHierarchyTest.kt` — the 3 tests that hardcoded the old
NAQ-only shape were rewritten (Paris now has its own department and is asserted visited, not "every
department stays unvisited"; the blanket `parentId == admin1:FR-NAQ` assertion was generalized to "every
department's parentId resolves to a loaded region," with a separate test still pinning NAQ's own 12 to
`admin1:FR-NAQ`; the exact-count test now asserts 13 regions / 96 departments) — plus new coverage
tests: every region has at least one department child, region/department ids are each globally unique.
`InteriorPointFinderRealDataTest.kt` extended with department-level equivalents of its existing
region-level tests (findable verified interior point for all 96; genuinely inside its own geometry;
matches exactly one real Country component; Corse's 2 departments map to the Corsica component, the
other 94 to mainland). `ObserveMapReadStateTest.kt` — one test asserting "a Paris discovery leaves
every department unvisited" was rewritten to assert it visits exactly Paris's own department, since
Paris is no longer outside all loaded departments.

**Validation run (manual `kotlinc`/JBR JUnit toolchain, same as established practice — Gradle's
standing `Unable to establish loopback connection` failure attempted once, confirmed still present,
not retried):
`core-discovery-engine`: 543/543 passed, 0 failures**, including the hierarchy, classification,
map-read-state, geometry, interior-point and trajectory/corridor suites — full elapsed time ~6.8s
including JVM startup, no pathological slowdown despite most of the loaded-department count going from
12 to 96. `feature-map` was not recompiled (Android Gradle plugin required, blocked by the same
loopback failure; its own hierarchy/navigation tests are synthetic and have no dependency on the real
department count, per direct source inspection). **Superseded by the provenance correction round
below** — see that entry for the re-confirmed post-fix result; the count did not change.

**Resource size:** 96 department JSON files total ≈2.06 MB (≈2.2 MB on disk), bringing
`geo/france/`'s total to ≈3.1 MB. Eager loading (parse-once-at-startup, same as before) remains
reasonable at this size — no caching/streaming/database architecture introduced, consistent with the
"do not introduce it without an actual measured problem" instruction; nothing measured warranted it.

**Documentation:** `ARCHITECTURE_DECISIONS.md`'s "External geographic data" entry and
`docs/ai-context/UX_UI_SPEC.md`'s Map section both updated to describe full 96-department coverage
instead of the old "Nouvelle-Aquitaine's 12 departments only" wording; both now explicitly flag that
only Nouvelle-Aquitaine/Haute-Vienne has been physically validated on-device so far and the remaining
regions still need the physical-validation pass below.

**Privacy/repository safety:** all 84 new resource files scanned for accidental private content
(usernames, local paths, credentials) — clean, as expected for public OSM administrative boundary
data; nothing staged, committed, or pushed; `docs/ai-context/OPEN_QUESTIONS.md`'s pre-existing local
future-basemap hunk was read but never touched.

**Physical validation plan (not yet performed — automated tests above are not a substitute):**
A. Select France, then navigate into at least Nouvelle-Aquitaine (already-validated baseline), one
northern/eastern region (e.g. Hauts-de-France or Grand Est), one southern region (e.g. Occitanie or
Provence-Alpes-Côte d'Azur), and Corse. B. For each, confirm Region → Department immediate visibility
on selection (no extra tap/reload needed). C. Confirm Department selection and Back navigation return
correctly to Region, then Country. D. Confirm existing H3 discovery-cell rendering is visually
unaffected in a region outside Nouvelle-Aquitaine. E. Where existing discovery data allows it, confirm
the derived first-discovery corridor still renders correctly and is unaffected by the larger loaded
department set. F. Re-validate any previously outstanding Round 5/Round 6 physical items from Phase H
above, since they predate this data-completion phase and were never marked resolved on-device. No
travel to these regions is required — map navigation alone exercises the hierarchy.

### Phase FH-1, Codex review provenance correction (uncommitted)

**Independent Codex review of Phase FH-1 above: FAIL, blocked on one substantive defect — provenance
integrity.** Everything else (13 regions, 96 departments, 84 new resources, generic-engine
preservation, corridor semantics, geometry validation, privacy audit, the 543/543 test result, the
known Gradle loopback limitation) was confirmed correct by that review and did not need rework.

**Root cause:** every one of the 84 newly generated department resources carried
`sourceVersion` text stating `retrieved 2026-09-18` alongside a `generatedAt` of `2026-09-02` — an
impossible chronology (the artifact claiming to have been generated over two weeks *before* its own
source data was retrieved). Traced to `tools/geo/GenerateFranceAdministrativeReference.kt`, which
hard-coded `generatedAt = "2026-09-02"` (the date the generator was first written) rather than
computing it at generation time; reusing the same generator on 2026-09-18 for the FH-1 batch left
that stale literal in place. The 12 pre-existing Nouvelle-Aquitaine resources were unaffected (their
own `generatedAt`/retrieval dates were both genuinely `2026-09-02`, no chronology conflict).

**Fix (generator only, no schema change):** the hard-coded literal was replaced with
`currentGenerationDate(clock: Clock = Clock.systemDefaultZone())`, a small function that derives
`generatedAt` fresh from the real clock at generation time (ISO-8601 `yyyy-MM-dd`, the same string
format every existing artifact already uses) and defaults to the operator's local time zone rather
than UTC, so it stays comparable with a `sourceVersionNote`'s own "retrieved <date>" text instead of
drifting a day apart near midnight UTC (this was caught and corrected during the fix itself — an
initial `Clock.systemUTC()` default produced `2026-09-17` against a `sourceVersion` of "retrieved
2026-09-18," reproducing a smaller version of the same class of bug). `sourceVersion` semantics are
untouched — it still records the source/retrieval note, never generation time; the two fields are not
conflated. A new focused, deterministic test,
`tools/geo/GenerateFranceAdministrativeReferenceProvenanceTest.kt` (a plain `fun main()` following
this repository's existing `tools/` smoke-test convention, no JUnit dependency needed for this
standalone CLI directory), injects fixed `Clock`s rather than asserting a real calendar date, proving
the mechanism tracks whatever clock it is given and can never silently regress to the old
`2026-09-02` literal.

**Regeneration:** only the 84 FH-1 department resources were regenerated, from the same already-
fetched, already-INSEE-verified raw OSM geometry (`dept-raw/*.geojson`, unchanged) via the corrected
generator — the 12 pre-existing Nouvelle-Aquitaine resources were left untouched, and no geometry was
hand-edited. Two regeneration passes were needed in practice (the `Clock.systemUTC()` → local-zone
correction above), both applied before this entry was written.

**Provenance verification:** all 84 resources now read `generatedAt = 2026-09-18`, consistent with
their own `sourceVersion` "retrieved 2026-09-18" text — chronology valid. The 12 Nouvelle-Aquitaine
resources remain `generatedAt = 2026-09-02`, unchanged.

**Content-stability check (corrected framing — see the round-2 provenance-fix entry below for why
the original wording here overstated this):** the pre-fix 84 files were untracked and were
overwritten in place, so no Git snapshot exists to run a strict, field-by-field before/after diff
against — the aggregate byte count staying identical (2,058,361 bytes both before and after) is
consistent with nothing else having drifted, but it is not, by itself, proof of content identity.
What the available evidence actually supports: `sourceId` (`openstreetmap`) and `sourceProvenance`
(`EXTERNAL_REFERENCE_DATASET`) are uniform across all 96 files; the `license` field hashes
identically (one MD5 value) across all 96 files; `id`/`type`/`parentId` spot-checked against the
authoritative Region→Department mapping for Paris, Corse-du-Sud, Finistère, Bouches-du-Rhône and
Charente — all correct; the complete hierarchy/geometry/interior-point validation suite below passes
against the regenerated files. No evidence of geographic drift was found, but this is not the same
claim as a strict normalized proof.

**Regression re-run (manual `kotlinc`/JBR toolchain, resources reloaded fresh, main/test code
unchanged from Phase FH-1 above so no recompilation of `core-discovery-engine` itself was needed):
`core-discovery-engine`: 543/543 passed, 0 failures again**, confirming the fix did not regress
anything the prior run had already proven (hierarchy, classification, map-read-state, geometry,
interior-point, trajectory/corridor). Gradle attempted once more:
`Unable to establish loopback connection`, same known standing environment failure, not retried.

**Documentation:** this entry itself is the correction; the misleading `JAVA_HOME`/loopback phrasing
in Phase H's and Phase FH-1's own status lines above was corrected to name the actual, specific
failure (`Unable to establish loopback connection`) rather than conflating it with `JAVA_HOME`.
Physical validation remains explicitly **NOT YET PERFORMED** — unchanged by this provenance fix, and
`feature-map`/Android validation limitations remain as stated above (Android Gradle plugin blocked by
the same loopback failure; `feature-map`'s hierarchy/navigation tests are synthetic and unaffected
either way).

**Scope audit:** confirmed no change to hierarchy architecture, classification logic, click resolver,
selection-relative styling, camera/focus, corridor semantics, basemap, reconstruction, trajectory
buffer, Safety Gate, adaptive cadence, or Certified logic — only the generator's date/provenance
handling, its new focused test, the 84 resources' `generatedAt` metadata, and this documentation
entry.

**Privacy/repository safety:** re-scanned all 84 regenerated files — still clean, as expected (public
OSM administrative boundary data, no local paths/usernames/credentials); `docs/ai-context/
OPEN_QUESTIONS.md`'s pre-existing local future-basemap hunk remains untouched; nothing staged,
committed, or pushed.

### Phase FH-1, Codex review provenance correction round 2 (uncommitted)

**Independent Codex re-review of the round-1 provenance fix above: FAIL — the 84 resources
*themselves* were correct, but the generator contract was not.** `currentGenerationDate(clock: Clock
= Clock.systemDefaultZone())` was host-timezone dependent: the same real instant could legitimately
produce different `generatedAt` calendar dates on different machines, so the date domain shared with
`sourceVersion`'s retrieval-date convention was not formally guaranteed, only coincidentally correct
on the machine this round happened to run on. Codex also found the round-1 regression test
insufficient: it used only UTC clocks, never exercised the actual midnight-boundary case where the
UTC and target calendar dates genuinely differ, and tested `currentGenerationDate()` in isolation
rather than the real artifact-generation path `main()` calls — a test that would keep passing even
if that path stopped using the helper at all.

**Explicit provenance date domain:** `Europe/Paris`, always — defined as `PROVENANCE_ZONE =
ZoneId.of("Europe/Paris")` directly in `tools/geo/GenerateFranceAdministrativeReference.kt`, next to
[currentGenerationDate]'s own definition, and documented in that file's class-level doc comment as
the single explicit calendar-date domain every date this France-specific generator writes is defined
in — including the retrieval-date convention an operator follows when writing a `sourceVersionNote`
by hand (the generator does not parse that text, but the shared convention is what makes it and
`generatedAt` comparable). `Clock.systemDefaultZone()` was removed entirely — this generator now
never depends on the host's own timezone for any date it writes.

**Implementation — enforced, not merely documented (design A from the review's own options):**
`currentGenerationDate(clock: Clock = Clock.systemUTC())` now reads `clock.withZone(PROVENANCE_ZONE)`
before computing `LocalDate.now(...)` — i.e. it takes whatever clock (and whatever zone) it is given
and always reinterprets the underlying instant through `Europe/Paris`, rather than trusting the
caller (or the default parameter) to already carry the right zone. This makes the contract
structurally impossible to violate by accident: a caller passing a UTC clock, a Tokyo clock, or a
clock in the host's own arbitrary default zone all produce the identical result for the same real
instant, proven by the new host-timezone-independence test below. The default parameter's own zone
is deliberately irrelevant for this same reason (`Clock.systemUTC()` is used only as an unremarkable,
conventional default, not because UTC has any special status here).

**`sourceVersion` contract:** unchanged in meaning — it still records the source/retrieval note, not
a generation timestamp, and this generator does not parse or redesign it. Documented explicitly
(class-level doc comment) that a `sourceVersionNote`'s own "retrieved &lt;date&gt;" text, when
written by an operator for this France generator, is understood to follow the same `Europe/Paris`
calendar-date convention as `generatedAt` — this is what makes the two fields comparable at all, not
an automatic guarantee independent of the operator following the convention.

**Midnight-boundary regression (new):** `verifyMidnightBoundary()` in
`GenerateFranceAdministrativeReferenceProvenanceTest.kt` fixes a clock to the instant
`2026-01-15T23:30:00Z` — still `2026-01-15` in UTC, but Europe/Paris is `UTC+1` in January (CET, no
DST ambiguity), so the same instant is already `2026-01-16` there. Only an implementation that
genuinely reinterprets through `Europe/Paris` (rather than trusting the input clock's own UTC zone,
or defaulting to the host's zone) answers `2026-01-16`. **PASS.**

**Host-timezone-independence regression (new):** `verifyHostTimezoneIndependence()` fixes the SAME
instant (`2026-06-10T12:00:00Z`) across five clocks tagged with different, mutually unrelated zones
(UTC, `America/Los_Angeles`, `Asia/Tokyo`, `Australia/Sydney`, and `Europe/Paris` itself, standing in
for "whatever zone the host machine happens to default to") and asserts all five produce the
identical `2026-06-10` result — proving the caller's own clock zone can never leak into the answer.
**PASS.**

**Production-artifact-path regression (new):** the parse/filter/simplify/construct pipeline
previously inlined in `main()` was extracted, minimally, into `internal fun
buildGeographicAreaReference(...)` (same logic, same behavior, `main()` is now a thin wrapper around
it plus the file-write/summary printing) — this is the function every real bundled artifact is
actually produced through. `verifyProductionArtifactPath()` calls it directly against a small
synthetic ~100 km² square GeoJSON fixture written to a local temp file at test time (no network
access, no dependency on any previously-fetched OSM file, deleted after the test), with a fixed
clock at the same midnight-boundary instant, and asserts the resulting artifact's own `generatedAt`
equals `currentGenerationDate(clock)` for that same clock — proving the real generation path is
wired to the same provenance mechanism, not a test-only stand-in for it. This test does not
reimplement the timezone-conversion logic; it calls the real production function once and compares
against the real helper's own output, so a future regression (e.g. `buildGeographicAreaReference`
reverting to a hard-coded literal) would make the two diverge and fail the test. **PASS.**

**84 FH-1 resource status:** regenerated a third time (round 1 had already produced two internal
passes; this is the first regeneration under the corrected `Europe/Paris`-enforced contract) from the
same already-fetched, INSEE-verified raw OSM geometry (`dept-raw/*.geojson`, unchanged) — the 12
pre-existing Nouvelle-Aquitaine resources were, again, left untouched. Result: **identical to before
this round** — all 84 still read `generatedAt = 2026-09-18` (this session's host machine's own
timezone already happens to align with `Europe/Paris` for the instant in question, so the explicit
zone did not change the answer this time; it removes the *dependency*, not necessarily today's
specific value), total byte count across all 96 files unchanged at 2,058,361, `sourceId`/
`sourceProvenance`/`license` unchanged and uniform. This was legitimate, non-churning regeneration
under §7's own stated condition ("if regenerating them ... produces the same legitimate `generatedAt`
value and no other content change, regeneration is acceptable") — not skipped, since the contract
genuinely changed even though today's specific output did not.

**Content-stability claim, corrected:** the round-1 entry above overstated this — "identical
aggregate byte count" is consistent with, but is not proof of, content identity, since the pre-fix 84
files were untracked and were overwritten in place with no Git snapshot to diff against. That entry
has been corrected in place above; this round adds no stronger claim than the corrected one.

**Dataset/geometry regression:** re-ran the complete coverage/geometry/interior-point suite — 13
regions, 96 departments, every region has department children, unique region/department ids, every
department `parentId` resolves to a loaded region, Corsica component mapping correct for both
Corse departments. No change from the round-1 result.

**Automated tests (manual `kotlinc`/JBR toolchain — resources reloaded fresh; `core-discovery-engine`
main/test code unchanged from Phase FH-1 above, so no recompilation of that module was needed):
`core-discovery-engine`: 543/543 passed, 0 failures.** Gradle attempted once more:
`Unable to establish loopback connection`, same known standing environment failure, not retried — no
Android/Gradle success is claimed.

**Documentation:** this entry; the round-1 "Content-stability check" paragraph corrected in place
(see above) rather than left overstated. Physical validation remains explicitly **NOT YET
PERFORMED**.

**Scope audit:** confirmed no change to hierarchy architecture, classification logic, click resolver,
selection-relative styling, camera/focus, corridor semantics, basemap, reconstruction, trajectory
buffer, Safety Gate, adaptive cadence, or Certified logic — only the generator's timezone contract,
its strengthened test, the extraction of `buildGeographicAreaReference` (pure refactor, identical
generation logic), the 84 resources (regenerated, content unchanged), and this documentation.

**Privacy/repository safety:** re-scanned all 84 regenerated files and the new/changed generator
files — clean; `docs/ai-context/OPEN_QUESTIONS.md`'s pre-existing local future-basemap hunk remains
untouched; nothing staged, committed, or pushed.

### Phase FH-1, Codex review provenance correction round 3 — test-only (uncommitted)

**Final Codex re-review: one remaining weakness, test-only.** Round 2's production-artifact-path
test called `buildGeographicAreaReference(...)` with a single `Clock`, so a hypothetical future
regression that replaced its `generatedAt` with any one fixed, hard-coded date could still satisfy
that single assertion by coincidence. Fixed by strengthening the test alone — **no production code
changed**: `verifyProductionArtifactPath()` in
`tools/geo/GenerateFranceAdministrativeReferenceProvenanceTest.kt` now calls the real
`buildGeographicAreaReference(...)` twice, against the same synthetic local fixture, with two
deterministic clocks whose Europe/Paris calendar dates genuinely differ (clock A →
`2026-01-16`, clock B → `2026-07-20`, both explicit literals, never recomputed via
`currentGenerationDate` itself), and asserts both individual matches AND that the two results
differ from each other. Verified by a manual mutation check (temporarily hard-coding
`generatedAt = "2026-01-16"` in the generator): the strengthened test correctly failed
(`expected ... clock B to produce generatedAt=2026-07-20, got 2026-01-16`); the production line was
then restored to `generatedAt = currentGenerationDate(clock)` and re-verified passing. The three
previously-passing tests (Europe/Paris contract via the midnight-boundary case, host-timezone
independence, obsolete-literal protection) were kept unmodified.

**84 FH-1 resources: untouched, as instructed** — this was a test-only change with no production
code modification, so no regeneration was needed or performed; still 84/84 at
`generatedAt = 2026-09-18` matching their own `2026-09-18` retrieval date, total byte count
unchanged at 2,058,361. The 12 Nouvelle-Aquitaine resources remain untouched.

**Validation:** provenance test — PASS (all four checks: Europe/Paris contract, midnight boundary,
host-timezone independence, two-date production-artifact path). `core-discovery-engine` regression
(manual `kotlinc`/JBR toolchain): **543/543 passed, 0 failures**, unchanged from round 2. Gradle not
re-attempted for this test-only change, per instruction; the standing
`Unable to establish loopback connection` environment failure remains recorded from the prior
attempts above and is assumed unchanged. Physical validation remains explicitly
**NOT YET PERFORMED**.

**Scope audit:** confirmed no change to hierarchy architecture, classification, navigation, styling,
camera/focus, corridor, basemap, reconstruction, trajectory buffer, Safety Gate, adaptive cadence,
Certified logic, or the 84 FH-1 geographic resources — only the provenance test file.

### Phase FH-1, runtime hierarchy fix — unvisited administrative children must remain navigable (uncommitted)

**FH-1's dataset itself was confirmed correct (13 Regions / 96 Departments genuinely loaded at
runtime) — the defect was in `feature-map`'s UI layer, found during the first real physical-device
validation attempt.** `MapScreen` filtered `franceAdmin1Statuses`/`franceAdmin2Statuses` down to
`visited == true` before ever handing them to `DiscoveryMapView`, discarding the unvisited majority
along with their own visited-state information. Consequence: only Nouvelle-Aquitaine/Haute-Vienne
(the one region/department with real discovery data) ever reached rendering or MapLibre hit-testing
— every other Region/Department was genuinely loaded and valid but simply never appeared or resolved
a click, since `queryRenderedFeatures` cannot hit-test a feature that was never rendered. **This
behavior predates FH-1**; FH-1 only exposed it by finally completing the France hierarchy enough for
the gap to be physically observable at Region/Department level (previously only Nouvelle-Aquitaine
existed to test against).

**Product rule now enforced end-to-end: administrative EXISTENCE and discovery PRESENCE are
different concepts.** `visited` still determines styling (orange = VISITED/PRESENCE, unchanged
semantics); it must never determine whether an area exists in the interactive hierarchy. `MapScreen`
now passes the COMPLETE `GeographicAreaVisitedStatus` lists straight through (no filtering, no
`.area`-only projection that discards the visited flag) to `DiscoveryMapView`, which already owns the
current focus/selection state needed to do the real PARENT-SCOPED render-candidate decision:
`administrativeRenderCandidates` (new, in `AdministrativeOverlayRendering.kt`) exposes all 13 loaded
Regions unconditionally (there is only one loaded Country, so "France selected → all 13 Regions" has
no narrower parent to scope against) and restricts Departments to whichever Region is currently
focused — empty at Country/World view, never all 96 at once. Click resolution needed **no logic
change at all**: `resolveGeographicClick`'s own parent-scoping filter (`region.parentId ==
focusedCountryId` / `department.parentId == focusedAdmin1Id`) was already fully generic and already
correct — the bug was entirely in the caller narrowing the candidate universe before it ever reached
that function. `GeographicClickContext`'s `visitedRegions`/`visitedDepartments` fields were renamed
to `regions`/`departments` (now genuinely the full candidate universe, never a visited-only subset)
for accuracy, mechanical rename only.

**Styling: a new, independent `visited` dimension, orthogonal to the existing selection-relative
`GeographicAreaStyleRole`.** `administrativeFillColorHex(role, visited)` (new, in
`GeographicHierarchyStyling.kt`) combines both: the three existing orange shades when
`visited == true` (byte-identical to before this fix), three new neutral greys
(`GEOGRAPHIC_UNVISITED_SELECTED_FILL_COLOR` `#9E9E9E` / `..._DIRECT_SUBLEVEL...` `#707070` /
`..._ANCESTOR_CONTEXT...` `#454545`, provisional calibration values, same status as the existing
orange constants) when `visited == false` — **orange never appears for an unvisited area, regardless
of its selection role.** Each rendered Region/Department Feature now carries an explicit
`visited` GeoJSON property (`"true"`/`"false"`) alongside the existing `styleRole` property;
`administrativeAreaColorExpression` (new) reads both (via `Expression.concat`) to pick the final
color. The Country-level overlay (`CountryOverlayRendering.kt`) is completely unchanged — out of this
fix's scope, since Country-level rendering was never filtered to visited-only in the first place.

**Files changed:** `MapScreen.kt` (stopped filtering/projecting — passes full
`GeographicAreaVisitedStatus` lists), `DiscoveryMapView.kt` (renamed params to
`admin1Statuses`/`admin2Statuses`; click context now built from the full area lists; render effect
now calls `administrativeRenderCandidates` before `applyAdministrativeOverlay`; Department
route-clip lookup now searches the complete `admin2Statuses`, not a visited-only subset, so clipping
still resolves correctly for an unvisited selected Department), `AdministrativeOverlayRendering.kt`
(`applyAdministrativeOverlay`/`administrativeOverlayFeatureCollection` now take
`List<GeographicAreaVisitedStatus>`; new `administrativeRenderCandidates`/
`AdministrativeRenderCandidates`; features now tagged with the new `visited` property), `Geographic
HierarchyStyling.kt` (new unvisited color constants, `administrativeFillColorHex`,
`ADMIN_OVERLAY_VISITED_PROPERTY`, `administrativeAreaColorExpression`), `AdministrativeAreaNavigation.kt`
(field rename only, `GeographicClickContext.visitedRegions/visitedDepartments` →
`regions`/`departments`; one doc-comment correction). No corridor/H3/trajectory/reconstruction/
Safety Gate/basemap/Certified code touched.

**Tests:** `AdministrativeOverlayRenderingTest.kt` — existing tests updated to the new
`GeographicAreaVisitedStatus`-based signature (no behavior change to what they prove) plus new
coverage: an unvisited candidate is still rendered as a Feature; every Feature carries an accurate
explicit `visited` property; `styleRole` and `visited` are proven independent tags; and a dedicated
`administrativeRenderCandidates` section proving — verbatim against this round's own required
regression list — (A) France/no-Region-focus exposes every loaded Region regardless of visited state
and Departments stay empty; (B) a focused Region exposes ALL its own Department children regardless
of visited state; (C) an unvisited candidate keeps its own real `visited=false`, never silently
promoted; (H) a sibling Region's Department never leaks into the render-candidate set when one Region
is focused — proving Departments never render all-96-at-once. `AdministrativeAreaNavigationTest.kt`
— mechanical rename of the renamed fields across every existing call site (regression-neutral) plus
two new tests proving click resolution can genuinely select a Region/Department sourced from an
`unvisited` `GeographicAreaVisitedStatus` (E/F), and one test re-confirming the existing
Nouvelle-Aquitaine → Haute-Vienne click sequence still resolves correctly end to end (I).
`GeographicHierarchyStylingTest.kt` — new coverage for `administrativeFillColorHex`: visited output
matches the existing plain role color exactly; unvisited output is never one of the three orange
visited colors, for any role; the three unvisited colors are themselves mutually distinct; visited
and unvisited differ for the same role. Corridor/H3/classification tests are untouched (out of
scope) and remain green per `core-discovery-engine`'s own unaffected 543/543 (J) — nothing in this
fix touches that module.

**Validation (manual `kotlinc`/JBR toolchain — `android-sdk-opengl`'s real MapLibre classes extracted
from its AAR, plus `android-sdk-geojson`, `gson`, `androidx.annotation`, `androidx.lifecycle-common`,
and a real `android.jar` platform stub; the two Jetpack-Compose-heavy files, `MapScreen.kt` and
`DiscoveryMapView.kt`, could not be compiled in this manual toolchain — no Compose runtime/UI/
foundation/material3/activity-compose jars are set up here — so those two files' edits were verified
by careful full-file manual review plus consistency cross-checks against the other 17 files'
compiled, real signatures instead): the other 17 `feature-map` main source files (everything actually
containing this fix's logic) compiled cleanly, and **172/172 `feature-map` unit tests passed, 0
failures** — every pre-existing test that needed updating for the new signatures, every new test
listed above, and every untouched file's own existing tests (proving no collateral regression).
`core-discovery-engine` untouched this round; its own 543/543 result stands. Gradle attempted once
(`:feature-map:testDebugUnitTest`): `Unable to establish loopback connection`, same known standing
environment failure, not retried. **No Android/MapLibre/physical validation is claimed** — the user
must rebuild through Android Studio for that.

**Performance:** no repeated resource parsing introduced (France resources still parse once per
`AppContainer`/process, unchanged); Departments are never rendered all-96-at-once — parent-scoped to
at most one Region's own children (2-13 Departments) at a time, same or lower feature count than a
naive "render everything" approach would produce. `administrativeRenderCandidates` and
`.map{it.area}` calls are plain `O(n)` list operations over at most 96 items, run once per style
re-application (already-existing effect cadence, not a new subscription or new re-render trigger) —
no measured or structurally obvious slowdown; no caching/database/streaming architecture introduced,
consistent with the "do not introduce it without an actual measured problem" instruction from FH-1's
own §9.

**Google Sign-In failure after account selection** — physically observed during this round's
validation pass, recorded here as a **separate, out-of-scope issue** per explicit instruction: not
investigated or touched in this fix.

**Documentation:** this entry. FH-1's dataset correctness (13 Regions / 96 Departments genuinely
loaded) is unaffected and remains as documented above; this entry adds the runtime UI-layer
correction on top. Physical validation was **NOT YET PERFORMED at the time this entry was written**
— see the following entry for the actual physical-validation result, performed after this fix
landed in the working tree.

### Phase FH-1, second physical validation — PASS (uncommitted)

**Functional physical validation: PASS.** The user rebuilt and installed the current working tree
through Android Studio on the physical Samsung SM-G998U1. Android Studio build: PASS. App launch:
PASS. Physically confirmed on-device:

- **Country level:** the France view exposes the metropolitan Regions, including previously
  unvisited ones, all clickable.
- **Unvisited Region (Grand Est):** visible, selectable; selection/focus/camera-fit worked; its
  Department boundaries became available immediately, exactly as the runtime hierarchy fix above
  intended.
- **Unvisited Department (inside Grand Est):** visible, clickable; selection/focus/camera-fit worked
  despite having no discovered H3 in it at all — direct physical confirmation of the fix's own core
  claim ("an unvisited administrative area must remain navigable").
- **Visited-branch regression check:** existing visited geography still reads orange; the
  Nouvelle-Aquitaine/Haute-Vienne visited behavior (the one region/department this app had real
  discovery data for before this phase) remains functional; existing discovered H3 cells remain
  present and rendered.

**FH-1 is now functionally physically validated.** This closes the runtime defect the first physical
validation attempt (above) exposed. This is a functional-behavior result only — it does **not** claim
visual-design completion, worldwide hierarchy support, or final basemap completion; those remain
exactly as scoped everywhere else in this document.

**UX observations from this validation round — recorded as future work, not FH-1 blockers, and not
acted on in this round:**

- **Department labels:** Department boundaries render, but World Discovery does not yet provide its
  own controlled Department-name labels — only the basemap's own city labels are visible. Future
  hierarchy/basemap work should add deliberate administrative labeling.
- **Administrative contrast:** the current provisional neutral unvisited styling
  (`GEOGRAPHIC_UNVISITED_*_FILL_COLOR`, see the runtime-hierarchy-fix entry above) can visually blend
  with surrounding basemap geography for an unvisited selected Department. A future visual
  calibration concern, not redesigned in this round.
- **Zoom-dependent hierarchy visibility:** Country/Region/Department/H3/label overlays each become
  visible only at their own current zoom thresholds (`ADMIN1_OVERLAY_MIN_ZOOM`/
  `ADMIN2_OVERLAY_MIN_ZOOM`/etc., all still `PRODUCT CALIBRATION REQUIRED` provisional values per
  their own doc comments). Future basemap/hierarchy UX work must define deliberate visibility
  thresholds across all levels together; not changed in this round.
- **Basemap clutter:** the current `DEV_ONLY_DEMO_STYLE_URL` (OpenFreeMap Liberty) shows many roads,
  city labels, road numbers, and POI/context details the user considers too visually busy for the
  final product experience. OpenFreeMap Liberty remains the documented temporary validation-only
  basemap (see `DiscoveryMapView.kt`'s own doc comment and `docs/ai-context/OPEN_QUESTIONS.md`'s
  "Final Map art direction" entry) — not final, not modified in this round.

**Tracking/reconstruction observation (separate future work, not an FH-1 defect):** during this
validation round the user also performed a physical urban route validation, inspecting existing
discovery/tracking data rendered for that area. Current rendering visibly contains many small H3 cells, raw/
diagnostic blue location observations, clusters of blue observations where the device stayed in one
place, gaps between discovered samples along travelled routes, and visually fragmented/punctuated
route coverage. Trajectory reconstruction remains intentionally inactive (§20/§21 above) — this
observation is consistent with that and is not caused by, or a defect in, the FH-1 hierarchy work.
The already-documented product direction stands unchanged: raw GPS observations → ordered temporary
trajectory history → safe reconstruction/map-matching → reconstructed geographic trajectory → H3
discovery, with observed and reconstructed provenance kept strictly distinct, and any future
reconstructed segment initially `NON_CERTIFIED` unless/until a future Certified policy explicitly
allows otherwise. Nothing here was changed this round; recorded as an observation only.

**Map-tab re-entry performance:** the previously-reported noticeable delay when leaving and returning
to the Map tab remains **OBSERVED PHYSICALLY, NOT YET PRECISELY DIAGNOSED** — not claimed fixed, not
optimized this round.

**Google Sign-In:** fails after account selection, physically observed again this round — remains a
**separate, open, out-of-scope issue**, not investigated or touched.

**Scope audit:** this round changed documentation only — no production code, no tests, no geographic
resources, no generator, no provenance.

## 18. Constraints for Codex / Claude Code

1.  Treat `7a906a9` as the historical Phase 1--4 code baseline (§6--§16)
    and `d54f0c77bb376c558778232cb28219ddd8fe1ef5` as the current pushed
    `HEAD` of `main` — see §19 for the full commit chain between them,
    including the five-commit stabilization series that landed §17's
    Phase H hierarchy/corridor, §20's trajectory buffer foundation, and
    §21's Reconstruction Safety Gate as real pushed history. Verify with
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
and `d222fd7` below — only documentation/governance commits landed in
between.

``` text
branch: main
commit: d222fd7
commit message: docs: adopt MapLibre as map rendering engine
```

On top of `d222fd7`, real Map/discovery-visualization code landed
(`b2ec52e`, `30c0320`), then a stabilization fix (`8c477f2`), then a
five-commit stabilization series that lands §17's Phase H
(hierarchy/corridor), §20 (trajectory buffer foundation), and §21
(Reconstruction Safety Gate) below as real, pushed history:

``` text
8c477f298b864e9c3db23ce302c9b742cb4ca3e1
  fix: preserve observed provenance during reconstructed cell merges
→ 531fa35496d655b9e55b9d78b1b487bc827d4acd
  chore: protect private benchmark and calibration artifacts
→ 43594f96dca1012ea703b24cbbb0ea8364a38264
  feat: add France administrative hierarchy and derived discovery corridor
→ 5d98269e2e3c7189c39bf5cd0ab9c9555da5b372
  feat: add debug-only tracking calibration diagnostics
→ fcc8de9a22e365e3d738fc2a9e919f45d82c0a6e
  feat: add trajectory buffering and reconstruction safety foundation
→ d54f0c77bb376c558778232cb28219ddd8fe1ef5
  test: add reproducible map-matching benchmark infrastructure
```

Current pushed `HEAD` of `main`:

``` text
branch: main
commit: d54f0c77bb376c558778232cb28219ddd8fe1ef5
commit message: test: add reproducible map-matching benchmark infrastructure
```

Verify this is still accurate with `git log`/`git rev-parse HEAD` rather
than assuming it stays current as the repository evolves. Phase 3A
(§21) is **ACCEPTED but INACTIVE/NOT WIRED** even though it is now
committed and pushed — see §21's own guardrails before any future round
considers live wiring; being pushed is not the same as being active.
The map-matching benchmark tooling/evidence (§17's own cross-references
to `docs/ai-context/MAP_MATCHING_ENGINE_STUDY.md` and
`docs/ai-context/PHASE_2B_BENCHMARK_PROTOCOL.md`) is also now committed
and pushed, as reference engineering evidence only — no matcher is
selected or wired.

## 20. Trajectory reconstruction — Phase 1 foundations (committed, pushed via `fcc8de9a`; still INACTIVE/NOT WIRED)

Follows a dedicated architecture study (background-tracking physical test:
Samsung SM-G998U1, 43/15/11/11 observations across 4 phases, background
cadence ≈27s measured) that concluded observation spacing during fast
movement — not a tracking defect — is the real product gap, and that a
future reconstruction engine should work on a real geographic trajectory
(eventually road-network map matching), converting to H3 only at the end.

**Full detail lives in `docs/ai-context/LOCATION_TRACKING.md`'s "Trajectory
reconstruction / map matching" section — this entry only summarizes.**

**IMPLEMENTED, inactive by default — no reconstruction runs anywhere:**
- Pure domain models (`core-discovery-engine`'s new
  `com.cedervs.worlddiscovery.core.discovery.trajectory` package, zero
  Android/Room/MapLibre/H3/map-matching-engine dependency): `TrajectoryObservation`,
  `ObservationWindow`, `TrajectoryReconstructor` (+ `NoOpTrajectoryReconstructor`,
  the only implementation wired anywhere), `TrajectoryReconstructionResult`
  (`AcceptedTrajectory`/`Ambiguous`/`UnsupportedMode`/`InsufficientEvidence`/
  `NoReconstruction`, all explainable), `ReconstructionConfidence` (13
  independent components, never one collapsed float), `TransportMode`/
  `TransportModeHypothesis`, `ObservationCadenceRecommendation` (a pure
  future-cadence-intention contract, never touching real Android location
  parameters).
- Local trajectory buffer: `BufferedObservationRecord` / `:core-database`'s
  `BufferedObservationEntity` in a **new, separate** Room database
  (`TrajectoryBufferDatabase`, `trajectory_buffer.db`) — never sharing
  `WorldDiscoveryDatabase`'s file. `discovered_cells`/`WorldDiscoveryDatabase`
  are completely unmodified, still schema version 1. Idempotent dedup
  (`buildObservationDedupKey`, keyed only on fix identity —
  `elapsedRealtimeClockDomainId`/`elapsedRealtimeNanos`, never `source`/
  `processSessionId`, and never a bare timestamp/lat/lon triple — corrected
  after an independent review found the original key wrongly included
  delivery metadata), an atomic claim/lease mechanism
  (`claimPendingObservations(limit, claimedAt)` returns a
  `ClaimedObservationBatch`; the repository — never the caller — generates
  each claim's `ClaimToken` via an injectable `ClaimTokenGenerator`, and the
  DAO's read-back is scoped to that exact call's selected ids as defense in
  depth; `limit` must be `> 0`), ownership-checked `markProcessed`, a
  crash-recovery primitive (`reclaimStalledProcessing`, which also
  invalidates the old claim's token), `BufferedObservationRecord` state
  invariants enforced at construction (a row can no longer be built in an
  impossible `PROCESSING`/`PENDING`/`PROCESSED`/`DISCARDED` + lease-field
  combination), and an injectable/configurable retention policy
  (`TrajectoryBufferRetentionPolicy`, private constructor + named factories
  `boundedByAge`/`boundedByCount`/`bounded`/`unboundedForTestingOnly` so no
  implicit unbounded construction is possible, never removing an
  actively-leased row) are all implemented and tested against a real
  in-memory Room database. See `docs/ai-context/LOCATION_TRACKING.md` for
  the full corrected design across both review rounds.
- Backup exclusion: `app/src/main/res/xml/data_extraction_rules.xml` +
  `backup_rules.xml` exclude `trajectory_buffer.db` (+ WAL/SHM/journal) from
  Android Cloud Backup/Auto Backup/device-transfer **by name only** —
  `world_discovery.db`'s own backup eligibility is untouched.
- `LocationObservation` gained `bearingDegrees`/`bearingAccuracyDegrees`/
  `speedAccuracyMetersPerSecond`/`elapsedRealtimeNanos`/`isMockLocation`, all
  defaulted so every existing call site keeps compiling unchanged; captured
  uniformly by the one shared `Location.toLocationObservation()` used by all
  three tracking paths (one-shot/foreground/background) — zero changes to
  those three files themselves. `buildBufferedObservationRecord`
  (`:core-location`) bridges a `LocationObservation` into the buffer's
  domain shape — **nothing calls it yet.**

**DECIDED / NOT IMPLEMENTED this round, deliberately deferred:** wiring the
buffer into the live tracking pipeline; any real `TrajectoryReconstructor`;
choosing a map-matching engine/graph source; converting any reconstruction
result into H3/`Provenance.RECONSTRUCTED`; any change to the derived
first-discovery corridor (`DiscoveredRoute.kt`, untouched); any change to
current GPS cadence (foreground/background interval, `maxUpdateDelay`,
priority — all untouched); any Certified-side change.

**Tests (after two Codex-review correction rounds)**: `:core-discovery-engine`
350/350 (manual toolchain); `:core-location` 191/191 (manual toolchain, same
4 pre-existing Robolectric-dependent files excluded as prior rounds).
`:core-database`'s Robolectric-based `RoomTrajectoryObservationBufferRepositoryTest`
requires Room's KSP codegen, which the manual toolchain cannot perform (each
round's real Gradle attempt still hits this environment's standing
loopback/JAVA_HOME failure); its pure `BufferedObservationMapperTest` (5/5)
was compiled and run manually. `core-database` main sources (including the
claim/lease DAO) were verified to type-check cleanly against real Room 2.8.4
classes via the manual toolchain.

**Git**: this section's content was written against baseline
`8c477f298b864e9c3db23ce302c9b742cb4ca3e1`, uncommitted at the time — it
is now committed and pushed via `fcc8de9a22e365e3d738fc2a9e919f45d82c0a6e`
(still INACTIVE/NOT WIRED, see §19). The 7 pre-existing local artifacts
(`map-doc-diff.txt`, `review-context.txt`, `review.ps1`, `trip1.txt`,
`trip2.txt`, `trip3.txt`, `vehicle1.txt`) plus the also-untracked
`tracking-calibration.ndjson` (a pulled physical-device diagnostic file,
unrelated byproduct of the temporary calibration logger) remain untouched.

## 21. Reconstruction Safety Gate — Phase 3A domain contract, Correction Round 3 applied (committed, pushed via `fcc8de9a`; ACCEPTED but still INACTIVE/NOT WIRED)

Turns the Phase 2B real-ground-truth map-matching benchmark's accepted
findings (`docs/ai-context/PHASE_2B_BENCHMARK_PROTOCOL.md`) into a generic,
engine-neutral domain contract, in the same existing `core-discovery-engine`
`trajectory` package as item 20 above — no new package, no new module, no
matcher selected, no live wiring. **Full detail lives in
`docs/ai-context/LOCATION_TRACKING.md`'s "Reconstruction Safety Gate — Phase
3A (Correction Round 3 applied)" section — this entry only summarizes.**

**Three independent Codex re-review rounds found real defects.** Round 1:
fail-open acceptance, weak candidate/evidence association, a policy-bypassable
bridge check, unvalidated contradictory evidence, no transport-mode check, a
permissive policy, a decision type fabricable outside the gate. Round 2 found
Round 1's fixes for candidate/evidence identity, bridge protection, and
numeric safety were each still insufficient, and found Round 1's
authorization-boundary doc comment made a false claim about Kotlin
visibility (corrected, not merely patched over). **Round 3 found one
remaining blocker: `OrderedObservationWindowIdentity` (Round 2's own Layer A
fix) stored only `buildObservationDedupKey`'s output — on that function's
`STRONG` path (`elapsedRealtimeClockDomainId` + `elapsedRealtimeNanos`
alone), two observations sharing that pair but differing in content (e.g.
coordinate) could still receive the same window identity. Fixed by binding
observation content, not only dedup identity, into Layer A.**

**IMPLEMENTED, inactive by default — Round 3 fix:**
- **`ObservationContentFingerprint`** (new) — a canonical, deterministic,
  locale-/timezone-independent fingerprint of a `TrajectoryObservation`'s own
  safety/reconstruction-relevant content: latitude/longitude, `observedAt`'s
  epoch-second+nanosecond decomposition, `elapsedRealtimeClockDomainId`/
  `elapsedRealtimeNanos`, accuracy/speed/speed-accuracy/bearing/bearing-accuracy
  (each as the source `Float`'s own `toRawBits()`, `null` distinguished from a
  genuine `0.0f`), `provider`, `isMockLocation`. Excludes `receivedAt`/`source`/
  `processSessionId`/`batchId`/`indexInBatch` as delivery metadata, mirroring
  this codebase's own established `buildObservationDedupKey` rationale.
- **`ObservationIdentityEntry(dedupKey, contentFingerprint)`** — binds dedup
  identity and content identity explicitly, per observation, replacing the
  bare `orderedObservationKeys: List<String>` field.
  `OrderedObservationWindowIdentity.orderedObservationIdentities` is now
  `List<ObservationIdentityEntry>`; equality is over that full list, never
  the (still-retained, still-supplementary-only) `sequenceChecksum` alone.
- `buildObservationDedupKey`'s own semantics are unchanged.

**IMPLEMENTED, inactive by default — Round 2 additions (unchanged this round):**
- **`OrderedObservationWindowIdentity`** (new) — binds identity to the
  *ordered raw observation sequence* itself (reuses the existing
  `buildObservationDedupKey` per-observation identity primitive), not
  candidate geometry. Carried by `AcceptedTrajectory.observationWindowIdentity`
  (new, nullable) and mandatory on `ReconstructionSafetyEvidence`.
- **`MatcherRunIdentity`** (new, opaque value type, mirrors this package's
  own `ClaimToken` pattern) — replaces the free-form `matcherEvaluationId:
  String` the gate had nothing to compare against. Carried by
  `AcceptedTrajectory.matcherRunIdentity` (new, nullable) and mandatory on
  `ReconstructionSafetyEvidence`.
- Candidate/evidence association is now **three independent layers** (window,
  run, geometry/provenance) — `ReconstructionCandidateIdentity`'s own KDoc
  corrected to state it only covers the third.
- **Edge-based matcher-declined-continuity** — `EdgeRange`/`MatcherDeclinedInterval`
  now operate in observation-window edge space, compared against a new
  mandatory `AcceptedTrajectory.bridgedObservationEdges: Set<Int>` field (no
  default — would silently under-report bridging), fixing a real gap where a
  vertex-only check could miss a bridge between two `observed`-labelled
  vertices.
- **Numeric-extreme safety fix** — `ObservationEvidence`'s derived-speed
  check no longer uses `Duration.toNanos()` (overflow-throwing) and
  explicitly rejects a non-finite derived speed before any tolerance
  comparison (`Infinity <= Infinity` was silently validating before).
- **`unknownClassificationObservationCount`** added to `MatcherEvidence`'s
  accounting (now a 4-way exact partition).
- **`DeterministicReconstructionSafetyGate.authorize(...)`** (new) returns an
  `EvaluationResult(decision, authorization)`; `ReconstructionAuthorization`
  is a separate type from the diagnostic `AcceptReconstruction` — **its
  actual Kotlin guarantee is `internal`, module-wide, the same ceiling
  Round 1's `of` factory already had; Kotlin has no compiler-enforced
  single-caller-only construction mechanism**, corrected from an initial
  (wrong) claim that nesting + a private constructor would achieve that.

**Tests**: 206/206 passing (manual `kotlinc`/JBR toolchain, `-Xfriend-paths`)
across the Round 1/2 suites plus new `ObservationWindowContentIdentityTest`
(Round 3's own mandatory STRONG-dedup-path blocker reproduction: same dedup
key, different coordinate, still a different window identity; a full
per-field content-mutation matrix; reordering; cross-construction stability;
and a gate-level regression proving Layer A alone rejects stale-content
evidence), plus `ReconstructionSafetyIdentityAssociationTest`
(window/run identity), `EdgeContinuityTest` (edge-boundary/validation
matrix), `AuthorizationBoundaryTest`, and extensions to
`ReconstructionEvidenceCoherenceTest` (numeric extremes, 4-way accounting)
and the pre-existing `TrajectoryReconstructorTest` (new `AcceptedTrajectory`
field invariants). Full module (main + entire pre-existing test sourceset)
compiles with zero errors. One `:core-discovery-engine:test` Gradle attempt
this round again hit the same standing loopback-connection environment
failure; not retried.

**DECIDED / NOT IMPLEMENTED:** wiring the gate into any live pipeline or a
real `TrajectoryReconstructor`; converting an accepted decision into
H3/`Provenance.RECONSTRUCTED`; adaptive-cadence consumption; Certified
reconstruction policy; choosing a map-matching engine; any change to
`discovered_cells`, `DiscoveredRoute.kt`, GPS cadence, or
`Provenance`/`TrustStatus`.

**CALIBRATION REQUIRED (numeric values only):** every `ReconstructionSafetyPolicy`
field; `NetworkRouteEvidence.pathContinuityMagnitude`'s eventual threshold.

**Known, honestly-documented limitations:** a confidently-wrong single
continuous matcher output with no other unfavorable signal is outside this
gate's reach (2B-2C regression fixture); `MatcherRunIdentity` can't detect a
caller mixing up which run was *intended*; the authorization boundary is
`internal`/module-wide, not a compiler-enforced single-caller guarantee (see
above); `bridgedObservationEdges` correctness depends on the producer's own
honest disclosure — the gate cannot independently verify it.

**Git**: this section's content was written against baseline
`8c477f298b864e9c3db23ce302c9b742cb4ca3e1`, uncommitted at the time; no
file outside `core-discovery-engine`'s `trajectory` package (main and
test) was touched by this round itself. Everything named at the time as
"the pre-existing uncommitted work" alongside it
(`BufferedObservation*`, `ClassifyDiscoveredCellsByGeographicAreas.kt`,
`DiscoveredRoute.kt`, France admin GeoJSON resources,
`AdministrativeAreaNavigation.kt`, `CalibrationDiagnostic*`,
`ScreenStateCalibrationReceiver.kt`, `TrajectoryObservationCapture.kt`,
etc.) is now committed and pushed across the five-commit stabilization
series (§19) — the hierarchy/corridor files via `43594f96`, the
calibration files via `5d98269e`, and this Safety Gate/trajectory-buffer
package itself (including `TrajectoryObservationCapture.kt`) via
`fcc8de9a`. Still INACTIVE/NOT WIRED regardless — see §19.
