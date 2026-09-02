package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClassifyDiscoveredCellsByGeographicAreaComponentsTest {

    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
    private val parisCenter = Coordinate(latitude = 48.8566, longitude = 2.3522) // mainland France
    private val cayenneCell = CanonicalCell(h3Index = "8c3232830031fff", resolution = 12)
    private val cayenneCenter = Coordinate(latitude = 4.9333, longitude = -52.3260) // French Guiana
    private val ajaccioCell = CanonicalCell(h3Index = "8c3e51c6ca6d5ff", resolution = 12)
    // Ajaccio citadel -- deliberately not the textbook "Ajaccio city center" lat/lon, which sits
    // essentially ON the harbor coastline (confirmed by direct inspection against the real
    // geoBoundaries Corsica polygon: the nearest boundary vertex was ~2m away), an inherently
    // ambiguous boundary case rather than a real classification bug -- see
    // PointInPolygonClassifier's own "boundary-point contract". This point is solidly inland.
    private val ajaccioCenter = Coordinate(latitude = 41.9188, longitude = 8.7369) // Corsica
    private val londonCell = CanonicalCell(h3Index = "8c195da4a1281ff", resolution = 12)
    private val londonCenter = Coordinate(latitude = 51.5074, longitude = -0.1276) // outside France entirely

    private val timestamp = Instant.parse("2026-01-01T10:00:00Z")

    private lateinit var cellConverter: FakeComponentClassificationCellConverter
    private lateinit var classify: ClassifyDiscoveredCellsByGeographicAreaComponents
    private lateinit var franceArea: GeographicArea

    @Before
    fun setUp() {
        cellConverter = FakeComponentClassificationCellConverter(
            mapOf(
                parisCell to parisCenter,
                cayenneCell to cayenneCenter,
                ajaccioCell to ajaccioCenter,
                londonCell to londonCenter,
            ),
        )
        classify = ClassifyDiscoveredCellsByGeographicAreaComponents(cellConverter)
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
    fun `returns one status per component, matching area components() exactly`() {
        val statuses = classify(emptyList(), franceArea)

        assertEquals(franceArea.components(), statuses.map { it.component })
    }

    @Test
    fun `no discoveries means every component is not visited`() {
        val statuses = classify(emptyList(), franceArea)

        assertTrue(statuses.all { !it.visited })
    }

    @Test
    fun `a discovery in mainland France marks exactly one component visited -- the one whose own geometry actually contains Paris`() {
        val statuses = classify(listOf(discoveredCell(parisCell, TrustStatus.NON_CERTIFIED)), franceArea)

        val mainlandStatus = statuses.single { it.visited }
        assertTrue(PointInPolygonClassifier.contains(mainlandStatus.component.polygon, parisCenter))
        // The concrete guarantee behind "tapping mainland France must not pull French Guiana in":
        // the visited component's own bounds must not reach into Guiana's longitude range.
        assertTrue(mainlandStatus.component.bounds.southWestLongitude > -50.0)
    }

    @Test
    fun `a discovery in French Guiana marks exactly one component visited -- the one whose own geometry actually contains Cayenne`() {
        val statuses = classify(listOf(discoveredCell(cayenneCell, TrustStatus.NON_CERTIFIED)), franceArea)

        val guianaStatus = statuses.single { it.visited }
        assertTrue(PointInPolygonClassifier.contains(guianaStatus.component.polygon, cayenneCenter))
        assertTrue(guianaStatus.component.bounds.northEastLongitude < 0.0)
    }

    @Test
    fun `a discovery in Corsica marks exactly one component visited -- the one whose own geometry actually contains Ajaccio`() {
        val statuses = classify(listOf(discoveredCell(ajaccioCell, TrustStatus.NON_CERTIFIED)), franceArea)

        val corsicaStatus = statuses.single { it.visited }
        assertTrue(PointInPolygonClassifier.contains(corsicaStatus.component.polygon, ajaccioCenter))
    }

    @Test
    fun `discoveries in all three real components simultaneously distinguish each of them independently`() {
        val statuses = classify(
            listOf(
                discoveredCell(parisCell, TrustStatus.NON_CERTIFIED),
                discoveredCell(ajaccioCell, TrustStatus.NON_CERTIFIED),
                discoveredCell(cayenneCell, TrustStatus.NON_CERTIFIED),
            ),
            franceArea,
        )

        val mainlandStatus = statuses.single { PointInPolygonClassifier.contains(it.component.polygon, parisCenter) }
        val corsicaStatus = statuses.single { PointInPolygonClassifier.contains(it.component.polygon, ajaccioCenter) }
        val guianaStatus = statuses.single { PointInPolygonClassifier.contains(it.component.polygon, cayenneCenter) }

        assertTrue(mainlandStatus.visited)
        assertTrue(corsicaStatus.visited)
        assertTrue(guianaStatus.visited)
        // All three are genuinely distinct components, not the same one matching multiple points.
        assertEquals(3, setOf(mainlandStatus.component, corsicaStatus.component, guianaStatus.component).size)
        // Every other real component (there are only these 3) stays not visited.
        assertTrue(statuses.filter { it.component !in setOf(mainlandStatus.component, corsicaStatus.component, guianaStatus.component) }.all { !it.visited })
    }

    @Test
    fun `a discovery outside every component of the area marks nothing visited`() {
        val statuses = classify(listOf(discoveredCell(londonCell, TrustStatus.NON_CERTIFIED)), franceArea)

        assertTrue(statuses.all { !it.visited })
    }

    @Test
    fun `certified and non-certified presence in different components are tracked independently`() {
        val statuses = classify(
            listOf(
                discoveredCell(parisCell, TrustStatus.CERTIFIED),
                discoveredCell(cayenneCell, TrustStatus.NON_CERTIFIED),
            ),
            franceArea,
        )

        val mainlandStatus = statuses.single { PointInPolygonClassifier.contains(it.component.polygon, parisCenter) }
        val guianaStatus = statuses.single { PointInPolygonClassifier.contains(it.component.polygon, cayenneCenter) }
        assertTrue(mainlandStatus.certifiedPresent)
        assertFalse(mainlandStatus.nonCertifiedPresent)
        assertTrue(guianaStatus.nonCertifiedPresent)
        assertFalse(guianaStatus.certifiedPresent)
    }
}

private class FakeComponentClassificationCellConverter(
    private val centers: Map<CanonicalCell, Coordinate>,
) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell = error("not expected to be called in this test")
    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> = error("not expected to be called in this test")
    override fun cellCenter(cell: CanonicalCell): Coordinate = centers[cell] ?: error("No fake center configured for $cell")
    override fun isValidCell(cell: CanonicalCell): Boolean = error("not expected to be called in this test")
}
