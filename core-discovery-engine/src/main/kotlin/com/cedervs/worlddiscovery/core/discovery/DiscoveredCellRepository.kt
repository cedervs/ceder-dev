package com.cedervs.worlddiscovery.core.discovery

/**
 * Local persistence port for [DiscoveredCell]. The discovery engine depends only on this
 * interface — the actual storage (Room, or an in-memory fake for tests) lives outside this
 * module. Offline-first by construction: nothing here implies or requires network access.
 */
interface DiscoveredCellRepository {
    suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell?

    suspend fun upsert(discoveredCell: DiscoveredCell)
}
