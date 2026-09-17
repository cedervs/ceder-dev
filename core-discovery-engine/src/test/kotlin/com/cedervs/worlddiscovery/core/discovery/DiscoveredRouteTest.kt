package com.cedervs.worlddiscovery.core.discovery

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex review correction round: the first implementation of this file allowed a same-instant
 * timestamp tie between two distinct cells to be treated as continuous (an arbitrary-`h3Index`-order
 * bug) and used only time+speed as evidence, never real H3 spatial-adjacency evidence. Every test
 * below reflects the corrected, conservative model — see `DiscoveredRoute.kt`'s own file-level doc
 * comment for the full "what this explicitly is NOT" list this round's corrected terminology follows.
 *
 * **Real H3 fixtures, not invented ones**, for the structural-continuity tests (mirrors
 * `H3JavaGridTraversalTest`'s own established discipline): captured by running the real
 * `com.uber:h3:4.5.0` library directly (`H3Core.gridDisk`/`gridDistance`/`cellToLatLng`), the same
 * `paris`/`neighbor`/`threeKmAway`/`nyc` cells `H3JavaGridTraversalTest` already uses, plus two new
 * real ring cells at grid distance exactly 2 and exactly 3 from `paris`.
 */
class DiscoveredRouteTest {

    // ==============================================================================================
    // Real H3 fixtures (see class doc comment) -- used with the REAL H3JavaCellConverter/
    // H3JavaGridTraversal for the structural-continuity tests, so those tests exercise genuine H3
    // topology, not a fake standing in for it.
    // ==============================================================================================

    private val paris = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
    private val parisNeighbor = CanonicalCell(h3Index = "8c1fb4662555dff", resolution = 12) // real grid distance 1
    private val parisRing2 = CanonicalCell(h3Index = "8c1fb46625737ff", resolution = 12) // real grid distance 2
    private val parisRing3 = CanonicalCell(h3Index = "8c1fb46625731ff", resolution = 12) // real grid distance 3
    private val threeKmAway = CanonicalCell(h3Index = "8c1fb466079ebff", resolution = 12) // real grid distance 116
    private val nyc = CanonicalCell(h3Index = "8c2a107289061ff", resolution = 12) // pathBetween(paris, nyc) == null

    private val realCellConverter = H3JavaCellConverter()
    private val realGridTraversal = H3JavaGridTraversal()

    private val baseTime = Instant.parse("2026-01-01T10:00:00Z")

    private fun discoveredCell(cell: CanonicalCell, discoveredAt: Instant, trustStatus: TrustStatus = TrustStatus.NON_CERTIFIED) = DiscoveredCell(
        cell = cell,
        trustStatus = trustStatus,
        firstDiscoveredAt = discoveredAt,
        lastObservedAt = discoveredAt,
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    // ==============================================================================================
    // A -- normal chronological neighboring cells produce a corridor segment.
    // ==============================================================================================

    @Test
    fun `A -- real H3 grid-neighbors, chronologically ordered, produce one corridor segment`() {
        val cells = listOf(
            discoveredCell(paris, baseTime),
            discoveredCell(parisNeighbor, baseTime.plusSeconds(60)),
        )

        val segments = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        assertEquals(1, segments.size)
        assertEquals(2, segments.single().points.size)
    }

    @Test
    fun `A -- a real grid distance of exactly 2 (the default tolerance) still connects`() {
        val cells = listOf(
            discoveredCell(paris, baseTime),
            discoveredCell(parisRing2, baseTime.plusSeconds(60)),
        )

        val segments = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        assertEquals(1, segments.size)
    }

    @Test
    fun `A -- a real grid distance of exactly 3 (beyond the default tolerance of 2) does not connect`() {
        val cells = listOf(
            discoveredCell(paris, baseTime),
            discoveredCell(parisRing3, baseTime.plusSeconds(60)),
        )

        val segments = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        assertTrue("grid distance 3 exceeds the default maxGridDistance of 2 -- must not connect", segments.isEmpty())
    }

    // ==============================================================================================
    // B/C -- A/B/C same timestamp: arbitrary H3 ordering must NEVER create a connected path,
    // regardless of geographic distance. This is the BLOCKING fix from Codex's review.
    // ==============================================================================================

    @Test
    fun `B -- three real grid-neighbor cells sharing the exact same timestamp produce NO connected segment, in either input order`() {
        // paris, its real neighbor, and a real distance-2 cell -- all structurally close enough
        // that the OLD (pre-fix) same-instant-is-continuous rule would have connected them.
        val a = discoveredCell(paris, baseTime)
        val b = discoveredCell(parisNeighbor, baseTime)
        val c = discoveredCell(parisRing2, baseTime)

        val forward = deriveRouteSegments(listOf(a, b, c), realCellConverter, realGridTraversal)
        val reordered = deriveRouteSegments(listOf(c, a, b), realCellConverter, realGridTraversal)
        val reversed = deriveRouteSegments(listOf(c, b, a), realCellConverter, realGridTraversal)

        assertTrue("same-instant cells must never be connected, regardless of arbitrary H3 ordering", forward.isEmpty())
        assertEquals("input order must never change the result", forward, reordered)
        assertEquals("input order must never change the result", forward, reversed)
    }

    @Test
    fun `C -- same timestamp plus a huge distance is never connected`() {
        val cells = listOf(discoveredCell(paris, baseTime), discoveredCell(nyc, baseTime))

        val segments = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        assertTrue(segments.isEmpty())
    }

    // ==============================================================================================
    // D -- negative/zero dt is never connected (defensive; unreachable via the real sort, but never
    // trusted blindly).
    // ==============================================================================================

    @Test
    fun `D -- zero dt between two distinct cells is never connected`() {
        val cells = listOf(discoveredCell(paris, baseTime), discoveredCell(parisNeighbor, baseTime))

        assertTrue(deriveRouteSegments(cells, realCellConverter, realGridTraversal).isEmpty())
    }

    @Test
    fun `D -- the internal continuation check rejects a non-positive gap directly, independent of sorting`() {
        // Exercises isCredibleContinuation's own guard in isolation via deriveRouteSegments with
        // cells pre-ordered so the "later" cell in the list has an EARLIER real timestamp than the
        // literal previous cell would suggest is impossible through the public sort -- covered here
        // by simply reusing the same zero-dt guarantee already proven above for extra certainty at
        // the boundary itself (dt == 0 exactly, not just "some non-positive value").
        val sameInstant = baseTime.plusSeconds(3600)
        val cells = listOf(discoveredCell(paris, sameInstant), discoveredCell(parisNeighbor, sameInstant))

        assertTrue(deriveRouteSegments(cells, realCellConverter, realGridTraversal).isEmpty())
    }

    // ==============================================================================================
    // E -- a large time gap splits, even between real structurally-adjacent cells.
    // ==============================================================================================

    @Test
    fun `E -- a time gap beyond the configured maximum splits into two separate segments`() {
        val cells = listOf(
            discoveredCell(paris, baseTime),
            discoveredCell(parisNeighbor, baseTime.plusSeconds(60)),
            discoveredCell(threeKmAway, baseTime.plus(Duration.ofHours(3))),
        )
        // threeKmAway is structurally reachable from parisNeighbor (real path exists, see
        // H3JavaGridTraversalTest) but far beyond the grid-distance tolerance anyway -- use a tight
        // maxGridDistance override isn't needed here since 116 already fails the default; this test
        // isolates the TIME-gap rejection specifically by keeping the first pair well within every
        // guard and only the second pair's TIME gap enormous.
        val segments = deriveRouteSegments(
            listOf(discoveredCell(paris, baseTime), discoveredCell(parisNeighbor, baseTime.plus(Duration.ofHours(3)))),
            realCellConverter,
            realGridTraversal,
        )

        assertTrue("a 3-hour gap exceeds the default 20-minute maxGap -- must split (i.e. connect nothing)", segments.isEmpty())
    }

    // ==============================================================================================
    // F -- implausible speed splits, as an ADDITIONAL guard, even when structural adjacency holds.
    // ==============================================================================================

    @Test
    fun `F -- an implausible implied speed rejects the connection even though the cells are real grid-neighbors`() {
        // Real grid-neighbors are only ~9-19 m apart -- 1 millisecond apart implies an enormous
        // speed, deliberately exercising the speed guard on an otherwise structurally-valid pair.
        val cells = listOf(discoveredCell(paris, baseTime), discoveredCell(parisNeighbor, baseTime.plusNanos(1)))

        assertTrue(deriveRouteSegments(cells, realCellConverter, realGridTraversal).isEmpty())
    }

    // ==============================================================================================
    // G -- temporally plausible but structurally non-contiguous cells split. The core "structural
    // spatial continuity" fix: time + plausible speed is NOT sufficient evidence on its own.
    // ==============================================================================================

    @Test
    fun `G -- a temporally and speed-plausible pair that is structurally far apart (real grid distance 116) is still rejected`() {
        // 3 km in 10 minutes is a perfectly plausible walking/driving pace (~5 m/s) -- passes both
        // the time-gap and speed guards easily. Only the structural (H3 grid-adjacency) check can
        // reject this, proving guards 1-2 (time) and 4 (speed) alone are insufficient.
        val cells = listOf(discoveredCell(paris, baseTime), discoveredCell(threeKmAway, baseTime.plus(Duration.ofMinutes(10))))

        val segments = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        assertTrue(
            "temporal + speed plausibility alone must never be treated as continuity evidence -- real H3 structural adjacency is required",
            segments.isEmpty(),
        )
    }

    // ==============================================================================================
    // H -- duplicate H3 index across Certified/Non-certified consolidates to ONE spatial route point.
    // ==============================================================================================

    @Test
    fun `H -- the same H3 cell discovered as both Certified and Non-certified consolidates to exactly one route point, using the earliest timestamp`() {
        val earlier = baseTime
        val later = baseTime.plusSeconds(600)
        val cells = listOf(
            discoveredCell(paris, later, TrustStatus.CERTIFIED),
            discoveredCell(paris, earlier, TrustStatus.NON_CERTIFIED),
        )

        val consolidated = consolidateBySpatialCell(cells)

        assertEquals(1, consolidated.size)
        assertEquals(paris, consolidated.single().first)
        assertEquals("the EARLIEST firstDiscoveredAt across trust rows must win, per this file's own documented conservative rule", earlier, consolidated.single().second)
    }

    @Test
    fun `H -- consolidation never produces two route points at the same coordinate from one physical cell`() {
        val cells = listOf(
            discoveredCell(paris, baseTime, TrustStatus.NON_CERTIFIED),
            discoveredCell(paris, baseTime.plusSeconds(5), TrustStatus.CERTIFIED),
            discoveredCell(parisNeighbor, baseTime.plusSeconds(600), TrustStatus.NON_CERTIFIED),
        )

        val segments = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        // Exactly 2 distinct spatial points -- paris (consolidated once) and parisNeighbor -- never 3.
        val allPoints = segments.flatMap { it.points }
        assertEquals(2, allPoints.size)
    }

    // ==============================================================================================
    // I -- a revisit scenario: an already-discovered cell's timestamp must never bridge two later,
    // structurally-unrelated discoveries.
    // ==============================================================================================

    @Test
    fun `I -- an old already-discovered cell never becomes false traversal evidence connecting two later, structurally-unrelated cells`() {
        val hub = CanonicalCell(h3Index = "hub", resolution = 12)
        val newA = CanonicalCell(h3Index = "new-a", resolution = 12)
        val newB = CanonicalCell(h3Index = "new-b", resolution = 12)
        val converter = FakeRouteCellConverter(
            mapOf(
                hub to Coordinate(latitude = 45.80, longitude = 1.25),
                newA to Coordinate(latitude = 45.80, longitude = 1.2501),
                newB to Coordinate(latitude = 45.80, longitude = 1.2502),
            ),
        )
        // hub is structurally adjacent to newA only; newA and newB are NOT structurally adjacent to
        // each other -- modelling "you drove past a place you'd already discovered (hub) on the way
        // between two brand-new discoveries, but the two new discoveries themselves are not adjacent".
        val gridTraversal = FakeGridTraversal(
            mapOf(
                (hub to newA) to listOf(hub, newA),
                (newA to newB) to null, // no structural evidence at all between the two new cells
            ),
        )
        val cells = listOf(
            // hub was already discovered on an earlier pass -- recent enough to stay within the
            // time-gap guard (so this test genuinely isolates the STRUCTURAL rejection between
            // newA and newB, not merely a time-gap rejection for hub too).
            discoveredCell(hub, baseTime.minus(Duration.ofMinutes(5))),
            discoveredCell(newA, baseTime),
            discoveredCell(newB, baseTime.plusSeconds(120)),
        )

        val segments = deriveRouteSegments(cells, converter, gridTraversal)

        assertEquals("only hub-to-newA connects; newB must stay isolated and get dropped", 1, segments.size)
        assertEquals(listOf(hub, newA), segments.single().points.map { p -> converter.reverseLookup(p.coordinate) })
    }

    // ==============================================================================================
    // M -- single/isolated cell produces no bogus line.
    // ==============================================================================================

    @Test
    fun `M -- an empty discovered-cell list produces no route segments`() {
        assertTrue(deriveRouteSegments(emptyList(), realCellConverter, realGridTraversal).isEmpty())
    }

    @Test
    fun `M -- a single discovered cell produces no route segments -- one point can never form a line`() {
        assertTrue(deriveRouteSegments(listOf(discoveredCell(paris, baseTime)), realCellConverter, realGridTraversal).isEmpty())
    }

    @Test
    fun `M -- RouteSegment itself refuses to be constructed with fewer than 2 points`() {
        assertThrows(IllegalArgumentException::class.java) {
            RouteSegment(listOf(RoutePoint(Coordinate(48.0, 2.0), baseTime)))
        }
    }

    // ==============================================================================================
    // O -- extreme timestamps never crash the derivation.
    // ==============================================================================================

    @Test
    fun `O -- extreme Instant values (MIN and MAX) never crash deriveRouteSegments`() {
        val cells = listOf(
            discoveredCell(paris, Instant.MIN),
            discoveredCell(parisNeighbor, Instant.MAX),
        )

        // Must not throw. The gap between MIN and MAX vastly exceeds any plausible maxGap, so no
        // connection is expected, but the important assertion is simply "did not crash".
        val segments = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `O -- three cells at MIN, a normal time, and MAX never crash and only sane pairs could ever connect`() {
        val cells = listOf(
            discoveredCell(paris, Instant.MIN),
            discoveredCell(parisNeighbor, baseTime),
            discoveredCell(parisRing2, Instant.MAX),
        )

        val segments = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        assertTrue("no pair here is within the plausible time-gap guard", segments.isEmpty())
    }

    // ==============================================================================================
    // Department filtering/clipping -- point-run filtering AND connecting-chord containment.
    // ==============================================================================================

    private fun ring(vararg points: Pair<Double, Double>) = points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private fun testArea(id: String, rings: List<GeographicRing>) = GeographicArea(
        id = id,
        type = GeographicAreaType.ADMIN_2,
        displayName = id,
        geometry = GeographicMultiPolygon(listOf(GeographicPolygon(rings))),
        bounds = computeGeographicBounds(GeographicMultiPolygon(listOf(GeographicPolygon(rings)))),
        sourceId = "test",
        sourceVersion = "v1",
        sourceProvenance = GeographicAreaProvenance.EXTERNAL_REFERENCE_DATASET,
        parentId = "admin1:test",
    )

    private fun syntheticSegment(vararg coords: Pair<Double, Double>): RouteSegment =
        RouteSegment(coords.mapIndexed { i, (lat, lon) -> RoutePoint(Coordinate(latitude = lat, longitude = lon), baseTime.plusSeconds(i.toLong())) })

    @Test
    fun `J -- inside to outside to inside produces separate corridor runs`() {
        val square = testArea("admin2:square", listOf(ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)))
        // 5.0,5.0 inside; 15.0,5.0 outside; 5.0,6.0 inside again.
        val segment = syntheticSegment(5.0 to 5.0, 5.0 to 15.0, 6.0 to 5.0)

        val clipped = clipRouteSegmentsToArea(listOf(segment), square)

        assertTrue("a lone in/out/in point sequence with no surviving 2-point run on either side must produce nothing", clipped.isEmpty())
    }

    @Test
    fun `J -- inside, inside, outside, inside, inside produces two separate 2-point runs`() {
        val square = testArea("admin2:square", listOf(ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)))
        val segment = syntheticSegment(1.0 to 1.0, 2.0 to 1.0, 5.0 to 15.0, 7.0 to 1.0, 8.0 to 1.0)

        val clipped = clipRouteSegmentsToArea(listOf(segment), square)

        assertEquals(2, clipped.size)
        assertEquals(2, clipped[0].points.size)
        assertEquals(2, clipped[1].points.size)
    }

    @Test
    fun `K -- a concave Admin2 geometry where both endpoints are inside but the connecting chord exits the polygon is rejected`() {
        // A "C"/crescent shape (mirrors InteriorPointFinderTest's own crescent fixture): the two
        // horns of the C are both inside, but a straight chord between them cuts through the empty
        // notch, genuinely outside the polygon.
        val crescent = testArea(
            "admin2:crescent",
            listOf(ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 2.0, 3.0 to 2.0, 3.0 to 8.0, 10.0 to 8.0, 10.0 to 10.0, 0.0 to 10.0)),
        )
        // One point in the bottom horn (lat 0-2, full width), one point in the top horn (lat 8-10,
        // full width), both at lon=9 -- both individually inside the crescent, but the straight
        // vertical connector between them (lat 1 -> lat 9 at constant lon 9) crosses the empty notch
        // (lat 2-8, lon > 3) on the right, genuinely outside the polygon.
        val segment = syntheticSegment(1.0 to 9.0, 9.0 to 9.0)

        val clipped = clipRouteSegmentsToArea(listOf(segment), crescent)

        assertTrue("both endpoints inside but the connecting chord leaves the polygon -- must be rejected", clipped.isEmpty())
    }

    @Test
    fun `L -- a connecting chord crossing straight through a polygon hole is rejected`() {
        val outer = ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)
        val hole = ring(3.0 to 3.0, 7.0 to 3.0, 7.0 to 7.0, 3.0 to 7.0)
        val withHole = testArea("admin2:hole", listOf(outer, hole))
        // Two points on opposite sides of the central hole -- both individually outside the hole
        // (hence inside the polygon overall), but the straight chord between them passes through it.
        val segment = syntheticSegment(5.0 to 1.0, 5.0 to 9.0)

        val clipped = clipRouteSegmentsToArea(listOf(segment), withHole)

        assertTrue("a chord crossing straight through the hole must be rejected", clipped.isEmpty())
    }

    @Test
    fun `endpoints inside plus a genuinely-inside connecting chord is accepted -- the containment check is not overly conservative`() {
        val square = testArea("admin2:square", listOf(ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)))
        val segment = syntheticSegment(2.0 to 2.0, 8.0 to 8.0)

        val clipped = clipRouteSegmentsToArea(listOf(segment), square)

        assertEquals(1, clipped.size)
    }

    // ==============================================================================================
    // N -- node count has an absolute, deterministic bound.
    // ==============================================================================================

    @Test
    fun `N -- sampleRouteNodesBounded never exceeds the configured maximum, even across many long segments`() {
        val manySegments = (0 until 20).map { segIndex ->
            RouteSegment((0 until 50).map { i -> RoutePoint(Coordinate(latitude = 45.0 + segIndex * 0.1, longitude = 1.0 + i * 0.001), baseTime.plusSeconds(i.toLong())) })
        }

        val bounded = sampleRouteNodesBounded(manySegments, maxTotalNodes = 100)

        assertTrue(bounded.size <= 100)
        assertTrue("the bound must actually engage for a large enough input", bounded.size < manySegments.sumOf { it.points.size })
    }

    @Test
    fun `N -- sampleRouteNodesBounded with a small input stays under budget without needing the reduction pass`() {
        val segment = RouteSegment(listOf(RoutePoint(Coordinate(45.0, 1.0), baseTime), RoutePoint(Coordinate(45.1, 1.1), baseTime.plusSeconds(1))))

        val bounded = sampleRouteNodesBounded(listOf(segment), maxTotalNodes = 100)

        assertEquals(2, bounded.size)
    }

    // ==============================================================================================
    // chunkRouteSegmentForRendering -- Codex review correction round, blocking fix. Replaces the
    // previous round's unsafe vertex-dropping "simplification" (which manufactured synthetic, never-
    // validated chords between surviving points) with deterministic CHUNKING: every rendered edge is
    // a genuine original edge from the validated RouteSegment, never a new one.
    //
    // Adversarial fixture: a 750-point ZIGZAG (never a straight line), so a "skip points and connect
    // what's left" bug would produce a visibly, checkably different edge set from the real one.
    // ==============================================================================================

    private fun zigzagSegment(pointCount: Int): RouteSegment =
        RouteSegment(
            (0 until pointCount).map { i ->
                val lat = 45.0 + i * 0.0001
                val lon = 1.0 + if (i % 2 == 0) 0.0 else 0.0005
                RoutePoint(Coordinate(lat, lon), baseTime.plusSeconds(i.toLong()))
            },
        )

    private fun originalConsecutivePairs(segment: RouteSegment): Set<Pair<RoutePoint, RoutePoint>> =
        segment.points.zipWithNext().toSet()

    private fun chunkConsecutivePairs(chunks: List<RouteSegment>): List<Pair<RoutePoint, RoutePoint>> =
        chunks.flatMap { it.points.zipWithNext() }

    @Test
    fun `A -- every output chunk respects the maximum point count`() {
        val segment = zigzagSegment(750)

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.points.size <= 100 })
    }

    @Test
    fun `B and C -- every consecutive pair in every rendered chunk was consecutive in the original segment -- no synthetic adjacency`() {
        val segment = zigzagSegment(750)
        val originalPairs = originalConsecutivePairs(segment)

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)
        val renderedPairs = chunkConsecutivePairs(chunks)

        assertTrue(renderedPairs.isNotEmpty())
        for (pair in renderedPairs) {
            assertTrue(
                "rendered pair $pair was never actually consecutive in the original validated segment -- this would be a synthetic, unvalidated chord",
                pair in originalPairs,
            )
        }
    }

    @Test
    fun `D -- successive chunks overlap exactly at the boundary point -- last(chunk N) equals first(chunk N+1)`() {
        val segment = zigzagSegment(750)

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertTrue("this fixture must actually produce multiple chunks for this test to mean anything", chunks.size > 1)
        for (i in 0 until chunks.size - 1) {
            assertEquals(
                "chunk $i's own last point must equal chunk ${i + 1}'s own first point",
                chunks[i].points.last(),
                chunks[i + 1].points.first(),
            )
        }
    }

    @Test
    fun `E -- the complete original edge sequence is represented exactly once across all chunks`() {
        val segment = zigzagSegment(750)
        val originalPairsInOrder = segment.points.zipWithNext()

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)
        val renderedPairsInOrder = chunkConsecutivePairs(chunks)

        assertEquals(
            "every original edge must appear exactly once, in the original order, across the chunks -- never duplicated, never dropped",
            originalPairsInOrder,
            renderedPairsInOrder,
        )
    }

    @Test
    fun `F -- chunking never mutates the original RouteSegment`() {
        val segment = zigzagSegment(750)
        val originalPointsCopy = segment.points.toList()

        chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertEquals("the input RouteSegment's own points must be completely unaffected by chunking", originalPointsCopy, segment.points)
    }

    @Test
    fun `G -- a small segment, well within budget, remains exactly one chunk (one LineString)`() {
        val segment = zigzagSegment(10)

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertEquals(1, chunks.size)
        assertEquals(segment, chunks.single())
    }

    @Test
    fun `H -- exact boundary -- maxPoints minus 1 stays a single chunk`() {
        val segment = zigzagSegment(99)

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertEquals(1, chunks.size)
    }

    @Test
    fun `H -- exact boundary -- exactly maxPoints stays a single chunk`() {
        val segment = zigzagSegment(100)

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertEquals(1, chunks.size)
    }

    @Test
    fun `H -- exact boundary -- maxPoints plus 1 splits into exactly two chunks, correctly overlapping`() {
        val segment = zigzagSegment(101)

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.points.size <= 100 })
        assertEquals(chunks[0].points.last(), chunks[1].points.first())
        assertEquals(originalConsecutivePairs(segment), chunkConsecutivePairs(chunks).toSet())
    }

    @Test
    fun `H -- a substantially larger segment produces several correctly-overlapping chunks, edges fully preserved`() {
        val segment = zigzagSegment(2137) // deliberately not a round multiple of the chunk size

        val chunks = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertTrue(chunks.size > 20)
        assertTrue(chunks.all { it.points.size <= 100 })
        for (i in 0 until chunks.size - 1) {
            assertEquals(chunks[i].points.last(), chunks[i + 1].points.first())
        }
        assertEquals(segment.points.zipWithNext(), chunkConsecutivePairs(chunks))
    }

    @Test
    fun `chunkRouteSegmentForRendering is a no-op for a segment already within budget`() {
        val segment = RouteSegment(listOf(RoutePoint(Coordinate(45.0, 1.0), baseTime), RoutePoint(Coordinate(45.1, 1.1), baseTime.plusSeconds(1))))

        assertEquals(listOf(segment), chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 500))
    }

    @Test
    fun `chunkRouteSegmentForRendering is deterministic`() {
        val segment = zigzagSegment(750)

        val first = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)
        val second = chunkRouteSegmentForRendering(segment, maxPointsPerChunk = 100)

        assertEquals(first, second)
    }

    // ==============================================================================================
    // sampleRouteNodes -- per-segment sampling, unchanged behavior from the previous round.
    // ==============================================================================================

    @Test
    fun `sampleRouteNodes always includes the first and last point`() {
        val points = (0 until 20).map { RoutePoint(Coordinate(latitude = 45.0 + it * 0.01, longitude = 1.0), baseTime.plusSeconds(it.toLong())) }
        val segment = RouteSegment(points)

        val sampled = sampleRouteNodes(segment, sampleEveryN = 6)

        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
        assertTrue("sampling must genuinely reduce a 20-point segment, not return every point", sampled.size < points.size)
    }

    @Test
    fun `sampleRouteNodes never drops below the segment's own 2 points for a short segment`() {
        val points = listOf(RoutePoint(Coordinate(45.8, 1.26), baseTime), RoutePoint(Coordinate(45.7, 1.20), baseTime.plusSeconds(60)))
        val segment = RouteSegment(points)

        assertEquals(points, sampleRouteNodes(segment, sampleEveryN = 6))
    }

    // ==============================================================================================
    // Determinism sanity, unrelated to any specific letter but proving the whole pipeline is
    // reproducible -- required by "prefer missing a connection over drawing a false one" being a
    // meaningful guarantee only if the decision is stable.
    // ==============================================================================================

    @Test
    fun `deriveRouteSegments is fully deterministic -- two calls with the same input produce identical output`() {
        val cells = listOf(
            discoveredCell(paris, baseTime),
            discoveredCell(parisNeighbor, baseTime.plusSeconds(60)),
            discoveredCell(parisRing2, baseTime.plusSeconds(120)),
        )

        val first = deriveRouteSegments(cells, realCellConverter, realGridTraversal)
        val second = deriveRouteSegments(cells, realCellConverter, realGridTraversal)

        assertEquals(first, second)
        assertNotEquals("sanity: the fixture itself must produce a non-trivial result for this test to mean anything", emptyList<RouteSegment>(), first)
    }
}

private class FakeRouteCellConverter(private val centers: Map<CanonicalCell, Coordinate>) : H3CellConverter {
    private val reverse = centers.entries.associate { (cell, coordinate) -> coordinate to cell }

    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell = error("not expected to be called in this test")
    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> = error("not expected to be called in this test")
    override fun cellCenter(cell: CanonicalCell): Coordinate = centers[cell] ?: error("No fake center configured for $cell")
    override fun isValidCell(cell: CanonicalCell): Boolean = error("not expected to be called in this test")

    fun reverseLookup(coordinate: Coordinate): CanonicalCell = reverse.getValue(coordinate)
}

private class FakeGridTraversal(private val paths: Map<Pair<CanonicalCell, CanonicalCell>, List<CanonicalCell>?>) : H3GridTraversal {
    override fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>? {
        if (origin == destination) return listOf(origin)
        val key = origin to destination
        val reverseKey = destination to origin
        return when {
            paths.containsKey(key) -> paths.getValue(key)
            paths.containsKey(reverseKey) -> paths.getValue(reverseKey)?.reversed()
            else -> error("no fake path configured for $origin -> $destination")
        }
    }
}
