# GraphHopper — exact reproduction commands (Phase 2B)

**Status: EXECUTED** (Phase 2A left this unexecuted; Codex required real execution for Phase 2B —
see §12 of the Phase 2B request). No Docker image exists for GraphHopper from the project itself
(only unofficial third-party images of unclear provenance/version) — instead, the officially
published, version-pinned "web bundle" jar from Maven Central was used directly, matching the
project's own documented standalone installation method.

**Engine**: `com.graphhopper:graphhopper-web:11.0` (the "web bundle" fat jar, includes the
map-matching module — map-matching was merged into the main GraphHopper repository, no longer a
separate artifact).
**Exact jar URL**: https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/11.0/graphhopper-web-11.0.jar
**Version reported at startup**: `version 11.0|2025-10-14T14:28:00Z (9,24,7,5,2,9)`
**Runtime**: JBR (JetBrains Runtime) `java.exe`, reported JRE 25 — matches the project's documented
minimum (Java 25+).

**Data**: same `isle-of-man.osm.pbf` extract as the OSRM/Valhalla spikes (see
`../osrm/commands.md` for the exact Geofabrik source URL and dated-filename caveat).

## 1. Download the jar and example config

```bash
curl -sL -o graphhopper-web-11.0.jar "https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/11.0/graphhopper-web-11.0.jar"
curl -sL -o config-example.yml "https://raw.githubusercontent.com/graphhopper/graphhopper/11.x/config-example.yml"
```

The example config's default profile list already includes `car` (uncommented by default); no
edits were made to `config-example.yml` itself. The OSM extract path is supplied via a system
property at launch instead (see below), rather than editing the file, so the config stays the
unmodified upstream example.

## 2. Generate the input GPX from the shared trace

`CsvToGpx.kt` in this directory converts `../shared/input-trace.csv` (the same 36-point trace used
identically for OSRM and Valhalla) into `input-trace.gpx`, a GPX 1.1 track — the input format
GraphHopper's map-matching CLI/REST interface consumes. Run from this directory:

```powershell
kotlinc -include-runtime -d csvtogpx.jar CsvToGpx.kt
java -jar csvtogpx.jar
```

**Input-fidelity gap, recorded explicitly (Phase 2B §10 requirement):** GPX 1.1's standard schema
has no per-point accuracy/radius/bearing field. `../shared/input-trace.csv`'s
`radius_or_accuracy_m` column (25m for normal points, 10m for the deliberately displaced point at
index 21) **cannot be carried into the GPX at all** — every trackpoint is emitted identically
regardless of its declared accuracy. This is a genuine, structural difference from OSRM
(`radiuses` — one value per coordinate in the URL) and Valhalla (`accuracy` — one value per shape
point in the JSON body): **GraphHopper's GPX-based interface has no per-point equivalent.** The
closest available control is a single **global** `--gps_accuracy` value for the whole trace (see
below) — not a per-point override. `request.gpx` in this directory is the exact GPX produced;
`../shared/input-trace.csv` remains the authoritative source of the per-point values GraphHopper
could not receive.

`request.gpx` in this directory **is** the exact, complete GPX actually submitted (byte-identical
to what `CsvToGpx.kt` produces from the current `input-trace.csv`).

## 3. Run map matching (CLI — no web server, avoids this environment's Jetty/NIO limitation)

**Environment note, load-bearing for why this reproduction path was chosen:** attempting
`java -jar graphhopper-web-11.0.jar server config-example.yml` (the normal, documented way to run
GraphHopper as an HTTP service, needed for the REST `/match?type=json` endpoint) fails in this
execution environment with `MultiException[java.io.IOException: Unable to establish loopback
connection, ...]`, originating in `WEPollSelectorImpl`/`PipeImpl` — the JVM's own internal NIO
selector-wakeup pipe fails to establish itself over a Unix-domain-socket loopback when the JVM
runs as a sandboxed child process on this Windows machine. This is the **same class of standing
environmental failure** already documented for Gradle's daemon throughout this project's session
history (`java.io.IOException: Unable to establish loopback connection`) — an unresolved, known
class of JDK/Windows-sandbox issue (OpenJDK bug JDK-8312215 documents the same failure signature
in a different context), not specific to GraphHopper and not fixable by a JVM flag (one workaround,
`-Djdk.nio.channels.spi.SelectorProvider=sun.nio.ch.WindowsSelectorProvider`, was tried once and
did not help). **GraphHopper's `match` CLI subcommand does not start a web server or bind any
port, so it never hits this code path** — this is why it was used instead of the REST endpoint.
Consequence: the richer REST JSON response shape (`/match?type=json`) was **not** obtained or
inspected in this round; only the CLI's GPX output was.

```bash
java -Ddw.graphhopper.datareader.file=isle-of-man.osm.pbf \
  -jar graphhopper-web-11.0.jar match --file config-example.yml --profile car \
  --gps_accuracy 25 input-trace.gpx
```

This imports the OSM extract into an in-memory graph (first run only; near-instant for this ~6MB
extract), then map-matches `input-trace.gpx`, writing the result to `input-trace.gpx.res.gpx`.

**Two variants were run and both preserved, since `--gps_accuracy` materially changed the result**
(`response-default-accuracy40.gpx` / `response-accuracy25.gpx` in this directory):
- **Default** (`--gps_accuracy` omitted, CLI default = 40m): `matches: 204, gps entries: 36`.
- **accuracy=25** (chosen to align with `input-trace.csv`'s "normal" point accuracy, the closest
  single value obtainable given the no-per-point-accuracy limitation above): `matches: 205, gps
  entries: 36`.

Both runs report `gps entries:36` (all 36 input points were read), `gps import took: ~0.03-0.04s`,
`match took: ~0.07-0.11s`. Both are deterministic (rerunning the default case reproduced
`matches: 204` exactly).

**Other CLI parameters available** (from `match --help`, not modified from their defaults in
either run above): `--instructions` (locale for turn instructions, irrelevant to matching itself),
`--transition_probability_beta` (default `2.0` — the HMM transition-cost weighting, conceptually
analogous to Valhalla's `beta`/`sigma_z`, see `../valhalla/effective-config-meili.json`).

## 4. Output format — a real, documented limitation for per-index auditing

Unlike OSRM's `tracepoints[]` and Valhalla's `matched_points[]` (both a clean one-to-one array
matching the 36 input points, enabling the exact per-index audit tables in
`../osrm/audit-result.md` / `../valhalla/audit-result.md`), GraphHopper's `.res.gpx` output is a
**densely resampled polyline** (204-205 points, far more than the 36 inputs) with **only some**
points carrying a `<time>` element — and where present, those times are **not** always identical
to the original input timestamps. For example, input indices 19-23 (epoch seconds
1757000513/540/567/657/684, i.e. 2025-09-04T15:41:53Z / 15:42:20Z / 15:42:47Z / 15:44:17Z /
15:44:44Z per `../shared/input-trace.csv`) do **not** appear verbatim in
`response-default-accuracy40.gpx`; instead nearby but different times appear (e.g.
`15:44:15Z`/`15:44:27Z`/`15:44:43Z`/`15:44:59Z`/`15:45:11Z`), clustered close together right around
where the injected gap/displaced point (index 21) sits. **This means a clean per-input-index audit
table, of the same kind built for OSRM/Valhalla, could not be constructed from this CLI GPX output
without further reverse-engineering GraphHopper's own internal resampling** — reported here as an
honest limitation, not forced into a fake equivalent table. This is itself a real, useful finding
for the benchmark harness design (see `../../PHASE_2B_BENCHMARK_PROTOCOL.md` §Engine-native
signals): **GraphHopper's most accessible output interface (CLI GPX) is structurally the least
suited of the three engines to per-observation auditing** — the REST JSON endpoint might expose a
cleaner per-point structure, but could not be reached in this environment (see the environment
note above).

## Sources

- GraphHopper map-matching README (CLI usage, REST endpoint):
  https://github.com/graphhopper/graphhopper/blob/master/map-matching/README.md
- GraphHopper standalone installation (jar/config download, Java version requirement):
  https://github.com/graphhopper/graphhopper/blob/master/README.md
- GraphHopper repository / license (Apache-2.0):
  https://github.com/graphhopper/graphhopper/blob/master/LICENSE.txt
