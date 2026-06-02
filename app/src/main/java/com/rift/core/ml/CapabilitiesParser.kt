package com.rift.core.ml

/**
 * Parses the raw Android WifiScanResult.capabilities string into structured booleans
 * and a continuous encryption score suitable for ML feature input.
 *
 * Example capabilities strings:
 *   "[WPA2-PSK-CCMP][ESS]"
 *   "[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]"
 *   "[WEP][ESS]"
 *   "[SAE][ESS]"
 *   "[ESS]"  ← open network
 */
data class ParsedCapabilities(
    val isOpen: Boolean,
    val isWep: Boolean,
    val isWpa: Boolean,     // WPA1 without WPA2
    val isWpa2: Boolean,    // WPA2-PSK or RSN
    val isWpa3: Boolean,    // SAE or OWE
    val hasWps: Boolean,
    val usesTkip: Boolean,
    /** Continuous score [0.0, 1.0]: 0.0 = open (most risk), 1.0 = WPA3 (least risk). */
    val encryptionScore: Float
)

object CapabilitiesParser {

    fun parse(capabilities: String): ParsedCapabilities {
        val caps = capabilities.uppercase()

        val isWpa3 = caps.contains("[SAE]") || caps.contains("[OWE]")
        val isWpa2 = !isWpa3 && (caps.contains("[WPA2") || caps.contains("[RSN"))
        val isWep  = !isWpa3 && !isWpa2 && caps.contains("[WEP]")
        // WPA1 alone: has [WPA- but not WPA2
        val isWpa  = !isWpa3 && !isWpa2 && !isWep && caps.contains("[WPA-")
        val isOpen = !isWpa3 && !isWpa2 && !isWpa && !isWep
        val hasWps  = caps.contains("[WPS]")
        val usesTkip = caps.contains("TKIP")

        val encryptionScore = when {
            isWpa3                     -> 1.00f
            isWpa2 && !usesTkip        -> 0.75f  // WPA2+CCMP
            isWpa2 && usesTkip         -> 0.50f  // WPA2+TKIP (downgrade)
            isWpa && usesTkip          -> 0.30f  // WPA1+TKIP
            isWep                      -> 0.10f
            else                       -> 0.00f  // open
        }

        return ParsedCapabilities(
            isOpen = isOpen,
            isWep = isWep,
            isWpa = isWpa,
            isWpa2 = isWpa2,
            isWpa3 = isWpa3,
            hasWps = hasWps,
            usesTkip = usesTkip,
            encryptionScore = encryptionScore
        )
    }
}
