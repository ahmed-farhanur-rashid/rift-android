package com.rift.core.ml

import com.rift.core.data.ApReading
import com.rift.core.ml.experts.AnomalyDetector
import com.rift.core.ml.experts.EvilTwinDetector
import com.rift.core.ml.experts.InterferencePredictor
import com.rift.core.ml.experts.RiskScorer
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mixture of Experts Gate.
 *
 * Routes each scan observation to the appropriate subset of experts, then
 * aggregates results into a single [ThreatReport]. The gate logic is
 * rule-based — the input space is discrete enough that learned routing would
 * require a labeled routing dataset that doesn't exist.
 *
 * Routing rules:
 *   EvilTwinDetector  → only if 2+ APs share the same SSID
 *   RiskScorer        → always, for every AP
 *   InterferencePredictor → always, using channel-level aggregates
 *   AnomalyDetector   → always, but returns null until baseline established
 *
 * FAULT TOLERANCE: Any exception during expert evaluation is caught, logged,
 * and skipped. The rest of the report is assembled from whichever experts
 * succeeded. The scan pipeline is NEVER interrupted by ML failures.
 */
@Singleton
class MoEGate @Inject constructor(
    private val evilTwinDetector: EvilTwinDetector,
    private val riskScorer: RiskScorer,
    private val interferencePredictor: InterferencePredictor,
    private val anomalyDetector: AnomalyDetector,
    private val rssiTracker: RssiTracker,
    private val ouiLookup: OuiLookup
) {
    /**
     * Evaluates the current scan and returns a [ThreatReport].
     *
     * Must be called from a non-UI coroutine (Dispatchers.Default).
     * Worst-case latency budget: < 20ms for all four experts combined.
     */
    suspend fun evaluate(readings: List<ApReading>): ThreatReport {
        // Step 1 — update RSSI ring buffers (required before feature extraction)
        readings.forEach { rssiTracker.update(it.bssid, it.rssi) }

        // Step 2 — Expert 1: EvilTwinDetector (gate: duplicate SSIDs present)
        val evilTwinResult = if (hasDuplicateSsids(readings)) {
            safeRun("EvilTwinDetector") { evilTwinDetector.analyze(readings) }
        } else null

        // Step 3 — Expert 2: RiskScorer (always runs)
        val riskScores = safeRun("RiskScorer") {
            riskScorer.scoreAll(readings)
        } ?: emptyList()

        // Step 4 — Expert 3: InterferencePredictor (always runs)
        val interferenceReport = safeRun("InterferencePredictor") {
            interferencePredictor.predict(readings)
        }

        // Step 5 — Expert 4: AnomalyDetector (always runs; returns null before baseline)
        val anomalyReport = safeRun("AnomalyDetector") {
            anomalyDetector.analyze(readings)
        }

        // Step 6 — Derive overall risk level
        val overallRisk = deriveRiskLevel(evilTwinResult, riskScores, interferenceReport, anomalyReport)

        return ThreatReport(
            overallRiskLevel = overallRisk,
            evilTwinResult = evilTwinResult,
            riskScores = riskScores,
            interferenceReport = interferenceReport,
            anomalyReport = anomalyReport,
            timestamp = System.currentTimeMillis()
        )
    }

    // ── Gate conditions ───────────────────────────────────────────────────────

    private fun hasDuplicateSsids(readings: List<ApReading>): Boolean {
        val nonEmptySsids = readings.filter { it.ssid.isNotBlank() }.map { it.ssid }
        return nonEmptySsids.size != nonEmptySsids.toSet().size
    }

    // ── Risk level derivation (per IMPROVEMENT.md §Phase 3) ──────────────────

    private fun deriveRiskLevel(
        evilTwin: EvilTwinResult?,
        riskScores: List<RiskScore>,
        interference: InterferenceReport?,
        anomalies: AnomalyReport?
    ): RiskLevel {
        // CRITICAL: high-confidence evil twin
        if (evilTwin != null && evilTwin.confidence > 0.8f) return RiskLevel.CRITICAL

        // CRITICAL: encryption downgrade anomaly
        if (anomalies?.anomalies?.any { it.type == AnomalyType.ENCRYPTION_DOWNGRADE } == true) {
            return RiskLevel.CRITICAL
        }

        // WARNING: any AP with risk score > 0.7
        if (riskScores.any { it.score > 0.7f }) return RiskLevel.WARNING

        // CAUTION: high interference
        if (interference?.severity == InterferenceSeverity.HIGH ||
            interference?.severity == InterferenceSeverity.SEVERE
        ) return RiskLevel.CAUTION

        // CAUTION: any anomaly present
        if (anomalies?.anomalies?.isNotEmpty() == true) return RiskLevel.CAUTION

        return RiskLevel.SAFE
    }

    // ── Fault-tolerant expert invocation ─────────────────────────────────────

    private inline fun <T> safeRun(expertName: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Timber.e(e, "MoEGate: $expertName threw — skipping")
        null
    }
}
