package com.cedervs.worlddiscovery.core.discovery

import com.uber.h3core.H3Core
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference cells/paths below were captured by running the real `com.uber:h3:4.5.0` library
 * directly outside Gradle (H3Core.gridPathCells/gridDisk/gridDistance/getPentagonAddresses,
 * plus a systematic search over a pentagon's neighborhood for a genuinely failing pair), not
 * invented by hand — same discipline as `H3JavaCellConverterTest`. This is what established,
 * deterministically, which real cell pairs succeed vs. genuinely throw
 * `com.uber.h3core.exceptions.H3Exception` in the real library.
 */
class H3JavaGridTraversalTest {

    private val traversal = H3JavaGridTraversal()

    private val paris = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

    @Test
    fun `origin equal to destination returns a single-element path containing that cell`() {
        val path = traversal.pathBetween(paris, paris)

        assertEquals(listOf(paris), path)
    }

    @Test
    fun `adjacent cells return origin then destination, both included`() {
        // A real grid-neighbor of the Paris cell, captured via H3Core.gridDisk(paris, 1).
        val neighbor = CanonicalCell(h3Index = "8c1fb4662555dff", resolution = 12)

        val path = traversal.pathBetween(paris, neighbor)

        assertEquals(listOf(paris, neighbor), path)
    }

    @Test
    fun `every cell in a longer path is a genuine grid-neighbor of the previous one`() {
        // Real ~3km-apart pair in Paris, captured via H3Core.gridDistance/gridPathCells (grid
        // distance 116, path size 117) — large enough to genuinely exercise the "walk the grid"
        // logic, not just a 1-2 cell edge case.
        val a = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
        val b = CanonicalCell(h3Index = "8c1fb466079ebff", resolution = 12)
        val h3Core = H3Core.newInstance()

        val path = requireNotNull(traversal.pathBetween(a, b))

        assertEquals(117, path.size)
        assertEquals(a, path.first())
        assertEquals(b, path.last())
        for (i in 0 until path.size - 1) {
            val distance = h3Core.gridDistance(path[i].h3Index, path[i + 1].h3Index)
            assertEquals("cells at index $i and ${i + 1} are not grid-neighbors", 1, distance)
        }
    }

    @Test
    fun `a path crossing the antimeridian is computed correctly, with no special-casing needed`() {
        // Real cells straddling +180/-180 at the equator, ~110m apart — captured via
        // H3Core.latLngToCellAddress(0.0, 179.9995, 12) / (0.0, -179.9995, 12). H3's grid-index
        // space is antimeridian-agnostic by construction (unlike geometric/lat-lng rendering,
        // which does need explicit unwrapping — see feature-map's AntimeridianUnwrappingTest).
        val east = CanonicalCell(h3Index = "8c7eb57221a15ff", resolution = 12)
        val west = CanonicalCell(h3Index = "8c7eb57221b55ff", resolution = 12)

        val path = requireNotNull(traversal.pathBetween(east, west))

        assertEquals(9, path.size)
        assertEquals(east, path.first())
        assertEquals(west, path.last())
    }

    @Test
    fun `a pentagon distortion on the path returns null rather than throwing`() {
        // Two real, individually valid cells from H3Core.gridDisk(pentagon, 3) (the *safe*
        // enumeration — does not itself throw) around a genuine H3 pentagon (captured via
        // H3Core.getPentagonAddresses(12)) — this exact pair was empirically confirmed to make
        // H3Core.gridPathCells throw a real H3Exception, searched systematically rather than
        // guessed.
        val ringCellA = CanonicalCell(h3Index = "8c08000000035ff", resolution = 12)
        val ringCellB = CanonicalCell(h3Index = "8c08000000041ff", resolution = 12)

        val path = traversal.pathBetween(ringCellA, ringCellB)

        assertNull(path)
    }

    @Test
    fun `a genuinely far-apart pair returns null rather than throwing`() {
        // Paris and New York at resolution 12 — real intercontinental distance, confirmed to
        // throw H3Exception("The operation failed but a more specific error is not available")
        // in the real library. Deterministic: this pair reliably fails every time, not a
        // near-threshold case whose outcome could vary.
        val nyc = CanonicalCell(h3Index = "8c2a107289061ff", resolution = 12)

        val path = traversal.pathBetween(paris, nyc)

        assertNull(path)
    }

    @Test
    fun `a moderate real-world distance still succeeds`() {
        // Sanity check that the "far apart" case above is genuinely about extreme distance, not
        // about pathBetween being broken for any multi-km gap.
        val threeKmAway = CanonicalCell(h3Index = "8c1fb466079ebff", resolution = 12)

        val path = traversal.pathBetween(paris, threeKmAway)

        assertTrue(path != null && path.isNotEmpty())
    }

    @Test
    fun `a non-hex h3Index is a contract violation, never silently null`() {
        // Verified directly against the real library: H3Core.isValidCell/getResolution/
        // gridPathCells all throw NumberFormatException (a subtype of IllegalArgumentException)
        // for a non-hex-parseable string, rather than returning a clean false/result.
        val invalid = CanonicalCell(h3Index = "not-a-real-h3-index", resolution = 12)

        assertThrows(NumberFormatException::class.java) {
            traversal.pathBetween(invalid, paris)
        }
        assertThrows(NumberFormatException::class.java) {
            traversal.pathBetween(paris, invalid)
        }
    }

    @Test
    fun `a hex-parseable but semantically invalid h3Index is a contract violation, never silently null`() {
        // "ffffffffffffffff" is valid hexadecimal but not a real H3 cell — H3Core.isValidCell
        // returns a clean `false` for it (confirmed directly against the real library), which is
        // exactly the case this class's own explicit validation must catch itself.
        val invalid = CanonicalCell(h3Index = "ffffffffffffffff", resolution = 12)

        assertThrows(IllegalArgumentException::class.java) {
            traversal.pathBetween(invalid, paris)
        }
        assertThrows(IllegalArgumentException::class.java) {
            traversal.pathBetween(paris, invalid)
        }
    }

    @Test
    fun `two valid cells at genuinely different H3 resolutions are a contract violation, never silently null`() {
        // Real resolution-9 Paris cell, captured via H3Core.latLngToCellAddress(48.8566, 2.3522, 9)
        // — individually valid and self-consistent (its declared resolution matches what's
        // actually encoded), but a real resolution mismatch against the resolution-12 `paris`.
        val parisResolution9 = CanonicalCell(h3Index = "891fb466257ffff", resolution = 9)

        assertThrows(IllegalArgumentException::class.java) {
            traversal.pathBetween(paris, parisResolution9)
        }
    }

    @Test
    fun `a declared resolution inconsistent with the real origin index is a contract violation`() {
        // The real h3Index is a genuine resolution-12 cell; claiming resolution 9 for it is a
        // caller bug (CanonicalCell doesn't itself validate this — see CanonicalCell.kt).
        val misdeclaredOrigin = CanonicalCell(h3Index = paris.h3Index, resolution = 9)

        assertThrows(IllegalArgumentException::class.java) {
            traversal.pathBetween(misdeclaredOrigin, paris)
        }
    }

    @Test
    fun `a declared resolution inconsistent with the real destination index is a contract violation`() {
        val misdeclaredDestination = CanonicalCell(h3Index = paris.h3Index, resolution = 9)

        assertThrows(IllegalArgumentException::class.java) {
            traversal.pathBetween(paris, misdeclaredDestination)
        }
    }
}
