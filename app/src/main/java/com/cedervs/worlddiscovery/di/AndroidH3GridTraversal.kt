package com.cedervs.worlddiscovery.di

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.H3GridTraversal
import com.uber.h3core.H3Core
import com.uber.h3core.exceptions.H3Exception

/**
 * [H3GridTraversal] backed by `com.uber:h3-android` — mirrors [AndroidH3CellConverter] (same
 * AAR-native-loading rationale: `H3Core.newSystemInstance()`, not `newInstance()` — see that
 * class's doc comment) and `H3JavaGridTraversal` in `core-discovery-engine` (same contract-
 * validation and `H3Exception`-narrowing logic, used there for JVM-only unit tests against the
 * generic `com.uber:h3` artifact). Same H3 API, same behavior — only the packaged native library
 * differs.
 */
class AndroidH3GridTraversal(private val h3Core: H3Core = H3Core.newSystemInstance()) : H3GridTraversal {

    override fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>? {
        validateCell(origin)
        validateCell(destination)

        val originRealResolution = h3Core.getResolution(origin.h3Index)
        val destinationRealResolution = h3Core.getResolution(destination.h3Index)
        require(origin.resolution == originRealResolution) {
            "origin.resolution (${origin.resolution}) does not match the resolution actually " +
                "encoded in origin.h3Index (${origin.h3Index} -> $originRealResolution)"
        }
        require(destination.resolution == destinationRealResolution) {
            "destination.resolution (${destination.resolution}) does not match the resolution " +
                "actually encoded in destination.h3Index (${destination.h3Index} -> $destinationRealResolution)"
        }
        require(originRealResolution == destinationRealResolution) {
            "origin and destination have different H3 resolutions " +
                "($originRealResolution vs $destinationRealResolution); gridPathCells requires the same resolution"
        }

        return try {
            h3Core.gridPathCells(origin.h3Index, destination.h3Index)
                .map { h3Index -> CanonicalCell(h3Index = h3Index, resolution = origin.resolution) }
        } catch (e: H3Exception) {
            null
        }
    }

    private fun validateCell(cell: CanonicalCell) {
        require(h3Core.isValidCell(cell.h3Index)) {
            "CanonicalCell.h3Index (${cell.h3Index}) is not a valid H3 cell address"
        }
    }
}
