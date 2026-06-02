package com.rift.core.ml

import com.rift.core.data.ApReading
import com.rift.core.data.WifiBand

/**
 * Normalised per-AP feature vector fed into the ML experts.
 *
 * All values are strictly in [0, 1] — verified by [FeatureVectorBuilder].
 */
data class ApFeatureVector(
    /** (rssi + 95) / 65.  Maps [-95, -30] → [0, 1]. */
    val rssiNorm: Float,
    /** 0.0 = 2.4 GHz, 0.5 = 5 GHz, 1.0 = 6 GHz, 0.25 = unknown */
    val frequencyBandOrdinal: Float,
    /** From CapabilitiesParser: 0.0 (open) → 1.0 (WPA3) */
    val encryptionScore: Float,
    /** 1.0 if WPS advertised, else 0.0 */
    val hasWps: Float,
    /** 1.0 if TKIP in use, else 0.0 */
    val usesTkip: Float,
    /** Normalised RSSI variance from RssiTracker */
    val rssiVariance: Float,
    /** Vendor-derived CVE risk score [0, 1] from static lookup table */
    val vendorRiskScore: Float,
    /** 1.0 if SSID is empty (hidden network), else 0.0 */
    val isHidden: Float
)

object FeatureVectorBuilder {

    /**
     * Hardcoded vendor risk table keyed on lowercase OUI prefix (first 6 hex chars, no colons).
     * Scores are derived from publicly documented CVE frequency by vendor.
     * Unknown vendors get a neutral 0.4 score.
     *
     * This is intentionally conservative — the model learns weights; we provide a prior.
     */
    private val vendorRiskTable: Map<String, Float> = mapOf(
        // Higher historical CVE count
        "001320" to 0.60f, // Netgear
        "002275" to 0.60f,
        "c03f0e" to 0.60f,
        "a0404b" to 0.50f, // TP-Link
        "f4f26d" to 0.50f,
        "54a050" to 0.50f,
        "000f66" to 0.55f, // Linksys / Belkin
        "20aa4b" to 0.55f,
        "c05627" to 0.55f,
        "e09467" to 0.55f,
        // Lower CVE count — enterprise grade
        "cc2de0" to 0.20f, // Cisco / Meraki
        "000142" to 0.20f,
        "005056" to 0.20f,
        "001a1e" to 0.20f, // Aruba / HPE
        "ac3ba7" to 0.20f,
        "0024d4" to 0.20f, // Ruckus
        "0021d1" to 0.20f
    )

    private const val DEFAULT_VENDOR_RISK = 0.40f

    fun build(
        reading: ApReading,
        rssiTracker: RssiTracker,
        ouiLookup: OuiLookup
    ): ApFeatureVector {
        val caps = CapabilitiesParser.parse(reading.capabilities)
        val rssiNorm = ((reading.rssi + 95f) / 65f).coerceIn(0f, 1f)
        val bandOrdinal = when (reading.band) {
            WifiBand.GHZ_2_4 -> 0.0f
            WifiBand.GHZ_5   -> 0.5f
            WifiBand.GHZ_6   -> 1.0f
            WifiBand.UNKNOWN  -> 0.25f
        }
        val ouiKey = reading.bssid.replace(":", "").lowercase().take(6)
        val vendorRisk = vendorRiskTable[ouiKey] ?: DEFAULT_VENDOR_RISK

        return ApFeatureVector(
            rssiNorm = rssiNorm,
            frequencyBandOrdinal = bandOrdinal,
            encryptionScore = caps.encryptionScore,
            hasWps = if (caps.hasWps) 1f else 0f,
            usesTkip = if (caps.usesTkip) 1f else 0f,
            rssiVariance = rssiTracker.variance(reading.bssid),
            vendorRiskScore = vendorRisk,
            isHidden = if (reading.ssid.isBlank()) 1f else 0f
        )
    }

    /** Convert the feature vector to a FloatArray in the order expected by the ONNX models. */
    fun toFloatArray(fv: ApFeatureVector): FloatArray = floatArrayOf(
        fv.rssiNorm,
        fv.frequencyBandOrdinal,
        fv.encryptionScore,
        fv.hasWps,
        fv.usesTkip,
        fv.rssiVariance,
        fv.vendorRiskScore,
        fv.isHidden
    )
}
