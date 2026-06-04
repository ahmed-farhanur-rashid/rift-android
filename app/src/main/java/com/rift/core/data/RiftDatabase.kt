package com.rift.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        ScanPointEntity::class,
        ApReadingEntity::class,
        ThreatReportEntity::class,
        WifiSourceEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class RiftDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun scanPointDao(): ScanPointDao
    abstract fun apReadingDao(): ApReadingDao
    abstract fun threatReportDao(): ThreatReportDao
    abstract fun wifiSourceDao(): WifiSourceDao
}
