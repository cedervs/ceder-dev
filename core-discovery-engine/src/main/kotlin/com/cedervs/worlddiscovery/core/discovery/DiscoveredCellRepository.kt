package com.cedervs.worlddiscovery.core.discovery

import kotlinx.coroutines.flow.Flow

/**
 * Local persistence port for [DiscoveredCell]. The discovery engine depends only on this
 * interface — the actual storage (Room, or an in-memory fake for tests) lives outside this
 * module. Offline-first by construction: nothing here implies or requires network access.
 */
interface DiscoveredCellRepository {
    suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell?

    suspend fun upsert(discoveredCell: DiscoveredCell)

    /**
     * Reactive read of every locally stored discovered cell — both trust statuses together;
     * callers filter/style by [DiscoveredCell.trustStatus] as needed (see
     * [ObserveDiscoveredCellGeometries]). Re-emits automatically whenever local storage changes.
     * Never triggers a network call, and never mutates anything — offline-first and read-only by
     * construction, like the rest of this port.
     */
    fun observeAll(): Flow<List<DiscoveredCell>>
}
