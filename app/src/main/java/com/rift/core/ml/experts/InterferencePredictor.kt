package com.rift.core.ml.experts

import android.content.Context
import com.rift.core.data.ApReading
import com.rift.core.data.WifiBand
import com.rift.core.ml.InterferenceReport
import com.rift.core.ml.InterferenceSeverity
import com.rift.core.ml.OnnxModel
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.pow

/**
 * Expert 3 — Interference Predictor.
 *
 * Given current channel occupancy derived from the passive scan, predicts
 * interference severity for the RF environment and identifies worst offenders.
 *
 * Always runs every scan cycle. Result is surfaced to UI only when severity
 * exceeds MEDIUM (as per gate threshold of 0.6 → HIGH or SEVERE).
 *
 * Model: RandomForestClassifier (sklearn) exported via skl2onnx.
 * Input: 6-feature channel-level aggregate vector.
 * Output: 4-class label {LOW=0, MEDIUM=1, HIGH=2, SEVERE=3}.
 *
 * recommendedChannel is computed post-inference by finding the least-occupied
 * non-adjacent channel — this is rule-based logic, not a model output.
 */
@Singleton
class InterferencePredictor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MODEL_FILE = "interference_predictor_int8.onnx"
        private const val INTERFERENCE_THRESHOLD = 0.6f
        // Non-overlapping 2.4 GHz channels
        private val NON_OVERLAPPING_24 = setOf(1, 6, 11)
    }

    private val model: InternalModel? = try {
        InternalModel(context)
    } catch (e: Exception) {
        Timber.w("InterferencePredictor: model not found ($MODEL_FILE) — using rule-based fallback")
        null
    }

    /**
     * Analyzes current channel occupancy and returns an interference report.
     * [targetChannel] is the channel being evaluated (usually the connected AP's channel).
     * If null, uses the most congested detected channel.
     */
    fun predict(readings: List<ApReading>, targetChannel: Int? = null): InterferenceReport? {
        if (readings.isEmpty()) return null

        val features = buildChannelFeatures(readings, targetChannel)
        val severityOrdinal = model?.infer(features) ?: ruleBasedSeverity(features)
        val severity = InterferenceSeverity.entries.getOrElse(severityOrdinal) { InterferenceSeverity.LOW }

        // Only surface result above threshold
        val severityScore = severityOrdinal.toFloat() / 3f
        if (severityScore < INTERFERENCE_THRESHOLD) return null

        val worstOffender = findWorstOffender(readings, targetChannel)
        val recommended = findRecommendedChannel(readings)

        return InterferenceReport(
            severity = severity,
            worstOffenderBssid = worstOffender?.bssid,
            recommendedChannel = recommended
        )
    }

    /**
     * Builds the 6-feature channel aggregate vector.
     *
     * Features (per IMPROVEMENT.md §Expert 3):
     *  [0] channelOccupancy   — APs on same channel / total visible APs
     *  [1] avgRssiOnChannel   — mean RSSI of co-channel APs, normalised
     *  [2] maxRssiOnChannel   — strongest competitor, normalised
     *  [3] adjacentChannelCount — APs on ±1 channel (2.4 GHz partial overlap)
     *  [4] band               — 0.0 = 2.4 GHz, 1.0 = 5/6 GHz
     *  [5] totalApCount       — normalised total AP count (density proxy, /50)
     */
    private fun buildChannelFeatures(readings: List<ApReading>, targetChannel: Int?): FloatArray {
        val targetCh = targetChannel ?: readings
            .groupBy { frequencyToChannel(it.frequencyMhz) }
            .maxByOrNull { it.value.size }?.key ?: 6

        val coChannel = readings.filter { frequencyToChannel(it.frequencyMhz) == targetCh }
        val adjacent = readings.filter {
            val ch = frequencyToChannel(it.frequencyMhz)
            ch != targetCh && kotlin.math.abs(ch - targetCh) <= 1
        }

        val totalAps = readings.size.toFloat()
        val channelOccupancy = coChannel.size / totalAps
        val avgRssiOnChannel = if (coChannel.isEmpty()) 0f
        else ((coChannel.map { it.rssi }.average() + 95) / 65).toFloat().coerceIn(0f, 1f)
        val maxRssiOnChannel = coChannel.maxOfOrNull { it.rssi }?.let {
            ((it + 95f) / 65f).coerceIn(0f, 1f)
        } ?: 0f
        val adjacentChannelCount = (adjacent.size / totalAps).coerceIn(0f, 1f)
        // Use majority vote for band detection
        val band24Count = readings.count { it.band == WifiBand.GHZ_2_4 }
        val band = if (band24Count > readings.size / 2) 0f else 1f
        val totalApCountNorm = (totalAps / 50f).coerceIn(0f, 1f)

        return floatArrayOf(
            channelOccupancy,
            avgRssiOnChannel,
            maxRssiOnChannel,
            adjacentChannelCount,
            band,
            totalApCountNorm
        )
    }

    /** Returns the AP on the target channel with the strongest (most interfering) signal. */
    private fun findWorstOffender(readings: List<ApReading>, targetChannel: Int?): ApReading? {
        val targetCh = targetChannel ?: return null
        return readings
            .filter { frequencyToChannel(it.frequencyMhz) == targetCh }
            .maxByOrNull { it.rssi }
    }

    /**
     * Finds the least-occupied non-adjacent channel.
     * For 2.4 GHz, checks channels 1, 6, 11.
     * For 5/6 GHz, returns null (channels are already non-overlapping in practice).
     */
    private fun findRecommendedChannel(readings: List<ApReading>): Int? {
        if (readings.firstOrNull()?.band != WifiBand.GHZ_2_4) return null
        val occupancy = readings.groupBy { frequencyToChannel(it.frequencyMhz) }
        return NON_OVERLAPPING_24.minByOrNull { occupancy[it]?.size ?: 0 }
    }

    /** Physics-based fallback using SINR estimation. */
    private fun ruleBasedSeverity(features: FloatArray): Int {
        val channelOccupancy = features[0]
        val maxRssi = features[2]
        return when {
            channelOccupancy > 0.6f && maxRssi > 0.6f -> 3 // SEVERE
            channelOccupancy > 0.4f && maxRssi > 0.4f -> 2 // HIGH
            channelOccupancy > 0.2f                   -> 1 // MEDIUM
            else                                      -> 0 // LOW
        }
    }

    private fun frequencyToChannel(freqMhz: Int): Int = when {
        freqMhz in 2412..2484 -> (freqMhz - 2412) / 5 + 1
        freqMhz in 5170..5835 -> (freqMhz - 5170) / 5 + 34
        freqMhz in 5955..7115 -> (freqMhz - 5955) / 5 + 1
        else -> 0
    }

    private class InternalModel(context: Context) : OnnxModel(context, MODEL_FILE) {
        fun infer(features: FloatArray): Int {
            val tensor = floatTensor(features)
            return try {
                val results = session.run(mapOf("float_input" to tensor))
                val label = (results[0].value as LongArray)[0].toInt()
                results.close()
                label.coerceIn(0, 3)
            } finally {
                tensor.close()
            }
        }
    }
}
