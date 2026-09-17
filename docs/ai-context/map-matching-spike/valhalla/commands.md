# Valhalla — exact reproduction commands

**Engine**: `ghcr.io/valhalla/valhalla:latest`
**Digest observed at execution**: `sha256:a7d0d02ed5ce4f2817105b443eb58494a5757fc6b48780a39c9cc62740296432`
**Version reported at `/status`**: `3.8.3-7f372987b`

**Data**: same `isle-of-man.osm.pbf` as the OSRM spike (see `../osrm/commands.md` for the exact
Geofabrik source URL and the dated-filename caveat).

All commands below assume a working directory `WORKDIR` containing `isle-of-man.osm.pbf`, mounted
into the container at `/custom_files`.

## 1. Generate the base config

```bash
docker run --rm -t -v "${WORKDIR}:/custom_files" ghcr.io/valhalla/valhalla:latest \
  valhalla_build_config \
  --mjolnir-tile-dir /custom_files/valhalla_tiles \
  --mjolnir-timezone /custom_files/valhalla_tiles/timezones.sqlite \
  --mjolnir-admin /custom_files/valhalla_tiles/admins.sqlite \
  > valhalla.json
```

**Important**: redirect only stdout (`>`), never merge stderr into the same file (`2>&1`) if the
image has not been pulled yet — the image-pull progress text would corrupt the generated JSON.
Pull the image first (`docker pull ghcr.io/valhalla/valhalla:latest`) or run the command once to
let Docker pull silently on stderr, then discard and rerun for a clean capture.

**Windows Git Bash (MSYS) note**: as with OSRM, prefix container-internal paths with an extra
leading slash (`//custom_files/...`) if MSYS mangles them into bogus Windows paths.

`effective-config-meili.json` in this directory is the exact `meili` section extracted from the
`valhalla.json` this command produced in the audited run (via
`(Get-Content valhalla.json -Raw | ConvertFrom-Json).meili`) — see `effective-config-meili.json`
and §Effective configuration in `../../MAP_MATCHING_ENGINE_STUDY.md` for what these values mean.

## 2. Build tiles

```bash
docker run --rm -t -v "${WORKDIR}:/custom_files" ghcr.io/valhalla/valhalla:latest \
  valhalla_build_tiles -c //custom_files/valhalla.json //custom_files/isle-of-man.osm.pbf
```

Took approximately 5 minutes for this small (~6MB) extract.

## 3. Start the service

```bash
docker run -d --name valhalla-spike -p 8002:8002 -v "${WORKDIR}:/custom_files" \
  ghcr.io/valhalla/valhalla:latest valhalla_service //custom_files/valhalla.json 1
```

Verify with:

```bash
curl -s http://localhost:8002/status
```

## 4. Generate the self-referential source route

Identical route as the OSRM spike (same Douglas → Peel `/route` request against the OSRM server —
see `../osrm/commands.md` step 6) was used to derive `../shared/input-trace.csv`. Valhalla's own
`/route` action was **not** used to derive the input trace, to keep exactly one shared synthetic
reference between both engine spikes (see `../README.md` for why this makes both spikes
self-referential, not independent ground truth).

## 5. Run the exact audited `trace_attributes` request

`request.json` in this directory is the exact, complete JSON body actually sent (built directly
from `../shared/input-trace.csv`: `shape[].lat`/`lon` from the coordinate columns, `time` from
`timestamp_epoch_s`, `accuracy` from `radius_or_accuracy_m`).

```bash
curl -s -X POST -H "Content-Type: application/json" -d @request.json \
  http://localhost:8002/trace_attributes -o response.json
```

`response.json` in this directory is the exact, unmodified raw response from this request.

**Note on attribute filter evolution**: `request.json` requests
`matched.distance_along_edge`/`matched.begin_route_discontinuity`/`matched.end_route_discontinuity`
in addition to the base set (`edge.names`, `matched.point`, `matched.type`, `matched.edge_index`,
`matched.distance_from_trace_point`) — these three were added in the correction round specifically
to audit what happened to the injected point (index 21); an earlier request in this same study did
not include them. `request.json`/`response.json` here reflect the **enriched** (final, audited)
version, run against the same already-built tiles (no tile rebuild between the two request
variants).

## 6. Cleanup

```bash
docker stop valhalla-spike
docker rm valhalla-spike
```

## Sources

- Valhalla Map Matching API reference (`trace_attributes`, `matched_points[]` fields including the
  confirmed-meters `distance_from_trace_point`):
  https://github.com/valhalla/valhalla-docs/blob/master/map-matching/api-reference.md
- Valhalla Meili algorithm overview (HMM/Viterbi, Newson-Krumm):
  https://valhalla.github.io/valhalla/contributing/architecture/meili/algorithms/
- Valhalla repository / license (MIT): https://github.com/valhalla/valhalla/blob/master/LICENSE.md
