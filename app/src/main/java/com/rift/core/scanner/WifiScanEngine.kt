package com.rift.core.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import com.rift.core.data.ApReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi scanning engine that reads passive scan results from WifiManager.
 *
 * Android 9+ throttles WifiManager.startScan() to 4 calls/2 min in foreground.
 * We work around this by reading cached getScanResults() on a timer — the OS
 * scans continuously in the background anyway. This gives 1–4s refresh rate
 * at walking pace, which is more than enough.
 */
@Singleton
class WifiScanEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    companion object {
        private const val PASSIVE_POLL_INTERVAL_MS = 1500L
        private const val MIN_RSSI_THRESHOLD = -95
    }

    /**
     * Emits lists of ApReading every ~1.5s using cached passive scan results.
     * No active scanning — respects Android throttling limits.
     */
    fun scanResultsFlow(): Flow<List<ApReading>> = flow {
        while (true) {
            val results = getScanResults()
            if (results.isNotEmpty()) {
                emit(results)
            }
            delay(PASSIVE_POLL_INTERVAL_MS)
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
