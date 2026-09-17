# OSRM — exact reproduction commands

**Engine**: `osrm/osrm-backend:latest`
**Digest observed at execution**: `sha256:af5d4a83fb90086a43b1ae2ca22872e6768766ad5fcbb07a29ff90ec644ee409`
**Version reported at startup**: `v5.26.0`

**Data**: `isle-of-man-latest.osm.pbf`, downloaded from
`https://download.geofabrik.de/europe/isle-of-man-latest.osm.pbf` (Geofabrik's rolling "latest"
alias; at the time of download it redirected to `isle-of-man-260904.osm.pbf`, i.e. the 2026-09-04
Geofabrik build). If reproducing later, either fetch the current "latest" (contents may differ
slightly from the exact matching results in `response.json`, since OSM data is edited continuously)
or note this exact dated filename to obtain the byte-identical extract.

**Profile**: `/opt/car.lua`, the default `car` profile bundled inside the `osrm/osrm-backend` image
(not modified).

All commands below assume a working directory `WORKDIR` containing `isle-of-man.osm.pbf` (the
downloaded file, renamed for brevity), mounted into the container at `/data`.

## 1. Pull the image (optional — `docker run` pulls automatically if missing)

```bash
docker pull osrm/osrm-backend:latest
```

## 2. Download the OSM extract

```bash
curl -sL -o isle-of-man.osm.pbf "https://download.geofabrik.de/europe/isle-of-man-latest.osm.pbf"
```

## 3. Extract

```bash
docker run --rm -t -v "${WORKDIR}:/data" osrm/osrm-backend osrm-extract -p /opt/car.lua /data/isle-of-man.osm.pbf
```

**Windows Git Bash (MSYS) note**: MSYS's automatic path conversion can mangle container-internal
paths such as `/opt/car.lua` or `/data/isle-of-man.osm.pbf` into bogus Windows paths (e.g.
`C:/Program Files/Git/opt/car.lua`). If this happens, prefix the container-internal path arguments
with an extra leading slash (`//opt/car.lua`, `//data/isle-of-man.osm.pbf`) to make MSYS skip the
conversion for those specific arguments — do **not** set `MSYS_NO_PATHCONV=1` globally, since that
also breaks the `-v` volume-mount argument's host-side conversion. This is an environment quirk of
Windows Git Bash only; it does not apply to a native Linux/macOS shell or to PowerShell.

## 4. Partition (MLD)

```bash
docker run --rm -t -v "${WORKDIR}:/data" osrm/osrm-backend osrm-partition /data/isle-of-man.osrm
```

## 5. Customize (MLD)

```bash
docker run --rm -t -v "${WORKDIR}:/data" osrm/osrm-backend osrm-customize /data/isle-of-man.osrm
```

## 6. Start the routing server

```bash
docker run -d --name osrm-spike -p 5000:5000 -v "${WORKDIR}:/data" osrm/osrm-backend osrm-routed --algorithm mld /data/isle-of-man.osrm
```

## 7. Generate the self-referential source route (shared/synthetic-self-referential-route.json)

This is the step that makes the whole spike **self-referential, not independent ground truth** —
see `../README.md`. A real cross-island route (Douglas → Peel) was requested from this same OSRM
server:

```bash
curl -s "http://localhost:5000/route/v1/driving/-4.4816,54.1509;-4.6944,54.2237?overview=full&geometries=geojson" -o synthetic-self-referential-route.json
```

That route's polyline was then downsampled to ~500m spacing (matching our own measured ~27-30s
background cadence at 50-90 km/h) with one deliberate ~1500m/90s gap and one deliberate ~120m
off-road noisy point injected — producing exactly the 36 rows in `../shared/input-trace.csv`. The
downsampling/perturbation logic itself (not preserved as a runnable script in this fixture, since
`input-trace.csv` already preserves its exact numeric output) worked as follows, for anyone wanting
to reproduce the derivation logic rather than just replay the fixed CSV:
1. Compute cumulative haversine distance along the route polyline.
2. Take one point every 500m of cumulative distance.
3. Drop 3 consecutive points partway through (the source of the ~1500m gap).
4. Clone the point immediately after the gap, offset its latitude by `+120/111320` degrees
   (~120m north), and insert it immediately before the clone's un-offset original — this produces
   the index 21 (noisy) / index 22 (true) pair in `input-trace.csv`.
5. Assign epoch-second timestamps 27s apart, except a single 90s step between index 21 and 22.

## 8. Run the exact audited `/match` request

`request.txt` in this directory is the byte-exact request URL that was actually executed, built
directly from `../shared/input-trace.csv`'s columns (coordinates in `lon,lat` order, `radiuses`
from `radius_or_accuracy_m`, `timestamps` from `timestamp_epoch_s`):

```bash
curl -s "$(cat request.txt)" -o response.json
```

`response.json` in this directory is the exact, unmodified raw response received.

**Match parameters actually used** (see `request.txt` for the literal values): `geometries=geojson`,
`overview=full`, `annotations=true`, a `radiuses` list (25 meters for every point except 10 meters
for input index 21, matching `../shared/input-trace.csv`'s `radius_or_accuracy_m` column), and a
`timestamps` list (the 36 epoch-second values from that same CSV's `timestamp_epoch_s` column).
**Not set** (so service defaults applied, unverified explicitly in this run): `bearings`, `gaps`,
`tidy`, `steps`.

## 9. Cleanup

```bash
docker stop osrm-spike
docker rm osrm-spike
```

## Sources

- OSRM Match service API reference: https://project-osrm.org/docs/v5.22.0/api/#match-service
- OSRM repository / license (BSD 2-Clause): https://github.com/Project-OSRM/osrm-backend/blob/master/LICENSE.TXT
