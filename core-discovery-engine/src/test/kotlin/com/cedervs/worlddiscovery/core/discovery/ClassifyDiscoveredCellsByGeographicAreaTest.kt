package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClassifyDiscoveredCellsByGeographicAreaTest {

    private val insideParisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
    private val insideParisCenter = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val outsideLondonCell = CanonicalCell(h3Index = "8c195da4a1281ff", resolution = 12)
    private val outsideLondonCenter = Coordinate(latitude = 51.5074, longitude = -0.1276)

    private val timestamp = Instant.parse("2026-01-01T10:00:00Z")

    private lateinit var cellConverter: FakeClassificationCellConverter
    private lateinit var classify: ClassifyDiscoveredCellsByGeographicArea
    private lateinit var franceArea: GeographicArea

    @Before
    fun setUp() {
        cellConverter = FakeClassificationCellConverter(
            mapOf(insideParisCell to insideParisCenter, outsideLondonCell to outsideLondonCenter),
        )
        classify = ClassifyDiscoveredCellsByGeographicArea(cellConverter)
        franceArea = loadFranceGeographicAreaReference()
    }

    private fun discoveredCell(cell: CanonicalCell, trustStatus: TrustStatus) = DiscoveredCell(
        cell = cell,
        trustStatus = trustStatus,
        firstDiscoveredAt = timestamp,
        lastObservedAt = timestamp,
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    @Test
    fun `no discoveries means the area is not visited`() {
        val status = classify(emptyList(), franceArea)

        assertFalse(status.visited)
        assertFalse(status.certifiedPresent)
        assertFalse(status.nonCertifiedPresent)
    }

    @Test
    fun `a single discovered cell whose center is inside France marks France visited`() {
        val status = classify(listOf(discoveredCell(insideParisCell, TrustStatus.NON_CERTIFIED)), franceArea)

        assertTrue(status.visited)
    }

    @Test
    fun `a single discovered cell whose center is outside France does not mark France visited`() {
        val status = classify(listOf(discoveredCell(outsideLondonCell, TrustStatus.NON_CERTIFIED)), franceArea)

        assertFalse(status.visited)
    }

    @Test
    fun `multiple discovered cells, only one inside France, still marks France visited`() {
        val status = classify(
            listOf(
                discoveredCell(outsideLondonCell, TrustStatus.NON_CERTIFIED),
                discoveredCell(insideParisCell, TrustStatus.NON_CERTIFIED),
            ),
            franceArea,
        )

        assertTrue(status.visited)
    }

    @Test
    fun `a non-certified-only presence sets nonCertifiedPresent but not certifiedPresent`() {
        val status = classify(listOf(discoveredCell(insideParisCell, TrustStatus.NON_CERTIFIED)), franceArea)

        assertTrue(status.nonCertifiedPresent)
        assertFalse(status.certifiedPresent)
    }

    @Test
    fun `a certified-only presence sets certifiedPresent but not nonCertifiedPresent`() {
        val status = classify(listOf(discoveredCell(insideParisCell, TrustStatus.CERTIFIED)), franceArea)

        assertTrue(status.certifiedPresent)
        assertFalse(status.nonCertifiedPresent)
    }

    @Test
    fun `both certified and non-certified presence inside France are both reported, never collapsed to one status`() {
        val status = classify(
            listOf(
                discoveredCell(insideParisCell, TrustStatus.CERTIFIED),
                discoveredCell(outsideLondonCell, TrustStatus.NON_CERTIFIED), // outside -- must not contribute
            ),
            franceArea,
        )

        assertTrue(status.visited)
        assertTrue(status.certifiedPresent)
        assertFalse("the outside-France cell must not contribute non-certified presence", status.nonCertifiedPresent)
    }

    @Test
    fun `the same location represented by both a certified and a non-certified row reports both presences`() {
        // Two DiscoveredCell rows can legitimately coexist for the same cell -- one per trust
        // status (DiscoveredCellRepository.find is keyed by (cell, trustStatus), not by cell alone).
        val status = classify(
            listOf(
                discoveredCell(insideParisCell, TrustStatus.CERTIFIED),
                discoveredCell(insideParisCell, TrustStatus.NON_CERTIFIED),
            ),
            franceArea,
        )

        assertTrue(status.visited)
        assertTrue(status.certifiedPresent)
        assertTrue(status.nonCertifiedPresent)
    }

    @Test
    fun `classification uses the H3 cell center from the converter, never the raw discovered cell alone`() {
        classify(listOf(discoveredCell(insideParisCell, TrustStatus.NON_CERTIFIED)), franceArea)

        assertEquals(listOf(insideParisCell), cellConverter.centerCalls)
    }

    @Test
    fun `GeographicAreaVisitedStatus rejects certifiedPresent true when visited is false`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeographicAreaVisitedStatus(area = franceArea, visited = false, certifiedPresent = true, nonCertifiedPresent = false)
        }
    }
}

private class FakeClassificationCellConverter(private val centers: Map<CanonicalCell, Coordinate>) : H3CellConverter {
    val centerCalls = mutableListOf<CanonicalCell>()

    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        error("not expected to be called in this test")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        error("not expected to be called in this test")

    override fun cellCenter(cell: CanonicalCell): Coordinate {
        centerCalls.add(cell)
        return centers[cell] ?: error("No fake center configured for $cell")
    }

    override fun isValidCell(cell: CanonicalCell): Boolean =
        error("not expected to be called in this test")
}
