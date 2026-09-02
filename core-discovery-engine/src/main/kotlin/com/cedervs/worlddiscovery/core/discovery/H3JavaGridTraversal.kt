package com.cedervs.worlddiscovery.core.discovery

import com.uber.h3core.H3Core
import com.uber.h3core.exceptions.H3Exception

/**
 * [H3GridTraversal] backed by Uber's official maintained H3 Java bindings (`com.uber:h3` — see
 * build.gradle.kts) — mirrors [H3JavaCellConverter]: desktop natives, used for this module's own
 * JVM unit tests. The Android variant (`AndroidH3GridTraversal`, same logic, `com.uber:h3-android`
 * natives) is what production actually wires — see `AppContainer`.
 */
class H3JavaGridTraversal(private val h3Core: H3Core = H3Core.newInstance()) : H3GridTraversal {

    /**
     * Contract violations — an invalid `h3Index`, mismatched real H3 resolutions, or a
     * [CanonicalCell.resolution] that doesn't match what's actually encoded in its `h3Index` — are
     * distinct from an expected traversal failure and are never turned into `null`; they propagate
     * as [IllegalArgumentException] (see [validateCell] and the `require` calls below).
     * [NumberFormatException] (a subtype of [IllegalArgumentException]) surfaces the same way when
     * `h3Index` isn't even hex-parseable — verified directly against the real library:
     * `H3Core.isValidCell`/`getResolution`/`gridPathCells` all throw `NumberFormatException` for a
     * non-hex string such as `"not-a-real-h3-index"`, rather than returning a clean `false`/result.
     *
     * Only once both cells are individually valid and mutually comparable (same real resolution,
     * matching their declared [CanonicalCell.resolution]) is `gridPathCells` itself attempted.
     * `com.uber.h3core.exceptions.H3Exception` from *that* call — a pentagon distortion cell
     * encountered on the path, or an excessive/problematic grid distance (verified directly: a
     * real pentagon-adjacent pair at grid-ring distance 2 throws "Pentagon distortion was
     * encountered"; a genuinely intercontinental pair, e.g. Paris/New York at resolution 12,
     * throws "The operation failed but a more specific error is not available") — is the only
     * thing narrowly caught and turned into `null`. Any other, genuinely unexpected exception is
     * not this method's job to hide and propagates.
     */
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
