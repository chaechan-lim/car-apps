package dev.carapps.parking

import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager

/**
 * The cells in range, as a second fingerprint that works below ground.
 *
 * Korean apartment garages are shared structures with carrier repeaters installed
 * throughout, so a basement usually has service where it has no satellites and
 * little Wi-Fi — households are too far above to reach the parking levels. That
 * makes the visible cell set the more dependable footprint of the two down there,
 * and it costs no permission the app does not already hold.
 *
 * Repeaters also make this finer-grained than street-level cell positioning would
 * suggest: a garage often sits under its own repeater rather than the macro cell
 * outside.
 */
class CellFingerprint(private val context: Context) {

    fun capture(): Map<String, Int> = runCatching {
        val telephony = context.applicationContext
            .getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("MissingPermission")
        telephony.allCellInfo
            .orEmpty()
            .mapNotNull { it.toEntry() }
            .sortedByDescending { it.second }
            .take(MAX_CELLS)
            .toMap()
    }.getOrDefault(emptyMap())

    private fun CellInfo.toEntry(): Pair<String, Int>? = when (this) {
        is CellInfoLte -> {
            val id = cellIdentity
            "lte:${id.ci}/${id.pci}" to cellSignalStrength.dbm
        }

        is CellInfoWcdma -> {
            val id = cellIdentity
            "wcdma:${id.cid}/${id.psc}" to cellSignalStrength.dbm
        }

        is CellInfoGsm -> {
            val id = cellIdentity
            "gsm:${id.cid}/${id.lac}" to cellSignalStrength.dbm
        }

        else -> nrEntry()
    }

    /** 5G identifiers moved behind a separate identity type; guarded for older devices. */
    private fun CellInfo.nrEntry(): Pair<String, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val info = this as? CellInfoNr ?: return null
        val identity = info.cellIdentity as? CellIdentityNr ?: return null
        return "nr:${identity.nci}/${identity.pci}" to info.cellSignalStrength.dbm
    }

    private companion object {
        const val MAX_CELLS = 12
    }
}
