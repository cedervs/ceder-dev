package com.cedervs.worlddiscovery.core.discovery

import com.uber.h3core.H3Core

/**
 * [H3HierarchyConverter] backed by Uber's official maintained H3 Java bindings (`com.uber:h3` —
 * see build.gradle.kts) — mirrors [H3JavaCellConverter]/[H3JavaGridTraversal]: desktop natives,
 * used for this module's own JVM unit tests. The Android variant (`AndroidH3HierarchyConverter`,
 * same logic, `com.uber:h3-android` natives) is what production actually wires — see
 * `AppContainer`. Both implementations enforce identical contracts, verified directly below.
 *
 * **Parseable hexadecimal is not sufficient proof of a genuine H3 cell** — confirmed directly
 * against the real library: `"ffffffffffffffff"` is a syntactically parseable 64-bit hex string,
 * `H3Core.isValidCell` correctly reports it as `false`, yet `H3Core.cellToParentAddress` does
 * **not** throw for it — it silently returns `"ff6fffffffffffff"`, a fabricated result for an
 * index that was never a real cell. [validateCell] exists specifically to close this gap: every
 * public method here calls it first, so a malformed-but-parseable `h3Index` is rejected before any
 * hierarchy call can silently produce garbage, rather than trusting H3's own per-call validation
 * (which this probe demonstrates is inconsistent across different H3 entry points).
 *
 * [validateCell] enforces, in order: [H3Core.isValidCell] is genuinely true (an unparseable string
 * throws [NumberFormatException] here, a subtype of [IllegalArgumentException], propagated
 * unchanged); and [CanonicalCell.resolution] equals what's actually encoded in `h3Index` via
 * [H3Core.getResolution] — a cell whose declared resolution disagrees with its own index is a
 * contract violation, never silently trusted. Neither check is ever caught or narrowed — both
 * throw [IllegalArgumentException] (a plain `require` failure, or H3's own) that propagates to the
 * caller unchanged, matching [H3JavaGridTraversal]'s established "contract violations are never
 * silently absorbed" convention.
 */
class H3JavaHierarchyConverter(private val h3Core: H3Core = H3Core.newInstance()) : H3HierarchyConverter {

    override fun parentCell(cell: CanonicalCell, parentResolution: Int): CanonicalCell {
        validateCell(cell)
        require(parentResolution in 0..cell.resolution) {
            "parentResolution ($parentResolution) must be between 0 and cell.resolution " +
                "(${cell.resolution}), inclusive"
        }

        val parentH3Index = h3Core.cellToParentAddress(cell.h3Index, parentResolution)
        return CanonicalCell(h3Index = parentH3Index, resolution = parentResolution)
    }

    override fun descendantCount(parent: CanonicalCell, childResolution: Int): Long {
        validateCell(parent)
        require(childResolution in parent.resolution..15) {
            "childResolution ($childResolution) must be between parent.resolution " +
                "(${parent.resolution}) and 15, inclusive"
        }

        return h3Core.cellToChildrenSize(parent.h3Index, childResolution)
    }

    /** See the class doc comment — the single enforcement point every public method here calls
     * before touching [h3Core]'s hierarchy operations. */
    private fun validateCell(cell: CanonicalCell) {
        require(h3Core.isValidCell(cell.h3Index)) {
            "CanonicalCell.h3Index (${cell.h3Index}) is not a valid H3 cell address"
        }
        val realResolution = h3Core.getResolution(cell.h3Index)
        require(cell.resolution == realResolution) {
            "CanonicalCell.resolution (${cell.resolution}) does not match the resolution " +
                "actually encoded in h3Index (${cell.h3Index} -> $realResolution)"
        }
    }
}
