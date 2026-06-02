package com.rift

import com.rift.core.positioning.ScaleCalibration
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PdrMathTest {

    @Test
    fun `scale calibration computes correct pixels per meter`() {
        // Two points 100px apart representing 2 meters
        val cal = ScaleCalibration(
            point1Px = Pair(0f, 0f),
            point2Px = Pair(100f, 0f),
            realWorldDistanceMeters = 2f
        )
        assertEquals(50f, cal.pixelsPerMeter, 0.01f)
    }

    @Test
    fun `scale calibration diagonal distance`() {
        // 3-4-5 triangle: 300px, 400px apart = 500px = 10 meters → 50 px/m
        val cal = ScaleCalibration(
            point1Px = Pair(0f, 0f),
            point2Px = Pair(300f, 400f),
            realWorldDistanceMeters = 10f
        )
        assertEquals(50f, cal.pixelsPerMeter, 0.01f)
    }

    @Test
    fun `meters to pixels conversion is correct`() {
        val cal = ScaleCalibration(
            point1Px = Pair(0f, 0f),
            point2Px = Pair(100f, 0f),
            realWorldDistanceMeters = 2f,
            originPx = Pair(200f, 300f)
        )
        val (px, py) = cal.metersToPixels(1.0, 2.0)
        // 1m * 50 px/m + 200 origin = 250, 2m * 50 + 300 = 400
        assertEquals(250f, px, 0.01f)
        assertEquals(400f, py, 0.01f)
    }

    @Test
    fun `pixels to meters round-trips correctly`() {
        val cal = ScaleCalibration(
            point1Px = Pair(0f, 0f),
            point2Px = Pair(100f, 0f),
            realWorldDistanceMeters = 2f,
            originPx = Pair(50f, 50f)
        )
        val originalX = 3.5
        val originalY = 7.2
        val (px, py) = cal.metersToPixels(originalX, originalY)
        val (rx, ry) = cal.pixelsToMeters(px, py)
        assertEquals(originalX, rx, 0.001)
        assertEquals(originalY, ry, 0.001)
    }

    @Test
    fun `pdr step updates x and y correctly heading north`() {
        // Heading = 0 (north = positive Y axis)
        val headingNorth = 0.0
        val stepLength = 0.74

        val newX = 0.0 + stepLength * Math.sin(headingNorth)
        val newY = 0.0 + stepLength * Math.cos(headingNorth)

        assertEquals(0.0, newX, 0.001)    // no east/west movement
        assertEquals(0.74, newY, 0.001)   // moves north
    }

    @Test
    fun `pdr step updates x correctly heading east`() {
        val headingEast = Math.PI / 2
        val stepLength = 1.0

        val newX = 0.0 + stepLength * Math.sin(headingEast)
        val newY = 0.0 + stepLength * Math.cos(headingEast)

        assertEquals(1.0, newX, 0.001)    // moves east
        assertEquals(0.0, newY, 0.001)    // no north/south movement
    }

    @Test
    fun `confidence degrades with steps since anchor`() {
        val degradationPerStep = 0.01f
        var confidence = 1f
        val steps = 50

        repeat(steps) {
            confidence = (confidence - degradationPerStep).coerceAtLeast(0.1f)
        }

        // After 50 steps at 0.01/step: 1.0 - 0.50 = 0.50
        assertEquals(0.50f, confidence, 0.01f)
    }

    @Test
    fun `confidence is clamped at minimum 0_1`() {
        val degradationPerStep = 0.01f
        var confidence = 1f

        // 200 steps would overshoot
        repeat(200) {
            confidence = (confidence - degradationPerStep).coerceAtLeast(0.1f)
        }

        assertEquals(0.1f, confidence, 0.001f)
    }
}
