package com.cedervs.worlddiscovery.core.discovery

/** Converts a raw coordinate to its canonical H3 cell. Kept as an interface so the H3 library
 * choice stays swappable (docs/architecture.md principle 10) and so callers can be tested
 * against a fake without touching the real H3 library. */
interface H3CellConverter {
    fun toCanonicalCell(coordinate: Coordinate): CanonicalCell
}
