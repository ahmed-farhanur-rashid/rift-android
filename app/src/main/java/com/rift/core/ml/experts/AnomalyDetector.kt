package com.rift.core.ml.experts

import android.content.Context
import com.rift.core.data.ApReading
import com.rift.core.data.WifiBand
import com.rift.core.ml.Anomaly
import com.rift.core.ml.AnomalyReport
import com.rift.core.ml.AnomalyType
import com.rift.core.ml.CapabilitiesParser
import com.rift.core.ml.OnnxModel
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Expert 4 — Anomaly Detector.
 *
 * Self-supervised. Learns what THIS user's RF environment looks like during the
 * first [BASELINE_SCAN_COUNT] scans, then flags deviations from that baseline.
 * No external dataset; no OUI or CVE data needed.
 *
 * Phase 1 (first 10 scans): builds baseline statistics per BSSID and accumulates
 * feature vectors for autoencoder reconstruction error threshold calibration.
 *
 * Phase 2 (scan 11+): classifies each scan using both:
 *  - Z-score deviation for discrete anomalies (NEW_UNKNOWN_AP, RSSI_SPIKE, etc.)
 *  - Autoencoder reconstruction error for holistic environmental shift
 *
 * IMPORTANT: The autoencoder ONNX model is trained on the user's own baseline data
 * in the Python training script. Until 10 baseline scans are collected, this expert
 * returns null to avoid false positives.
 */
@Singleton
class AnomalyDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MODEL_FILE = "anomaly_autoencoder_int8.onnx"
        private const val BASELINE_SCAN_COUNT = 10
        private const val RSSI_SPIKE_SIGMA = 3.0
        private const val AP_COUNT_DROP_THRESHOLD = 0.30f
    }

    private val model: InternalModel? = try {
        InternalModel(context)
    } catch (e: Exception) {
        Timber.w("AnomalyDetector: model not found ($MODEL_FILE) — using z-score fallback")
        null
    }

    // ── Baseline state ────────────────────────────────────────────────────────

    private val scanHistory = mutableListOf<List<ApReading>>()

    /** Per-BSSID: mean RSSI, variance, expected band, expected encScore */
    private data class ApBaseline(
        val bssid: String,
        val ssid: String,
        val meanRssi: Double,
        val rssiStd: Double,
        val band: WifiBand,
        val encryptionScore: Float,
        val seenInAllScans: Boolean
    )

    private var baseline: Map<String, ApBaseline> = emptyMap()
    private var baselineApCountRange: IntRange = 0..100
    private var baselineFeatureVectors: List<FloatArray> = emptyList()
    private var reconstructionErrorThreshold: Float = Float.MAX_VALUE

    val isBaselineEstablished: Boolean get() = scanHistory.size >= BASELINE_SCAN_COUNT

    /**
     * Feed a new scan into the detector. Returns an [AnomalyReport] if baseline is
     * established, or null while still accumulating baseline data.
     */
    fun analyze(readings: List<ApReading>): AnomalyReport? {
        scanHistory.add(readings)

        if (!isBaselineEstablished) {
            if (scanHistory.size == BASELINE_SCAN_COUNT) {
                buildBaseline()
            }
            return null
        }

        return detectAnomalies(readings)
    }

    /** Allow user to reset and re-establish baseline (e.g. moved to a new location). */
    fun resetBaseline() {
        scanHistory.clear()
        baseline = emptyMap()
        reconstructionErrorThreshold = Float.MAX_VALUE
        baselineFeatureVectors = emptyList()
        Timber.i("AnomalyDetector: baseline reset")
    }

    // ── Baseline construction ─────────────────────────────────────────────────

    private fun buildBaseline() {
        val allScans = scanHistory.take(BASELINE_SCAN_COUNT)
        val allBssids = allScans.flatMap { it.map { ap -> ap.bssid } }.toSet()

        val newBaseline = mutableMapOf<String, ApBaseline>()

        for (bssid in allBssids) {
            val observations = allScans.mapNotNull { scan ->
                scan.firstOrNull { it.bssid == bssid }
            }
            if (observations.isEmpty()) continue

            val rssiValues = observations.map { it.rssi.toDouble() }
            val meanRssi = rssiValues.average()
            val rssiVariance = rssiValues.map { (it - meanRssi).let { d -> d * d } }.average()
            val rssiStd = sqrt(rssiVariance)

            val ap = observations.last()
            val caps = CapabilitiesParser.parse(ap.capabilities)
            val seenInAll = observations.size == BASELINE_SCAN_COUNT

            newBaseline[bssid] = ApBaseline(
                bssid = bssid,
                ssid = ap.ssid,
                meanRssi = meanRssi,
                rssiStd = rssiStd,
                band = ap.band,
                encryptionScore = caps.encryptionScore,
                seenInAllScans = seenInAll
            )
        }

        val apCounts = allScans.map { it.size }
        baselineApCountRange = (apCounts.min())..(apCounts.max())
        baseline = newBaseline

        // Build aggregate feature vectors for autoencoder threshold calibration
        baselineFeatureVectors = allScans.map { buildAggregateFeatures(it) }
        calibrateReconstructionThreshold()

        Timber.i("AnomalyDetector: baseline established with ${newBaseline.size} BSSIDs")
    }

    private fun calibrateReconstructionThreshold() {
        val onnx = model ?: return
        val errors = baselineFeatureVectors.map { fv ->
            try {
                onnx.reconstructionError(fv)
            } catch (e: Exception) {
                0f
            }
        }
        if (errors.isEmpty()) return
        val meanError = errors.average().toFloat()
        val stdError = errors.map { (it - meanError).let { d -> d * d } }.average()
            .let { sqrt(it).toFloat() }
        reconstructionErrorThreshold = meanError + 2 * stdError
        Timber.d("AnomalyDetector: reconstruction error threshold = $reconstructionErrorThreshold")
    }

    // ── Anomaly detection ─────────────────────────────────────────────────────

    private fun detectAnomalies(readings: List<ApReading>): AnomalyReport {
        val anomalies = mutableListOf<Anomaly>()
        val currentBssids = readings.map { it.bssid }.toSet()

        // Rule-based discrete anomaly checks
        for (ap in readings) {
            val bl = baseline[ap.bssid]
            val caps = CapabilitiesParser.parse(ap.capabilities)

            if (bl == null) {
                // NEW_UNKNOWN_AP
                anomalies.add(Anomaly(
                    type = AnomalyType.NEW_UNKNOWN_AP,
                    bssid = ap.bssid,
                    ssid = ap.ssid.ifBlank { "[hidden]" },
                    score = 0.7f,
                    description = "Access point '${ap.ssid.ifBlank { ap.bssid }}' " +
                        "was not present during baseline collection"
                ))
            } else {
                // ENCRYPTION_DOWNGRADE
                if (caps.encryptionScore < bl.encryptionScore - 0.2f) {
                    anomalies.add(Anomaly(
                        type = AnomalyType.ENCRYPTION_DOWNGRADE,
                        bssid = ap.bssid,
                        ssid = ap.ssid,
                        score = 0.9f,
                        description = "'${ap.ssid}' is now using weaker encryption than " +
                            "recorded during baseline"
                    ))
                }

                // RSSI_SPIKE — more than 3σ above baseline mean
                val rssiDev = if (bl.rssiStd > 0)
                    (ap.rssi - bl.meanRssi) / bl.rssiStd
                else 0.0
                if (rssiDev > RSSI_SPIKE_SIGMA) {
                    val score = (rssiDev / 6.0).toFloat().coerceIn(0f, 1f)
                    anomalies.add(Anomaly(
                        type = AnomalyType.RSSI_SPIKE,
                        bssid = ap.bssid,
                        ssid = ap.ssid,
                        score = score,
                        description = "'${ap.ssid}' signal is ${ap.rssi} dBm — " +
                            "significantly stronger than its baseline average of " +
                            "${bl.meanRssi.toInt()} dBm"
                    ))
                }

                // WRONG_CHANNEL
                if (ap.band != bl.band) {
                    anomalies.add(Anomaly(
                        type = AnomalyType.WRONG_CHANNEL,
                        bssid = ap.bssid,
                        ssid = ap.ssid,
                        score = 0.6f,
                        description = "'${ap.ssid}' is transmitting on ${ap.band.label} " +
                            "instead of its baseline ${bl.band.label}"
                    ))
                }
            }
        }

        // KNOWN_AP_MISSING — BSSIDs that were in every baseline scan but are gone now
        for ((bssid, bl) in baseline) {
            if (bl.seenInAllScans && bssid !in currentBssids) {
                anomalies.add(Anomaly(
                    type = AnomalyType.KNOWN_AP_MISSING,
                    bssid = bssid,
                    ssid = bl.ssid,
                    score = 0.5f,
                    description = "'${bl.ssid.ifBlank { bssid }}' was consistently present " +
                        "in baseline scans but is not visible now"
                ))
            }
        }

        // AP_COUNT_DROP — total visible APs dropped > 30% vs baseline minimum
        val baselineMin = baselineApCountRange.first
        if (baselineMin > 0) {
            val dropFraction = (baselineMin - readings.size).toFloat() / baselineMin
            if (dropFraction > AP_COUNT_DROP_THRESHOLD) {
                anomalies.add(Anomaly(
                    type = AnomalyType.AP_COUNT_DROP,
                    bssid = null,
                    ssid = null,
                    score = dropFraction.coerceIn(0f, 1f),
                    description = "Visible access point count dropped from $baselineMin " +
                        "to ${readings.size} — ${(dropFraction * 100).toInt()}% reduction"
                ))
            }
        }

        // Autoencoder holistic check
        try {
            val onnx = model
            if (onnx != null && reconstructionErrorThreshold < Float.MAX_VALUE) {
                val currentFeatures = buildAggregateFeatures(readings)
                val error = onnx.reconstructionError(currentFeatures)
                if (error > reconstructionErrorThreshold) {
                    val score = (error / (reconstructionErrorThreshold * 2f)).coerceIn(0f, 1f)
                    anomalies.add(Anomaly(
                        type = AnomalyType.NEW_UNKNOWN_AP, // closest category for holistic shift
                        bssid = null,
                        ssid = null,
                        score = score,
                        description = "The overall RF environment profile deviates significantly " +
                            "from the recorded baseline"
                    ))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "AnomalyDetector: autoencoder inference failed")
        }

        return AnomalyReport(anomalies)
    }

    /**
     * Builds the 12-feature aggregate environment vector for the autoencoder.
     *
     * Features (per IMPROVEMENT.md §Expert 4):
     * mean RSSI norm, RSSI variance, AP count norm, open ratio, WEP ratio,
     * WPA ratio, WPA2 ratio, WPA3 ratio, WPS ratio, 2.4GHz ratio, 5GHz ratio,
     * known BSSID ratio
     */
    private fun buildAggregateFeatures(readings: List<ApReading>): FloatArray {
        if (readings.isEmpty()) return FloatArray(12)
        val n = readings.size.toFloat()

        val rssiNorms = readings.map { ((it.rssi + 95f) / 65f).coerceIn(0f, 1f) }
        val meanRssiNorm = rssiNorms.average().toFloat()
        val rssiVariance = rssiNorms.map { (it - meanRssiNorm).let { d -> d * d } }
            .average().toFloat()

        val caps = readings.map { CapabilitiesParser.parse(it.capabilities) }
        val openRatio = caps.count { it.isOpen } / n
        val wepRatio = caps.count { it.isWep } / n
        val wpaRatio = caps.count { it.isWpa } / n
        val wpa2Ratio = caps.count { it.isWpa2 } / n
        val wpa3Ratio = caps.count { it.isWpa3 } / n
        val wpsRatio = caps.count { it.hasWps } / n
        val ghz24Ratio = readings.count { it.band == WifiBand.GHZ_2_4 } / n
        val ghz5Ratio = readings.count { it.band == WifiBand.GHZ_5 || it.band == WifiBand.GHZ_6 } / n
        val knownBssidRatio = if (baseline.isEmpty()) 1f
        else readings.count { it.bssid in baseline } / n

        return floatArrayOf(
            meanRssiNorm, rssiVariance, (n / 50f).coerceIn(0f, 1f),
            openRatio, wepRatio, wpaRatio, wpa2Ratio, wpa3Ratio,
            wpsRatio, ghz24Ratio, ghz5Ratio, knownBssidRatio
        )
    }

    private class InternalModel(context: Context) : OnnxModel(context, MODEL_FILE) {
        /**
         * Runs the autoencoder forward pass and returns MSE reconstruction error.
         * Input: 12-feature environment vector. Output: 12-feature reconstruction.
         */
        fun reconstructionError(features: FloatArray): Float {
            val tensor = floatTensor(features)
            return try {
                val results = session.run(mapOf("env_features" to tensor))
                val reconstruction = (results[0].value as Array<FloatArray>)[0]
                results.close()
                // MSE between input and reconstruction
                features.indices.sumOf { i ->
                    val diff = (features[i] - reconstruction[i]).toDouble()
                    diff * diff
                }.toFloat() / features.size
            } finally {
                tensor.close()
            }
        }
    }
}
