package com.rift.ml

import com.rift.core.data.ApReading
import com.rift.core.data.WifiBand
import com.rift.core.ml.CapabilitiesParser
import com.rift.core.ml.FeatureVectorBuilder
import com.rift.core.ml.OuiLookup
import com.rift.core.ml.RssiTracker
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verifies that all normalised feature values stay strictly in [0, 1].
 * This is a non-negotiable constraint — the ONNX models were trained on
 * data in [0,1] and will produce undefined outputs if features exceed this range.
 *
 * Tests cover boundary RSSI values, all frequency bands, all encryption types,
 * and vendor OUI edge cases.
 */
class FeatureVectorTest {

    private lateinit var rssiTracker: RssiTracker
    private lateinit var ouiLookup: OuiLookup

    @Before fun setUp() {
        rssiTracker = RssiTracker()
        // OuiLookup is a final class — use Mockito mock instead of subclassing
        ouiLookup = mock()
        whenever(ouiLookup.lookup(any())).thenReturn(null)
    }

    private fun buildReading(
        bssid: String = "AA:BB:CC:DD:EE:FF",
        ssid: String = "TestNetwork",
        rssi: Int = -65,
        frequencyMhz: Int = 2412,
        capabilities: String = "[WPA2-PSK-CCMP][ESS]"
    ) = ApReading(bssid, ssid, rssi, frequencyMhz, capabilities)

    private fun assertAllInRange(label: String, reading: ApReading) {
        val fv = FeatureVectorBuilder.build(reading, rssiTracker, ouiLookup)
        val arr = FeatureVectorBuilder.toFloatArray(fv)
        arr.forEachIndexed { idx, v ->
            assertTrue("$label feature[$idx]=$v not in [0,1]", v in 0f..1f)
        }
    }

    // ── RSSI boundary values ──────────────────────────────────────────────────

    @Test fun `minimum RSSI -95 dBm stays in range`() {
        assertAllInRange("RSSI=-95", buildReading(rssi = -95))
    }

    @Test fun `maximum RSSI -30 dBm stays in range`() {
        assertAllInRange("RSSI=-30", buildReading(rssi = -30))
    }

    @Test fun `RSSI beyond bounds clamped`() {
        // Android sometimes reports out-of-spec values; verify clamp
        assertAllInRange("RSSI=-10 (OOB high)", buildReading(rssi = -10))
        assertAllInRange("RSSI=-120 (OOB low)", buildReading(rssi = -120))
    }

    // ── Frequency bands ───────────────────────────────────────────────────────

    @Test fun `2 4 GHz band feature in range`() {
        assertAllInRange("2.4GHz", buildReading(frequencyMhz = 2412))
    }

    @Test fun `5 GHz band feature in range`() {
        assertAllInRange("5GHz", buildReading(frequencyMhz = 5180))
    }

    @Test fun `6 GHz band feature in range`() {
        assertAllInRange("6GHz", buildReading(frequencyMhz = 5955))
    }

    @Test fun `unknown band feature in range`() {
        assertAllInRange("unknown band", buildReading(frequencyMhz = 900))  // non-standard
    }

    // ── All encryption types ──────────────────────────────────────────────────

    @Test fun `open network features in range`() {
        assertAllInRange("open", buildReading(capabilities = "[ESS]"))
    }

    @Test fun `WEP features in range`() {
        assertAllInRange("WEP", buildReading(capabilities = "[WEP][ESS]"))
    }

    @Test fun `WPA1 TKIP features in range`() {
        assertAllInRange("WPA1", buildReading(capabilities = "[WPA-PSK-TKIP][ESS]"))
    }

    @Test fun `WPA2 CCMP features in range`() {
        assertAllInRange("WPA2", buildReading(capabilities = "[WPA2-PSK-CCMP][ESS]"))
    }

    @Test fun `WPA2 TKIP downgrade features in range`() {
        assertAllInRange("WPA2+TKIP", buildReading(capabilities = "[WPA2-PSK-TKIP][ESS]"))
    }

    @Test fun `WPA3 SAE features in range`() {
        assertAllInRange("WPA3", buildReading(capabilities = "[SAE][ESS]"))
    }

    @Test fun `WPS flag features in range`() {
        assertAllInRange("WPS", buildReading(capabilities = "[WPA2-PSK-CCMP][WPS][ESS]"))
    }

    // ── Hidden SSID ───────────────────────────────────────────────────────────

    @Test fun `hidden SSID empty string`() {
        assertAllInRange("hidden SSID", buildReading(ssid = ""))
    }

    @Test fun `visible SSID non-empty`() {
        assertAllInRange("visible SSID", buildReading(ssid = "MyNetwork"))
    }

    // ── Vendor OUI edge cases ─────────────────────────────────────────────────

    @Test fun `known vendor OUI in table`() {
        // Netgear OUI → vendorRiskScore should be 0.6
        val reading = buildReading(bssid = "00:13:20:AA:BB:CC")
        val fv = FeatureVectorBuilder.build(reading, rssiTracker, ouiLookup)
        assertEquals(0.6f, fv.vendorRiskScore, 0.001f)
        assertTrue(fv.vendorRiskScore in 0f..1f)
    }

    @Test fun `unknown vendor OUI gets default score`() {
        val reading = buildReading(bssid = "FF:EE:DD:CC:BB:AA")
        val fv = FeatureVectorBuilder.build(reading, rssiTracker, ouiLookup)
        assertEquals(0.4f, fv.vendorRiskScore, 0.001f)
    }

    // ── Feature array length ──────────────────────────────────────────────────

    @Test fun `feature array has exactly 8 elements`() {
        val reading = buildReading()
        val fv = FeatureVectorBuilder.build(reading, rssiTracker, ouiLookup)
        val arr = FeatureVectorBuilder.toFloatArray(fv)
        assertEquals(8, arr.size)
    }

    // ── RSSI variance contribution ────────────────────────────────────────────

    @Test fun `rssi variance feature updated after multiple readings`() {
        val bssid = "AA:BB:CC:DD:EE:FF"
        listOf(-60, -70, -65, -80, -55).forEach { rssiTracker.update(bssid, it) }

        val reading = buildReading(bssid = bssid, rssi = -65)
        val fv = FeatureVectorBuilder.build(reading, rssiTracker, ouiLookup)

        // After 5 varied readings, variance should be non-zero
        assertTrue("Expected non-zero rssiVariance, got ${fv.rssiVariance}", fv.rssiVariance > 0f)
        assertTrue(fv.rssiVariance in 0f..1f)
    }
}
