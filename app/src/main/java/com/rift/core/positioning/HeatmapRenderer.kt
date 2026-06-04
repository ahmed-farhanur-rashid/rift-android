package com.rift.core.positioning

import android.graphics.Bitmap
import android.graphics.Color
import com.rift.core.data.ScanDataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Inverse Distance Weighting heatmap renderer.
 *
 * Renders a colour-coded overlay showing WiFi signal strength (or risk score)
 * across the blueprint. Uses tiled computation to handle large blueprints without
 * OOM errors.
 *
 * Signal palette  — red (-90 dBm, weak) → yellow → green (-30 dBm, excellent)
 * Risk palette    — green (0 risk) → yellow → red (max risk)
 *   The risk palette is the inverse of the signal palette so both overlays are
 *   visually distinct and immediately readable.
 */
@Singleton
class HeatmapRenderer @Inject constructor() {

    companion object {
        private const val IDW_POWER = 2.0
        private const val IDW_EPS = 1e-9
        private const val HEATMAP_ALPHA = 180  // 0..255
        private const val RSSI_MIN = -90f
        private const val RSSI_MAX = -30f
    }

    data class HeatmapPoint(
        val xPx: Float,
        val yPx: Float,
        val rssi: Float,
        val confidence: Float = 1f
    )

    /**
     * Render heatmap for all visible APs combined (max RSSI per location).
     *
     * @param useRiskPalette When true, uses the risk colour scale (green=safe → red=risky)
     *   instead of the default signal strength scale. The caller must pre-substitute RSSI
     *   values with risk-score-derived synthetic values before calling.
     */
    suspend fun renderCombined(
        points: List<ScanDataPoint>,
        width: Int,
        height: Int,
        filterBssid: String? = null,
        useRiskPalette: Boolean = false
    ): Bitmap = withContext(Dispatchers.Default) {
        val heatmapPoints = extractHeatmapPoints(points, filterBssid)
        if (heatmapPoints.isEmpty()) {
            return@withContext Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        Timber.d("Rendering heatmap: ${heatmapPoints.size} pts, ${width}x${height}px, risk=$useRiskPalette")
        renderIdwTiled(heatmapPoints, width, height, useRiskPalette)
    }

    private fun extractHeatmapPoints(
        points: List<ScanDataPoint>,
        filterBssid: String?
    ): List<HeatmapPoint> = points.mapNotNull { point ->
        val rssi = if (filterBssid != null) {
            point.readings.firstOrNull { it.bssid == filterBssid }?.rssi?.toFloat()
        } else {
            point.readings.maxOfOrNull { it.rssi }?.toFloat()
        }
        rssi?.let {
            HeatmapPoint(xPx = point.xPixels, yPx = point.yPixels, rssi = it, confidence = point.confidence)
        }
    }

    private fun renderIdwTiled(
        points: List<HeatmapPoint>,
        width: Int,
        height: Int,
        useRiskPalette: Boolean
    ): Bitmap {
        // Bin points to reduce computation for large point sets
        val binnedPoints = if (points.size > 100) binPoints(points, 20f) else points
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width)
        val xs = FloatArray(binnedPoints.size) { binnedPoints[it].xPx }
        val ys = FloatArray(binnedPoints.size) { binnedPoints[it].yPx }
        val vals = FloatArray(binnedPoints.size) { binnedPoints[it].rssi }
        val confs = FloatArray(binnedPoints.size) { binnedPoints[it].confidence }
        val n = binnedPoints.size

        // Pre-compute power values for performance
        val powerCache = DoubleArray(n)

        for (py in 0 until height) {
            for (px in 0 until width) {
                var weightedSum = 0.0
                var weightSum = 0.0
                for (i in 0 until n) {
                    val dx = px - xs[i]
                    val dy = py - ys[i]
                    val distSq = (dx * dx + dy * dy).toDouble()
                    val weight = confs[i] / (distSq.pow(IDW_POWER / 2.0) + IDW_EPS)
                    weightedSum += weight * vals[i]
                    weightSum += weight
                }
                val value = if (weightSum > 0) (weightedSum / weightSum).toFloat() else RSSI_MIN
                pixels[px] = if (useRiskPalette) valueToRiskColor(value) else rssiToSignalColor(value)
            }
            bitmap.setPixels(pixels, 0, width, 0, py, width, 1)
        }
        return bitmap
    }

    fun binPoints(points: List<HeatmapPoint>, binSizePx: Float = 15f): List<HeatmapPoint> {
        if (points.isEmpty()) return points
        val buckets = mutableMapOf<Pair<Int, Int>, MutableList<HeatmapPoint>>()
        for (pt in points) {
            val bx = (pt.xPx / binSizePx).toInt()
            val by = (pt.yPx / binSizePx).toInt()
            buckets.getOrPut(Pair(bx, by)) { mutableListOf() }.add(pt)
        }
        return buckets.values.map { bucket ->
            HeatmapPoint(
                xPx = bucket.map { it.xPx }.average().toFloat(),
                yPx = bucket.map { it.yPx }.average().toFloat(),
                rssi = bucket.map { it.rssi }.average().toFloat(),
                confidence = bucket.map { it.confidence }.average().toFloat()
            )
        }
    }

    /** Signal strength palette: red (weak) → yellow → green (strong). */
    private fun rssiToSignalColor(rssi: Float): Int {
        val t = ((rssi - RSSI_MIN) / (RSSI_MAX - RSSI_MIN)).coerceIn(0f, 1f)
        val r: Int; val g: Int
        when {
            t < 0.5f -> { r = 220; g = (t / 0.5f * 200).toInt() }
            else     -> { r = ((1f - (t - 0.5f) / 0.5f) * 220).toInt(); g = 200 }
        }
        return Color.argb(HEATMAP_ALPHA, r, g, 0)
    }

    /**
     * Risk palette: green (low risk / low synthetic RSSI) → yellow → red (high risk).
     * Inverse of signal palette so the two maps are visually distinguishable at a glance.
     *
     * Input range is the same [-90, -30] used by the renderer; caller maps
     * risk [0,1] → [-95, -30] before passing data in.
     */
    private fun valueToRiskColor(rssi: Float): Int {
        val t = ((rssi - RSSI_MIN) / (RSSI_MAX - RSSI_MIN)).coerceIn(0f, 1f)
        // Invert: high normalised value = high risk = more red
        val r: Int; val g: Int
        when {
            t < 0.5f -> { r = (t / 0.5f * 220).toInt(); g = 180 }
            else     -> { r = 220; g = ((1f - (t - 0.5f) / 0.5f) * 180).toInt() }
        }
        return Color.argb(HEATMAP_ALPHA, r, g, 20)
    }
}
