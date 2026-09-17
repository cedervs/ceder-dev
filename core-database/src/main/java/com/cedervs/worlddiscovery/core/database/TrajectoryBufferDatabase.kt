package com.cedervs.worlddiscovery.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local trajectory-buffer database — the trajectory-reconstruction Phase 1 foundation (see
 * `docs/ai-context/LOCATION_TRACKING.md`). **Deliberately a separate database file from
 * [WorldDiscoveryDatabase]**, for two independent reasons:
 *
 * 1. **Backup targeting.** [BufferedObservationEntity] stores raw latitude/longitude — see its
 *    own doc comment for the bounded, documented exception this represents. Android's Auto
 *    Backup / Cloud Backup (`android:allowBackup="true"`, already set for this app) backs up
 *    private app files, including Room database files, unless explicitly excluded. Keeping this
 *    buffer in its own file lets `app/src/main/res/xml/data_extraction_rules.xml` /
 *    `backup_rules.xml` exclude it **by name**, without touching whether `world_discovery.db`
 *    (canonical discovery history, not a privacy concern in the same way) is backed up — see
 *    those two files' own comments for the exact exclusion.
 * 2. **Blast-radius isolation.** This is genuinely a different, temporary, short-lived dataset
 *    with its own lifecycle (bounded retention, purge, crash recovery) — keeping it physically
 *    separate means its own future schema evolution, or even outright removal once trajectory
 *    reconstruction lands for real, can never risk `discovered_cells`' own migration history.
 *
 * `discovered_cells` itself is completely untouched by this database — see [WorldDiscoveryDatabase],
 * still schema version 1.
 */
@Database(entities = [BufferedObservationEntity::class], version = 1, exportSchema = true)
abstract class TrajectoryBufferDatabase : RoomDatabase() {
    abstract fun bufferedObservationDao(): BufferedObservationDao

    companion object {
        const val DATABASE_NAME = "trajectory_buffer.db"
    }
}
