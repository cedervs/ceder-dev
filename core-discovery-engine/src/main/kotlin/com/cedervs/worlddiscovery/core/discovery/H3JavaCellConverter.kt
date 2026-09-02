package com.cedervs.worlddiscovery.core.discovery

import com.uber.h3core.H3Core

/**
 * [H3CellConverter] backed by Uber's official maintained H3 Java bindings
 * (`com.uber:h3` — see build.gradle.kts). We deliberately do not implement H3 ourselves.
 *
 * [H3Core.newInstance] loads/unpacks the native H3 library; it is safe and cheap enough to
 * hold as a long-lived singleton per converter instance, not per call.
 */
class H3JavaCellConverter(
    private val resolution: Int = DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION,
    private val h3Core: H3Core = H3Core.newInstance(),
) : H3CellConverter {

    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell {
        val h3Index = h3Core.latLngToCellAddress(coordinate.latitude, coordinate.longitude, resolution)
        return CanonicalCell(h3Index = h3Index, resolution = resolution)
    }

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        h3Core.cellToBoundary(cell.h3Index).map { latLng -> Coordinate(latLng.lat, latLng.lng) }

    override fun cellCenter(cell: CanonicalCell): Coordinate {
        val latLng = h3Core.cellToLatLng(cell.h3Index)
        return Coordinate(latLng.lat, latLng.lng)
    }

    /**
     * `H3Core.isValidCell` itself throws `NumberFormatException` when `h3Index` isn't even
     * parseable as hexadecimal (verified directly against the real library: `isValidCell("")`
     * and `isValidCell("not-a-real-h3-index")` both throw rather than returning `false`) — the
     * one narrow, confirmed case caught here. Any other exception is genuinely unexpected and is
     * not this method's job to hide; it propagates.
     */
    override fun isValidCell(cell: CanonicalCell): Boolean =
        try {
            h3Core.isValidCell(cell.h3Index)
        } catch (e: NumberFormatException) {
            false
        }
}
