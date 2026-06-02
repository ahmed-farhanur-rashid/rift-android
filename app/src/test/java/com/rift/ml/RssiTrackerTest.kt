package com.rift.ml

import com.rift.core.ml.RssiTracker
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for RssiTracker:
 *  - Ring buffer size limit (capacity = 10)
 *  - Variance calculation correctness
 *  - Empty state safety
 *  - Multiple independent BSSIDs
 *  - Normalised variance stays in [0,1]
 */
class RssiTrackerTest {

    private lateinit var tracker: RssiTracker

    @Before fun setUp() {
        tracker = RssiTracker()
    }

    // ── Empty state ───────────────────────────────────────────────────────────

    @Test fun `variance returns 0 for unknown BSSID`() {
        assertEquals(0f, tracker.variance("AA:BB:CC:DD:EE:FF"), 0.0001f)
    }

    @Test fun `history returns empty list for unknown BSSID`() {
        assertTrue(tracker.history("AA:BB:CC:DD:EE:FF").isEmpty())
    }

    @Test fun `latestRssi returns null for unknown BSSID`() {
        assertNull(tracker.latestRssi("AA:BB:CC:DD:EE:FF"))
    }

    // ── Single observation ────────────────────────────────────────────────────

    @Test fun `single observation returns 0 variance (need at least 2)`() {
        tracker.update("AA:BB:CC:DD:EE:FF", -70)
        assertEquals(0f, tracker.variance("AA:BB:CC:DD:EE:FF"), 0.0001f)
    }

    @Test fun `latest rssi after one update`() {
        tracker.update("AA:BB:CC:DD:EE:FF", -65)
        assertEquals(-65, tracker.latestRssi("AA:BB:CC:DD:EE:FF"))
    }

    // ── Ring buffer ───────────────────────────────────────────────────────────

    @Test fun `history limited to 10 entries`() {
        val bssid = "11:22:33:44:55:66"
        repeat(15) { tracker.update(bssid, -70 + it) }
        val history = tracker.history(bssid)
        assertEquals(10, history.size)
    }

    @Test fun `oldest entries evicted after 10 updates`() {
        val bssid = "11:22:33:44:55:66"
        // Insert values 0..14 (15 total), ring buffer holds last 10 (5..14)
        repeat(15) { tracker.update(bssid, it) }
        val history = tracker.history(bssid)
        assertEquals(10, history.size)
        // The oldest remaining value should be 5 (0-indexed)
        assertTrue("Expected 5 as oldest, got ${history.first()}", history.first() == 5)
        assertTrue("Expected 14 as newest, got ${history.last()}", history.last() == 14)
    }

    // ── Variance ─────────────────────────────────────────────────────────────

    @Test fun `identical RSSI readings produce zero variance`() {
        val bssid = "AA:BB:CC:DD:EE:11"
        repeat(5) { tracker.update(bssid, -70) }
        assertEquals(0f, tracker.variance(bssid), 0.0001f)
    }

    @Test fun `high RSSI variance produces non-zero variance`() {
        val bssid = "AA:BB:CC:DD:EE:22"
        // Alternate between -30 and -95 — maximum possible spread
        listOf(-30, -95, -30, -95, -30, -95).forEach { tracker.update(bssid, it) }
        val v = tracker.variance(bssid)
        assertTrue("Expected high variance, got $v", v > 0.5f)
    }

    @Test fun `variance normalised to 0-1 range`() {
        val bssid = "AA:BB:CC:DD:EE:33"
        // Insert extreme alternating values
        repeat(5) {
            tracker.update(bssid, -30)
            tracker.update(bssid, -95)
        }
        val v = tracker.variance(bssid)
        assertTrue("Variance $v not in [0,1]", v in 0f..1f)
    }

    @Test fun `variance matches manual calculation`() {
        val bssid = "AA:BB:CC:DD:EE:44"
        val readings = listOf(-60, -70, -65, -75, -60)
        readings.forEach { tracker.update(bssid, it) }

        // Manual variance: mean = -66, deviations: 6,4,1,9,6 → variance = (36+16+1+81+36)/5 = 34
        // Normalised: 34 / 1225.0
        val expectedNorm = 34.0f / 1225.0f
        assertEquals(expectedNorm, tracker.variance(bssid), 0.005f)
    }

    // ── Multiple BSSIDs ───────────────────────────────────────────────────────

    @Test fun `multiple BSSIDs tracked independently`() {
        tracker.update("AA:BB:CC:DD:EE:01", -60)
        tracker.update("AA:BB:CC:DD:EE:01", -65)
        tracker.update("AA:BB:CC:DD:EE:02", -80)
        tracker.update("AA:BB:CC:DD:EE:02", -90)

        assertEquals(-65, tracker.latestRssi("AA:BB:CC:DD:EE:01"))
        assertEquals(-90, tracker.latestRssi("AA:BB:CC:DD:EE:02"))
        assertEquals(2, tracker.knownBssidCount())
    }

    @Test fun `known bssids count correct`() {
        val bssids = (1..5).map { "AA:BB:CC:DD:EE:0$it" }
        bssids.forEach { tracker.update(it, -70) }
        assertEquals(5, tracker.knownBssidCount())
        assertTrue(tracker.knownBssids().containsAll(bssids))
    }
}
