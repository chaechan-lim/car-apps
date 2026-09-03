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

    /**
     * Age of the freshest scan result, in seconds.
     *
     * The platform throttles scans, so these results can predate the descent — a
     * fingerprint taken from the street above would look like a basement one and
     * quietly poison the data. Recording the age makes that detectable instead.
     */
    fun scanAgeSeconds(): Long? = runCatching {
        val manager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val newest = manager.scanResults.maxOfOrNull { it.timestamp } ?: return null
        (android.os.SystemClock.elapsedRealtime() * 1000 - newest) / 1_000_000
    }.getOrNull()

    /**
     * The network the phone is actually joined to, if any.
     *
     * Usually null in a Korean apartment garage: those are large shared structures
     * and household networks do not reach the parking levels. Kept because an
     * office garage sometimes does have coverage, and because a null is itself a
     * distinguishing observation between the two sites.
     */
    fun connectedNetwork(): String? = runCatching {
        val manager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = manager.connectionInfo ?: return null
        val bssid = info.bssid ?: return null
        // The platform hands back this placeholder when it will not disclose the AP.
        if (bssid == "02:00:00:00:00:00") return null
        @Suppress("DEPRECATION")
        val ssid = info.ssid?.trim('"').orEmpty()
        if (ssid.isEmpty() || ssid == "<unknown ssid>") bssid else "$ssid ($bssid)"
    }.getOrNull()

    private companion object {
        /** Enough to identify a spot; beyond this the weak tail is mostly noise. */
        const val MAX_APS = 25
    }
}
