package dev.carapps.parking

import android.content.Context
import android.net.wifi.WifiManager

/**
 * The access points in range, as a fingerprint of a spot.
 *
 * This is the part that has to carry the repeat visits. A garage level has a
 * distinctive set of BSSIDs bleeding down from the building, and unlike GPS it does
 * not care about the concrete overhead — a basement is often a *better* place to
 * fingerprint than a street, since the signal set is stable and shielded from
 * neighbours.
 *
 * Scans are throttled by the platform, so this reads the last results rather than
 * forcing a fresh scan. At the moment of parking the phone has usually just scanned
 * anyway, having lost the car's network.
 */
class WifiFingerprint(private val context: Context) {

    fun capture(): Map<String, Int> = runCatching {
        val manager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        manager.scanResults
            .sortedByDescending { it.level }
            .take(MAX_APS)
            .associate { it.BSSID to it.level }
    }.getOrDefault(emptyMap())

    private companion object {
        /** Enough to identify a spot; beyond this the weak tail is mostly noise. */
        const val MAX_APS = 25
    }
}
