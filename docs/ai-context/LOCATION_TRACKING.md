# Location Tracking

## Foreground — IMPLEMENTED
Process foreground lifecycle starts the automatic tracking session; leaving foreground stops it before background registration is considered. A **user-triggered one-shot current location** also exists from phase 2; it is not a manual discovery-entry workflow.

If the initial foreground session ends because permission was missing, a grant returned through `MapScreen`'s permission launcher explicitly calls `retryLocationTrackingAfterPermissionGranted()` and can restart the session without restarting the app. The code does not implement a general continuous observer for arbitrary permission changes made externally in Android Settings; lifecycle re-entry can provide another start opportunity.

### Foreground acquisition quality — EXPERIMENTAL, physically A/B-tested increment in progress
**Trips 1–2** (Samsung SM-G998U1, forest/garden + road; then a 2 km open-road out-and-back on the D59, used as ground truth), run with `PRIORITY_BALANCED_POWER_ACCURACY` at a 15 s interval, both showed hundreds-of-meters accuracy, false excursions of several kilometers (e.g. ~2926 m in 11.3 s, implied ~258 m/s), and long sequences where the reported position stayed effectively frozen while accuracy degraded into hundreds of meters. This was visible even on the open D59 test, which weakens forest-canopy sky obstruction as the *sole* explanation, and Precise Location permission was confirmed granted on-device, which rules out Approximate-location capping as an explanation. That left `PRIORITY_BALANCED_POWER_ACCURACY` — which explicitly permits substituting coarser Wi-Fi/cell-derived fixes for GPS purely to save power — as a **plausible contributor worth testing, not a confirmed cause**.

**Trip 3** switched to `PRIORITY_HIGH_ACCURACY` (`waitForAccurateLocation(true)` added; interval still 15 s, no explicit `minUpdateIntervalMillis`) on the same D59 open-road protocol, and was dramatically better: 97 observations / 96 transitions, median accuracy ~10.4 m, best ~3.37 m, worst ~45.6 m, 94/97 fixes ≤25 m, 97/97 ≤50 m, **zero** fixes >100 m, largest transition distance ~36.9 m, max implied speed ~4.18 m/s — no 500–750 m degradation sequences like Trip 2. H3 transition path counts: 50×`pathCellCount=1`, 38×`=2`, 7×`=3`, 1×`=4` (reconstruction stayed disabled — `eligible=false` throughout — so intermediate cells were never filled). This is strong evidence for keeping `HIGH_ACCURACY` as the current experimental baseline, though one A/B trip does not universally prove it.

Physically, some H3 cells were still missing on the outbound leg and only appeared on the return — **not acceptable as final behavior**, since many real journeys are one-way. The `pathCellCount` distribution explains why quantitatively: only 50/96 transitions (52%) were single-cell (no gap); the other 46/96 (48%) already spanned 2–4 cells even at Trip 3's good per-fix accuracy — acquisition-quality improvement alone does not eliminate the need for reconstruction.

**Cadence finding, corrected**: although Trip 3's `LocationRequest` still specified a 15 s `intervalMillis` with no explicit `minUpdateIntervalMillis`, callbacks arrived with a median delta of ~7.45 s (min ~6.79 s, max ~16.44 s). Play Services' documented behavior is that `intervalMillis` is a *desired* cadence, not a strict period, and when `minUpdateIntervalMillis` is left unset the SDK applies an *implicit* minimum of roughly **half** the requested interval (~7.5 s here) rather than the full 15 s. Trip 3's numbers are compatible with opportunistic faster FLP production under that implicit ~7.5 s floor — **this is not evidence that some other app/system requester overrode our request**; our own implicit floor already permitted this cadence on its own.

**Trip 4 (this increment)**: `FOREGROUND_PROVISIONAL` now requests `PRIORITY_HIGH_ACCURACY` (unchanged) at an **explicit, controlled** `intervalMillis = 7_000L` / `minUpdateIntervalMillis = 7_000L` (both now wired into `foregroundLocationRequest` in `FusedLocationUpdatesProvider`), with `waitForAccurateLocation(true)` unchanged and `maxUpdateDelayMillis` still unwired (see below). This is **not** expected to necessarily double Trip 3's sample count — Trip 3 already delivered a ~7.45 s median interval opportunistically. The controlled question Trip 4 is designed to answer is:

> Does explicitly requesting a sustained 7-second high-accuracy cadence reduce longer gaps and missing one-way cells compared with receiving approximately 7-second callbacks opportunistically under a 15-second desired interval?

i.e. whether making the ~7 s cadence *deliberate and reproducible* (rather than an artifact of one trip's implicit-minimum behavior) measurably tightens the `pathCellCount` distribution above, particularly the 48% multi-cell fraction.

**Corrected field semantics** (see also `LocationUpdateConfig.kt`'s own doc comment):
- `intervalMillis`: desired time between updates — delivery may be faster (down to `minUpdateIntervalMillis`), slower, or occasionally absent; never a strict period.
- `minUpdateIntervalMillis`: the fastest callbacks *this request* accepts — a hard per-request floor, apart from normal FLP scheduling jitter. Left unset, Play Services applies an implicit default of roughly half `intervalMillis`, not the full value. Set explicitly to `7_000L` here, callbacks should not arrive substantially faster than ~7 s, apart from normal jitter.
- `maxUpdateDelayMillis`: batching control, **kept unwired** for this experiment.

**This is best-effort, not a guarantee, and not a filter:**
- `PRIORITY_HIGH_ACCURACY` heavily prioritizes GPS but cannot force a fix under genuinely poor sky visibility (e.g. dense forest canopy) — some degradation there is still expected and is an environmental limit, not evidence the change failed.
- `waitForAccurateLocation(true)` may briefly delay delivery of an initial low-accuracy location, in case a more accurate initial fix becomes available shortly after. This is best-effort and bounded, per the Play Services API contract — it does **not** guarantee the resulting initial fix meets any accuracy threshold, does **not** guarantee rejection of every cached/stale fix, and does **not** filter or affect any later coarse/degraded fix once the session is underway.
- The explicit `minUpdateIntervalMillis` floor governs only *this app's own request*; it does not, and cannot, prevent Play Services from computing locations more often for reasons unrelated to our request (e.g. other active requesters) — it only guarantees *our* callback won't be throttled below ~7 s by our own configuration.
- No acceptance/rejection/plausibility logic exists anywhere in this pipeline, before or after this change — see "Location quality metadata" below. **`DenyAllReconstructionEligibilityPolicy` remains the only wired eligibility policy; reconstruction, filtering thresholds, and map matching all remain fully inactive.** This increment only changes what a fix *is* and how often it arrives, never what happens to it afterward.
- `maxUpdateDelayMillis`/`minUpdateDistanceMeters`/granularity remain deliberately unset in the production request. `maxUpdateDelayMillis` specifically **must** stay unwired for the foreground profile: `FusedLocationUpdatesProvider`'s callback reads only `LocationResult.lastLocation`, never the full `LocationResult.locations` list (unlike the background path's `extractBackgroundLocationObservations`, which correctly processes a whole batch) — enabling batching without first updating that callback would silently discard every location in a batch except the newest. Any future increment that wires `maxUpdateDelayMillis` for foreground must update that callback first.
- **`LocationAcquisitionResult.LocationUnavailable`**: `FusedLocationUpdatesProvider`'s `LocationCallback` now also overrides `onLocationAvailability` — FLP's own live, per-subscription signal that it currently cannot compute a location (GPS genuinely lost — indoors, a tunnel, deep obstruction), reported independently of `isAnyLocationProviderEnabled`'s one-time-at-subscription OS-level check. **This is not equivalent to the user disabling Location Services** (`LocationServicesDisabled`): the latter is a system setting checked once; the former is transient, best-effort, and self-healing (the next `onLocationResult`/`Success` signals recovery on its own — no explicit "available again" emission exists or is needed). Reuses the pre-existing `LocationAcquisitionResult.LocationUnavailable` case rather than a new type. Still a single callback method on the same, single, already-registered `LocationCallback` — not a second request/client/subscription. Discovery submission is entirely untouched by this signal; only the live current-position marker (below) reacts to it.

Next step: a fourth physical walk (same open-road D59 protocol) at this explicit ~7 s cadence, compared against Trip 3's `pathCellCount` distribution and metrics above, plus a battery percentage reading before/after — before any further interval change, filtering layer, or reconstruction activation is considered.

## Live current-position marker — IMPLEMENTED
"Where am I right now" on the map — deliberately separate from "what have I discovered": never derived from, and never derives, any H3/discovered-cell state; never persisted; never logged with its raw coordinate. Reuses the single existing foreground acquisition stream — no second `FusedLocationProviderClient`, request, or subscription.

**Data flow**: `LocationTrackingSession.currentObservation: StateFlow<LocationObservation?>` → `AppContainer.currentLocationObservation` (typed `Flow<LocationObservation?>` at this boundary, matching `observeDiscoveredCellGeometries`'s existing convention) → `MainActivity` → `WorldDiscoveryApp` → `MapScreen` (`collectAsState`) → `DiscoveryMapView` → `CurrentPositionRendering.kt`'s `applyCurrentPosition(style, observation)`.

**Publication independence**: for every `Success`, `LocationTrackingSession` publishes `currentObservation` *before* calling `submitObservationSafely` — the marker is never delayed by, or coupled to, discovery submission or diagnostic outcomes (same independence principle already applied to submission vs. diagnostics above).

### Staleness grace window — physical flicker fix
Physical testing found the marker flickering roughly every acquisition cycle: it turned out `LocationCallback.onLocationAvailability` — wired to `LocationAcquisitionResult.LocationUnavailable` for the marker (see below) — reports brief, best-effort unavailability blips even under stable outdoor conditions, and the original design cleared the marker immediately on every such event, then republished it on the next `Success` seconds later. **`LocationUnavailable` no longer clears the marker immediately.** Instead, `LocationTrackingSession` tracks `lastSuccessReceiptMillis` — the [monotonic clock](#monotonic-clock) time the most recent `Success` was *received* — and `LocationUnavailable` schedules **at most one** clear at the fixed deadline `lastSuccessReceiptMillis + 21_000L`:
- Repeated `LocationUnavailable` events never reschedule or extend that deadline — only a genuinely new `Success` establishes a new one (and immediately cancels any pending clear).
- If no `Success` has occurred yet this session, no timer is scheduled at all — there's nothing to be stale relative to.
- If the fixed deadline has already passed by the time a `LocationUnavailable` is processed, it clears immediately rather than scheduling a needless coroutine.

**21 seconds is provisional, `CALIBRATION REQUIRED`** — three configured foreground acquisition cycles (`3 × 7_000L`), chosen to comfortably cover the ~16.4 s legitimate inter-fix delay physically observed on Trip 3, with margin. This is a **UI-only staleness policy** — not a GPS acceptance threshold, a filtering threshold, a discovery threshold, or a reconstruction threshold; it changes only how long the marker may keep showing the last successfully received fix, nothing about what reaches `SubmitDiscoveryObservation`. `PermissionDenied`, `LocationServicesDisabled`, `stop()`, and unexpected collector termination all still clear the marker **immediately**, unaffected by any pending grace window.

`LocationUnavailable` itself remains a best-effort FLP availability signal, not proof of genuine GPS loss — see `FusedLocationUpdatesProvider`'s own doc comment for why it's treated as transient rather than as an authoritative "no GPS" signal.

#### Monotonic clock
Freshness is measured with `MonotonicClock` (`System.nanoTime()`-backed by default, a tiny injectable interface for deterministic tests) — **never** wall-clock (`Instant.now()`, immune to NTP/timezone/manual-clock changes) and **never** `LocationObservation.observedAt` (an Android-reported `Location.time` that can itself be old, zero, or otherwise unreliable — using it for UI freshness would let a malformed fix timestamp corrupt an unrelated UI concern).

### Session-generation ownership (race safety)
`LocationTrackingSession`'s collector runs on a real thread pool (`Dispatchers.Default`), and coroutine cancellation is cooperative — a plain `job.cancel()` plus a direct `_currentObservation.value = null` in `stop()` is **not** sufficient to prevent an obsolete collector from publishing a stale observation afterward, or an old generation's cleanup from clobbering a newer session's already-published position. A single monitor (`positionLock`) jointly owns `(activeGeneration, currentObservation, lastSuccessReceiptMillis, pendingClearJob, job)`:
- `start()`/`stop()` are the *only* places allowed to write `_currentObservation.value` directly, each inside a `synchronized(positionLock)` block that also owns the generation/job/timer transition for that same call — `start()` allocates a new generation and clears everything before launching; `stop()` invalidates the generation and clears everything, atomically, before cancelling both the collector job and any pending stale-clear job (two independent `Job`s — cancelling one can never affect the other, or acquisition, or discovery submission).
- Every write the collector itself performs (`Success`, `PermissionDenied`, `LocationServicesDisabled`, and the collector's own termination cleanup) goes through `publishCurrentObservation(generation, value)`, which re-checks `generation == activeGeneration` inside the same lock immediately before writing, and unconditionally cancels/nulls any pending stale-clear timer as part of the same write.
- The stale-clear timer scheduled by `LocationUnavailable` needs a *finer-grained* guard than generation alone, since a superseding `Success` can arrive within the *same* generation: the delayed coroutine checks, atomically at fire time, both `generation == activeGeneration` **and** that it is still reference-identical to `pendingClearJob` (via `coroutineContext[Job]`) before writing `null`. Any newer event nulls or replaces `pendingClearJob` first, so a stale timer that races past cancellation and reaches the lock anyway correctly no-ops.
- No suspending call is ever made while `positionLock` is held; where a captured `Job` needs cancelling as a result of a lock-protected ownership change, the capture happens under the lock but the `.cancel()` call happens just after releasing it (`.cancel()` itself never suspends either — this split only keeps the critical section minimal).

### Lifecycle semantics
| Event | `currentObservation` |
|---|---|
| Before first fix | `null` |
| `Success` | published immediately; establishes a new freshness basis; cancels any pending stale-clear |
| `PermissionDenied` | cleared immediately — terminal for the session |
| `LocationServicesDisabled` | cleared immediately |
| `LocationUnavailable` | schedules at most one clear at the fixed 21s deadline from the last `Success` — never immediate, never rescheduled by repetition |
| `stop()` | cleared immediately, race-safe per above |
| Unexpected collector termination | cleared immediately, via the `finally` above |

### Rendering (MapLibre, `CurrentPositionRendering.kt`)
Custom `GeoJsonSource`/layer pairs, **not** MapLibre's `LocationComponent` (unused anywhere in this codebase). Two independent pairs, each reused in place (`setGeoJson` if the source already exists, added once otherwise — never removed/recreated), mirroring `applyDiscoveredCellGeometries`'s established pattern: the accuracy `FillLayer` is added *before* the position `CircleLayer` so it renders below the dot. `observation = null` updates both existing sources to empty `FeatureCollection`s rather than removing anything. A genuine style reload (a new `Style` instance) naturally recreates both from whatever `observation` currently holds, since `getSourceAs` returns `null` on it — no separate reload path needed.

**Accuracy contract**: Android's `Location.getAccuracy()` is an estimated horizontal uncertainty radius in meters, conventionally ~68% confidence (roughly one standard deviation of a 2D Gaussian error model) — **not** a guaranteed hard boundary. `renderableAccuracyMetersOrNull` renders a real geodesic accuracy polygon only for a finite, positive value; `null`, `NaN`, `+`/`-Infinity`, zero, or negative all render the dot only, never a fabricated fallback radius, never a crash (`isFinite()` specifically excludes both infinities — a naive `> 0.0` check alone would let `+Infinity` through).

**Geographic scaling**: `CircleLayer`'s `circle-radius` is always in screen pixels, never meters — fine for the dot (a fixed-size "you are here" marker), wrong for the accuracy circle. The accuracy polygon is instead built from real geodesic points via a new, generic `destinationPoint(from, bearingDegrees, distanceMeters)` utility (`core-discovery-engine`, beside `haversineDistanceMeters` — the direct-geodesic counterpart to that inverse-geodesic function), so it scales correctly with the map's real projection like the H3 cell polygons already do, rather than needing manual per-zoom pixel math.

**Camera independence**: `applyCurrentPosition` takes only a `Style`, never a `MapLibreMap` — structurally incapable of touching the camera, not merely a convention. No recentering, zoom, pitch, or bearing change; the user stays free to pan/zoom while the marker keeps updating.

**Visuals**: small, luminous/electric-blue dot (`#2979FF`) with a subtle white stroke/halo for visibility across basemap colors, and a translucent accuracy fill in the same hue. Provisional constants only, not final art direction (`docs/ai-context/OPEN_QUESTIONS.md`) — easy to retune.

### Future product requirements — NOT IMPLEMENTED
Recorded here so they aren't lost, not yet designed or built:
- **One-way discovery must not depend on returning over the same route.** A single outbound pass must achieve reasonable corridor coverage once reconstruction is active; relying on a return trip to fill missed cells (as currently happens) is not acceptable final behavior.
- **Vehicle/high-speed continuity remains unsolved.** At vehicle speed, even a controlled ~7 s interval covers many multiples of an H3 resolution-12 cell width (~18–19 m average edge-to-edge) — acquisition frequency alone cannot guarantee continuity at that speed, and pushing frequency further has diminishing returns against battery cost and the GNSS chip's natural output rate.
- **Future pipeline**: acquisition → quality/plausibility filtering (CALIBRATION REQUIRED) → reconstruction → H3 discovery.
- **Conditional road/path/rail map matching is future work** — evidence-based (consistent speed/heading/coincidence with a real road/rail link), applied only when movement coherently indicates road-following travel.
- **Never blindly snap legitimate off-road movement to the nearest road** — a user may legitimately be walking a field/forest/parallel path; indiscriminate snapping would misrepresent that.
- **Adaptive, speed-based cadence** (tightening/loosening acquisition frequency by movement regime) remains future calibration/design work, not implemented here.
- **PLANNED lifecycle hardening**: generation-gate `TrackingSessionState` the same way `currentObservation` now is, so an obsolete collector can never write session status (e.g. `Active`) after a newer generation exists or after `stop()` set it to `Idle`. Not implemented in this increment — deliberately out of scope, not blocking.
- **PLANNED / NOT IMPLEMENTED — multi-scale discovery visualization.** At medium/far zoom, individual H3 resolution-12 cells become visually indistinguishable. Future rendering should support: close zoom → canonical fine H3 cells (current behavior); intermediate zoom → derived corridor/area aggregation; far zoom → broader derived coverage. Every representation must derive from the canonical fine-resolution discovery data at render time — never a second, independently-computed discovery truth at coarser resolution.

## Background — IMPLEMENTED
Uses Google Fused Location Provider with a `PendingIntent`, not a permanent foreground service. Background tracking is off unless explicit application consent exists and Android permissions are sufficient. Registrar rechecks actual OS permission every time.

## BACKGROUND ACQUISITION CALIBRATION — EXPERIMENTAL
First physical vehicle calibration (round 1): with background tracking enabled and the app closed for the return leg, observations arrived roughly 10–20 minutes apart, some as coarse as ~1700 m accuracy. **Do not claim the previous request alone caused this result.** The previous configuration (Balanced Power; target interval 20 min; minimum 10 min; maximum batching delay 30 min) is *consistent with* and largely explains the observed spacing and the poor accuracy (`BALANCED_POWER_ACCURACY` can fall back to network-tier fixes), but Android background-execution throttling and Samsung-specific battery/process behavior remain possible additional contributors this alone cannot isolate.

**This experiment's question**: when World Discovery requests a substantially shorter `BALANCED_POWER` background cadence, does FLP produce useful intermediate observations that are later delivered in batches, or does Android/OEM background behavior still leave the observation history too sparse for meaningful discovery?

**Round 2 configuration** (current `BACKGROUND_PROVISIONAL` — see its doc comment in `LocationUpdateConfig.kt` for full rationale): priority unchanged at `BALANCED_POWER_ACCURACY`; `intervalMillis` 20 min → **1 min**; `minUpdateIntervalMillis` 10 min → **30 s** (kept at half of `intervalMillis`, not itself the variable under test); `maxUpdateDelayMillis` 30 min → **15 min**, deliberately kept a full 15× larger than the new `intervalMillis` (not shrunk proportionally) so genuine batching headroom is preserved — collapsing that ratio would make "FLP only computes sparsely" indistinguishable from "FLP computes densely but batching got disabled," the exact confound this experiment exists to avoid.

**Debug-only diagnostics added** (see `BackgroundLocationDiagnosticLogger.kt` / `AndroidBackgroundLocationDiagnosticLogger.kt`), entirely separate from the existing per-fix `LocationDiagnosticLogger`/`"LocationQuality"` tag: a dedicated `"BackgroundLocationCalibration"` Logcat tag records every registration attempt (configured priority/interval/min-interval/max-delay, plus outcome) and every delivery (batch size, wall-clock receipt time, and per-location observed timestamp, age at receipt, accuracy, speed, provider). Never logs latitude/longitude, an H3 cell, or any persisted trajectory. Diagnostics are wired through the same non-throwing contract as every other diagnostic logger in this module — they cannot affect registration or submission, including a fabricated `CancellationException`.

**Registration outcomes** (`BackgroundRegistrationOutcome`) — `requestLocationUpdates(LocationRequest, PendingIntent)` returns an asynchronous Play Services `Task<Void>`; the call returning normally only means the request was *submitted*, not that it succeeded, so outcomes are logged strictly from Task completion, never right after the call that starts it:
- `REGISTERED` = the Task **completed successfully**. This confirms Play Services accepted the standing `PendingIntent` request — it is **not** proof Android will actually honor the requested cadence, or that any location will ever be delivered, only that registration itself succeeded.
- `FAILED_TASK` = the Task **completed unsuccessfully** (an asynchronous Play Services failure). The exception itself is never logged — only this outcome tag — so no arbitrary or sensitive exception content ever reaches Logcat.
- `FAILED_SECURITY_EXCEPTION` = a **synchronous** permission/security race — `SecurityException` thrown by the call itself, despite the permission check having just passed.
- `SKIPPED_NO_PERMISSION` = `ACCESS_BACKGROUND_LOCATION` was absent before the request was even attempted; nothing was submitted.

Both listeners run on an inline (calling-thread) `Executor`, not Play Services' own main-thread default, since registration can be triggered from a background coroutine dispatcher with no prepared `Looper` (`BootCompletedReceiver`, `BackgroundLocationController`).

**Not a calibrated product value** — this is a diagnostic experiment, not a product decision; see `docs/discovery-engine.md` §1/§8's `[OUVERT — à calibrer]` movement/sampling thresholds. Physical results from this round should be recorded here before any further change.

**FUTURE / NOT IMPLEMENTED, requires explicit product approval before implementation** (per the background-architecture design review — see the "Deliberately not used" section below, which this list does not yet override):
- Activity Recognition / Activity Transition API bootstrap;
- a temporary, movement-window-only location foreground service (with its required persistent notification while active);
- adaptive stationary/moving acquisition state model;
- any of the above being implemented without this explicit approval step.

## Background tracking calibration diagnostic logger — TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE

A physical walking test (Samsung SM-G998U1, four phases: foreground; backgrounded with screen on; backgrounded and locked; app swiped from recents and locked) found tracking density collapsing sharply once the app left the foreground, and confirmed a live `adb logcat -s BackgroundLocationCalibration:D` capture cannot survive the test: normal Logcat ring buffers wrap too quickly, and a detached `adb shell logcat` process was killed by the OS a few minutes after USB disconnection. This section documents the resulting **offline, app-owned** diagnostic logger built to capture the same events durably instead.

**This is explicitly NOT**: production analytics/telemetry, a product feature, route/discovery history, or a permanent part of the tracking architecture. It exists solely to gather the evidence needed to diagnose the background-density question above.

**Architecture**: `CalibrationDiagnosticFileWriter` (`:core-location`) writes one NDJSON (newline-delimited JSON) line per event to the app-specific external files directory — no broad storage permission needed. **A release build never constructs this class at all** — `AppContainer.calibrationDiagnosticWriter` stays `null` unless `BuildConfig.DEBUG`, and the two sinks below fall back directly to the existing `NoOpCalibrationDiagnosticSink`/`NoOpDiscoveryDiagnosticSink` (Codex review round correction: a prior version still allocated a "disabled" writer, its executor, and its bounded queue even in release, even though it performed no file I/O — contradicting the intended debug-only architecture). Two thin adapters share one writer instance (one physical file per process): `AndroidCalibrationDiagnosticSink` (`CalibrationDiagnosticSink`, for process/app/tracking-lifecycle and location-delivery events) and `AndroidDiscoveryDiagnosticSink` (`DiscoveryDiagnosticSink`, `:core-discovery-engine`'s pure interface — that module must stay Android-free, so its real implementation lives in `:core-location` instead, mirroring why `LocationDiagnosticLogger`'s own Android implementation lives here rather than there).

**Events recorded** (see `CalibrationDiagnosticEvent.kt`'s `CalibrationLifecycleKind` for the full list): process/app foreground start+stop (`AppForegroundTrackingController`), background arm/disarm requests (`BackgroundLocationController`), background registration attempts and their outcome (`FusedBackgroundLocationRegistrar`, alongside — not instead of — the existing `BackgroundLocationDiagnosticLogger` Logcat line), every delivered location fix on both the foreground-callback and background-PendingIntent paths (batch id/size/index, fix age, accuracy, speed, provider — never a raw coordinate or H3 cell), the discovery engine's own per-fix outcome (`NEW_CELL` / `MERGED_EXISTING` / `REJECTED_PRE_H3` / `REJECTED`, via `SubmitDiscoveryObservation`'s optional `DiscoveryDiagnosticSink` parameter — see "Discovery conversion failure" and "Delivery correlation" below), `BackgroundLocationReceiver`/`BootCompletedReceiver` actually running (`AppContainer.recordCriticalCalibrationEvent` — see "Receiver durability" below), and `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` (`ScreenStateCalibrationReceiver`, a debug-only dynamically-registered receiver — the only signal in this whole pipeline able to distinguish "backgrounded, screen still on" from "backgrounded, locked," since `AppForegroundTrackingController` leaves the foreground identically in both cases).

**Privacy**: never a raw coordinate; a canonical H3 cell index is logged for the discovery-result event only (already the same resolution `DiscoveredCell` persists at — not a new sensitivity level), matching this whole file's existing "no coordinate/H3 in diagnostics" stance elsewhere, relaxed only for that one field because distinguishing `MERGED_EXISTING` from `NEW_CELL` needs some real spatial identity. `REJECTED_PRE_H3` carries no `h3Cell` at all (`null`), since no cell was ever successfully computed.

**Discovery conversion failure — `REJECTED_PRE_H3`**: a prior version computed the H3 cell (`cellConverter.toCanonicalCell`) *outside* the diagnostic try boundary, so a conversion failure could produce a `LOCATION_DELIVERED` calibration event with no matching `DISCOVERY_RESULT` at all — indistinguishable from the diagnostic pipeline simply never having reached that observation. `SubmitDiscoveryObservation` now wraps the conversion call in its own boundary and records `REJECTED_PRE_H3` (with only the exception's class name, never its message) before rethrowing. Deliberately catches only `Exception`, never `Throwable` — a genuine JVM `Error` (`OutOfMemoryError`, `StackOverflowError`, ...) still propagates completely untouched, never turned into an ordinary domain rejection; and a genuine `CancellationException` is rethrown immediately, before any diagnostic recording, exactly like every other cancellation boundary in this codebase.

**Delivery correlation**: `DiscoveryObservationDiagnostics.deliveryId` lets a background batch's `LOCATION_DELIVERED` calibration event be explicitly paired with the `DISCOVERY_RESULT` it produced, instead of an analyst inferring that pairing from file ordering alone — `SubmitBackgroundLocationObservations` passes `"$batchId:$index"` as `SubmitDiscoveryObservation.invoke`'s new optional `diagnosticDeliveryId` parameter. The foreground/one-shot paths (`LocationTrackingSession`, `SubmitCurrentLocationUseCase`) deliberately leave this `null`: correlating those would require threading a new field through `LocationAcquisitionResult.Success` — a core, widely-used domain type — purely for diagnostic purposes, judged more invasive than this temporary logger justifies. Those paths are always single-fix-per-delivery, and foreground/background tracking are never simultaneously active (`AppForegroundTrackingController`), so file-adjacency alone stays reliable there.

**Receiver durability and orchestration**: `BackgroundLocationReceiver`/`BootCompletedReceiver` actually running is itself lifecycle-critical evidence — Android may kill the process moments after `onReceive` returns, so recording that fact through the ordinary fire-and-forget path could leave the write never actually reaching disk before the process dies, making "receiver actually ran" indistinguishable from "receiver never ran." `CalibrationDiagnosticSink.recordCritical`/`CalibrationDiagnosticFileWriter.appendEventCritical` block the calling thread (bounded, default 2s) until the write has actually reached the file-writing boundary, or the bound elapses — used only for this handful of receiver-lifecycle events, never for ordinary per-fix diagnostics, which must stay fire-and-forget. `AppContainer.flushCalibrationDiagnostics()` (a bounded wait, also default 2s) is called afterward so evidence already enqueued (including any pending overflow marker — see below) is durable too before the receiver's lifetime ends. Both `recordCriticalCalibrationEvent`/`flushCalibrationDiagnostics` are wrapped in their own swallow-all boundary (`recordCriticalSafely` at the sink level, a `try`/`catch` at the `AppContainer` level) — a Codex review round found the original `recordCriticalCalibrationEvent` had no such boundary, so a *thrown* diagnostic failure (not just a timeout) could have propagated out of a "best-effort" call and skipped the receiver's real functional work entirely.

The actual ordering both receivers need — critical record attempt, then the real functional work, then a bounded flush attempt, with a `try`/`finally` around the functional work so the flush still runs (and the original exception/cancellation still propagates unchanged) even if that work fails or is cancelled — is implemented once in `runReceiverWorkWithCriticalDiagnostics` (`:core-location`, pure/Android-free) and shared by both receivers, which stay thin wrappers adding only `goAsync()`/`PendingResult.finish()`. This function is directly unit-tested (`ReceiverDiagnosticOrchestrationTest`); the receivers themselves are not (this repository has no harness for invoking a real `BroadcastReceiver.onReceive`).

**Worst-case receiver delay**: `recordCriticalCalibrationEvent` and `flushCalibrationDiagnostics` each retry internally (brief bounded backoff) rather than failing on the very first full-queue rejection, but both stay bounded by their own already-budgeted timeout (default 2s each) — retrying only makes better use of that same budget, it never extends it. Worst case added latency per receiver invocation therefore remains **≈4 seconds** (2s + 2s), independent of how many locations a background batch carries — there is exactly one critical-record attempt and one flush attempt per receiver invocation, never per location.

**Overflow accounting — `DIAGNOSTIC_OVERFLOW`**: the background write queue is bounded (500 entries); a prior version silently dropped events past that bound, which is unacceptable for diagnostic interpretation — an incomplete capture could look complete. Every write (ordinary, critical, or a rejected critical write) that's rejected because the queue is full increments an in-memory counter, and the next task the background thread actually runs — an ordinary write, a critical write, or an `awaitFlush` barrier — checks it and, if nonzero, atomically resets it and attempts to write one `DIAGNOSTIC_OVERFLOW` record carrying the lost count. If that marker write itself fails, the count is restored (atomically added back, never a plain overwrite, so a concurrent new drop from another thread is never clobbered) rather than lost — a later successful flush will still report it. `awaitFlush` specifically retries submitting its own barrier task until accepted or its timeout elapses, rather than giving up on the first attempt: the moment right after a burst of drops is exactly when the queue is still most likely to be full, so **`awaitFlush()` completing successfully now genuinely guarantees any pending overflow marker has been attempted**, not merely raced against. This never re-enqueues the marker itself onto the same queue that caused the overflow (so it can never be lost the same way), and never blocks the producer that triggered the overflow.

**Safety**: every write goes through one background thread (never the calling thread — a location callback must never block on disk I/O), rotates the file to a single backup once it exceeds 4 MiB (bounding total size to roughly 8 MiB — far more than a 30–60 minute calibration session needs; falls back to truncating the current file if the rename itself fails, so a rotation failure can never grow the file unbounded), and swallows every failure (full disk, missing directory, anything) exactly like every other diagnostic logger in this module.

**Residual limitation, stated honestly**: an abrupt process kill can still truncate the very tail of the file before any bounded flush gets a chance to run at all — this is an inherent limit of a temporary, best-effort, append-only logger with no resumable transaction log, and is deliberately not specially handled (avoiding over-engineering a temporary tool). What the fixes above guarantee is narrower but real: **whenever a receiver's completion actually reaches its explicit flush call, any pending overflow/loss evidence has been attempted before that call returns** — the residual gap is only the case where the process dies *before* that flush call is ever reached (or mid-flush, faster than its own bound). Treat a non-parseable final line, or the complete absence of an expected final flush's evidence, as an expected artifact of process death, not a bug.

**Retrieval** (device unlocked, USB connected, debug build):
```
adb pull /sdcard/Android/data/com.cedervs.worlddiscovery/files/calibration-diagnostics/tracking-calibration.ndjson .
```
No `run-as` needed — this is the app's own external-storage directory, not internal storage.

**Removal point**: once the background-density question this exists to answer is resolved and a real fix (or a decision not to fix) is recorded here, delete `CalibrationDiagnosticFileWriter.kt`, `CalibrationDiagnosticEvent.kt`, `CalibrationDiagnosticSink.kt`, `AndroidCalibrationDiagnosticSink.kt`, `AndroidDiscoveryDiagnosticSink.kt`, `ScreenStateCalibrationReceiver.kt`, `ReceiverDiagnosticOrchestration.kt` (all `:core-location`), `DiscoveryDiagnosticSink.kt` (`:core-discovery-engine`) and its now-unused parameters on `SubmitDiscoveryObservation`, and every `calibrationDiagnosticSink`/`discoveryDiagnosticSink`/`calibrationDiagnosticWriter`/`screenStateCalibrationReceiver`/`recordCriticalCalibrationEvent`/`flushCalibrationDiagnostics` reference in `AppContainer`, `BackgroundLocationReceiver`, and `BootCompletedReceiver` (the latter two would then revert to plain `goAsync()` wrappers with no diagnostic calls at all).

## Batch behavior — IMPLEMENTED
A `LocationResult` can contain multiple locations. Process **all** of them, ordered safely, preserving each `Location.time`. Do not regress to `lastLocation` and do not stamp the whole batch with `Instant.now()`. This now also holds for the one-shot and continuous foreground paths, not just background — see below.

## Location quality metadata — PARTIALLY IMPLEMENTED
A shared `LocationObservation` (coordinate, `observedAt`, `accuracyMeters`, `speedMetersPerSecond`, `provider`) is now the one place `android.location.Location` is converted for all three paths (one-shot, foreground-continuous, background). `observedAt` is always the fix's own `Location.time`, never `Instant.now()` at processing time, and is never silently replaced even when old or zero — foreground/one-shot now match the batch-background behavior above instead of diverging from it. `accuracyMeters`/`speedMetersPerSecond` are `null` when `Location` itself reports none (`hasAccuracy()`/`hasSpeed()`), never a guessed default.

This metadata is diagnostic only, logged to Logcat (never the raw coordinate or a derived H3 cell, never a durable/permanent store) so a future suspect discovered cell is diagnosable from recent device logs — see the ~93 m isolated cell that prompted this. **No acceptance/rejection decision exists anywhere in this pipeline.** Filtering itself, its exact algorithm and thresholds, a durable/longer-retention diagnostic buffer, and any richer per-observation provenance stay **CALIBRATION REQUIRED** / not implemented — see `docs/discovery-engine.md` §23 for the already-decided product principle (reject obvious GPS noise before validating a discovery) this metadata exists to eventually support.

## Spatial continuity / reconstruction — PARTIALLY IMPLEMENTED, inactive by default
A ~15 s fixed foreground sampling interval can leave H3 cells physically crossed between two fixes undiscovered, even walking — worse for faster movement. `docs/discovery-engine.md` §3/§23/§24 already validate the underlying product principle (corridor continuity, reject obvious noise, reconstruct between reliable points); this phase is architecture only — **no reconstruction is active anywhere in production.**

- **`H3GridTraversal`** (`core-discovery-engine`, JVM-test implementation `H3JavaGridTraversal`; Android implementation `AndroidH3GridTraversal` in `:app`, mirroring `AndroidH3CellConverter`'s `newSystemInstance()` native-loading pattern): a narrow abstraction around H3's native `gridPathCells`, deliberately separate from `H3CellConverter`. Given two H3 cells, returns the grid path between them (inclusive of both endpoints) — but only after validating **contract preconditions**, which are never silently turned into `null`: both `h3Index` values must be genuine H3 addresses, both cells' real H3 resolutions must match each other, and each `CanonicalCell.resolution` must match what's actually encoded in its own `h3Index`. Any violation throws (`IllegalArgumentException`, or `NumberFormatException` — a subtype of it — for a non-hex-parseable index), verified directly against the real library rather than assumed. **Only once both cells are individually valid and mutually comparable** does an expected *operational* traversal failure (a pentagon-distortion cell encountered on the path, or an excessive/problematic distance — both verified as real, reachable `H3Exception` cases) become `null`; a contract violation is never confused with this normal "no path available" outcome. No map/road data involved; H3's grid-index space is antimeridian-agnostic by construction (verified with a real crossing pair), unlike geometric/lat-lng rendering.
- **`DiscoveredCellMerger`** now has one explicit exception to its normal "chronologically most recent provenance wins" rule: an `OBSERVED`/`RECONSTRUCTED` pair, in either arrival order and regardless of which is chronologically newer, always resolves to `OBSERVED` — a direct observation is never demoted by a reconstruction that happens to also pass through the same cell. No priority is defined between `IMPORTED`/`MANUAL_NON_CERTIFIED` and anything else; their existing chronological behavior is unchanged.
- **`ForegroundReconstructionScheduler`** (`core-location`): a pure, testable class that evaluates one transition between two `LocationObservation`s and can produce a `ReconstructionCandidate` — it never persists anything and never calls `SubmitDiscoveryObservation` itself. A `Candidate` exposes only `intermediateCells`: the H3 path is inclusive of both endpoints, but origin and destination came from real observations and are stripped before the candidate is returned — they are never presented as `RECONSTRUCTED`, they stay conceptually `OBSERVED`. Same cell, or grid-adjacent cells, produce an empty `intermediateCells` list — there is nothing strictly between them to reconstruct. No policy for the intermediate cells' own timestamps is defined anywhere in this increment. Its eligibility policy is injected; the only policy used anywhere today is `DenyAllReconstructionEligibilityPolicy` (the constructor's own default, unchanged by the calibration work below), so no `Candidate` can ever be produced in production and H3 is never even called through this class. **Still not wired into `LocationTrackingSession`** — no functional notion of "last accepted observation"/trusted anchor exists anywhere in this codebase, so nothing calls this scheduler during real tracking. It never assumes either transition endpoint is validated ground truth.
- **`ForegroundTransitionDiagnostics`** (`core-location`) — **debug-only field-calibration instrumentation, IMPLEMENTED and wired, but strictly diagnostic, not a step toward reconstruction.** A separate class from `ForegroundReconstructionScheduler` above, on purpose: it computes an H3 path between two consecutive `LocationObservation`s for diagnostic purposes only, entirely independent of any `ReconstructionEligibilityPolicy` — there is no policy to consult at all, so `eligible` in its output is always `false` (nothing was evaluated, not "denied"). It never returns/produces a `ReconstructionCandidate` and never persists a cell. `LocationTrackingSession` keeps the single most recent `LocationObservation` **local to the coroutine each `start()` launches** (not a field) purely to pair it with the next one for this diagnostic — never persisted, never survives past that collector, never treated as a trusted "anchor". A fresh collector always begins paired with nothing, so `stop()` needs no separate cleanup and no stop/restart interleaving can leak a stale pre-stop observation into a new collector.
  - **Wiring is debug-only at the object-graph level, not just a silenced logger.** In `AppContainer`, `ForegroundTransitionDiagnostics` (and the `AndroidH3GridTraversal`/`AndroidTransitionDiagnosticLogger` it needs) is constructed only when `BuildConfig.DEBUG` is true; in a release build neither is constructed at all and `LocationTrackingSession` receives `transitionDiagnostics = null`. A release build therefore performs **zero** H3 path/distance/transition computation for calibration — the instrumentation is absent from the release object graph, not merely non-verbose.
  - **Ordering**: for every observation, `LocationTrackingSession` submits to `SubmitDiscoveryObservation` first, unconditionally; both diagnostics (the existing per-fix `LocationDiagnosticLogger` and this transition diagnostic) run only after, and never delay, block, or gate that submission.
  - **`CancellationException` boundary**: only the `submit` call to `SubmitDiscoveryObservation` is a genuine job-cancellation boundary, because only that call is `suspend` and can really be cancelled — its `catch (e: CancellationException) { throw e }` is deliberate and must keep propagating. Neither `LocationDiagnosticLogger.log` nor `TransitionDiagnosticLogger.log`/`ForegroundTransitionDiagnostics.record` is `suspend`, so nothing in either diagnostic path can observe real structured-concurrency cancellation — any `CancellationException` thrown from within them is necessarily fabricated by a misbehaving component, not real cancellation. Both `LocationDiagnosticLogger.logSafely` and `TransitionDiagnosticLogger.logSafely` therefore swallow *every* `Throwable`, including a fabricated `CancellationException`, exactly the same as any other diagnostic failure — no special-casing. `logSafely` is the single shared enforcement point for `LocationDiagnosticLogger`, so this holds identically across all three of its callers: `LocationTrackingSession` (foreground-continuous), `SubmitCurrentLocationUseCase` (one-shot), and `SubmitBackgroundLocationObservations` (background) — none of the three can be stopped by a misbehaving diagnostic logger, fabricated cancellation included.
  - **Logged content**: `TransitionDiagnosticLogger` (+ `AndroidTransitionDiagnosticLogger`, same `LocationQuality` Logcat tag as `LocationDiagnosticLogger`, same non-throwing/debug-gated pattern) logs real aggregated values, not booleans — time delta, geodesic distance (via the standalone `haversineDistanceMeters`, deliberately kept out of `H3CellConverter`), implied speed, both fixes' accuracy and Android-reported speed when available, whether an H3 path was computed, and its cell count. It never logs a coordinate, an H3 index, or the full cell path.

**Still `CALIBRATION REQUIRED` / not implemented — none of this is decided by the architecture above:**
- any real eligibility policy (replacing deny-all) and its numeric thresholds (max plausible distance/speed/time between two fixes before reconstruction is attempted);
- what makes an observation a trusted "anchor" at all (the accuracy/freshness/plausibility filter from the GPS-quality analysis is the same unresolved prerequisite);
- background reconstruction (foreground-only for now);
- dynamic acquisition frequency by movement regime;
- map matching (deferred entirely — off-road use is core to this product; H3-native `gridPathCells` needs no map/road data for the case this phase targets);
- any Global/À pied transport-mode distinction (the scheduler's inputs are purely physical/sensor-derived and stay mode-agnostic by construction);
- any Certified-side change (none made; client-side reconstruction has no authority there regardless).

## Trajectory reconstruction / map matching — Phase 1 foundations — PARTIALLY IMPLEMENTED, inactive by default

Follows a dedicated architecture study this round (grounded in `docs/discovery-engine.md` §24/§26 and `docs/certified-mode.md` §8/§10, and in a real 20-minute physical background-tracking test — 43/15/11/11 observations across 4 phases, background cadence ≈27s in that test) that concluded the real product problem is **observation spacing during fast movement**, not a tracking failure, and that a future reconstruction engine should work on a real geographic trajectory (eventually road-network map matching), converting to H3 only at the very end — **not** on the H3 grid directly, unlike the [Spatial continuity / reconstruction](#spatial-continuity--reconstruction--partially-implemented-inactive-by-default) section above, which remains a separate, still-inert, H3-only mechanism. The two are intentionally not merged: this section's `com.cedervs.worlddiscovery.core.discovery.trajectory` package never imports an H3 type, and nothing here activates `ForegroundReconstructionScheduler`/`DenyAllReconstructionEligibilityPolicy`.

**No reconstruction runs anywhere in this app.** This phase built only the durable domain boundary a future strategy will plug into, plus the local buffer that future strategy will read from — **nothing wires either into the live tracking pipeline yet.**

**Correction round (independent Codex review):** an independent review of this phase found and required fixing a real correctness bug in the dedup/identity design and a real concurrency-safety bug in the buffer's processing primitives, both described in detail below. Both are now fixed and tested; the bullets in this section describe the **corrected** design, not the original one. The buffer remains entirely dormant — the correction did not, and did not need to, wire anything into the live pipeline.

**IMPLEMENTED — pure domain models** (`core-discovery-engine`'s `com.cedervs.worlddiscovery.core.discovery.trajectory` package, zero Android/Room/MapLibre/H3/Valhalla-OSRM-GraphHopper dependency by construction): `TrajectoryObservation`/`ObservationWindow` (the reconstruction input shape); `TrajectoryReconstructor` (a `fun interface`, `reconstruct(window, context) -> TrajectoryReconstructionResult`) with `NoOpTrajectoryReconstructor` as the only implementation wired anywhere — it always returns `NoReconstruction`/`CONFIGURATION_DISABLED`, mirroring `DenyAllReconstructionEligibilityPolicy`'s existing "infrastructure exists, nothing activated it" posture; `TrajectoryReconstructionResult` (`AcceptedTrajectory`/`Ambiguous`/`UnsupportedMode`/`InsufficientEvidence`/`NoReconstruction`, every variant explainable via `reasons: List<ReconstructionReason>`); `ReconstructionConfidence` (13 independent, individually-`Unknown`-capable components — gpsQuality, temporalCoherence, gapDurationAndDistance, kinematicPlausibility, observationToCandidateDistance, topologicalContinuity, pathAmbiguity, networkCoverage, transportModeConfidence, bearingCoherence, speedCoherence, modeChangeLikelihood, graphFreshness — deliberately never collapsed into one float); `TransportMode`/`TransportModeHypothesis`; `ObservationCadenceRecommendation`/`ObservationCadenceLevel` (`NORMAL`/`INCREASED`/`HIGH`) — a pure *intention* contract a future strategy could emit, never itself touching `LocationRequest`/`FusedLocationProviderClient` parameters. **`AcceptedTrajectory`'s invariants are enforced at construction (corrected/hardened this round)**: `geometry.size >= 2`; every `observedIndices`/`inferredIndices` entry is a valid `geometry` index; the two sets never overlap; their union covers every `geometry` index (no point without a provenance); `engineVersion >= 1`; `inputObservationCount > 0` and every `observationMatches[i].observationIndex` is a valid index into that count (the field that makes this validatable at all, since `ObservationNetworkMatch` alone has no visibility into the input window's size); `geometry`/`observedIndices`/`inferredIndices`/`parameters`/`observationMatches`/`reasons` are defensively copied so a caller mutating its own list/set/map after construction cannot affect the built instance (`reasons` was missed in the first hardening pass and added in the second review round). `ObservationNetworkMatch.distanceMeters`, when present, must be finite and `>= 0`, and `observationIndex` must be non-negative.

**Product nuance on `NoReconstruction`, recorded now for whichever strategy eventually replaces `NoOpTrajectoryReconstructor`:** it is a normal, explicit, expected result — but on a clearly road-based movement, a future strategy should make a genuine effort to reconstruct via the network before falling back to it. `NoReconstruction` is a safety net for real ambiguity/insufficient evidence/incompatible networks, never a default first response to any gap.

**IMPLEMENTED — the local trajectory buffer** (Codex review round's own explicit product approval: bounded, short-lived, local, private raw-coordinate storage *for reconstruction purposes only*, distinct from `discovered_cells`): `BufferedObservationRecord` (pure domain shape) / `BufferedObservationEntity` (`:core-database`, table `buffered_observations`) in a **separate database**, `TrajectoryBufferDatabase` (`trajectory_buffer.db`) — deliberately never sharing `WorldDiscoveryDatabase`'s file, for two reasons: (1) backup-exclusion targeting by filename (see below) without touching `world_discovery.db`'s own backup eligibility, (2) blast-radius isolation of a genuinely different-lifecycle dataset. `discovered_cells`/`WorldDiscoveryDatabase` remain completely unmodified, still schema version 1.

- **Deduplication — fix identity vs. delivery metadata**: `buildObservationDedupKey` (pure, tested) never uses a bare `(timestamp, lat, lon)` triple, and — **corrected this round** — never uses `source`/`processSessionId`/`batchId`/`indexInBatch` either, since those are *delivery metadata* (how/where a fix arrived), not *fix identity* (which physical GPS fix it is). The original version of this key did include `source`/`processSessionId`, which meant the exact same Android fix redelivered under a different source (a foreground/background transition) or a different process session (a process restart, a redelivered `PendingIntent` picked up by a fresh process) was wrongly treated as a new physical observation — a real bug, not a style preference. Primary identity is now `elapsedRealtimeClockDomainId + elapsedRealtimeNanos` (`FixIdentityGuarantee.STRONG`) when `elapsedRealtimeNanos` is genuinely available (non-zero) — a monotonic per-fix nanosecond reading Android assigns once, only ever comparable within the same clock domain (a different clock domain with a coincidentally identical `elapsedRealtimeNanos` is never assumed to be the same fix). A `0` `elapsedRealtimeNanos` (never genuinely available, e.g. a hand-built test fixture) falls back to a `FixIdentityGuarantee.WEAK` key built from `(providerTimeEpochMillis, lat, lon, accuracyMeters)` — deterministic, still never a bare triple, but explicitly documented as lacking the strong path's collision-free guarantee; the two guarantee levels carry disjoint key prefixes so they can never collide with each other. Enforced by a `UNIQUE` index plus `OnConflictStrategy.IGNORE` at the DAO — insertion is atomic and idempotent by construction, never a separate racy exists-check.
- **Ordering**: `Location.time` (wall clock) is explicitly never trusted as monotonic (device clock/NTP/timezone adjustment). `elapsedRealtimeNanos` is the trustworthy *relative* signal, but only within one `elapsedRealtimeClockDomainId`. **Clock-domain production status: NOT WIRED / ENGINEERING DESIGN REQUIRED.** Android exposes no stable, permission-free per-boot UUID, and this phase deliberately does **not** fabricate a boot identity — an earlier version of this document inaccurately described a "real capture path" that conservatively equated `elapsedRealtimeClockDomainId` with `processSessionId`; no such capture path exists (nothing in this codebase calls `buildBufferedObservationRecord` at all), so that claim has been removed. A future implementation must either find and wire a genuine boot-scoped signal, or make an explicit, reviewed engineering decision to treat every process session as its own clock domain (conservative: never wrongly merges two domains, at the cost of treating a mere process restart as a new one). Test code that sets the two fields equal is exercising dedup logic only, not asserting production behavior. `BufferedObservationRecord.id` (Room `AUTOINCREMENT`, never reused even after a purge empties the table) is the always-available, always-monotonic *retrieval/insertion* order — **never a claim about the physical order of the underlying trip**; a future window-normalizer must still apply the same clock-domain-scoped `elapsedRealtimeNanos` comparison rule above, never a naive cross-domain comparison, with wall-clock time treated only as secondary/anomaly-detection information.
- **Crash recovery — atomic claim/lease (corrected across two review rounds)**: `PENDING -> PROCESSING -> PROCESSED` states (plus `DISCARDED`, for retention acting directly). The original version of this primitive split claiming into two separate calls — `pendingObservationsOrderedBySequence()` then a separate `markProcessing(ids)` — which had a real race: two concurrent workers could both read the same `PENDING` ids before either marked them, and both would then believe they owned the same rows. This became a single atomic operation, `TrajectoryObservationBufferRepository.claimPendingObservations(limit, claimedAt)` (Room `@Transaction`), but its **first version still had a gap a second review round found**: the claim's ownership token was a caller-supplied `String`, and the DAO's read-back query filtered by that token alone (`rowsForToken`) — so a token accidentally reused across two claim calls (a caller bug, not a repository guarantee) would make the second claim's result silently include the first claim's rows too. **Corrected**: the token (`ClaimToken`, a validated, non-blank value type) is now generated internally by the repository's own `ClaimTokenGenerator` (`UuidClaimTokenGenerator` in production) and simply returned to the caller inside a `ClaimedObservationBatch(claimToken, observations)` — a caller can no longer construct or supply one. The DAO's read-back (`rowsForIdsAndToken`) is additionally scoped to the exact ids selected by *this* call, not just the token, so the result is correct even in the token-reuse scenario as defense in depth, not only because tokens are now unique by construction. `markProcessed(ids, claimToken, processedAt)` only affects rows currently owned by that exact token, so a worker can never terminate a lease it does not own. `reclaimStalledProcessing(olderThan)` resets a stale `PROCESSING` row (a process that claimed but crashed before calling `markProcessed`) back to `PENDING`, **clearing the old `claimToken`** so a late `markProcessed` call from the original (now-stale) owner cannot terminate whichever newer lease a subsequent claim gives the row. `claimPendingObservations`'s `limit` must be strictly positive — `0` or negative is rejected (`IllegalArgumentException`), never silently treated as "unlimited"; `pendingObservationsOrderedBySequence` (unbounded, nullable `limit`) remains the separate read-only diagnostic path for "give me everything". A `BufferedObservationRecord` can no longer even be *constructed* in an impossible combination (`PROCESSING` without a `claimToken`/`processingStartedAt`, `PENDING`/`DISCARDED` with one, `PROCESSED` without a retained `claimToken`/timestamps) — this used to be documented only, now it is a constructor-level invariant (`IllegalArgumentException` on violation), closing the gap the second review round flagged (the type only *said* this was impossible; nothing enforced it). `pendingObservationsOrderedBySequence()` still exists as a **read-only, non-claiming** snapshot for diagnostics/tests — explicitly documented as never safe to use for deciding what to process, since a concurrent claim can remove any of its rows before the caller acts. The claim/lease primitives are tested against a real in-memory Room database (`RoomTrajectoryObservationBufferRepositoryTest`), including a dedicated regression test that deliberately injects a broken/reusing `ClaimTokenGenerator` to prove the id+token scoping holds even then; see this document's own honesty note below on what that test suite can and cannot prove about genuine multi-thread concurrency. No component transitions any row to `PROCESSING`/`PROCESSED` in the live app yet.
- **Retention**: `TrajectoryBufferRetentionPolicy` — `maxAge`/`maxObservationCount` independently optional, **exact values CALIBRATION REQUIRED, not decided here** (mirrors `DiscoveredRoute.kt`'s own `*_CALIBRATION_REQUIRED` convention). `purgeAccordingTo` evaluates the policy **live** against `now` and each row's own `receivedAt` (never a value frozen at insert time), so changing the policy takes effect immediately across everything already buffered. Purge never removes a row currently `PROCESSING` (an active lease), regardless of how old it is — a stale lease must go through `reclaimStalledProcessing` first, so purge can never silently delete a row a worker still believes it owns. No byte-size bound is modeled — `maxObservationCount` is the practical, testable proxy, since precisely enforcing on-disk SQLite/WAL size is disproportionate complexity for this bounded buffer's small, roughly-fixed row size. **Corrected this round: no implicit unbounded construction is possible any more.** The first correction round renamed the "no bound" constant to `unboundedForTestingOnly()`, but a second review round found this was cosmetic: the primary constructor (`TrajectoryBufferRetentionPolicy(maxAge = null, maxObservationCount = null)` by default) remained public, so a call site could still silently end up unbounded without ever calling that function. The constructor is now **private**; every instance is built through a named factory — `boundedByAge(...)`, `boundedByCount(...)`, `bounded(...)`, or `unboundedForTestingOnly()` — so an unbounded policy can now only be reached by a future call site explicitly typing "ForTestingOnly". `maxAge`/`maxObservationCount` themselves remain CALIBRATION REQUIRED either way.
- **Privacy / backup**: `app/src/main/res/xml/data_extraction_rules.xml` (API 31+) and `backup_rules.xml` (API 26-30, still consulted by `android:fullBackupContent` on those levels) exclude `trajectory_buffer.db` and its WAL/SHM/journal companion files from Android Cloud Backup / Auto Backup / device-transfer **by name only** — `world_discovery.db` is untouched, still eligible for backup exactly as before this round. **A `DELETE` is a genuine SQL delete, not a claim of forensic erasure**: SQLite's WAL journal can retain recently-written bytes until checkpointed, and no `PRAGMA secure_delete` or equivalent is enabled (a real cost/behavior tradeoff, not attempted without explicit review). Never exposed to `feature-map`/`feature-journey` — no repository or DAO reference exists outside `:core-discovery-engine`/`:core-database`.
- **`receivedAt` vs. `observedAt`**: `observedAt` is the fix's own Android-reported time (`Location.time`); `receivedAt` is meant to be the moment World Discovery's own process actually ingested the observation. **Since the buffer is not wired into any live pipeline, nothing in this codebase actually produces a real `receivedAt` today** — every value seen in tests is supplied directly by test code, not derived from a real clock. A future wiring point should obtain it from an injectable `Clock`-style boundary (matching this codebase's testability conventions elsewhere), not `Instant.now()` called inline at an arbitrary point in real tracking code — that boundary is not designed here, since no real tracking code calls into this buffer yet.

**On testing genuine concurrency (honesty note):** `RoomTrajectoryObservationBufferRepositoryTest` proves the claim/lease *invariants* — that two sequential claims never return overlapping rows, that a stale token can never terminate a lease it no longer owns, that a reclaimed row's new owner is the only one who can complete it, and that a claimed row always carries both lease fields together — using real, sequential (not genuinely multi-threaded) calls against a real in-memory Room database. It does **not** attempt a true concurrent-thread race against Robolectric's SQLite, since that would be inherently timing-dependent and would make the suite flaky rather than reliably prove anything; the atomicity guarantee itself comes from Room's `@Transaction` wrapping the claim's select/update/read-back into one SQLite transaction, which is a property of the underlying transaction mechanism, not something a flaky thread-race test would prove more convincingly than the deterministic invariant tests already do.

**IMPLEMENTED — Android metadata capture, all three tracking paths uniformly**: `LocationObservation` gained `bearingDegrees`, `bearingAccuracyDegrees`, `speedAccuracyMetersPerSecond`, `elapsedRealtimeNanos`, `isMockLocation` — all with defaults, so every existing construction call site (tests included) keeps compiling unchanged. Captured in the one shared `Location.toLocationObservation()` extension already used by all three paths (one-shot, foreground, background), so this required **zero changes** to `FusedLocationProvider`/`FusedLocationUpdatesProvider`/`extractBackgroundLocationObservations` themselves. **Capturing a signal is not the same as acting on it** — no acceptance/rejection criterion changed. `buildBufferedObservationRecord` (`:core-location`) bridges a `LocationObservation` into a `BufferedObservationRecord`, ready for `TrajectoryObservationBufferRepository.insert` — **nothing in this codebase calls it yet, and `NoOpTrajectoryReconstructor` remains the only `TrajectoryReconstructor` that exists anywhere, itself not wired to anything either.**

**DECIDED / NOT IMPLEMENTED (explicitly deferred past this phase, per this round's own scope):**
- wiring the buffer into the live tracking pipeline at all (`AppContainer`, `LocationTrackingSession`, `SubmitBackgroundLocationObservations`, `FusedLocationUpdatesProvider`) — which call sites, what threading/performance guarantee on the hot location-callback path, any consent gating beyond what already governs whether location processing happens at all;
- any real `TrajectoryReconstructor` implementation, geometric or network-based;
- choosing a map-matching engine/graph source (Valhalla/OSRM/GraphHopper/other) — deliberately not evaluated further this round beyond the prior architecture study;
- converting any `TrajectoryReconstructionResult` into H3 cells or a `Provenance.RECONSTRUCTED` `DiscoveredCell` — `SubmitDiscoveryObservation`/`DiscoveredCellMerger`/`discovered_cells` are completely untouched by this phase;
- any change to the derived first-discovery corridor (`DiscoveredRoute.kt`) — unmodified;
- any change to current GPS cadence (foreground interval, background interval/`maxUpdateDelay`/priority) — unmodified;
- any Certified-side change — none made; a future `TrajectoryReconstructionResult` has no Certified authority regardless (`docs/certified-mode.md` §8/§10 already require server validation for any reconstructed candidate).

**ENGINEERING DESIGN REQUIRED:**
- a genuine, reliable source for `elapsedRealtimeClockDomainId` (a real Android boot-scoped signal, if one can be found and justified) — or an explicit, reviewed decision to keep treating every process session as its own clock domain instead; neither is decided yet (see the "Ordering" bullet above);
- the actual windowing/segmentation policy that decides where one `ObservationWindow` starts/ends (gap-based trip boundaries vs. a fixed count/time span) — `ObservationWindow` itself only enforces non-empty, no boundary policy exists;
- a real `TrajectoryReconstructor` implementation and, eventually, its graph/network integration — see `docs/ai-context/MAP_MATCHING_ENGINE_STUDY.md` (Phase 2A) for the Valhalla/Meili vs. OSRM Match vs. GraphHopper Map Matching technical study; no engine chosen, no benchmark run yet, no integration;
- the future adaptive-cadence *consumer* (a component that reads an `ObservationCadenceRecommendation` and actually adjusts real Android location parameters) and its hysteresis/debounce state machine — the contract exists, no machinery reads it;
- live-pipeline wiring for the buffer once the above is ready to consume it, including where a real `receivedAt` (see above) and `claimPendingObservations`/`markProcessed`/`reclaimStalledProcessing` calls would actually be driven from.

**CALIBRATION REQUIRED:**
- `TrajectoryBufferRetentionPolicy`'s actual `maxAge`/`maxObservationCount` values;
- the stale-lease reclaim timeout (`reclaimStalledProcessing`'s `olderThan` distance from "now") once a real consumer exists to drive it;
- any future `ReconstructionConfidence` component's actual computation and acceptance threshold;
- `ObservationWindow` sizing/boundary parameters once a windowing policy exists;
- `ObservationCadenceRecommendation` trigger thresholds and hysteresis timing, once a consumer exists.

## Reconstruction Safety Gate — Phase 3A (Correction Round 3 applied) — IMPLEMENTED (domain contract only), inactive by default

Follows the Phase 2B real-ground-truth map-matching benchmark (`docs/ai-context/PHASE_2B_BENCHMARK_PROTOCOL.md`)
but deliberately produces **no production decision, no matcher selection, and no live wiring**.

**This section describes the design after three independent Codex re-review rounds.** Round 1 fixed
fail-open acceptance, a weak candidate/evidence association check, a bypassable bridge check,
unvalidated contradictory evidence, no transport-mode check, an over-permissive policy, and a decision
type fabricable outside the gate. Round 2 found the Round 1 fixes for candidate/evidence identity,
matcher-declined-bridge protection, and numeric safety were still each insufficient, and found the
Round 1 "authorization boundary" doc comment made a false claim about Kotlin visibility. **Round 3
found one remaining blocker in Round 2's own observation-window identity (Layer A below): on
`buildObservationDedupKey`'s `STRONG` path, dedup identity depends only on
`elapsedRealtimeClockDomainId`/`elapsedRealtimeNanos`, so two observations sharing that pair but
differing in content (e.g. coordinate) could still receive the same window identity — fixed by binding
observation *content*, not only dedup identity, into Layer A.** The design below is the corrected,
current, real behavior.

**Core architectural principle** (unchanged): a map matcher/`TrajectoryReconstructor` proposes a
reconstruction; it never directly authorizes discovered cells. `observations -> reconstruction
candidate -> reconstruction evidence -> SAFETY GATE -> decision -> (future) H3/discovery processing`.

**Mandatory positive-evidence contract for acceptance** (Round 1, unchanged in spirit, now including
two additional mandatory identity layers). `AcceptReconstruction` is **fail-closed**: reached only
once every mandatory fact is *affirmatively* established — never merely because nothing *known* is
wrong.

**Candidate/evidence association — three independent layers (Round 2 rewrite).** Round 1 shipped a
single geometry/provenance fingerprint (`ReconstructionCandidateIdentity`) and claimed it solved
candidate/evidence association; Round 2's re-review correctly found this insufficient — a geometry-only
fingerprint cannot prove which *original ordered observations*, or which *matcher run*, produced a
candidate (two different observation windows can honestly reconstruct to byte-for-byte identical
geometry/indices/endpoints). Three layers are now checked, in order, each decisive on a real mismatch:
1. **Layer A — observation window** (`OrderedObservationWindowIdentity`, new type). **Ordered
   observation-window identity binds the ordered observation dedup identity plus a canonical
   safety/reconstruction-relevant `TrajectoryObservation` content fingerprint — not "raw byte
   identity" and not the dedup key alone.** Computed directly from the ordered raw
   `TrajectoryObservation` sequence itself (never reconstructed later from candidate geometry).
   **Correction Round 3 fix**: the first version of this type stored only
   `buildObservationDedupKey`'s own output per observation — on that function's `STRONG` dedup path
   (`elapsedRealtimeClockDomainId` + `elapsedRealtimeNanos` alone), two observations sharing that
   pair but differing in content (e.g. coordinate) produced the *same* dedup key and therefore could
   receive the same window identity. `buildObservationDedupKey`'s own semantics are unchanged by this
   fix — dedup identity ("is this a redelivery of the same physical fix") and content identity ("what
   does this observation actually say") are now bound *both*, per observation
   (`ObservationIdentityEntry(dedupKey, contentFingerprint)`).
   - **Canonical content fields included** (`ObservationContentFingerprint`): latitude/longitude
     (raw `Double`, unrounded); `observedAt`'s epoch-second + nanosecond decomposition (full
     precision, timezone-free, locale-free — `Instant` is always UTC internally, so decomposing it
     this way rather than formatting it is what makes "timezone/locale independent" concrete rather
     than merely asserted); `elapsedRealtimeClockDomainId`/`elapsedRealtimeNanos` (also part of the
     dedup key, but restated here directly is exactly what closes this round's blocker);
     accuracy/speed/speed-accuracy/bearing/bearing-accuracy (each stored as the source `Float`'s own
     `toRawBits()` `Int` when present, `null` when the source field itself is `null` — a genuine
     `0.0f` reading and "never measured" are never conflated); `provider`; `isMockLocation`.
   - **Intentionally excluded fields, and why**: `receivedAt` (when *this app process* ingested the
     fix — delivery timing, not fix content), `source` (acquisition path), `processSessionId`
     (which process instance received it), `batchId`/`indexInBatch` (background-batch delivery
     bookkeeping) — all *delivery metadata*, mirroring this exact codebase's own established
     `buildObservationDedupKey` rationale for excluding the same kind of field from *that* key.
     `TrajectoryObservation` has no altitude field today; none was invented.
   - **Equality/collision semantics**: equality is over the full ordered `List<ObservationIdentityEntry>`
     (order-sensitive — reordering the same observations changes identity); `sequenceChecksum` remains
     a purely supplementary/debug signal, never the sole discriminator, matching this package's own
     `ReconstructionCandidateIdentity.geometryChecksum` convention.
   - Carried by `AcceptedTrajectory.observationWindowIdentity: OrderedObservationWindowIdentity?`
     (new field, default `null`) and required on `ReconstructionSafetyEvidence.observationWindowIdentity`
     (mandatory). Mismatch: `OBSERVATION_WINDOW_MISMATCH` (hard failure). Candidate-side `null`:
     `OBSERVATION_WINDOW_IDENTITY_UNKNOWN` (blocks acceptance, not a hard failure).
2. **Layer B — matcher run** (`MatcherRunIdentity`, new opaque value type, mirrors this package's own
   `ClaimToken`/`ClaimTokenGenerator` pattern): replaces Round 1's free-form `matcherEvaluationId:
   String`, which the gate had nothing to independently compare against. Produced once at the
   reconstruction/matching boundary and propagated into both `AcceptedTrajectory.matcherRunIdentity`
   (new field, default `null`) and `ReconstructionSafetyEvidence.matcherRunIdentity` (mandatory).
   Mismatch: `MATCHER_RUN_MISMATCH` (hard failure). Candidate-side `null`:
   `MATCHER_RUN_IDENTITY_UNKNOWN`.
3. **Layer C — candidate geometry/provenance** (`ReconstructionCandidateIdentity`, Round 1's original
   type, KDoc corrected to state only what it actually covers): an independent sanity check (e.g. a
   non-deterministic matcher reconstructing different geometry from the same window/run), not proof of
   layers A or B. Mismatch: `EVIDENCE_CANDIDATE_MISMATCH` (hard failure, checked last).

**Matcher-declined-continuity protection is now edge-based, not vertex-based (Round 2 rewrite of the
Round 1 fix).** Round 1 checked whether a declined interval (in candidate-geometry-vertex space)
overlapped the candidate's `inferredIndices` — Round 2's re-review found a real gap: a dangerous
bridge can exist between two geometry vertices that are **both** labelled `observed`, with no inferred
vertex anywhere, if the underlying raw observations they came from were not adjacent in the original
window or their connecting path was never independently confirmed. `EdgeRange`/`MatcherDeclinedInterval`
now operate in **observation-window edge space** (edge `i` connects original observation `i` to
`i+1`, valid `0..inputObservationCount-2`), compared against a new mandatory
`AcceptedTrajectory.bridgedObservationEdges: Set<Int>` field (no default — a default of `emptySet()`
would silently under-report bridging, the same fail-open shape Round 1's B1 fix closed elsewhere) —
the candidate's own honest disclosure of which observation-window edges it bridged/inferred, entirely
independent of geometry-vertex `observedIndices`/`inferredIndices` labels. Real edge overlap:
`UNSUPPORTED_BRIDGE` (hard failure). A declined interval referencing an edge index beyond the
candidate's valid range: `DECLINED_EDGE_OUT_OF_BOUNDS` (hard failure — the evidence cannot
structurally describe this candidate's window at all). Still non-negotiable — no
`ReconstructionSafetyPolicy` field controls this check.

**Numeric-extreme safety (Round 2 fix to the Round 1 relational-coherence check).** Round 1's
duration/displacement/speed coherence check had two real bugs: `Duration.toNanos()` throws
`ArithmeticException` for an otherwise-constructible extreme `Duration` (beyond ~292 years in
nanoseconds), and a derived speed could legitimately evaluate to `Infinity` (e.g. 1 nanosecond against
`Double.MAX_VALUE` meters) — and `Infinity <= Infinity` is `true` in IEEE-754, so the old tolerance
check could silently *validate* a contradiction. `ObservationEvidence`'s derived-speed computation now
uses `Duration.getSeconds()`/`getNano()` (never throws) and explicitly requires the derived speed
itself to be finite before any tolerance comparison — a non-finite derived quantity is unconditionally
incoherent, never silently waved through.

**UNKNOWN matcher classification (Round 2 minor fix).** `MatcherEvidence` gained a fourth accounting
bucket, `unknownClassificationObservationCount`, alongside matched/interpolated/unmatched — real
benchmark matcher output can classify some observations as none of the three. Included in the gate's
exact-partition check; any known positive count adds `UNCLASSIFIED_OBSERVATIONS_PRESENT` (uncertainty)
unconditionally — never silently dropped, never treated as supporting acceptance.

**Decision-construction authorization boundary — corrected claim (Round 2).** Round 1 claimed
`ReconstructionSafetyDecision`'s `internal fun of(...)` factory was the authorization boundary,
closing cross-module fabrication but honestly leaving same-module fabrication open. Round 2 attempted
a stronger fix (`ReconstructionAuthorization`, a type nested inside `DeterministicReconstructionSafetyGate`
with a private constructor) and **found, via the real compiler, that the intended guarantee does not
hold**: Kotlin's `private` visibility for a class member does not extend from a nested class to its
enclosing class the way Java's mutual nested/outer private access does — `authorize()` (a member of
the *outer* object) cannot call the nested class's own private constructor. **Kotlin has no
"friend class" feature; "constructible only from inside one specific other class" is not expressible
in Kotlin's visibility system without a custom compiler plugin or a separate Gradle module boundary.**
What is actually implemented: `DeterministicReconstructionSafetyGate.authorize(...)` returns an
`EvaluationResult(decision, authorization)`; `authorization: ReconstructionAuthorization?` is
non-null only for an `AcceptReconstruction` decision, constructed via
`ReconstructionAuthorization`'s own companion (`internal fun grantedByGateEvaluation`, standard
Kotlin class/companion private-sharing) — the same `internal`, module-wide ceiling Round 1's `of`
factory already sits at, **not a stronger compile-time guarantee**. What this design still adds: a
separate, deliberately awkwardly-named, non-`operator` type a future consumer must specifically ask
for, rather than treating the far more ordinary-looking `AcceptReconstruction` as sufficient proof —
a real structural/naming improvement, honestly not a single-caller compiler guarantee.

**Rules directly encoding Phase 2B's own accepted findings** (unchanged from Round 1, re-verified):
matcher-declined edges alone are never automatically unsafe (2B-5's central finding — only real
edge-overlap with a candidate-claimed bridge is `UNSUPPORTED_BRIDGE`); weak/unavailable matcher
confidence is always uncertainty, never hard failure; duration and a qualitative network-complexity
label are collected but never read by any rule (2B-5's own duration/complexity findings); cross-matcher
disagreement only ever adds uncertainty, never resolved by majority vote; the OSRM-27s benchmark
regression fixture is correctly attributed to a single-continuous-match-no-split lesson (2B-2C), with
the split/bridge lesson correctly attributed to 2B-5.

**Invariants enforced at construction:** every evidence group's known numeric fields validated
non-negative/finite/in-range plus relational coherence (Round 2 numeric-extreme fix included);
`AcceptedTrajectory`'s new invariants (`bridgedObservationEdges` entries valid against
`0..inputObservationCount-2`, `observationWindowIdentity.observationCount` must equal
`inputObservationCount` when present); `ReconstructionSafetyPolicy`'s ranges (including Round 1's
finite-bound/`>= 1` checks); `ReconstructionSafetyDecision`'s reason-category rules and defensive
copying; `MatcherEvidence.declinedIntervals`'s defensive copy. Candidate/evidence association (all
three layers) and matcher-accounting-vs-candidate-total coherence are enforced inside `evaluate` itself
(they need the actual evaluated candidate) rather than at a shared constructor — deliberate, documented.

**Tests**: 206/206 passing (manual `kotlinc`/JBR toolchain, `-Xfriend-paths` for `internal` factories)
across `ReconstructionSafetyGateTest`, `ReconstructionSafetyIdentityAssociationTest` (Layers A/B),
`ObservationWindowContentIdentityTest` (Round 3's own STRONG-dedup-path blocker reproduction, full
per-field content-mutation matrix, reordering, cross-construction stability, and a dedicated gate-level
stale-content-evidence regression), `EdgeContinuityTest` (the full edge-boundary/validation matrix),
`ReconstructionEvidenceCoherenceTest` (numeric-extreme + 4-way accounting), `AuthorizationBoundaryTest`,
`ReconstructionSafetyPolicyTest`, `ReconstructionSafetyEvidenceTest`, `ReconstructionSafetyDecisionTest`,
`ReconstructionSafetyBenchmarkRegressionFixturesTest`, and the pre-existing `TrajectoryReconstructorTest`
(extended with the new `AcceptedTrajectory` fields' own invariant tests). Full module (main + entire
pre-existing test sourceset) compiles with zero errors once `-Xfriend-paths` is applied.

**DECIDED / NOT IMPLEMENTED, deliberately deferred:** wiring the gate into any live pipeline; calling
it from a real `TrajectoryReconstructor`; converting an accepted decision into
H3/`Provenance.RECONSTRUCTED`; adaptive-cadence consumption of `RequireMoreEvidence.cadenceRecommendation`;
Certified reconstruction policy; choosing a map-matching engine; any change to `discovered_cells`,
`DiscoveredRoute.kt`, GPS cadence, or `Provenance`/`TrustStatus`.

**CALIBRATION REQUIRED (numeric values only — every structural safety rule above is `IMPLEMENTED`,
not a calibration question):** every `ReconstructionSafetyPolicy` field; `NetworkRouteEvidence.pathContinuityMagnitude`'s
eventual threshold.

**Known, honestly-documented limitations:** (1) a confidently-wrong single continuous matcher output
with no split/low-confidence/other unfavorable signal remains outside this generic gate's reach (the
2B-2C regression fixture); (2) `MatcherRunIdentity` distinguishes evidence from different runs but
cannot detect a caller mixing up which run was *intended*; (3) the authorization boundary
(`ReconstructionAuthorization`) is `internal`, module-wide — **not** a single-caller compiler
guarantee; Kotlin has no mechanism to express that without a compiler plugin or a separate module
boundary, and this is now stated accurately rather than overclaimed; (4) `bridgedObservationEdges`
correctness depends entirely on the producer honestly disclosing which edges were bridged — the gate
has no independent way to verify that disclosure itself.

**Gradle**: one `:core-discovery-engine:test` attempt this round again hit the same standing
"`Unable to establish loopback connection`" JVM/Windows environment failure — not retried past that
single attempt. Verified instead via the manual `kotlinc`/JBR toolchain (full module compile, zero
errors).

## Lifecycle/reboot — IMPLEMENTED
Foreground and background tracking are coordinated by the existing controller. `BootCompletedReceiver` may re-arm background tracking after reboot when consent and permissions permit.

## Permission changes — IMPLEMENTED
Consent and OS permission are different states. The registrar rechecks background permission before registration, and Android permission remains authoritative even when consent stays stored.

## Device validation — HISTORICALLY REPORTED / NOT REPOSITORY-PROVABLE
Foreground/background tracking, transitions, reboot re-arm, background permission downgrade and foreground permission revoke/re-grant recovery were reported as physically validated on a Samsung device before `7a906a9`. The implementation is consistent with those reports, but Git and source code alone cannot prove the physical executions occurred.

## Force-stop — platform limitation / accepted
Do not try to bypass Android force-stop. Tracking resumes only after manual relaunch.

## Privacy/trust
Coordinates are transient; derived H3 discovery state is persisted. Current automatic observations are `OBSERVED + NON_CERTIFIED`.

## Deliberately not used
- permanent foreground service for normal background discovery;
- WorkManager GPS polling;
- battery-optimization exemption prompt;
- continuous high-accuracy background GPS.

## Long-term behavior — PLANNED
Tracking should remain adaptive/battery-conscious. Conservative reconstruction after temporary signal loss is **DECIDED / NOT IMPLEMENTED** in principle, including preservation of provenance and server validation for any Certified result. The exact fusion design is **ENGINEERING DESIGN REQUIRED** and durations/confidence thresholds are **CALIBRATION REQUIRED**. IP alone is never proof.
