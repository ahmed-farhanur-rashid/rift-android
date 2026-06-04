package com.rift.core.data

import com.rift.core.ml.ThreatReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val scanPointDao: ScanPointDao,
    private val apReadingDao: ApReadingDao,
    private val threatReportDao: ThreatReportDao,
    private val wifiSourceDao: WifiSourceDao
) {

    fun getAllSessions(): Flow<List<SessionEntity>> = sessionDao.getAllSessions()

    suspend fun getSessionById(id: String): SessionEntity? = sessionDao.getSessionById(id)

    suspend fun createSession(
        name: String,
        blueprintUri: String,
        pixelsPerMeter: Float,
        originX: Float,
        originY: Float
    ): String {
        val id = UUID.randomUUID().toString()
        sessionDao.insertSession(
            SessionEntity(
                id = id,
                name = name,
                blueprintUri = blueprintUri,
                pixelsPerMeter = pixelsPerMeter,
                originX = originX,
                originY = originY,
                startedAt = System.currentTimeMillis(),
                endedAt = null,
                totalPoints = 0
            )
        )
        return id
    }

    suspend fun finalizeSession(sessionId: String) {
        val session = sessionDao.getSessionById(sessionId) ?: return
        val pointCount = sessionDao.getPointCount(sessionId)
        sessionDao.updateSession(
            session.copy(
                endedAt = System.currentTimeMillis(),
                totalPoints = pointCount
            )
        )
    }

    /**
     * Persists a scan data point and its AP readings.
     * Returns the Room-generated [scanPointId] so the caller can link a
     * [ThreatReport] to the same point via [saveThreatReport].
     */
    suspend fun saveScanDataPoint(sessionId: String, point: ScanDataPoint): Long {
        val pointId = scanPointDao.insertPoint(
            ScanPointEntity(
                sessionId = sessionId,
                xMeters = point.xMeters,
                yMeters = point.yMeters,
                xPixels = point.xPixels,
                yPixels = point.yPixels,
                timestamp = point.timestamp,
                confidence = point.confidence
            )
        )
        val readingEntities = point.readings.map { reading ->
            ApReadingEntity(
                scanPointId = pointId,
                bssid = reading.bssid,
                ssid = reading.ssid,
                rssi = reading.rssi,
                frequencyMhz = reading.frequencyMhz,
                capabilities = reading.capabilities
            )
        }
        apReadingDao.insertReadings(readingEntities)
        return pointId
    }

    suspend fun getFullSessionData(sessionId: String): List<ScanDataPoint> {
        val points = scanPointDao.getPointsForSession(sessionId)
        return points.map { point ->
            val readings = apReadingDao.getReadingsForPoint(point.id)
            ScanDataPoint(
                xMeters = point.xMeters,
                yMeters = point.yMeters,
                xPixels = point.xPixels,
                yPixels = point.yPixels,
                timestamp = point.timestamp,
                confidence = point.confidence,
                readings = readings.map { r ->
                    ApReading(
                        bssid = r.bssid,
                        ssid = r.ssid,
                        rssi = r.rssi,
                        frequencyMhz = r.frequencyMhz,
                        capabilities = r.capabilities
                    )
                }
            )
        }
    }

    suspend fun getDistinctApsForSession(sessionId: String): List<ApIdentity> =
        apReadingDao.getDistinctApsForSession(sessionId)

    suspend fun deleteSession(sessionId: String) = sessionDao.deleteSession(sessionId)

    /**
     * Persists a [ThreatReport] linked to the given scan point.
     * [scanPointId] is the Room-generated primary key returned by [saveScanDataPoint]
     * (exposed via the return value of [ScanPointDao.insertPoint]).
     */
    suspend fun saveThreatReport(scanPointId: Long, report: ThreatReport) {
        threatReportDao.insertThreatReport(
            ThreatReportEntity(
                scanPointId = scanPointId,
                overallRiskLevel = report.overallRiskLevel.name,
                evilTwinDetected = report.evilTwinResult != null,
                maxRiskScore = report.riskScores.maxOfOrNull { it.score } ?: 0f,
                anomalyCount = report.anomalyReport?.anomalies?.size ?: 0,
                interferenceLevel = report.interferenceReport?.severity?.name ?: "NONE",
                timestamp = report.timestamp
            )
        )
    }

    suspend fun getThreatReportsForSession(sessionId: String): List<ThreatReportEntity> =
        threatReportDao.getThreatReportsForSession(sessionId)

    // ── WiFi Source CRUD ─────────────────────────────────────────────────────

    suspend fun saveWifiSource(source: WifiSource): Long {
        return wifiSourceDao.insertSource(
            WifiSourceEntity(
                sessionId = source.sessionId,
                name = source.name,
                bssid = source.bssid,
                xMeters = source.xMeters,
                yMeters = source.yMeters,
                transmitPowerDbm = source.transmitPowerDbm,
                frequencyMhz = source.frequencyMhz
            )
        )
    }

    suspend fun getWifiSources(sessionId: String): List<WifiSource> {
        return wifiSourceDao.getSourcesForSession(sessionId).map { it.toDomain() }
    }

    fun observeWifiSources(sessionId: String): Flow<List<WifiSource>> {
        return wifiSourceDao.observeSourcesForSession(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun updateWifiSource(source: WifiSource) {
        wifiSourceDao.updateSource(
            WifiSourceEntity(
                id = source.id,
                sessionId = source.sessionId,
                name = source.name,
                bssid = source.bssid,
                xMeters = source.xMeters,
                yMeters = source.yMeters,
                transmitPowerDbm = source.transmitPowerDbm,
                frequencyMhz = source.frequencyMhz
            )
        )
    }

    suspend fun deleteWifiSource(id: Long) {
        wifiSourceDao.deleteSource(id)
    }

    private fun WifiSourceEntity.toDomain() = WifiSource(
        id = id,
        sessionId = sessionId,
        name = name,
        bssid = bssid,
        xMeters = xMeters,
        yMeters = yMeters,
        transmitPowerDbm = transmitPowerDbm,
        frequencyMhz = frequencyMhz
    )
}
