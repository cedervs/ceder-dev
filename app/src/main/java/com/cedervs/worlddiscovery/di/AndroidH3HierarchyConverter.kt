package com.cedervs.worlddiscovery.di

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.H3HierarchyConverter
import com.uber.h3core.H3Core

/**
 * [H3HierarchyConverter] backed by `com.uber:h3-android` — mirrors [AndroidH3CellConverter]/
 * [AndroidH3GridTraversal] (same AAR-native-loading rationale: `H3Core.newSystemInstance()`, not
 * `newInstance()`) and `H3JavaHierarchyConverter` in `core-discovery-engine` (identical contract,
 * used there for JVM-only unit tests against the generic `com.uber:h3` artifact — including the
 * same [validateCell] enforcement; see that class's doc comment for why parseable hexadecimal
 * alone is not sufficient proof of a genuine H3 cell, confirmed directly against the real
 * library). Same H3 API, same behavior — only the packaged native library differs.
 */
class AndroidH3HierarchyConverter(private val h3Core: H3Core = H3Core.newSystemInstance()) : H3HierarchyConverter {

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
