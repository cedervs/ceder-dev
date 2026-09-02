package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference cells/counts below were captured by running the real `com.uber:h3:4.5.0` library
 * directly outside Gradle (`H3Core.getPentagonAddresses`/`cellToChildrenSize`/
 * `cellToParentAddress`/`isValidCell`), not invented by hand — same discipline as
 * `H3JavaGridTraversalTest`/`H3JavaCellConverterTest`. This established, deterministically, both
 * that H3's real descendant count is genuinely pentagon-aware and differs from the naive
 * `7^(childResolution - parentResolution)` formula, and — the specific gap this file's contract
 * tests close — that a syntactically parseable hexadecimal string is not sufficient proof of a
 * genuine H3 cell: `"ffffffffffffffff"` parses cleanly and even `H3Core.getResolution` returns a
 * plausible `15` for it, yet `H3Core.isValidCell` correctly reports `false`, and — the actual bug
 * this file's `validateCell` was added to close — `H3Core.cellToParentAddress` does **not** throw
 * for it; it silently returns a fabricated `"ff6fffffffffffff"` result instead.
 */
class H3JavaHierarchyConverterTest {

    private val hierarchyConverter = H3JavaHierarchyConverter()

    // A real resolution-0 pentagon base cell, captured via H3Core.getPentagonAddresses(0).
    private val pentagonBaseCell = CanonicalCell(h3Index = "8009fffffffffff", resolution = 0)

    // A real resolution-0 ordinary (non-pentagon) base cell, captured via
    // H3Core.getRes0CellAddresses() filtered against getPentagonAddresses(0).
    private val hexagonBaseCell = CanonicalCell(h3Index = "8001fffffffffff", resolution = 0)

    private val paris = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

    // Parseable hexadecimal, confirmed NOT a valid H3 cell (H3Core.isValidCell == false) — see the
    // class doc comment for exactly what real H3 entry points do and don't catch this on their own.
    private val hexParseableButInvalidH3Index = "ffffffffffffffff"

    @Test
    fun `parentCell returns the real H3 ancestor at the requested resolution`() {
        // Captured via H3Core.cellToParentAddress(paris.h3Index, 6).
        val expectedParentAtRes6 = CanonicalCell(h3Index = "861fb4667ffffff", resolution = 6)

        val parent = hierarchyConverter.parentCell(paris, parentResolution = 6)

        assertEquals(expectedParentAtRes6, parent)
    }

    @Test
    fun `parentCell at the cell's own resolution returns the cell itself`() {
        val parent = hierarchyConverter.parentCell(paris, parentResolution = 12)

        assertEquals(paris, parent)
    }

    @Test
    fun `parentCell throws for a parentResolution above the cell's own real resolution`() {
        // Now caught by this class's own explicit parentResolution/cell.resolution check, before
        // H3Core.cellToParentAddress is even called (which would separately also throw
        // IllegalArgumentException for this exact case — "res (13) must be between 0 and 12,
        // inclusive" — but this class no longer relies on that).
        assertThrows(IllegalArgumentException::class.java) {
            hierarchyConverter.parentCell(paris, parentResolution = 13)
        }
    }

    @Test
    fun `parentCell throws for an unparseable h3Index rather than silently failing`() {
        val invalid = CanonicalCell(h3Index = "not-a-real-h3-index", resolution = 12)

        assertThrows(NumberFormatException::class.java) {
            hierarchyConverter.parentCell(invalid, parentResolution = 6)
        }
    }

    @Test
    fun `parentCell throws for a parseable-hex index that is not a genuine H3 cell, rather than silently returning garbage`() {
        val invalid = CanonicalCell(h3Index = hexParseableButInvalidH3Index, resolution = 15)

        // Without validateCell's explicit isValidCell check, this would previously have silently
        // succeeded and returned a fabricated parent — see the class doc comment.
        assertThrows(IllegalArgumentException::class.java) {
            hierarchyConverter.parentCell(invalid, parentResolution = 6)
        }
    }

    @Test
    fun `parentCell throws when the declared resolution disagrees with the resolution actually encoded in h3Index`() {
        // paris.h3Index really is resolution 12 -- declaring it as 6 is a contract violation, not
        // something to silently trust or "helpfully" correct.
        val mismatched = paris.copy(resolution = 6)

        assertThrows(IllegalArgumentException::class.java) {
            hierarchyConverter.parentCell(mismatched, parentResolution = 4)
        }
    }

    @Test
    fun `descendantCount for an ordinary hexagon parent matches H3's own exact count, not the naive power-of-7 formula`() {
        // Captured via H3Core.cellToChildrenSize(hexagonBaseCell.h3Index, 2) -- exactly 7^2, since
        // an ordinary hexagon base cell has the full aperture-7 fanout.
        val count = hierarchyConverter.descendantCount(hexagonBaseCell, childResolution = 2)

        assertEquals(49L, count)
        assertEquals(49L, Math.pow(7.0, 2.0).toLong())
    }

    @Test
    fun `descendantCount for a real pentagonal parent is genuinely smaller than the naive power-of-7 formula`() {
        // Captured via H3Core.cellToChildrenSize(pentagonBaseCell.h3Index, childRes) for several
        // resolutions -- a pentagon's descendant count is always less than 7^n, confirming this
        // must come from H3 itself and cannot be a formula reimplemented here.
        val naiveFormulaAtRes2 = Math.pow(7.0, 2.0).toLong()
        val countAtRes2 = hierarchyConverter.descendantCount(pentagonBaseCell, childResolution = 2)
        assertEquals(41L, countAtRes2)
        assertTrue("a pentagon must have fewer descendants than the naive formula", countAtRes2 < naiveFormulaAtRes2)

        // The canonical resolution this app actually uses (12) at res0.
        val naiveFormulaAtRes12 = Math.pow(7.0, 12.0).toLong()
        val countAtRes12 = hierarchyConverter.descendantCount(pentagonBaseCell, childResolution = 12)
        assertEquals(11_534_406_001L, countAtRes12)
        assertTrue(countAtRes12 < naiveFormulaAtRes12)
    }

    @Test
    fun `descendantCount for an ordinary hexagon at the canonical resolution 12 matches H3's own exact count`() {
        // Captured via H3Core.cellToChildrenSize(hexagonBaseCell.h3Index, 12).
        val count = hierarchyConverter.descendantCount(hexagonBaseCell, childResolution = DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION)

        assertEquals(13_841_287_201L, count)
    }

    @Test
    fun `descendantCount at the parent's own resolution is exactly one`() {
        val count = hierarchyConverter.descendantCount(paris, childResolution = 12)

        assertEquals(1L, count)
    }

    @Test
    fun `descendantCount throws for a childResolution coarser than the parent's own real resolution`() {
        // Now caught by this class's own explicit childResolution/parent.resolution check, before
        // H3Core.cellToChildrenSize is even called (which would separately also throw H3Exception
        // for this exact case — "Resolution argument was outside of acceptable range" — but this
        // class no longer relies on that).
        assertThrows(IllegalArgumentException::class.java) {
            hierarchyConverter.descendantCount(paris, childResolution = 6)
        }
    }

    @Test
    fun `descendantCount throws for an unparseable h3Index rather than silently failing`() {
        val invalid = CanonicalCell(h3Index = "not-a-real-h3-index", resolution = 0)

        assertThrows(NumberFormatException::class.java) {
            hierarchyConverter.descendantCount(invalid, childResolution = 6)
        }
    }

    @Test
    fun `descendantCount throws for a parseable-hex parent that is not a genuine H3 cell`() {
        val invalid = CanonicalCell(h3Index = hexParseableButInvalidH3Index, resolution = 15)

        assertThrows(IllegalArgumentException::class.java) {
            hierarchyConverter.descendantCount(invalid, childResolution = 15)
        }
    }

    @Test
    fun `descendantCount throws when the parent's declared resolution disagrees with the resolution actually encoded in h3Index`() {
        val mismatched = paris.copy(resolution = 6)

        assertThrows(IllegalArgumentException::class.java) {
            hierarchyConverter.descendantCount(mismatched, childResolution = 12)
        }
    }
}
