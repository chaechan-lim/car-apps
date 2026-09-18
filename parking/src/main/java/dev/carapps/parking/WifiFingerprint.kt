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
 *
 * Every failure here is logged rather than absorbed. Thirty-eight recorded drives
 * reported zero access points and it was put down to scan throttling; the real cause
 * was a missing ACCESS_WIFI_STATE declaration, and the only reason it went unnoticed
 * for weeks is that the exception was being swallowed into an empty map. An empty
 * result and a failed call must not look the same.
 */
class WifiFingerprint(private val context: Context) {

    // Suppressed at the call site because [attempt] catches and logs a rejected
    // permission; lint cannot see that through the lambda.
    @android.annotation.SuppressLint("MissingPermission")
    fun capture(): Map<String, Int> = attempt("scanResults") { manager ->
        @Suppress("DEPRECATION")
        manager.scanResults
            .sortedByDescending { it.level }
            .take(MAX_APS)
            .associate { it.BSSID to it.level }
    } ?: emptyMap()

    /**
     * Age of the freshest scan result, in seconds.
     *
     * The platform throttles scans, so these results can predate the descent — a
     * fingerprint taken from the street above would look like a basement one and
     * quietly poison the data. Recording the age makes that detectable instead.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun scanAgeSeconds(): Long? = attempt("scanAge") { manager ->
        @Suppress("DEPRECATION")
        val newest = manager.scanResults.maxOfOrNull { it.timestamp } ?: return@attempt null
        (android.os.SystemClock.elapsedRealtime() * 1000 - newest) / 1_000_000
    }

    /**
     * The network the phone is actually joined to, if any.
     *
     * Usually null in a Korean apartment garage: those are large shared structures
     * and household networks do not reach the parking levels. Kept because an
     * office garage sometimes does have coverage, and because a null is itself a
     * distinguishing observation between the two sites.
     */
    fun connectedNetwork(): String? = attempt("connectionInfo") { manager ->
        @Suppress("DEPRECATION")
        val info = manager.connectionInfo ?: return@attempt null
        val bssid = info.bssid ?: return@attempt null
        // The platform hands back this placeholder when it will not disclose the AP.
        if (bssid == "02:00:00:00:00:00") return@attempt null
        @Suppress("DEPRECATION")
        val ssid = info.ssid?.trim('"').orEmpty()
        if (ssid.isEmpty() || ssid == "<unknown ssid>") bssid else "$ssid ($bssid)"
    }

    /**
     * Runs a Wi-Fi call, and says so in the log when it fails.
     *
     * The failure must not be indistinguishable from a null answer. That is the
     * mistake this whole class is a correction of.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun <T> attempt(what: String, block: (WifiManager) -> T?): T? = try {
        val manager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        block(manager)
    } catch (t: Throwable) {
        DebugLog.write(context, "wifi $what failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private companion object {
        /** Enough to identify a spot; beyond this the weak tail is mostly noise. */
        const val MAX_APS = 25
    }
}
