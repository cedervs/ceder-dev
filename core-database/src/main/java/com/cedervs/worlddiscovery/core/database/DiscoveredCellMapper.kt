package com.cedervs.worlddiscovery.core.database

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import java.time.Instant

/** Pure mapping between the Room row and the [DiscoveredCell] domain model — kept as plain
 * functions (no Room/Android dependency) so they are testable without a database. */

fun DiscoveredCellEntity.toDomain(): DiscoveredCell = DiscoveredCell(
    cell = CanonicalCell(h3Index = h3Index, resolution = h3Resolution),
    trustStatus = TrustStatus.fromCode(trustStatus),
    firstDiscoveredAt = Instant.ofEpochMilli(firstDiscoveredAtEpochMillis),
    lastObservedAt = Instant.ofEpochMilli(lastObservedAtEpochMillis),
    provenance = Provenance.fromCode(provenance),
    engineVersion = engineVersion,
    h3Resolution = h3Resolution,
)

fun DiscoveredCell.toEntity(): DiscoveredCellEntity = DiscoveredCellEntity(
    h3Index = cell.h3Index,
    trustStatus = trustStatus.code,
    h3Resolution = h3Resolution,
    engineVersion = engineVersion,
    provenance = provenance.code,
    firstDiscoveredAtEpochMillis = firstDiscoveredAt.toEpochMilli(),
    lastObservedAtEpochMillis = lastObservedAt.toEpochMilli(),
)
