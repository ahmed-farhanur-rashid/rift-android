package com.rift.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        ScanPointEntity::class,
        ApReadingEntity::class,
        ThreatReportEntity::class      // Added in schema version 2
    ],
    version = 2,
    exportSchema = true
)
abstract class RiftDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun scanPointDao(): ScanPointDao
    abstract fun apReadingDao(): ApReadingDao
    abstract fun threatReportDao(): ThreatReportDao
}
