package com.rift.ml

import com.rift.core.ml.CapabilitiesParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for CapabilitiesParser covering all documented capability string combinations
 * including edge cases: empty string, malformed, WPA3 SAE+OWE, multiple flags.
 */
class CapabilitiesParserTest {

    // ── Open network ──────────────────────────────────────────────────────────

    @Test fun `open network empty brackets`() {
        val caps = CapabilitiesParser.parse("[ESS]")
        assertTrue(caps.isOpen)
        assertFalse(caps.isWep)
        assertFalse(caps.isWpa)
        assertFalse(caps.isWpa2)
        assertFalse(caps.isWpa3)
        assertEquals(0.0f, caps.encryptionScore, 0.001f)
    }

    @Test fun `open network empty string`() {
        val caps = CapabilitiesParser.parse("")
        assertTrue(caps.isOpen)
        assertEquals(0.0f, caps.encryptionScore, 0.001f)
    }

    @Test fun `open network no brackets`() {
        val caps = CapabilitiesParser.parse("ESS")
        assertTrue(caps.isOpen)
        assertEquals(0.0f, caps.encryptionScore, 0.001f)
    }

    // ── WEP ──────────────────────────────────────────────────────────────────

    @Test fun `WEP network`() {
        val caps = CapabilitiesParser.parse("[WEP][ESS]")
        assertFalse(caps.isOpen)
        assertTrue(caps.isWep)
        assertFalse(caps.isWpa)
        assertFalse(caps.isWpa2)
        assertEquals(0.10f, caps.encryptionScore, 0.001f)
    }

    // ── WPA1 ─────────────────────────────────────────────────────────────────

    @Test fun `WPA1 TKIP only`() {
        val caps = CapabilitiesParser.parse("[WPA-PSK-TKIP][ESS]")
        assertFalse(caps.isOpen)
        assertFalse(caps.isWep)
        assertTrue(caps.isWpa)
        assertFalse(caps.isWpa2)
        assertFalse(caps.isWpa3)
        assertTrue(caps.usesTkip)
        assertEquals(0.30f, caps.encryptionScore, 0.001f)
    }

    // ── WPA2 ─────────────────────────────────────────────────────────────────

    @Test fun `WPA2 CCMP — standard home router`() {
        val caps = CapabilitiesParser.parse("[WPA2-PSK-CCMP][ESS]")
        assertFalse(caps.isOpen)
        assertFalse(caps.isWpa)
        assertTrue(caps.isWpa2)
        assertFalse(caps.isWpa3)
        assertFalse(caps.usesTkip)
        assertEquals(0.75f, caps.encryptionScore, 0.001f)
    }

    @Test fun `WPA2 TKIP downgrade`() {
        val caps = CapabilitiesParser.parse("[WPA2-PSK-TKIP][ESS]")
        assertTrue(caps.isWpa2)
        assertTrue(caps.usesTkip)
        assertEquals(0.50f, caps.encryptionScore, 0.001f)
    }

    @Test fun `WPA and WPA2 dual mode prefers WPA2`() {
        val caps = CapabilitiesParser.parse("[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]")
        assertTrue(caps.isWpa2)
        assertFalse(caps.isWpa)
        // usesTkip is true because TKIP flag is present even if CCMP is also listed
        assertTrue(caps.usesTkip)
        // Score reflects WPA2+TKIP (downgrade condition) since TKIP is present
        assertEquals(0.50f, caps.encryptionScore, 0.001f)
    }

    @Test fun `RSN prefix is treated as WPA2`() {
        val caps = CapabilitiesParser.parse("[RSN-PSK-CCMP][ESS]")
        assertTrue(caps.isWpa2)
        assertEquals(0.75f, caps.encryptionScore, 0.001f)
    }

    // ── WPA3 ─────────────────────────────────────────────────────────────────

    @Test fun `WPA3 SAE`() {
        val caps = CapabilitiesParser.parse("[SAE][ESS]")
        assertFalse(caps.isWpa2)
        assertTrue(caps.isWpa3)
        assertEquals(1.0f, caps.encryptionScore, 0.001f)
    }

    @Test fun `WPA3 OWE (enhanced open)`() {
        val caps = CapabilitiesParser.parse("[OWE][ESS]")
        assertTrue(caps.isWpa3)
        assertEquals(1.0f, caps.encryptionScore, 0.001f)
    }

    @Test fun `WPA3 SAE and OWE transition mode`() {
        val caps = CapabilitiesParser.parse("[WPA2-PSK-CCMP][SAE][ESS]")
        assertTrue(caps.isWpa3)   // SAE takes precedence
        assertFalse(caps.isWpa2)
        assertEquals(1.0f, caps.encryptionScore, 0.001f)
    }

    // ── WPS ──────────────────────────────────────────────────────────────────

    @Test fun `WPS flag detected`() {
        val caps = CapabilitiesParser.parse("[WPA2-PSK-CCMP][WPS][ESS]")
        assertTrue(caps.hasWps)
    }

    @Test fun `no WPS flag`() {
        val caps = CapabilitiesParser.parse("[WPA2-PSK-CCMP][ESS]")
        assertFalse(caps.hasWps)
    }

    // ── Case insensitivity ────────────────────────────────────────────────────

    @Test fun `lowercase capabilities string handled`() {
        val caps = CapabilitiesParser.parse("[wpa2-psk-ccmp][ess]")
        assertTrue(caps.isWpa2)
        assertEquals(0.75f, caps.encryptionScore, 0.001f)
    }

    // ── Encryption score range ────────────────────────────────────────────────

    @Test fun `encryption score always in range 0 to 1`() {
        val testCases = listOf(
            "", "[ESS]", "[WEP][ESS]",
            "[WPA-PSK-TKIP][ESS]", "[WPA2-PSK-TKIP][ESS]",
            "[WPA2-PSK-CCMP][ESS]", "[SAE][ESS]", "[OWE][ESS]",
            "[GARBAGE_FLAGS][UNKNOWN][ESS]"
        )
        for (input in testCases) {
            val score = CapabilitiesParser.parse(input).encryptionScore
            assertTrue("Score $score out of range for '$input'", score in 0.0f..1.0f)
        }
    }

    // ── Malformed / edge cases ────────────────────────────────────────────────

    @Test fun `malformed string with only random text`() {
        val caps = CapabilitiesParser.parse("GARBAGE_FLAGS_ONLY")
        assertTrue(caps.isOpen)  // no recognized encryption → defaults to open
        assertEquals(0.0f, caps.encryptionScore, 0.001f)
    }

    @Test fun `multiple ESS IBSS flags`() {
        val caps = CapabilitiesParser.parse("[WPA2-PSK-CCMP][ESS][IBSS]")
        assertTrue(caps.isWpa2)
    }
}
