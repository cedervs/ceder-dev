package com.cedervs.worlddiscovery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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

    @Query("SELECT COUNT(*) FROM discovered_cells")
    suspend fun count(): Int
}
