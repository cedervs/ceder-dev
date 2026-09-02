package com.cedervs.worlddiscovery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiscoveredCellDao {

    @Query("SELECT * FROM discovered_cells WHERE h3Index = :h3Index AND trustStatus = :trustStatus")
    suspend fun find(h3Index: String, trustStatus: String): DiscoveredCellEntity?

    /**
     * Upsert by primary key `(h3Index, trustStatus)`. Repeated writes for the same key are
     * idempotent — [OnConflictStrategy.REPLACE] overwrites the existing row with the caller's
     * already fully-merged entity (see
     * [com.cedervs.worlddiscovery.core.discovery.DiscoveredCellMerger]); this DAO does not
     * merge on its own.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DiscoveredCellEntity)

    @Query("SELECT * FROM discovered_cells")
    suspend fun getAll(): List<DiscoveredCellEntity>

    /** Reactive read, re-emitting on every change to `discovered_cells` (Room's own Flow
     * invalidation tracking — no manual refresh trigger needed). Used by
     * [com.cedervs.worlddiscovery.core.discovery.ObserveDiscoveredCellGeometries] via
     * [RoomDiscoveredCellRepository]; never called directly outside this module. */
    @Query("SELECT * FROM discovered_cells")
    fun observeAll(): Flow<List<DiscoveredCellEntity>>

    @Query("SELECT COUNT(*) FROM discovered_cells")
    suspend fun count(): Int
}
