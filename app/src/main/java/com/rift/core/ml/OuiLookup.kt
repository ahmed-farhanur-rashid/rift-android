package com.rift.core.ml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the IEEE OUI CSV asset once and provides O(1) vendor name lookups.
 *
 * Asset: app/src/main/assets/oui.csv
 * Format: Registry,Assignment,Organization Name,Organization Address
 * Example: MA-L,FCECDA,Apple Inc,"1 Infinite Loop Cupertino CA US 95014"
 *
 * The CSV is loaded lazily on first call to [lookup] and cached for the app
 * lifetime. No network calls; entirely local asset lookup.
 */
@Singleton
class OuiLookup @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // OUI hex prefix (6 uppercase hex chars, no colons) → vendor name
    private val table: HashMap<String, String> by lazy { loadTable() }

    /**
     * Looks up the vendor name for the given BSSID.
     *
     * @param bssid MAC address in any common format (with or without colons/dashes)
     * @return vendor name string, or null if the OUI is not in the table
     */
    fun lookup(bssid: String): String? {
        val key = bssid.replace(":", "").replace("-", "").uppercase().take(6)
        return if (key.length == 6) table[key] else null
    }

    private fun loadTable(): HashMap<String, String> {
        val map = HashMap<String, String>(35_000)
        try {
            context.assets.open("oui.csv").bufferedReader().use { reader ->
                // Skip header line
                reader.readLine()
                reader.lineSequence().forEach { line ->
                    // Format: Registry,Assignment,Organization Name,Organization Address
                    val parts = line.split(",", limit = 4)
                    if (parts.size >= 3) {
                        val assignment = parts[1].trim().uppercase()
                        val orgName = parts[2].trim().removeSurrounding("\"")
                        if (assignment.length == 6) {
                            map[assignment] = orgName
                        }
                    }
                }
            }
            Timber.d("OuiLookup: loaded ${map.size} entries")
        } catch (e: Exception) {
            Timber.e(e, "OuiLookup: failed to load oui.csv — vendor lookup disabled")
        }
        return map
    }
}
