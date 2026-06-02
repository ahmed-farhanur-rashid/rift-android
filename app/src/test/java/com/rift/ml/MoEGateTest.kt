package com.rift.ml

import com.rift.core.data.ApReading
import com.rift.core.data.WifiBand
import com.rift.core.ml.*
import com.rift.core.ml.experts.AnomalyDetector
import com.rift.core.ml.experts.EvilTwinDetector
import com.rift.core.ml.experts.InterferencePredictor
import com.rift.core.ml.experts.RiskScorer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Tests for MoEGate:
 *  - EvilTwinDetector gate fires only when ≥2 APs share the same SSID
 *  - AnomalyDetector gate never returns results before baseline established
 *  - ThreatReport assembled from all expert outputs correctly
 *  - overallRiskLevel derivation rules
 *  - Fault tolerance: expert exceptions don't propagate to caller
 */
class MoEGateTest {

    private lateinit var evilTwinDetector: EvilTwinDetector
    private lateinit var riskScorer: RiskScorer
    private lateinit var interferencePredictor: InterferencePredictor
    private lateinit var anomalyDetector: AnomalyDetector
    private lateinit var rssiTracker: RssiTracker
    private lateinit var ouiLookup: OuiLookup
    private lateinit var gate: MoEGate

    @Before fun setUp() {
        evilTwinDetector    = mock(EvilTwinDetector::class.java)
        riskScorer          = mock(RiskScorer::class.java)
        interferencePredictor = mock(InterferencePredictor::class.java)
        anomalyDetector     = mock(AnomalyDetector::class.java)
        rssiTracker         = RssiTracker()
        ouiLookup           = mock(OuiLookup::class.java)

        gate = MoEGate(
            evilTwinDetector    = evilTwinDetector,
            riskScorer          = riskScorer,
            interferencePredictor = interferencePredictor,
            anomalyDetector     = anomalyDetector,
            rssiTracker         = rssiTracker,
            ouiLookup           = ouiLookup
        )

        // Default stubs
        `when`(riskScorer.scoreAll(any())).thenReturn(emptyList())
        `when`(interferencePredictor.predict(any(), any())).thenReturn(null)
        `when`(anomalyDetector.analyze(any())).thenReturn(null)
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private fun ap(bssid: String, ssid: String, rssi: Int = -65) = ApReading(
        bssid = bssid,
        ssid = ssid,
        rssi = rssi,
        frequencyMhz = 2412,
        capabilities = "[WPA2-PSK-CCMP][ESS]"
    )

    // ── EvilTwinDetector gate ─────────────────────────────────────────────────

    @Test fun `evil twin detector NOT called when all SSIDs are unique`() = runTest {
        val readings = listOf(
            ap("AA:BB:CC:00:00:01", "HomeNet"),
            ap("AA:BB:CC:00:00:02", "CoffeeShop"),
            ap("AA:BB:CC:00:00:03", "GuestWifi")
        )
        gate.evaluate(readings)
        verify(evilTwinDetector, never()).analyze(any())
    }

    @Test fun `evil twin detector IS called when two APs share same SSID`() = runTest {
        val readings = listOf(
            ap("AA:BB:CC:00:00:01", "HomeNet"),
            ap("DD:EE:FF:00:00:02", "HomeNet"),  // duplicate SSID
            ap("AA:BB:CC:00:00:03", "GuestWifi")
        )
        `when`(evilTwinDetector.analyze(readings)).thenReturn(null)
        gate.evaluate(readings)
        verify(evilTwinDetector).analyze(readings)
    }

    @Test fun `evil twin detector NOT called for hidden SSIDs (blank)`() = runTest {
        val readings = listOf(
            ap("AA:BB:CC:00:00:01", ""),  // hidden SSID
            ap("DD:EE:FF:00:00:02", "")   // another hidden SSID — blank SSIDs excluded from gate
        )
        gate.evaluate(readings)
        verify(evilTwinDetector, never()).analyze(any())
    }

    // ── RiskScorer gate ───────────────────────────────────────────────────────

    @Test fun `risk scorer always called regardless of readings`() = runTest {
        val readings = listOf(ap("AA:BB:CC:00:00:01", "Net"))
        gate.evaluate(readings)
        verify(riskScorer).scoreAll(readings)
    }

    @Test fun `risk scorer called with empty list`() = runTest {
        gate.evaluate(emptyList())
        verify(riskScorer).scoreAll(emptyList())
    }

    // ── AnomalyDetector gate ──────────────────────────────────────────────────

    @Test fun `anomaly detector called every scan cycle`() = runTest {
        val readings = listOf(ap("AA:BB:CC:00:00:01", "Net"))
        gate.evaluate(readings)
        verify(anomalyDetector).analyze(readings)
    }

    @Test fun `anomaly result is null before baseline established`() = runTest {
        `when`(anomalyDetector.analyze(any())).thenReturn(null)
        val readings = listOf(ap("AA:BB:CC:00:00:01", "Net"))
        val report = gate.evaluate(readings)
        assertNull(report.anomalyReport)
    }

    // ── ThreatReport assembly ─────────────────────────────────────────────────

    @Test fun `threat report assembles expert outputs correctly`() = runTest {
        val evilTwin = EvilTwinResult("HomeNet", 0.85f, listOf("AA:BB:CC", "DD:EE:FF"))
        val riskScores = listOf(RiskScore("AA:BB:CC", "HomeNet", 0.75f, RiskReason.OPEN_NETWORK))
        val interference = InterferenceReport(InterferenceSeverity.HIGH, "DD:EE:FF", 6)
        val anomalies = AnomalyReport(listOf(
            Anomaly(AnomalyType.RSSI_SPIKE, "AA:BB:CC", "HomeNet", 0.8f, "Spike detected")
        ))

        val readings = listOf(
            ap("AA:BB:CC:00:00:01", "HomeNet"),
            ap("DD:EE:FF:00:00:02", "HomeNet")
        )

        `when`(evilTwinDetector.analyze(readings)).thenReturn(evilTwin)
        `when`(riskScorer.scoreAll(readings)).thenReturn(riskScores)
        `when`(interferencePredictor.predict(any(), anyOrNull())).thenReturn(interference)
        `when`(anomalyDetector.analyze(readings)).thenReturn(anomalies)

        val report = gate.evaluate(readings)

        assertEquals(evilTwin, report.evilTwinResult)
        assertEquals(riskScores, report.riskScores)
        assertEquals(interference, report.interferenceReport)
        assertEquals(anomalies, report.anomalyReport)
        assertTrue(report.timestamp > 0L)
    }

    // ── Risk level derivation ─────────────────────────────────────────────────

    @Test fun `SAFE when no experts report issues`() = runTest {
        `when`(riskScorer.scoreAll(any())).thenReturn(listOf(
            RiskScore("AA:BB:CC", "Net", 0.1f, RiskReason.CLEAN)
        ))
        val report = gate.evaluate(listOf(ap("AA:BB:CC:00:00:01", "Net")))
        assertEquals(RiskLevel.SAFE, report.overallRiskLevel)
    }

    @Test fun `CRITICAL when high-confidence evil twin`() = runTest {
        val readings = listOf(
            ap("AA:BB:CC:00:00:01", "HomeNet"),
            ap("DD:EE:FF:00:00:02", "HomeNet")
        )
        `when`(evilTwinDetector.analyze(readings)).thenReturn(
            EvilTwinResult("HomeNet", 0.95f, listOf("AA:BB:CC", "DD:EE:FF"))
        )
        val report = gate.evaluate(readings)
        assertEquals(RiskLevel.CRITICAL, report.overallRiskLevel)
    }

    @Test fun `CRITICAL when encryption downgrade anomaly detected`() = runTest {
        `when`(anomalyDetector.analyze(any())).thenReturn(
            AnomalyReport(listOf(
                Anomaly(AnomalyType.ENCRYPTION_DOWNGRADE, "AA:BB:CC", "Net", 0.9f, "Downgrade")
            ))
        )
        val report = gate.evaluate(listOf(ap("AA:BB:CC:00:00:01", "Net")))
        assertEquals(RiskLevel.CRITICAL, report.overallRiskLevel)
    }

    @Test fun `WARNING when any AP has risk score above 0 7`() = runTest {
        `when`(riskScorer.scoreAll(any())).thenReturn(listOf(
            RiskScore("AA:BB:CC", "Net", 0.8f, RiskReason.OPEN_NETWORK)
        ))
        val report = gate.evaluate(listOf(ap("AA:BB:CC:00:00:01", "Net")))
        assertEquals(RiskLevel.WARNING, report.overallRiskLevel)
    }

    @Test fun `CAUTION when high interference`() = runTest {
        `when`(interferencePredictor.predict(any(), anyOrNull())).thenReturn(
            InterferenceReport(InterferenceSeverity.HIGH, null, 1)
        )
        val report = gate.evaluate(listOf(ap("AA:BB:CC:00:00:01", "Net")))
        assertEquals(RiskLevel.CAUTION, report.overallRiskLevel)
    }

    @Test fun `CAUTION when any anomaly present`() = runTest {
        `when`(anomalyDetector.analyze(any())).thenReturn(
            AnomalyReport(listOf(
                Anomaly(AnomalyType.NEW_UNKNOWN_AP, "FF:EE:DD", "Unknown", 0.7f, "New AP")
            ))
        )
        val report = gate.evaluate(listOf(ap("AA:BB:CC:00:00:01", "Net")))
        assertEquals(RiskLevel.CAUTION, report.overallRiskLevel)
    }

    // ── Fault tolerance ───────────────────────────────────────────────────────

    @Test fun `expert exception does not propagate — report still assembled`() = runTest {
        `when`(evilTwinDetector.analyze(any())).thenThrow(RuntimeException("ONNX session failed"))
        `when`(riskScorer.scoreAll(any())).thenThrow(RuntimeException("model error"))
        `when`(interferencePredictor.predict(any(), anyOrNull())).thenReturn(null)
        `when`(anomalyDetector.analyze(any())).thenReturn(null)

        val readings = listOf(
            ap("AA:BB:CC:00:00:01", "HomeNet"),
            ap("DD:EE:FF:00:00:02", "HomeNet")
        )

        // Must not throw
        val report = gate.evaluate(readings)

        assertNull(report.evilTwinResult)
        assertEquals(emptyList<RiskScore>(), report.riskScores)
        assertNull(report.interferenceReport)
        assertNull(report.anomalyReport)
        // Safe when all experts fail
        assertEquals(RiskLevel.SAFE, report.overallRiskLevel)
    }

    @Test fun `rssi tracker updated for every reading`() = runTest {
        val readings = listOf(
            ap("AA:BB:CC:00:00:01", "Net", rssi = -60),
            ap("DD:EE:FF:00:00:02", "Other", rssi = -75)
        )
        gate.evaluate(readings)
        assertEquals(-60, rssiTracker.latestRssi("AA:BB:CC:00:00:01"))
        assertEquals(-75, rssiTracker.latestRssi("DD:EE:FF:00:00:02"))
    }
}
