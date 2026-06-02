package com.rift

import com.rift.core.data.ApReading
import com.rift.core.data.SignalStrength
import com.rift.core.data.WifiBand
import com.rift.core.positioning.HeatmapRenderer
import org.junit.Assert.*
import org.junit.Test

class HeatmapBinningTest {

    private val renderer = HeatmapRenderer()

    @Test
    fun `binPoints merges nearby points into centroid`() {
        val points = listOf(
            HeatmapRenderer.HeatmapPoint(xPx = 10f, yPx = 10f, rssi = -60f),
            HeatmapRenderer.HeatmapPoint(xPx = 12f, yPx = 11f, rssi = -65f),
            HeatmapRenderer.HeatmapPoint(xPx = 500f, yPx = 500f, rssi = -40f)
        )

        val binned = renderer.binPoints(points, binSizePx = 50f)

        // First two should merge (both in same 50px bin), third stays separate
        assertEquals(2, binned.size)

        val closePair = binned.minByOrNull { it.xPx }!!
        assertEquals(11f, closePair.xPx, 1f)
        assertEquals(10.5f, closePair.yPx, 1f)
        assertEquals(-62.5f, closePair.rssi, 0.1f)
    }

    @Test
    fun `binPoints returns empty list for empty input`() {
        val result = renderer.binPoints(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `binPoints preserves single point unchanged`() {
        val points = listOf(
            HeatmapRenderer.HeatmapPoint(xPx = 100f, yPx = 200f, rssi = -55f)
        )
        val result = renderer.binPoints(points, binSizePx = 15f)
        assertEquals(1, result.size)
        assertEquals(100f, result[0].xPx, 0.1f)
        assertEquals(200f, result[0].yPx, 0.1f)
        assertEquals(-55f, result[0].rssi, 0.1f)
    }

    @Test
    fun `ApReading band detection 2_4GHz`() {
        val reading = ApReading(
            bssid = "aa:bb:cc:dd:ee:ff",
            ssid = "HomeNetwork",
            rssi = -60,
            frequencyMhz = 2437  // Channel 6, 2.4 GHz
        )
        assertEquals(WifiBand.GHZ_2_4, reading.band)
    }

    @Test
    fun `ApReading band detection 5GHz`() {
        val reading = ApReading(
            bssid = "aa:bb:cc:dd:ee:ff",
            ssid = "HomeNetwork",
            rssi = -60,
            frequencyMhz = 5180  // Channel 36, 5 GHz
        )
        assertEquals(WifiBand.GHZ_5, reading.band)
    }

    @Test
    fun `ApReading band detection 6GHz`() {
        val reading = ApReading(
            bssid = "aa:bb:cc:dd:ee:ff",
            ssid = "HomeNetwork-6G",
            rssi = -55,
            frequencyMhz = 5955  // 6 GHz band
        )
        assertEquals(WifiBand.GHZ_6, reading.band)
    }

    @Test
    fun `signal strength classifications are correct`() {
        val excellent = ApReading("", "", rssi = -45, frequencyMhz = 2437)
        val good = ApReading("", "", rssi = -60, frequencyMhz = 2437)
        val fair = ApReading("", "", rssi = -70, frequencyMhz = 2437)
        val poor = ApReading("", "", rssi = -80, frequencyMhz = 2437)
        val veryPoor = ApReading("", "", rssi = -88, frequencyMhz = 2437)

        assertEquals(SignalStrength.EXCELLENT, excellent.signalStrength)
        assertEquals(SignalStrength.GOOD, good.signalStrength)
        assertEquals(SignalStrength.FAIR, fair.signalStrength)
        assertEquals(SignalStrength.POOR, poor.signalStrength)
        assertEquals(SignalStrength.VERY_POOR, veryPoor.signalStrength)
    }

    @Test
    fun `signal strength boundary at -50 is excellent`() {
        val reading = ApReading("", "", rssi = -50, frequencyMhz = 2437)
        assertEquals(SignalStrength.EXCELLENT, reading.signalStrength)
    }

    @Test
    fun `signal strength boundary at -51 is good`() {
        val reading = ApReading("", "", rssi = -51, frequencyMhz = 2437)
        assertEquals(SignalStrength.GOOD, reading.signalStrength)
    }
}
