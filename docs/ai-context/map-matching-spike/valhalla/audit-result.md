# Valhalla audit result (precomputed, verified reproducible)

Produced by running `audit-valhalla.ps1` from this directory against `response.json` and
`../shared/input-trace.csv`. Re-run the script to regenerate; this file is a convenience snapshot,
not the source of truth.

```
matched_points count: 36
edges count: 217
```

| idx | intent | type | edge_index | edge name | distance_from_trace_point (**meters**) | distance_along_edge | discontinuity | matched lon,lat |
|---|---|---|---|---|---|---|---|---|
| 0–19 | NORMAL | matched | 0…142 (sequential) | "A1/Peel Road" etc. | 0–0.035 | varies | none | ≈ input |
| **20** | NORMAL, not perturbed | matched | **143** | A1/Peel Road | 0 | 0.976 | none | ≈ input |
| **21** | **INJECTED_NOISY** (+120m offset, accuracy=10) | matched | **144** | **(unnamed)** | **5.689192 ≈ 5.7 m** | 0.610 | none | -4.61339,54.198498 — essentially identical to the **noisy input coordinate** (-4.613303,54.198500), NOT the true unoffset location at -4.613303,54.197422 (~120m away) |
| 22 | NORMAL (true unoffset location) | matched | **148** | A1/Peel Road | 0 | 0.206 | none | ≈ input |
| 23–35 | NORMAL | matched | 150…216 (sequential) | "A1/Peel Road", "A3", "Poortown Road/A20", "Derby Road/A20" etc. | 0 | varies | none | ≈ input |

**Unit correction (load-bearing for this whole fixture):** `distance_from_trace_point` is
**meters** per Valhalla's own API reference (see `commands.md` §Sources) — `5.689192` is
**5.7 meters**, never kilometers, regardless of the top-level response `"units"` field (which
applies only to route/leg length fields, not this per-point field).

**Finding, proven by this table (not inferred from distance alone):** the injected point (index
21) was not pulled back onto the intended road (A1/Peel Road, edges 143/148 immediately before and
after) and no discontinuity flag fired. It was matched onto a **different, unnamed edge (144)**,
at a location essentially identical to its own **noisy input coordinate** — a plausible
nearby-road false positive for this synthetic fixture, not a generalizable claim about Valhalla.

**36/36 points came back `type:"matched"`** — none `unmatched`/`interpolated`, in contrast to
OSRM's two `null` tracepoints on the same input (see `../osrm/audit-result.md`).
