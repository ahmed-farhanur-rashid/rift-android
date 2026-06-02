package com.rift.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ─── Domain models ───────────────────────────────────────────────────────────

data class PdrState(
    val x: Double = 0.0,       // meters from origin
    val y: Double = 0.0,       // meters from origin
    val heading: Double = 0.0, // radians, 0 = north
    val stepCount: Int = 0,
    val confidence: Float = 1f  // 0..1, degrades with distance from last anchor
)

data class ApReading(
    val bssid: String,
    val ssid: String,
    val rssi: Int,          // dBm, typically -30 to -90
    val frequencyMhz: Int,  // MHz
    val capabilities: String = ""
) {
    val band: WifiBand get() = when {
        frequencyMhz in 2400..2500 -> WifiBand.GHZ_2_4
        frequencyMhz in 5150..5850 -> WifiBand.GHZ_5
        frequencyMhz in 5925..7125 -> WifiBand.GHZ_6
        else -> WifiBand.UNKNOWN
    }

    val signalStrength: SignalStrength get() = when {
        rssi >= -50 -> SignalStrength.EXCELLENT
        rssi >= -65 -> SignalStrength.GOOD
        rssi >= -75 -> SignalStrength.FAIR
        rssi >= -85 -> SignalStrength.POOR
        else -> SignalStrength.VERY_POOR
    }
}

enum class WifiBand(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    UNKNOWN("Unknown")
}

enum class SignalStrength(val label: String, val colorHex: String) {
    EXCELLENT("Excellent", "#00E676"),
    GOOD("Good", "#69F0AE"),
    FAIR("Fair", "#FFD740"),
    POOR("Poor", "#FF6D00"),
    VERY_POOR("Very Poor", "#D50000")
}

data class ScanDataPoint(
    val xMeters: Double,
    val yMeters: Double,
    val xPixels: Float,
    val yPixels: Float,
    val timestamp: Long,
    val confidence: Float,
    val readings: List<ApReading>
)

// ─── Room entities ────────────────────────────────────────────────────────────

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val blueprintUri: String,
    val pixelsPerMeter: Float,
    val originX: Float,
    val originY: Float,
    val startedAt: Long,
    val endedAt: Long?,
    val totalPoints: Int
)

@Entity(
    tableName = "scan_points",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class ScanPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val xMeters: Double,
    val yMeters: Double,
    val xPixels: Float,
    val yPixels: Float,
    val timestamp: Long,
    val confidence: Float
)

@Entity(
    tableName = "ap_readings",
    foreignKeys = [ForeignKey(
        entity = ScanPointEntity::class,
        parentColumns = ["id"],
        childColumns = ["scanPointId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scanPointId")]
)
data class ApReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanPointId: Long,
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val capabilities: String
)

/**
 * Persists summarised threat report results per scan point.
 * CASCADE-deletes with the parent scan point.
 *
 * NOTE: Schema version bumped to 2. [RiftDatabase] uses
 * fallbackToDestructiveMigration() in development — data is re-collected on
 * each install. Add a proper migration before any production release.
 */
@Entity(
    tableName = "threat_reports",
    foreignKeys = [ForeignKey(
        entity = ScanPointEntity::class,
        parentColumns = ["id"],
        childColumns = ["scanPointId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scanPointId")]
)
data class ThreatReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanPointId: Long,
    val overallRiskLevel: String,    // RiskLevel.name()
    val evilTwinDetected: Boolean,
    val maxRiskScore: Float,
    val anomalyCount: Int,
    val interferenceLevel: String,   // InterferenceSeverity.name() or "NONE"
    val timestamp: Long
)
