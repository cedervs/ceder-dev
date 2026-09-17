# Phase 2B map-matching benchmark harness

**Non-production tooling.** Never imported by `app`/`core-*`/`backend`; not a Gradle module; no
build-file changes anywhere in the repository were made to create this. Compiled and run through
the same manual `kotlinc`/JBR-`java` toolchain already established throughout this project's
sessions — see commands below.

Design and full rationale: `docs/ai-context/PHASE_2B_BENCHMARK_PROTOCOL.md`. Correction history:
**2B-1B** fixed several benchmark-invalidating defects in the original 2B-1 harness (an invalid
overlap metric, an antimeridian bug, an H3 under-sampling bug, an unimplemented degradation
generator, an untested/unserializable model layer, and a collapsed truth model). **2B-2** added
real-capture ingestion (`GpsLoggerCsvAdapter.kt`) for the first real physical scenario. **2B-2A**
fixed a truth-semantics bug in 2B-2 itself: a dense raw GPS polyline had been treated as exact
ground truth (`overallConfidence = HIGH` on the whole geometry) just because an independent map
annotation confirmed which *corridor* (road) was used — corridor identity and exact-geometry
accuracy are now two separate, independently-tracked claims (`TruthGuard.kt`), and no
exact-geometry-dependent metric (Hausdorff/Fréchet/overlap/H3 TP/FP/FN) can be computed until
`VerifiedRouteGeometry.exactGeometryTruthStatus` is explicitly `VALIDATED`. See each file's own doc
comment for what changed and why, and `docs/ai-context/PHASE_2B_BENCHMARK_PROTOCOL.md` for the full
correction record.

## Layout

```
src/
  Models.kt                 -- ReferenceTrace (rawObservations + verifiedGeometry + segments),
                               DegradationSpec, EngineConfigIdentity, DegradedObservationSet,
                               EngineMatchResult, BenchmarkResult, GeometryTruthStatus,
                               MetricEvaluability, etc. -- all genuinely @Serializable.
  GeometryMetrics.kt         -- Hausdorff, discrete Fréchet (iterative, not recursive -- see its own
                               doc comment), along-track deviation, length-weighted route overlap,
                               densification, antimeridian-safe longitude interpolation.
  H3Comparison.kt            -- geometry -> H3 cell sampling (ceil-based step count, antimeridian-safe),
                               TP/FP/FN/precision/recall.
  Degradation.kt             -- deterministic cadence-downsampling generator (7s/15s/27s/45s+).
  TruthGuard.kt               -- [2B-2A] gates exact-geometry-dependent metrics on
                               VerifiedRouteGeometry.exactGeometryTruthStatus == VALIDATED.
  BenchmarkEvaluation.kt      -- [2B-2A] the one guarded path from (EngineMatchResult,
                               VerifiedRouteGeometry) -> BenchmarkResult; always consults TruthGuard.
  GpsLoggerCsvAdapter.kt      -- [2B-2] raw GPSLogger CSV export -> ReferencePoint list + IngestionReport
                               (schema validation, no silent repair of raw evidence).
  IngestAndBuildScenario.kt   -- [2B-2] CLI runner: ingest -> build truth model -> generate
                               degraded sets -> provisional H3 cells, all local, no network.
  Sha256.kt                  -- file-content hashing for EngineConfigIdentity.
  OsrmAdapter.kt             -- raw OSRM /match JSON -> EngineMatchResult
  ValhallaAdapter.kt         -- raw Valhalla trace_attributes JSON -> EngineMatchResult
  SmokeTest.kt               -- end-to-end pipeline smoke test (see warning below)
  GeometryMetricsTest.kt     -- focused tests: identical/parallel lines, density mismatch,
                               antimeridian, degenerate input, length- vs vertex-weighted overlap.
  H3ComparisonTest.kt        -- focused tests: straight/diagonal/turn segments, near-boundary,
                               multi-cell, antimeridian, the ceil-vs-floor regression itself.
  DegradationTest.kt         -- focused tests: irregular intervals, stops, gaps, no duplicates,
                               final-point policy.
  GpsLoggerCsvAdapterTest.kt  -- [2B-2] focused tests on FABRICATED synthetic CSV (never reads
                               benchmark-private/): missing fields, duplicates, gaps, reordered
                               header, non-trackpoint rows, column-count mismatches.
  TruthGuardTest.kt           -- [2B-2A] focused tests: raw GPS evidence cannot become authoritative
                               truth automatically; geometry/H3 metrics NOT_EVALUABLE when pending;
                               corridor classification stays available regardless; positive control.
  GraphHopperAdapter.kt       -- [2B-2B] raw GraphHopper CLI `.res.gpx` -> EngineMatchResult;
                               perObservation always null (structurally unavailable, see below).
  GraphHopperAdapterTest.kt   -- [2B-2B] focused tests on a FABRICATED synthetic .res.gpx snippet.
  GenerateEngineRequests.kt   -- [2B-2B] one shared DegradedObservationSet -> OSRM URL / Valhalla
                               JSON body / GraphHopper GPX -- same observations, encoding differs only.
  AnalyzeEngineResults.kt     -- [2B-2B] loads real engine responses, runs BenchmarkEvaluation/
                               TruthGuard, reports a corridor-sanity distance check (NOT a truth score).
  ValhallaAdapterTest.kt      -- [2B-2C] focused tests for a real correctness bug: matchedGeometry
                               must exclude UNMATCHED/UNKNOWN points even when they carry
                               coordinates -- see "Tests are real, but NOT JUnit" below for cases A-E.
  TurnSegmentation.kt         -- [2B-3, extended 2B-4] generic (non-out-and-back) segment
                               construction: detects turns algorithmically from GPS bearing evidence
                               (never from a screenshot); [2B-4] also detects large real
                               observation-timestamp gaps (>60s default) as forced segment
                               boundaries, independent of spatial bearing -- a gap-adjacent segment
                               would otherwise silently absorb a real logger/app pause into an
                               ordinary turn-based leg.
  TurnSegmentationTest.kt     -- focused tests: straight line (no turns), L-shape (1 turn), noisy
                               straight line (no false trigger), short-trailing-segment merge;
                               [2B-4] large-gap detection (single gap, no gap, multiple gaps).
  IngestAndBuildUrbanScenario.kt -- [2B-3, extended 2B-4] CLI runner mirroring
                               IngestAndBuildScenario.kt but using TurnSegmentation instead of
                               out-and-back turnaround detection -- Scenario 1's own script/evidence
                               is never touched by this file. [2B-4] transportMode/corpusCategory
                               are now CLI arguments (default WALK, unchanged for Scenarios 1-2)
                               instead of hardcoded WALK -- generic across vehicle modes.
  EngineProfiles.kt            -- [2B-4, new] single source of truth mapping TransportMode to each
                               engine's profile/costing identifier (OSRM profile, Valhalla costing,
                               GraphHopper profile) -- used by both GenerateEngineRequests and
                               AnalyzeEngineResults so a new TransportMode is added in one place
                               only. Fixes a real gap: the harness previously hardcoded foot/
                               pedestrian everywhere, which was silently wrong for a CAR scenario.
  ControlledGap.kt              -- [2B-5, new] generic controlled-observation-gap infrastructure,
                               distinct from Degradation.kt: removes exactly ONE contiguous window
                               of real observations from an otherwise-dense trace (never simulates
                               or interpolates), isolating "what happens when observations vanish
                               for X seconds in ONE place" from uniform cadence downsampling. No
                               scenario-specific coordinates/indices anywhere in this file.
  ControlledGapTest.kt          -- [2B-5] focused tests (A-G): hidden observations truly absent from
                               matcher input; reference window retained separately; source trace
                               never mutated; deterministic generation; overlap with an excluded
                               real gap rejected; a stationary window rejected for a moving-gap
                               experiment; invalid index ranges rejected.
  BuildControlledGapVariants.kt -- [2B-5] driver applying a FROZEN list of ControlledGapSpecs
                               (supplied as CLI args, not hardcoded) to a real dense ReferenceTrace.
  ExploreGapCandidates.kt       -- [2B-5] prints aggregate-only (no coordinates) duration/distance/
                               speed/heading stats for candidate windows, used to select and freeze
                               windows BEFORE any matcher run (never after seeing failures).
  AnalyzeControlledGapResults.kt -- [2B-5] compares each engine's reconstruction against the real
                               (never-sent-to-a-matcher) hidden reference window -- explicitly
                               labeled a NON-AUTHORITATIVE diagnostic; see the round's own report
                               for why raw distance alone was insufficient and required structural
                               corroboration (splits, per-observation typing, edge continuity).
smoke-test-output.log        -- the exact output of the last verified smoke-test run
```

Real private scenario data (raw traces, screenshots, truth models, degraded sets, and any future
engine output) lives under `benchmark-private/phase2b/<scenario-id>/` — gitignored, never described
by exact coordinates in this file or in the protocol document.

**2B-2B** ("resume local engine benchmark") added the pieces needed to actually run all three
engines locally against a real captured scenario, once Docker became available:
- `GenerateEngineRequests.kt` — turns one shared `DegradedObservationSet` into an OSRM URL, a
  Valhalla JSON body, and a GraphHopper GPX file — same logical observations, engine-specific
  encoding only (protocol §10 "identical input rule").
- `GraphHopperAdapter.kt` (+ `GraphHopperAdapterTest.kt`) — parses GraphHopper CLI `.res.gpx`
  output into `EngineMatchResult`; `perObservation` stays `null` (structurally unavailable, per
  Phase 2A/2B's documented limitation, reconfirmed against a real capture this round).
- `AnalyzeEngineResults.kt` — loads a scenario's real engine responses, runs them through
  `BenchmarkEvaluation`/`TruthGuard` (proving the guard fires `NOT_EVALUABLE` on real output, not
  just synthetic tests), and reports a corridor-sanity distance check explicitly labeled as NOT a
  truth-metric score.

**[2B-2C] Corrected — `GraphHopperAdapter.kt` DOES exist** (added in 2B-2B, see above), with a
focused test (`GraphHopperAdapterTest.kt`). Its CLI GPX output still does not preserve a usable
one-to-one correspondence with input observations (see
`docs/ai-context/map-matching-spike/graphhopper/commands.md` §4, and reconfirmed against the real
2B-2 capture) — `perObservation` stays `null` for GraphHopper, never fabricated. Against the real
scenario's real engine output, GraphHopper's corridor classification came back **AMBIGUOUS at every
tested cadence** (7s/15s/27s/45s/dense) — its CLI match output is a coarse ~6-7 segment summary, not
a dense per-input match, so the corridor-sanity distance check it supports is noisier than
OSRM's/Valhalla's. See the private scenario manifest for the exact numbers (never coordinates).

## Tests are real, but NOT JUnit

There is no JUnit runner in this environment — this environment's JVM cannot start a Gradle daemon
or any JVM server-mode process (documented extensively elsewhere in this project's history; the
same root cause blocked GraphHopper's `server` mode, see
`docs/ai-context/map-matching-spike/graphhopper/commands.md`). All nine test files below are plain
Kotlin `main()` functions using `check()` assertions instead. Each prints its own diagnostic output
and either prints `ALL PASS` and exits 0, or throws (via a failed `check()`) and exits non-zero.
This is genuinely executed test evidence, not a claim of coverage beyond what these specific
assertions check — only suites actually run and confirmed exit 0 are ever described as passing.

Current suites (all confirmed `ALL PASS`, exit 0, in the last verified run — see the round 2B-5
report for the exact run):
- `GeometryMetricsTest.kt`
- `H3ComparisonTest.kt`
- `DegradationTest.kt`
- `GpsLoggerCsvAdapterTest.kt`
- `TruthGuardTest.kt`
- `GraphHopperAdapterTest.kt`
- `ValhallaAdapterTest.kt` — **[2B-2C]** covers a real Codex-flagged correctness bug: the
  adapter previously let `UNMATCHED`/`UNKNOWN` `matched_points[]` entries into `matchedGeometry`
  whenever they carried coordinates, silently treating a failed match as a real one. Cases: (A) a
  `MATCHED` point enters `matchedGeometry`; (B) an `UNMATCHED` point with coordinates does NOT; (C)
  an `UNKNOWN`-type point with coordinates does NOT; (D) both remain fully represented in
  `perObservation`, never dropped or fabricated as matched; (E) a mixed matched/unmatched input
  preserves the original order of the matched points that do remain.
- `TurnSegmentationTest.kt` — **[2B-3, new]** a straight line detects 0 turns; an L-shape detects
  exactly 1 turn near the corner; small lateral GPS jitter on an otherwise straight line does not
  false-trigger; one turn produces exactly 2 legs with distinct corridor IDs; a too-short trailing
  leg (a turn detected right near the end of a trace) merges into the previous leg rather than
  producing a degenerate 2-point segment; [2B-4] large-gap detection (single/no/multiple gaps).
- `ControlledGapTest.kt` — **[2B-5, new]** cases A-G: hidden observations absent from matcher input;
  reference window retained separately; source trace unmutated; deterministic generation; overlap
  with an excluded real gap rejected; a stationary window rejected for a moving-gap experiment;
  invalid ranges rejected.

## SMOKE TEST ONLY — NOT A BENCHMARK RESULT

`smoke-test-output.log` and running `SmokeTest.kt` again both use the Phase 2A Isle of Man
**self-referential** fixture (`docs/ai-context/map-matching-spike/shared/synthetic-self-referential-route.json`,
itself derived from an OSRM `/route` query) as a stand-in "reference" geometry. This proves the
harness's own plumbing works on real data — it proves **nothing** about which engine matches a real
trajectory better. See `docs/ai-context/map-matching-spike/README.md`'s
"SYNTHETIC SELF-REFERENTIAL REFERENCE" warning.

**The 2B-1 smoke test's own documented density-mismatch caveat is now mitigated** (not eliminated):
the Valhalla row still only has 36 native `matched_points` (Phase 2A's spike request never asked for
Valhalla's denser `shape` attribute) vs. OSRM's ~722-point native geometry, but Hausdorff/Fréchet/
overlap are now computed on **both inputs densified to the same 5m spacing** before comparing (see
`GeometryMetrics.kt`). The practical effect is visible in the numbers: Valhalla's Fréchet distance
dropped from 990.5m (2B-1, density-biased) to 341.4m (2B-1B, density-fair) — now matching OSRM's
341.4m almost exactly, which is the expected result for two adapters describing the same real
self-referential route. This is a genuine before/after improvement caught by actually re-running the
harness after the fix, not an assumed one.

## Compile

Requires the `kotlinx-serialization` compiler plugin jar bundled with kotlinc (needed now that the
models in `Models.kt` are genuinely `@Serializable` -- see its own doc comment) in addition to the
runtime jars used in Phase 2B-1.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$kotlinc = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
$plugin = "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\lib\kotlinx-serialization-compiler-plugin.jar"
$cp = @(
  "<gradle-cache>/org.jetbrains.kotlin/kotlin-stdlib/2.4.10/.../kotlin-stdlib-2.4.10.jar",
  "<gradle-cache>/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.7.3/.../kotlinx-serialization-core-jvm-1.7.3.jar",
  "<gradle-cache>/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.3/.../kotlinx-serialization-json-jvm-1.7.3.jar",
  "<gradle-cache>/com.uber/h3/4.5.0/.../h3-4.5.0.jar"
) -join ";"
& $kotlinc "-Xplugin=$plugin" -cp $cp -d out/mapmatch-benchmark (Get-ChildItem src -Filter "*.kt")
```

(The exact Gradle-cache SHA-hashed paths are machine-specific — resolve them the same way every
other manual-toolchain compile in this project's history has, by locating the jar under
`~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/`. The compiler plugin jar ships
inside kotlinc's own `lib/` directory, not the Gradle cache.)

## Run the smoke test and the focused tests

```powershell
$java = "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
$cp = "out/mapmatch-benchmark;<same 4 runtime jars as above>"
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.SmokeTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.GeometryMetricsTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.H3ComparisonTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.DegradationTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.GpsLoggerCsvAdapterTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.TruthGuardTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.GraphHopperAdapterTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.ValhallaAdapterTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.TurnSegmentationTestKt
& $java --enable-native-access=ALL-UNNAMED -cp $cp worlddiscovery.benchmark.ControlledGapTestKt
```

Run from the repository root (`SmokeTest.kt` reads `docs/ai-context/map-matching-spike/...` as
relative paths). `--enable-native-access=ALL-UNNAMED` suppresses the H3 native-library-load JVM
warning, which otherwise leaks the local Gradle cache path (and machine username) to stdout/stderr
-- see the privacy note in the correction report.

## Verified result of the last run (see `smoke-test-output.log` for the full, unedited output)

```
engine     points       hausdorff(m)     frechet(m)  alongTrack(m)   overlap%    H3-TP    H3-FP    H3-FN
osrm       722                 341.4          341.4            0.8       96.1     1242       22       64
valhalla   36                  341.4          341.4            3.3       65.1      532      668      774

perObservation entries -- osrm: 36, valhalla: 36
osrm null (unmatched) count: 2
valhalla unmatched count: 0
```

This still reproduces, through the harness's own independent code path, the exact same finding
Phase 2A's manual audit already established (OSRM: 2/36 unmatched; Valhalla: 0/36 unmatched) — a
useful cross-check that the adapters parse the real raw responses correctly, not just a
coincidence. The H3/geometry numbers themselves changed from the 2B-1 run because of the 2B-1B
metric fixes (length-weighted overlap, densified Hausdorff/Fréchet, corrected H3 sampling step) --
they are not comparable to the 2B-1 numbers point-for-point, only the unmatched-count cross-check
is expected to stay identical (and does).

All nine suites (`GeometryMetricsTest`, `H3ComparisonTest`, `DegradationTest`,
`GpsLoggerCsvAdapterTest`, `TruthGuardTest`, `GraphHopperAdapterTest`, `ValhallaAdapterTest`,
`TurnSegmentationTest`, `ControlledGapTest`) printed `ALL PASS` (exit code 0) in the last verified
run (round 2B-5).
