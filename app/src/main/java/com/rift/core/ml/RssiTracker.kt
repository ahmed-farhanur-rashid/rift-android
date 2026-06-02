package com.rift.core.ml

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Maintains a fixed-size ring buffer of recent RSSI readings per BSSID.
 *
 * Used to compute RSSI variance for the ML feature vector and to provide
 * the AnomalyDetector with historical context per access point.
 *
 * Thread-safety: synchronized on [buffers] map to handle concurrent scan
 * result delivery from the scan loop coroutine. All operations are brief.
 */
@Singleton
class RssiTracker @Inject constructor() {

    companion object {
        private const val BUFFER_CAPACITY = 10
    }

    // bssid → circular array of the last BUFFER_CAPACITY RSSI values (dBm)
    private val buffers = mutableMapOf<String, IntArray>()
    private val writePos = mutableMapOf<String, Int>()
    private val counts = mutableMapOf<String, Int>()

    /** Record one new RSSI observation for the given BSSID. */
    fun update(bssid: String, rssi: Int) {
        synchronized(buffers) {
            val buf = buffers.getOrPut(bssid) { IntArray(BUFFER_CAPACITY) }
            val pos = writePos.getOrDefault(bssid, 0)
            buf[pos % BUFFER_CAPACITY] = rssi
            writePos[bssid] = pos + 1
            counts[bssid] = minOf((counts.getOrDefault(bssid, 0) + 1), BUFFER_CAPACITY)
        }
    }

    /**
     * Returns the population variance of the stored RSSI readings for [bssid],
     * normalised to [0, 1] by dividing by 1225.0 (≈ 35² = max plausible std dev²
     * over the -30 to -95 dBm range).
     *
     * Returns 0.0 if fewer than 2 observations are available.
     */
    fun variance(bssid: String): Float {
        synchronized(buffers) {
            val buf = buffers[bssid] ?: return 0f
            val n = counts.getOrDefault(bssid, 0)
            if (n < 2) return 0f

            val mean = (0 until n).sumOf { buf[it % BUFFER_CAPACITY] } / n.toDouble()
            val variance = (0 until n).sumOf { i ->
                val diff = buf[i % BUFFER_CAPACITY] - mean
                diff * diff
            } / n.toDouble()

            return (variance / 1225.0).toFloat().coerceIn(0f, 1f)
        }
    }

    /**
     * Returns the ordered history list (oldest first) for the given BSSID.
     * Returns an empty list if the BSSID has never been seen.
     */
    fun history(bssid: String): List<Int> {
        synchronized(buffers) {
            val buf = buffers[bssid] ?: return emptyList()
            val n = counts.getOrDefault(bssid, 0)
            val pos = writePos.getOrDefault(bssid, 0)
            return if (n < BUFFER_CAPACITY) {
                buf.take(n).toList()
            } else {
                // Oldest entry is at writePos % BUFFER_CAPACITY
                val start = pos % BUFFER_CAPACITY
                (0 until BUFFER_CAPACITY).map { buf[(start + it) % BUFFER_CAPACITY] }
            }
        }
    }

    /** Latest recorded RSSI for [bssid], or null if not yet seen. */
    fun latestRssi(bssid: String): Int? {
        synchronized(buffers) {
            val buf = buffers[bssid] ?: return null
            val n = counts.getOrDefault(bssid, 0)
            if (n == 0) return null
            val pos = writePos.getOrDefault(bssid, 0)
            return buf[(pos - 1 + BUFFER_CAPACITY) % BUFFER_CAPACITY]
        }
    }

    /** How many unique BSSIDs have been recorded. */
    fun knownBssidCount(): Int = synchronized(buffers) { buffers.size }

    /** All BSSIDs that have been seen at least once. */
    fun knownBssids(): Set<String> = synchronized(buffers) { buffers.keys.toSet() }
}
