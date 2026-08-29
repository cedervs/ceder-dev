package com.cedervs.worlddiscovery.core.database

import androidx.room.Entity

/**
 * Room row for one discovered canonical H3 cell at one [trustStatus]. `(h3Index, trustStatus)`
 * is the primary key — Normal and Certified are separate rows for the same cell, never merged
 * (see [com.cedervs.worlddiscovery.core.discovery.DiscoveredCellMerger] and
 * docs/certified-mode.md §1). Never stores a raw latitude/longitude — only the already-derived
 * canonical cell (docs/discovery-engine.md §16/§23).
 */
@Entity(tableName = "discovered_cells", primaryKeys = ["h3Index", "trustStatus"])
data class DiscoveredCellEntity(
    val h3Index: String,
    val trustStatus: String,
    val h3Resolution: Int,
    val engineVersion: Int,
    val provenance: String,
    val firstDiscoveredAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
)
