package com.rift.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT COUNT(*) FROM scan_points WHERE sessionId = :sessionId")
    suspend fun getPointCount(sessionId: String): Int
}

@Dao
interface ScanPointDao {

    @Query("SELECT * FROM scan_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getPointsForSession(sessionId: String): List<ScanPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: ScanPointEntity): Long

    @Query("DELETE FROM scan_points WHERE sessionId = :sessionId")
    suspend fun deletePointsForSession(sessionId: String)
}

@Dao
interface ApReadingDao {

    @Query("SELECT * FROM ap_readings WHERE scanPointId IN (SELECT id FROM scan_points WHERE sessionId = :sessionId)")
    suspend fun getReadingsForSession(sessionId: String): List<ApReadingEntity>

    @Query("SELECT * FROM ap_readings WHERE scanPointId = :scanPointId")
    suspend fun getReadingsForPoint(scanPointId: Long): List<ApReadingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReadings(readings: List<ApReadingEntity>)

    @Query("SELECT DISTINCT bssid, ssid FROM ap_readings WHERE scanPointId IN (SELECT id FROM scan_points WHERE sessionId = :sessionId)")
    suspend fun getDistinctApsForSession(sessionId: String): List<ApIdentity>
}

data class ApIdentity(val bssid: String, val ssid: String)

@Dao
interface ThreatReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThreatReport(report: ThreatReportEntity)

    @Query("SELECT * FROM threat_reports WHERE scanPointId IN (SELECT id FROM scan_points WHERE sessionId = :sessionId) ORDER BY timestamp ASC")
    suspend fun getThreatReportsForSession(sessionId: String): List<ThreatReportEntity>

    @Query("SELECT * FROM threat_reports WHERE scanPointId = :scanPointId LIMIT 1")
    suspend fun getThreatReportForPoint(scanPointId: Long): ThreatReportEntity?
}
