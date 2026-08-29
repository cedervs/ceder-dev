package com.cedervs.worlddiscovery.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local World Discovery database. `version` is Room's own schema version — bump it and add an
 * explicit [androidx.room.migration.Migration] for any future schema change; never a
 * destructive migration as the normal production strategy (docs/architecture.md §12,
 * discovery-engine.md §16). This is intentionally the only entity for this phase — no raw
 * location history table, per the privacy constraints in docs/discovery-engine.md §16/§23.
 */
@Database(entities = [DiscoveredCellEntity::class], version = 1, exportSchema = true)
abstract class WorldDiscoveryDatabase : RoomDatabase() {
    abstract fun discoveredCellDao(): DiscoveredCellDao

    companion object {
        const val DATABASE_NAME = "world_discovery.db"
    }
}
