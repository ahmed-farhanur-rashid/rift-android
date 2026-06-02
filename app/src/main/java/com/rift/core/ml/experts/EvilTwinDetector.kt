package com.rift.core.ml.experts

import android.content.Context
import com.rift.core.data.ApReading
import com.rift.core.ml.CapabilitiesParser
import com.rift.core.ml.EvilTwinResult
import com.rift.core.ml.OnnxModel
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Expert 1 — Evil Twin Detector.
 *
 * Detects rogue APs impersonating legitimate networks by comparing pairs of APs
 * that share the same SSID. Runs only when the gate identifies duplicate SSIDs.
 *
 * Model: GradientBoostingClassifier (sklearn) exported via skl2onnx.
 * Input: 5-feature vector per AP pair (see [buildPairFeatures]).
 * Output: binary class 0=legitimate, 1=evil twin. Confidence from predict_proba.
 *
 * Fallback: If the ONNX model asset is missing (development builds before training
 * is complete), the detector uses a rule-based heuristic so the rest of the app
 * remains functional.
 */
@Singleton
class EvilTwinDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MODEL_FILE = "evil_twin_detector_int8.onnx"
        private const val CONFIDENCE_THRESHOLD = 0.60f
    }

    private val model: InternalModel? = try {
        InternalModel(context)
    } catch (e: Exception) {
        Timber.w("EvilTwinDetector: model not found ($MODEL_FILE) — using rule-based fallback")
        null
    }

    /**
     * Analyses all AP pairs sharing the same SSID.
     * Returns the highest-confidence result, or null if no suspicious pairs found.
     */
    fun analyze(readings: List<ApReading>): EvilTwinResult? {
        // Group APs by SSID (only non-empty SSIDs are relevant)
        val bySsid = readings
            .filter { it.ssid.isNotBlank() }
            .groupBy { it.ssid }
            .filter { it.value.size >= 2 }

        if (bySsid.isEmpty()) return null

        var bestResult: EvilTwinResult? = null

        for ((ssid, aps) in bySsid) {
            // Evaluate all unique pairs
            for (i in aps.indices) {
                for (j in i + 1 until aps.size) {
                    val apA = aps[i]
                    val apB = aps[j]
                    val features = buildPairFeatures(apA, apB)
                    val confidence = model?.infer(features) ?: ruleBasedScore(features)

                    if (confidence >= CONFIDENCE_THRESHOLD) {
                        val result = EvilTwinResult(
                            suspiciousSsid = ssid,
                            confidence = confidence,
                            affectedBssids = listOf(apA.bssid, apB.bssid)
                        )
                        if (bestResult == null || confidence > bestResult.confidence) {
                            bestResult = result
                        }
                    }
                }
            }
        }

        return bestResult
    }

    /**
     * Builds the 5-feature input vector for a pair of APs.
     *
     * Features:
     *  [0] bssidSimilarity  — edit distance of MACs, normalised [0,1]
     *  [1] ouiMatch         — 1 if same OUI prefix, 0 if not
     *  [2] encryptionDelta  — |encScore_A − encScore_B|
     *  [3] rssiDiff         — |rssi_A − rssi_B| normalised to [0,1]
     *  [4] bandMismatch     — 1 if different bands, 0 if same
     */
    private fun buildPairFeatures(apA: ApReading, apB: ApReading): FloatArray {
        val macA = apA.bssid.replace(":", "").uppercase()
        val macB = apB.bssid.replace(":", "").uppercase()

        val bssidSimilarity = normalizedEditDistance(macA, macB)
        val ouiMatch = if (macA.take(6) == macB.take(6)) 1f else 0f

        val capsA = CapabilitiesParser.parse(apA.capabilities)
        val capsB = CapabilitiesParser.parse(apB.capabilities)
        val encryptionDelta = abs(capsA.encryptionScore - capsB.encryptionScore)

        val rssiDiffNorm = (abs(apA.rssi - apB.rssi).toFloat() / 65f).coerceIn(0f, 1f)
        val bandMismatch = if (apA.band != apB.band) 1f else 0f

        return floatArrayOf(bssidSimilarity, ouiMatch, encryptionDelta, rssiDiffNorm, bandMismatch)
    }

    /**
     * Rule-based fallback used when the ONNX model asset is not yet available.
     * Based on the same logic the training data encodes.
     */
    private fun ruleBasedScore(features: FloatArray): Float {
        val bssidSim    = features[0]
        val ouiMatch    = features[1]
        val encDelta    = features[2]
        val rssiDiff    = features[3]
        val bandMismatch = features[4]
        var score = 0f
        if (bssidSim > 0.5f && ouiMatch < 0.5f) score += 0.4f
        if (encDelta > 0.3f) score += 0.3f
        if (rssiDiff < 0.15f) score += 0.15f
        if (bandMismatch > 0.5f) score += 0.05f
        return score.coerceIn(0f, 1f)
    }

    /** Levenshtein distance of two strings, normalised to [0, 1] by max length. */
    private fun normalizedEditDistance(a: String, b: String): Float {
        if (a == b) return 0f
        if (a.isEmpty()) return 1f
        if (b.isEmpty()) return 1f
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in dp.indices) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length].toFloat() / maxOf(a.length, b.length).toFloat()
    }

    private class InternalModel(context: Context) : OnnxModel(context, MODEL_FILE) {
        /**
         * Runs the classifier and returns the probability of class 1 (evil twin).
         * sklearn's GBC exports predict_proba as output index 1:
         *   output[0] = class labels (LongArray)
         *   output[1] = probabilities (List<Map<Long, Float>>)
         */
        fun infer(features: FloatArray): Float {
            val tensor = floatTensor(features)
            return try {
                val results = session.run(mapOf("float_input" to tensor))
                @Suppress("UNCHECKED_CAST")
                val probMap = (results[1].value as List<Map<Long, Float>>)[0]
                results.close()
                probMap[1L] ?: 0f
            } catch (e: Exception) {
                Timber.w(e, "EvilTwinDetector: inference failed")
                0f
            } finally {
                tensor.close()
            }
        }
    }
}
