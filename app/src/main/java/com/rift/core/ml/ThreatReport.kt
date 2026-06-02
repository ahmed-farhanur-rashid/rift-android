package com.rift.core.ml

// ─── Risk levels ──────────────────────────────────────────────────────────────

enum class RiskLevel { SAFE, CAUTION, WARNING, CRITICAL }

// ─── Expert 1 — EvilTwin ─────────────────────────────────────────────────────

data class EvilTwinResult(
    val suspiciousSsid: String,
    val confidence: Float,
    val affectedBssids: List<String>
)

// ─── Expert 2 — RiskScorer ───────────────────────────────────────────────────

enum class RiskReason {
    OPEN_NETWORK, WEAK_ENCRYPTION, WPS_ENABLED,
    VENDOR_VULNERABLE, UNSTABLE_SIGNAL, HIDDEN_SSID, CLEAN
}

data class RiskScore(
    val bssid: String,
    val ssid: String,
    val score: Float,
    val primaryReason: RiskReason
)

// ─── Expert 3 — Interference ─────────────────────────────────────────────────

enum class InterferenceSeverity { LOW, MEDIUM, HIGH, SEVERE }

data class InterferenceReport(
    val severity: InterferenceSeverity,
    val worstOffenderBssid: String?,
    val recommendedChannel: Int?
)

// ─── Expert 4 — Anomaly ──────────────────────────────────────────────────────

enum class AnomalyType {
    NEW_UNKNOWN_AP,
    ENCRYPTION_DOWNGRADE,
    RSSI_SPIKE,
    AP_COUNT_DROP,
    WRONG_CHANNEL,
    KNOWN_AP_MISSING
}

data class Anomaly(
    val type: AnomalyType,
    val bssid: String?,
    val ssid: String?,
    val score: Float,
    val description: String
)

data class AnomalyReport(val anomalies: List<Anomaly>)

// ─── Aggregate ThreatReport ───────────────────────────────────────────────────

data class ThreatReport(
    val overallRiskLevel: RiskLevel,
    val evilTwinResult: EvilTwinResult?,
    val riskScores: List<RiskScore>,
    val interferenceReport: InterferenceReport?,
    val anomalyReport: AnomalyReport?,
    val timestamp: Long
)
