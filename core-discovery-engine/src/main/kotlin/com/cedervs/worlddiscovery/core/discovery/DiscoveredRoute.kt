package com.cedervs.worlddiscovery.core.discovery

import java.time.Duration
import java.time.Instant

/**
 * **Derived first-discovery corridor — a conservative visual aid, never canonical discovery truth
 * and never a claim about the actual route travelled.** [DiscoveredCell] (persisted in
 * `discovered_cells`, see `core-database`'s own `DiscoveredCellEntity`) remains the sole canonical
 * discovery record; a corridor is recomputed fresh from it on every emission (see
 * [deriveRouteSegments]), matching `docs/ai-context/LOCATION_TRACKING.md`'s own "PLANNED / NOT
 * IMPLEMENTED — multi-scale discovery visualization" entry: "Every representation must derive from
 * the canonical fine-resolution discovery data at render time — never a second, independently-
 * computed discovery truth at a coarser resolution." Nothing here is persisted.
 *
 * **What this explicitly is NOT** (Codex review correction round — the first implementation of this
 * file overclaimed several of these):
 * - **not** a GPS breadcrumb trail — no raw GPS fix, ordered or otherwise, is persisted anywhere in
 *   this app (`docs/ai-context/REJECTED_APPROACHES.md`'s "Persistent raw GPS history" entry remains
 *   in force and is not reopened by this file);
 * - **not** the exact road/path travelled — every point is an [H3CellConverter.cellCenter], the
 *   deterministic geometric center of a resolution-12 hexagon (~9-19 m across), never a raw
 *   coordinate; a straight line between two centers is a schematic connector, not a claim about
 *   road/terrain geometry;
 * - **not** the complete journey — only *first discovery* is represented; a later revisit of an
 *   already-discovered cell leaves no trace here (see [deriveRouteSegments]'s own "revisit" note) —
 *   a corridor gap can simply mean "already discovered earlier," not "never visited";
 * - **not** proof that every straight connector was physically traversed — a connector is drawn only
 *   where the stored data provides genuine structural (H3 grid-adjacency) and temporal evidence for
 *   it (see [deriveRouteSegments]), and this evidence is still real-world circumstantial, not a
 *   recorded fact.
 *
 * **The guiding principle throughout this file: prefer missing a questionable connection over
 * drawing a false one.** Every decision below is conservative by design — see [deriveRouteSegments]
 * and [clipRouteSegmentsToArea]'s own doc comments for exactly what evidence is required before two
 * points are ever connected, and what happens (a split, or an omission) when that evidence is absent
 * or ambiguous.
 */

/** One point along a derived corridor — a **spatially consolidated** discovered cell's own H3
 * resolution-12 center ([H3CellConverter.cellCenter], never a raw GPS coordinate), paired with the
 * earliest real, already-persisted [DiscoveredCell.firstDiscoveredAt] known for that cell across every
 * trust-status row (see [consolidateBySpatialCell]). Carries no [TrustStatus]/certification meaning of
 * its own — see [deriveRouteSegments]'s own doc comment for why corridor styling does not derive from
 * trust status. */
data class RoutePoint(
    val coordinate: Coordinate,
    val observedAt: Instant,
)

/**
 * A single run of at least 2 [RoutePoint]s where every consecutive pair passed [deriveRouteSegments]'s
 * own conservative continuity test — chronological order, but **never itself a claim of continuous
 * physical travel**, only that the stored data does not contradict it and offers some structural
 * (H3-adjacency) support for it. [deriveRouteSegments] is the only place that constructs one — a run
 * is split into a fresh segment whenever continuity between two consecutive points cannot be credibly
 * established, so within one [RouteSegment] every consecutive pair may legitimately be rendered as a
 * connected line, but two different [RouteSegment]s must never be connected to each other.
 */
data class RouteSegment(val points: List<RoutePoint>) {
    init {
        require(points.size >= 2) {
            "A RouteSegment must have at least 2 points -- a single isolated point cannot form a " +
                "line; deriveRouteSegments/clipRouteSegmentsToArea must filter degenerate runs out " +
                "before constructing one, never pad or invent a second point."
        }
    }
}

// PRODUCT/ENGINEERING CALIBRATION REQUIRED -- starting values only, not measured/tuned against real
// field data. See docs/discovery-engine.md's own [OUVERT -- à calibrer] framing for the sibling
// movement/sampling thresholds this mirrors.

/** Beyond this gap, two consecutive first-discovered cells are never treated as one continuous
 * corridor run -- a rejection GUARD only (see [deriveRouteSegments]'s own doc comment for why time
 * alone never PROVES continuity), not evidence on its own. */
val ROUTE_MAX_GAP_DURATION_CALIBRATION_REQUIRED: Duration = Duration.ofMinutes(20)

/** Beyond this implied speed (distance / elapsed time between two consecutive first-discovered
 * cells), a connection is rejected even if it already passed the structural-adjacency check -- an
 * ADDITIONAL rejection guard, generous enough to cover real vehicle travel, never itself proof a
 * connection is credible. */
const val ROUTE_MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND_CALIBRATION_REQUIRED: Double = 55.0

/**
 * The maximum H3 grid distance (`gridPathCells(a, b).size - 1`, i.e. number of grid steps, via
 * [H3GridTraversal]) between two consecutive first-discovered cells for a connection to be even
 * considered — the core structural-continuity requirement (Codex review correction round): time and
 * implied speed alone are never sufficient evidence, because a cell discovered between two later
 * first-discoveries (a genuine revisit of already-known territory) is invisible to pure chronology —
 * see [deriveRouteSegments]'s own "revisit" note. `1` means direct H3 neighbors only (the strongest
 * possible evidence); this default of `2` additionally tolerates exactly one skipped intermediate
 * cell, acknowledging that real foreground GPS sampling (`docs/ai-context/LOCATION_TRACKING.md`'s own
 * Trip 3/4 field data: a real ~7s cadence at real walking/driving speed) can legitimately skip a
 * single resolution-12 cell (~9-19 m) between two fixes without implying discontinuity. Deliberately
 * **not** a broad tolerance chosen "to make the visualization prettier" — a future genuinely
 * calibrated value must come from real field measurement, not aesthetic preference.
 */
const val ROUTE_MAX_GRID_DISTANCE_CALIBRATION_REQUIRED: Int = 2

/** How many consecutive [RoutePoint]s a rendered corridor node marker represents — sampling to avoid
 * placing a marker on every single first-discovered cell (a resolution-12 cell is only ~9-19 m wide;
 * a raw per-cell node would be visual clutter, not a "meaningful route position" marker). See
 * [sampleRouteNodes]/[sampleRouteNodesBounded]. */
const val ROUTE_NODE_SAMPLE_INTERVAL_CALIBRATION_REQUIRED: Int = 6

/** The hard cap on how many node markers a single rendered overlay (all segments for one selected
 * Department, combined) may ever produce — independent of, and enforced *after*,
 * [ROUTE_NODE_SAMPLE_INTERVAL_CALIBRATION_REQUIRED]'s own per-segment sampling, so a Department with
 * an unusually large first-discovery history still renders a bounded number of `CircleLayer` features
 * (Codex review correction round: the previous per-segment-only sampling reduced density but never
 * bounded the total). See [sampleRouteNodesBounded]. */
const val ROUTE_MAX_NODES_PER_OVERLAY_CALIBRATION_REQUIRED: Int = 250

/** The hard cap on how many points a single rendered `LineString` chunk may ever carry, enforced only
 * at render time by CHUNKING a long [RouteSegment] into multiple smaller, boundary-overlapping
 * [RouteSegment]s (see [chunkRouteSegmentForRendering] — never by dropping/simplifying vertices) — the
 * domain [RouteSegment] itself always keeps its full, real, unmutated point sequence; only how many
 * separate `LineString` `Feature`s that sequence is split across for rendering is affected by this
 * purely-rendering concern. */
const val ROUTE_MAX_LINE_POINTS_PER_SEGMENT_CALIBRATION_REQUIRED: Int = 500

/**
 * **Step 1 of [deriveRouteSegments] — consolidate by real spatial H3 identity, never by the
 * `(h3Index, trustStatus)` persistence identity.** [DiscoveredCellRepository]'s own identity is
 * `(cell, trustStatus)` (see [DiscoveredCell]'s own doc comment) — the exact same physical H3 cell can
 * legitimately exist as two separate rows (once `CERTIFIED`, once `NON_CERTIFIED`). Without this step,
 * such a cell would silently become TWO chronological corridor points at the identical coordinate,
 * which can distort ordering/adjacency decisions for no real reason. Every [DiscoveredCell] sharing
 * the same [CanonicalCell] is collapsed into exactly one `(cell, timestamp)` pair, using the
 * **earliest** `firstDiscoveredAt` across all of that cell's own rows — the deliberate, documented
 * conservative rule: the cell was genuinely first reached (under *some* trust status) at that earlier
 * time; a later, separate discovery of the *same physical location* under a different trust status
 * does not erase that earlier evidence. **Never alters any persisted [DiscoveredCell] row** — this
 * consolidation exists only inside this derivation, is recomputed fresh every time, and has no write
 * path back to [DiscoveredCellRepository].
 */
internal fun consolidateBySpatialCell(discoveredCells: List<DiscoveredCell>): List<Pair<CanonicalCell, Instant>> =
    discoveredCells
        .groupBy { it.cell }
        .map { (cell, rows) -> cell to rows.minOf { it.firstDiscoveredAt } }

/**
 * Derives conservative [RouteSegment]s from [discoveredCells] — the one function that turns real,
 * already-persisted discovery data into a corridor, and the only place continuity decisions are made.
 * Never called with anything but the exact same validated [DiscoveredCell] snapshot every other Map
 * read-side derivation uses (see `ObserveMapReadState`) — this function itself performs no I/O and
 * holds no state, so calling it twice with the same input always produces the same output.
 *
 * **Step 1 — spatial consolidation.** See [consolidateBySpatialCell]. Fewer than 2 distinct spatial
 * cells after consolidation produces no segments at all.
 *
 * **Step 2 — deterministic ordering, not a chronology claim by itself.** The consolidated
 * `(cell, timestamp)` pairs are sorted by `(timestamp, h3Index)` purely so this function's own
 * iteration is deterministic regardless of [discoveredCells]' own input order — **this sort order is
 * never, by itself, treated as evidence of real chronological adjacency**: two distinct cells sharing
 * the identical timestamp have a genuinely **unknown internal order** (batch processing is common and
 * expected), and the tie-break by `h3Index` exists only to make repeated calls reproducible, never to
 * imply "this cell came before that one."
 *
 * **Step 3 — a connection requires ALL of these, checked for every sort-consecutive pair:**
 * 1. **`dt` (the time gap) is STRICTLY POSITIVE.** `dt <= 0` — a same-instant tie between two
 *    distinct cells, or (unreachable given the sort above, but checked defensively) a negative gap —
 *    means ordering between them is genuinely unknown, so they are **never** connected, regardless of
 *    how close together they are geographically. This single check is what makes the Codex-flagged
 *    "same-instant tie at a large distance is treated as continuous" bug structurally impossible: a
 *    same-instant tie now always fails here, unconditionally, before any distance/structural check
 *    ever runs.
 * 2. **`dt` is at most [maxGap].** A rejection GUARD, not proof — see [ROUTE_MAX_GAP_DURATION_CALIBRATION_REQUIRED]'s
 *    own doc comment.
 * 3. **Structural H3-adjacency evidence**: [H3GridTraversal.pathBetween] between the two cells returns
 *    a real path (never `null` — a `null` result, e.g. a pentagon-distortion cell or an excessively
 *    distant pair, is itself conservative rejection, not treated as "unknown, so allow it") whose grid
 *    distance (`path.size - 1`) is at most [maxGridDistance]. **This is the core fix for the
 *    "structural spatial continuity" requirement**: pure chronology plus a plausible average speed is
 *    NOT sufficient evidence two first-discovered cells should be connected — a cell physically
 *    crossed *between* two later first-discoveries, but itself already discovered earlier (a genuine
 *    revisit), is invisible to `firstDiscoveredAt` chronology alone, and connecting across that gap
 *    would draw a corridor through territory the data gives no direct evidence for. Requiring real H3
 *    grid-adjacency means a connection is only ever drawn between cells the data shows are actually
 *    next to (or one cell apart from) each other, not merely "temporally near and geographically
 *    plausible by average speed."
 * 4. **Implied speed is at most [maxPlausibleSpeedMetersPerSecond].** An ADDITIONAL rejection guard —
 *    kept per this round's own instruction ("retain time/speed checks only as additional rejection
 *    guards... must NOT be described as proving continuity") — computed only once the cheaper checks
 *    above have already passed (so `dt` is already known bounded by [maxGap], making the millisecond
 *    conversion this needs safe from overflow by construction, never requiring its own separate
 *    overflow guard).
 *
 * A run of fewer than 2 points after splitting is dropped entirely, never rendered as a degenerate
 * one-point "segment" — [RouteSegment]'s own constructor enforces this as a hard invariant.
 *
 * **Revisits are invisible here, by design — never treated as new traversal evidence.** If a cell was
 * already first-discovered on an earlier visit, a later pass through the same physical location leaves
 * `firstDiscoveredAt` unchanged (see [DiscoveredCellMerger] — `firstDiscoveredAt` is a `min(...)`
 * across merges, `lastObservedAt` the one field a revisit actually updates, and this function never
 * reads `lastObservedAt` at all). A corridor gap therefore does not mean "this territory was never
 * reached" — it may simply mean "reached earlier, on a different pass," which this derivation has no
 * way to distinguish from "never reached" and does not attempt to.
 *
 * **Extreme/malformed timestamps fail safe, never crash.** [Duration.between] (and the millisecond
 * conversion step 4 needs) can in principle throw for pathological [Instant] values; any such failure
 * for a given pair is treated as "not continuous" (reject that connection) rather than propagating —
 * matching this file's own "prefer missing a connection over drawing a false one" principle applied to
 * error handling, and guaranteeing a single malformed timestamp can never fail the whole derivation.
 *
 * **Certification is deliberately not consulted for ordering/continuity.** [DiscoveredCell.trustStatus]
 * plays no role beyond [consolidateBySpatialCell]'s own earliest-timestamp rule — every spatially
 * consolidated point contributes to the corridor on equal footing regardless of which trust status
 * (or both) originally discovered it. Corridor-segment trust styling remains an explicit open product
 * question (see `PROJECT_STATUS.md`'s own entry) — H3 rendering remains the sole place trust status is
 * actually represented; this corridor stays visually neutral/provisional everywhere.
 */
fun deriveRouteSegments(
    discoveredCells: List<DiscoveredCell>,
    cellConverter: H3CellConverter,
    gridTraversal: H3GridTraversal,
    maxGap: Duration = ROUTE_MAX_GAP_DURATION_CALIBRATION_REQUIRED,
    maxGridDistance: Int = ROUTE_MAX_GRID_DISTANCE_CALIBRATION_REQUIRED,
    maxPlausibleSpeedMetersPerSecond: Double = ROUTE_MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND_CALIBRATION_REQUIRED,
): List<RouteSegment> {
    val consolidated = consolidateBySpatialCell(discoveredCells)
    if (consolidated.size < 2) return emptyList()

    val ordered = consolidated.sortedWith(compareBy({ it.second }, { it.first.h3Index }))

    val runs = mutableListOf<MutableList<Pair<CanonicalCell, Instant>>>()
    var current = mutableListOf(ordered.first())
    for (index in 1 until ordered.size) {
        val (previousCell, previousTime) = ordered[index - 1]
        val (cell, time) = ordered[index]
        val continuous = isCredibleContinuation(
            previousCell, previousTime, cell, time,
            cellConverter, gridTraversal, maxGap, maxGridDistance, maxPlausibleSpeedMetersPerSecond,
        )
        if (continuous) {
            current.add(ordered[index])
        } else {
            runs.add(current)
            current = mutableListOf(ordered[index])
        }
    }
    runs.add(current)

    return runs.filter { it.size >= 2 }.map { run ->
        RouteSegment(run.map { (cell, time) -> RoutePoint(cellConverter.cellCenter(cell), time) })
    }
}

@Suppress("LongParameterList") // Internal step function, not a public API surface -- see
// deriveRouteSegments' own doc comment for the full, itemized evidence checklist this implements.
private fun isCredibleContinuation(
    previousCell: CanonicalCell,
    previousTime: Instant,
    cell: CanonicalCell,
    time: Instant,
    cellConverter: H3CellConverter,
    gridTraversal: H3GridTraversal,
    maxGap: Duration,
    maxGridDistance: Int,
    maxPlausibleSpeedMetersPerSecond: Double,
): Boolean {
    val gap = try {
        Duration.between(previousTime, time)
    } catch (e: ArithmeticException) {
        return false
    } catch (e: java.time.DateTimeException) {
        return false
    }
    // Same-instant tie (dt == 0) or, defensively, a negative gap (unreachable given the caller's own
    // sort, but never trusted blindly): ordering is genuinely unknown either way -- never connect.
    if (gap <= Duration.ZERO) return false
    if (gap > maxGap) return false

    val structurallyContinuous = try {
        val path = gridTraversal.pathBetween(previousCell, cell)
        path != null && (path.size - 1) <= maxGridDistance
    } catch (e: IllegalArgumentException) {
        false
    }
    if (!structurallyContinuous) return false

    val gapSeconds = try {
        gap.toMillis() / 1000.0
    } catch (e: ArithmeticException) {
        return false
    }
    if (gapSeconds <= 0.0) return false
    val previousCenter = cellConverter.cellCenter(previousCell)
    val center = cellConverter.cellCenter(cell)
    val impliedSpeed = haversineDistanceMeters(previousCenter, center) / gapSeconds
    return impliedSpeed <= maxPlausibleSpeedMetersPerSecond
}

/** How many interior points along a connecting chord are sampled (in addition to its own two, already
 * known-inside, endpoints) before [clipRouteSegmentsToArea] trusts that chord stays inside the
 * selected Department — see that function's own doc comment for why endpoint-only containment is not
 * sufficient for a concave polygon or one with a hole. */
private val CONNECTING_SEGMENT_SAMPLE_FRACTIONS = listOf(0.25, 0.5, 0.75)

/**
 * Filters [segments] down to only the portions that genuinely lie inside [area]'s own geometry —
 * "Department filtering/clipping": selecting Haute-Vienne must never visualize large out-of-scope
 * portions of a corridor as belonging to it.
 *
 * **Two independent conservative checks, not true line/polygon geometric clipping.** For each segment,
 * points are walked in order:
 * 1. A point outside [area] (per [PointInPolygonClassifier.contains] against its own real center) is
 *    dropped outright and ends whatever run was in progress — never included, never averaged/projected
 *    onto the border.
 * 2. **Even when both endpoints of a candidate connection are individually inside [area], the
 *    connecting chord between them is independently checked** by sampling
 *    [CONNECTING_SEGMENT_SAMPLE_FRACTIONS] points along it and requiring every sample to also test
 *    inside [area]'s geometry (Codex review correction round: two points can each individually be
 *    inside a concave Department, or inside on either side of a hole, while the straight segment
 *    connecting them briefly leaves the polygon or crosses the hole — endpoint-only containment alone
 *    would silently draw that false line). A chord that fails this check ends the current run — the
 *    point itself may still start a **new** run if it's genuinely inside, but it is never connected
 *    back to the previous point across the disqualified chord.
 *
 * **Still not exact Sutherland–Hodgman-style geometric clipping** — a genuinely thin, sharply concave
 * notch could in principle fall entirely between the three sampled fractions and go undetected. This is
 * a deliberate, documented, conservative approximation (`PROJECT_STATUS.md`'s own entry): the sampling
 * catches the realistic cases this round's own physical review flagged (a chord crossing a Department
 * border or a hole) without the materially larger complexity of true line/polygon intersection, and
 * remains strictly more conservative than the point-run-only filtering it replaces.
 *
 * A resulting run of fewer than 2 points is dropped, exactly like [deriveRouteSegments]'s own
 * degenerate-run handling — [RouteSegment]'s constructor enforces this regardless.
 */
fun clipRouteSegmentsToArea(segments: List<RouteSegment>, area: GeographicArea): List<RouteSegment> =
    segments.flatMap { segment ->
        val runs = mutableListOf<MutableList<RoutePoint>>()
        var currentRun: MutableList<RoutePoint>? = null
        for (point in segment.points) {
            if (!PointInPolygonClassifier.contains(area.geometry, point.coordinate)) {
                currentRun = null
                continue
            }
            val existingRun = currentRun
            if (existingRun == null) {
                currentRun = mutableListOf(point).also { runs.add(it) }
            } else if (connectingChordStaysInside(existingRun.last().coordinate, point.coordinate, area)) {
                existingRun.add(point)
            } else {
                currentRun = mutableListOf(point).also { runs.add(it) }
            }
        }
        runs.filter { it.size >= 2 }.map { RouteSegment(it) }
    }

private fun connectingChordStaysInside(from: Coordinate, to: Coordinate, area: GeographicArea): Boolean =
    CONNECTING_SEGMENT_SAMPLE_FRACTIONS.all { fraction ->
        val sample = Coordinate(
            latitude = from.latitude + (to.latitude - from.latitude) * fraction,
            // Department-scale connectors only (a few tens of meters at most, given the H3-adjacency
            // requirement above) -- never realistically antimeridian-crossing, so a plain linear
            // interpolation is a safe, deliberate simplification here, unlike the antimeridian-aware
            // unwrapping this codebase uses for genuinely long/global geometry elsewhere.
            longitude = from.longitude + (to.longitude - from.longitude) * fraction,
        )
        PointInPolygonClassifier.contains(area.geometry, sample)
    }

/**
 * Deterministically samples [segment] down to "meaningful" node positions — always its own first and
 * last point (so a rendered segment's own visual endpoints are always anchored by a node), plus every
 * [sampleEveryN]-th point in between. Never renders a marker on every single [RoutePoint] (a
 * resolution-12 discovered cell is only ~9-19 m wide; per-cell nodes would be clutter, not meaningful
 * route positions). **Per-segment only — does not itself bound the total across many segments**; see
 * [sampleRouteNodesBounded] for the overlay-wide cap. Pure and order-preserving; the caller
 * (`feature-map`) is the only place this list becomes a rendered `Feature`.
 */
fun sampleRouteNodes(segment: RouteSegment, sampleEveryN: Int = ROUTE_NODE_SAMPLE_INTERVAL_CALIBRATION_REQUIRED): List<RoutePoint> {
    require(sampleEveryN >= 1) { "sampleEveryN must be at least 1, got $sampleEveryN" }
    val points = segment.points
    if (points.size <= 2) return points
    val sampled = LinkedHashSet<Int>()
    sampled.add(0)
    var index = sampleEveryN
    while (index < points.size - 1) {
        sampled.add(index)
        index += sampleEveryN
    }
    sampled.add(points.size - 1)
    return sampled.sorted().map { points[it] }
}

/**
 * The overlay-wide node budget: [sampleRouteNodes] first, per segment, then — only if the combined
 * total still exceeds [maxTotalNodes] — one further deterministic uniform-stride reduction across the
 * *combined* sequence (always keeping the very first and very last node of that combined sequence,
 * never necessarily each individual segment's own endpoints once this second pass triggers — a
 * documented tradeoff of the bounding pass, not a bug). Guarantees a Department with an unusually large
 * first-discovery history still renders a **hard-bounded** number of node markers
 * ([ROUTE_MAX_NODES_PER_OVERLAY_CALIBRATION_REQUIRED] by default) — never thousands of `CircleLayer`
 * features from a long corridor history.
 */
fun sampleRouteNodesBounded(
    segments: List<RouteSegment>,
    sampleEveryN: Int = ROUTE_NODE_SAMPLE_INTERVAL_CALIBRATION_REQUIRED,
    maxTotalNodes: Int = ROUTE_MAX_NODES_PER_OVERLAY_CALIBRATION_REQUIRED,
): List<RoutePoint> {
    require(maxTotalNodes >= 0) { "maxTotalNodes must not be negative, got $maxTotalNodes" }
    val combined = segments.flatMap { sampleRouteNodes(it, sampleEveryN) }
    return uniformStrideDownsample(combined, maxTotalNodes)
}

/**
 * **Bounds a single rendered `LineString`'s point count by CHUNKING, never by dropping vertices.**
 *
 * Codex review correction round (blocking defect, previous round): the earlier
 * `simplifyRouteSegmentForRendering` reduced a long segment's point count via uniform-stride vertex
 * *dropping*, then let MapLibre draw a straight edge between whichever points survived. That
 * silently manufactured brand-new chords (`P0 -> P4 -> P8 -> ...`) that had never themselves passed
 * [deriveRouteSegments]'s own H3-grid-adjacency/time/speed evidence checks — exactly the kind of
 * unvalidated connector this whole file otherwise refuses to draw. **Fixed by construction, not by
 * revalidating synthetic chords**: [segment] is instead split into consecutive, **overlapping**
 * sub-lists — chunk *N*'s own last point is always chunk *N+1*'s own first point — so every edge any
 * chunk ever renders is a genuine, already-validated original edge from [segment]'s own point
 * sequence. No point is ever skipped, reordered, or connected to a non-adjacent point. [segment]
 * itself is never mutated — this returns a fresh list of smaller [RouteSegment]s; the canonical one
 * the caller passed in is untouched.
 *
 * A `[maxPointsPerChunk]`-sized [segment] (or smaller) returns unchanged as a single-element list —
 * chunking only ever engages once truly necessary. `feature-map` renders each returned chunk as its
 * own `LineString` `Feature`; because consecutive chunks share their boundary point exactly, the
 * rendered corridor stays visually continuous (no gap at a chunk boundary) despite being drawn as
 * multiple separate `Feature`s.
 */
fun chunkRouteSegmentForRendering(
    segment: RouteSegment,
    maxPointsPerChunk: Int = ROUTE_MAX_LINE_POINTS_PER_SEGMENT_CALIBRATION_REQUIRED,
): List<RouteSegment> {
    require(maxPointsPerChunk >= 2) { "maxPointsPerChunk must be at least 2 -- a RouteSegment can never have fewer, got $maxPointsPerChunk" }
    val points = segment.points
    if (points.size <= maxPointsPerChunk) return listOf(segment)

    val chunks = mutableListOf<RouteSegment>()
    var startIndex = 0
    while (startIndex < points.size - 1) {
        val endIndexExclusive = (startIndex + maxPointsPerChunk).coerceAtMost(points.size)
        chunks.add(RouteSegment(points.subList(startIndex, endIndexExclusive)))
        if (endIndexExclusive == points.size) break
        // The next chunk starts at this chunk's own last point (index endIndexExclusive - 1) --
        // the intentional one-point overlap that keeps every rendered edge a real original edge.
        startIndex = endIndexExclusive - 1
    }
    return chunks
}

/** Shared deterministic uniform-stride reduction: keeps the first and last element of [items] and
 * spreads the remaining budget evenly across the rest — used by [sampleRouteNodesBounded] only.
 * **Never used for `LineString` geometry** — see [chunkRouteSegmentForRendering]'s own doc comment for
 * why dropping intermediate vertices from a validated edge sequence is unsafe for rendered *lines*
 * (it manufactures new, unvalidated chords) even though it remains a safe, deliberate approximation
 * for *node markers* (a marker is a single point, not a chord — dropping one never creates a false
 * edge). Never exceeds [maxItems]; may return fewer if rounding collapses adjacent picks onto the
 * same index. */
private fun <T> uniformStrideDownsample(items: List<T>, maxItems: Int): List<T> {
    if (maxItems <= 0) return emptyList()
    if (items.size <= maxItems) return items
    if (maxItems == 1) return listOf(items.first())

    val stride = (items.size - 1).toDouble() / (maxItems - 1)
    // The very last index is forced to items.size - 1 explicitly, rather than trusted to the
    // floating-point stride computation to land there exactly -- confirmed empirically that
    // (maxItems - 1) * stride can round a hair short of items.size - 1 for some (size, maxItems)
    // combinations, which would otherwise silently break the "always keeps the last element"
    // guarantee this function's own doc comment promises.
    val indices = (0 until maxItems).map { i ->
        if (i == maxItems - 1) items.size - 1 else (i * stride).toInt().coerceIn(0, items.size - 1)
    }
    return indices.distinct().map { items[it] }
}
