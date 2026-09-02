package com.cedervs.worlddiscovery.core.database

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellRepository
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed implementation of the discovery engine's storage port. Offline-first: every
 * operation is a local SQLite read/write, never a network call. */
class RoomDiscoveredCellRepository(private val dao: DiscoveredCellDao) : DiscoveredCellRepository {

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        dao.find(cell.h3Index, trustStatus.code)?.toDomain()

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        dao.upsert(discoveredCell.toEntity())
    }

    override fun observeAll(): Flow<List<DiscoveredCell>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }
}
