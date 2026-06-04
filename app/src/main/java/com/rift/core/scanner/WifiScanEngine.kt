package com.rift.core.scanner

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.rift.core.data.ApReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi scanning engine that combines active scan requests with passive polling.
 *
 * Android 9+ throttles WifiManager.startScan() to 4 calls/2 min in foreground.
 * We request a scan every 30s (well within limits) and poll cached results
 * every 1.5s for responsive updates. This gives 1–4s effective refresh rate
 * at walking pace.
 */
@Singleton
class WifiScanEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    companion object {
        private const val PASSIVE_POLL_INTERVAL_MS = 1500L
        private const val ACTIVE_SCAN_INTERVAL_MS = 30_000L
        private const val MIN_RSSI_THRESHOLD = -95
    }

    private var lastActiveScanTime = 0L

    /**
     * Emits lists of ApReading every ~1.5s.
     * Requests an active scan every 30s to keep the OS cache fresh,
     * and reads cached results between active scans.
     */
    fun scanResultsFlow(): Flow<List<ApReading>> = flow {
        while (true) {
            maybeRequestScan()
            val results = getScanResults()
            if (results.isNotEmpty()) {
                emit(results)
            }
            delay(PASSIVE_POLL_INTERVAL_MS)
        }
    }

    /**
     * Requests an active WiFi scan if enough time has passed since the last one.
     * Respects Android's 4-calls/2-min throttle by spacing requests 30s apart.
     */
    private fun maybeRequestScan() {
        val now = System.currentTimeMillis()
        if (now - lastActiveScanTime >= ACTIVE_SCAN_INTERVAL_MS) {
            try {
                @Suppress("DEPRECATION")
                val success = wifiManager.startScan()
                if (success) {
                    lastActiveScanTime = now
                    Timber.d("Active WiFi scan requested")
                } else {
                    Timber.w("WiFi scan request rejected by OS (throttled)")
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Missing permission to request WiFi scan")
            } catch (e: Exception) {
                Timber.e(e, "Failed to request WiFi scan")
            }
        }
    }

    /**
     * Returns the current cached scan results from WifiManager.
     */
    @Suppress("DEPRECATION")
    fun getScanResults(): List<ApReading> {
        return try {
            wifiManager.scanResults
                ?.filter { it.level >= MIN_RSSI_THRESHOLD }
                ?.map { scanResult ->
                    ApReading(
                        bssid = scanResult.BSSID ?: "",
                        ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            scanResult.wifiSsid?.toString()?.trim('"') ?: ""
                        } else {
                            @Suppress("DEPRECATION")
                            scanResult.SSID?.trim('"') ?: ""
                        },
                        rssi = scanResult.level,
                        frequencyMhz = scanResult.frequency,
                        capabilities = scanResult.capabilities ?: ""
                    )
                }
                ?.sortedByDescending { it.rssi }
                ?: emptyList()
        } catch (e: SecurityException) {
            Timber.e(e, "Missing WiFi scan permission")
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Error reading scan results")
            emptyList()
        }
    }

    /**
     * Returns true if WiFi is enabled and scan results are available.
     */
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    /**
     * Returns info about the currently connected WiFi network, or null if not connected.
     */
    fun getConnectedWifi(): ConnectedWifi? {
        return try {
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo ?: return null
            val bssid = wifiInfo.bssid ?: return null
            @Suppress("DEPRECATION")
            val ssid = wifiInfo.ssid?.trim('"') ?: ""
            if (bssid.isBlank() || ssid.isBlank()) return null
            ConnectedWifi(
                ssid = ssid,
                bssid = bssid,
                rssi = wifiInfo.rssi,
                frequencyMhz = wifiInfo.frequency,
                linkSpeedMbps = wifiInfo.linkSpeed
            )
        } catch (e: Exception) {
            Timber.e(e, "Error reading connected WiFi info")
            null
        }
    }

    /**
     * Returns the BSSID of the currently connected AP, or null if not connected.
     */
    fun getConnectedBssid(): String? {
        return try {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo?.bssid
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Info about the currently connected WiFi network.
 */
data class ConnectedWifi(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val linkSpeedMbps: Int
)
