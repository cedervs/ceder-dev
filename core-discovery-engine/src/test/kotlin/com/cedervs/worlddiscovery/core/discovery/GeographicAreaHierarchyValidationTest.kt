package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeographicAreaHierarchyValidationTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private val defaultRing = ring(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)

    private fun testArea(id: String, type: GeographicAreaType, parentId: String? = null): GeographicArea {
        val geometry = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(defaultRing))))
        return GeographicArea(
            id = id,
            type = type,
            displayName = id,
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.WORLD_DISCOVERY_ZONE,
            parentId = parentId,
        )
    }

    private val country = testArea("country:X", GeographicAreaType.COUNTRY, parentId = null)
    private val region = testArea("admin1:A", GeographicAreaType.ADMIN_1, parentId = "country:X")
    private val department = testArea("admin2:A1", GeographicAreaType.ADMIN_2, parentId = "admin1:A")

    @Test
    fun `a well-formed Country-Region-Department set passes validation without throwing`() {
        validateGeographicAreaHierarchy(listOf(country, region, department))
    }

    @Test
    fun `duplicate area ids fail clearly`() {
        val duplicate = testArea("admin1:A", GeographicAreaType.ADMIN_1, parentId = "country:X") // same id as `region`

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateGeographicAreaHierarchy(listOf(country, region, duplicate))
        }
        assertTrue(exception.message!!.contains("Duplicate"))
        assertTrue(exception.message!!.contains("admin1:A"))
    }

    @Test
    fun `a COUNTRY area with a non-null parentId fails clearly`() {
        val malformedCountry = testArea("country:BAD", GeographicAreaType.COUNTRY, parentId = "country:X")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateGeographicAreaHierarchy(listOf(country, malformedCountry))
        }
        assertTrue(exception.message!!.contains("country:BAD"))
        assertTrue(exception.message!!.contains("must not have a parentId"))
    }

    @Test
    fun `an ADMIN_1 area with no parentId at all fails clearly`() {
        val orphanRegion = testArea("admin1:ORPHAN", GeographicAreaType.ADMIN_1, parentId = null)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateGeographicAreaHierarchy(listOf(country, orphanRegion))
        }
        assertTrue(exception.message!!.contains("admin1:ORPHAN"))
        assertTrue(exception.message!!.contains("must have a parentId"))
    }

    @Test
    fun `an ADMIN_1 area whose parentId does not resolve to anything in the loaded set fails clearly`() {
        val danglingRegion = testArea("admin1:DANGLING", GeographicAreaType.ADMIN_1, parentId = "country:NONEXISTENT")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateGeographicAreaHierarchy(listOf(country, danglingRegion))
        }
        assertTrue(exception.message!!.contains("admin1:DANGLING"))
        assertTrue(exception.message!!.contains("does not resolve"))
    }

    @Test
    fun `an ADMIN_1 area whose parentId resolves to something that is NOT a COUNTRY fails clearly`() {
        val regionAsParent = testArea("admin1:WRONG-PARENT-TYPE", GeographicAreaType.ADMIN_1, parentId = "admin1:A")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateGeographicAreaHierarchy(listOf(country, region, regionAsParent))
        }
        assertTrue(exception.message!!.contains("admin1:WRONG-PARENT-TYPE"))
        assertTrue(exception.message!!.contains("not a COUNTRY"))
    }

    @Test
    fun `an ADMIN_2 area whose parentId resolves to a COUNTRY instead of an ADMIN_1 fails clearly`() {
        val departmentUnderCountry = testArea("admin2:WRONG-PARENT-TYPE", GeographicAreaType.ADMIN_2, parentId = "country:X")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateGeographicAreaHierarchy(listOf(country, region, departmentUnderCountry))
        }
        assertTrue(exception.message!!.contains("admin2:WRONG-PARENT-TYPE"))
        assertTrue(exception.message!!.contains("not an ADMIN_1"))
    }

    @Test
    fun `an ADMIN_2 area with no parentId at all fails clearly`() {
        val orphanDepartment = testArea("admin2:ORPHAN", GeographicAreaType.ADMIN_2, parentId = null)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateGeographicAreaHierarchy(listOf(country, region, orphanDepartment))
        }
        assertTrue(exception.message!!.contains("admin2:ORPHAN"))
    }

    @Test
    fun `a LOCALITY or ZONE area is not constrained by ADMIN_1-ADMIN_2 parent-type rules`() {
        val zoneWithNoParent = testArea("zone:FREE", GeographicAreaType.ZONE, parentId = null)
        val localityUnderAnything = testArea("locality:FREE", GeographicAreaType.LOCALITY, parentId = "admin2:A1")

        // Must not throw -- LOCALITY/ZONE placement in the hierarchy is deliberately undecided
        // (see this function's own doc comment); only a non-null parentId must still resolve.
        validateGeographicAreaHierarchy(listOf(country, region, department, zoneWithNoParent, localityUnderAnything))
    }

    @Test
    fun `a LOCALITY area with a dangling non-null parentId still fails -- unconstrained type, not unconstrained existence`() {
        val danglingLocality = testArea("locality:DANGLING", GeographicAreaType.LOCALITY, parentId = "admin2:NONEXISTENT")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateGeographicAreaHierarchy(listOf(country, region, department, danglingLocality))
        }
        assertTrue(exception.message!!.contains("locality:DANGLING"))
    }

    @Test
    fun `the real bundled France Country plus Region plus Department set is backward-compatible and passes validation`() {
        val franceArea = loadFranceGeographicAreaReference()
        val administrativeAreas = loadFranceAdministrativeAreas(franceArea)

        // loadFranceAdministrativeAreas already calls validateGeographicAreaHierarchy internally
        // (it would have thrown in setUp-equivalent loading above if this failed) -- this test
        // additionally proves the SAME real data still validates when called directly, independent
        // of that internal call, so a future refactor of the loader can't silently stop validating.
        validateGeographicAreaHierarchy(listOf(franceArea) + administrativeAreas.regions + administrativeAreas.departments)
    }
}
