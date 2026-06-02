package com.rift.core.positioning

import kotlin.math.sqrt

/**
 * Converts between blueprint pixel coordinates and real-world meter coordinates.
 *
 * Setup: User taps two known points on the blueprint and enters the real-world
 * distance between them. This gives pixels-per-meter ratio.
 */
data class ScaleCalibration(
    val point1Px: Pair<Float, Float>,
    val point2Px: Pair<Float, Float>,
    val realWorldDistanceMeters: Float,
    val originPx: Pair<Float, Float> = Pair(0f, 0f)
) {
    val pixelsPerMeter: Float by lazy {
        val dx = point2Px.first - point1Px.first
        val dy = point2Px.second - point1Px.second
        val distancePx = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        distancePx / realWorldDistanceMeters
    }

    fun metersToPixels(xMeters: Double, yMeters: Double): Pair<Float, Float> {
        return Pair(
            originPx.first + (xMeters * pixelsPerMeter).toFloat(),
            originPx.second + (yMeters * pixelsPerMeter).toFloat()
        )
    }

    fun pixelsToMeters(xPx: Float, yPx: Float): Pair<Double, Double> {
        return Pair(
            ((xPx - originPx.first) / pixelsPerMeter).toDouble(),
            ((yPx - originPx.second) / pixelsPerMeter).toDouble()
        )
    }
}

object ScaleCalibratorConstants {
    const val DEFAULT_PIXELS_PER_METER = 50f   // fallback if calibration skipped
    const val MIN_CALIBRATION_DISTANCE_M = 0.5f
    const val MAX_CALIBRATION_DISTANCE_M = 100f
}
