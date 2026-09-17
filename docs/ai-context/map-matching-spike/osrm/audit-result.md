# OSRM audit result (precomputed, verified reproducible)

Produced by running `audit-osrm.ps1` from this directory against `response.json` and
`../shared/input-trace.csv`. Re-run the script to regenerate; this file is a convenience snapshot,
not the source of truth (the raw `response.json` and `input-trace.csv` are).

```
code: Ok
matchings count: 1
matchings[0].confidence = 0.878803
```

| idx | input lon,lat | intent | tracepoint | matched lon,lat | distance (m) | matchings_index | waypoint_index | alternatives_count |
|---|---|---|---|---|---|---|---|---|
| 0 | -4.481803,54.150724 | NORMAL | matched | -4.482648,54.150695 | 55.275155 | 0 | 0 | 0 |
| 1–18 | (route) | NORMAL | matched | ≈ identical to input | 0 | 0 | 1–18 | 0 |
| 19 | -4.600364,54.194293 | NORMAL (first point after the dropped-samples gap) | matched | identical | 0 | 0 | 19 | 0 |
| **20** | -4.605657,54.196767 | **NORMAL, not perturbed** | **NULL** | — | — | — | — | — |
| **21** | -4.613303,54.198500 | **INJECTED_NOISY** | **NULL** | — | — | — | — | — |
| 22 | -4.613303,54.197422 | NORMAL (true unoffset location) | matched | identical | 0 | 0 | 20 | 0 |
| 23–34 | (route) | NORMAL | matched | identical | 0 | 0 | 21–32 | 0 |
| 35 | -4.690383,54.222327 | NORMAL (last point) | matched | identical | 0 | 0 | 33 | **40** |

**Findings preserved exactly as observed, no invented explanation:**
- Index 21 (the deliberately injected, offset, tight-radius point) came back `null` — expected.
- Index 20 (a completely normal, non-perturbed point) **also** came back `null` — this is
  **not explained** by this audit or by anything else in this fixture. It is reported as-is.
- `waypoint_index` skips cleanly from 19 to 20 (input index 22), confirming both null points are
  fully excluded from the single `matchings[0]` (never interpolated, never counted).
- `alternatives_count` is 0 everywhere except the very last point (index 35), where it is 40.
- The ~90s temporal gap (between index 21 and 22) did not produce a trace split despite the
  documented default `gaps=split` behavior — not explained further here, a benchmark question.
