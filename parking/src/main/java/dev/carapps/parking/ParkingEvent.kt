package dev.carapps.parking

import org.json.JSONArray
import org.json.JSONObject

/**
 * One drive, recorded from the car's Bluetooth connecting to it disconnecting.
 *
 * Deliberately raw. This build exists to answer whether a floor can be told from
 * these signals at all, so it stores the measurements rather than a conclusion —
 * a wrong estimate baked in now would be indistinguishable from a wrong sensor
 * later.
 */
data class ParkingEvent(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,

    /** Pressure in hPa, sampled through the drive. The floor signal lives here. */
    val pressureSamples: List<PressureSample>,

    /** Cumulative yaw in degrees. Ramps show up as multiples of 360. */
    val yawDegrees: Float,

    /** Last fix before the roof cut it off — which building, not which floor. */
    val lastLocation: Fix?,
    val secondsSinceLastFix: Long?,

    /** BSSID -> level(dBm) at the moment of parking. The return-visit fingerprint. */
    val wifi: Map<String, Int>,

    /** The network the phone was joined to, if any reached the parking level. */
    val connectedWifi: String?,

    /** How stale the Wi-Fi scan was, so a fingerprint taken above ground is detectable. */
    val wifiScanAgeSeconds: Long?,

    /**
     * Cell id -> dBm. The footprint that survives underground in Korea, where
     * garages carry carrier repeaters but neither satellites nor home Wi-Fi.
     */
    val cells: Map<String, Int>,

    /** Ground truth, entered by hand afterwards. Null until then. */
    val actualFloor: String? = null,
) {
    data class PressureSample(val elapsedMs: Long, val hPa: Float)
    data class Fix(val lat: Double, val lon: Double, val accuracy: Float)

    /**
     * Pressure rise from the highest point of the drive to the end, in hPa.
     *
     * Descending raises pressure, so a positive number means the car ended below
     * where it had been. Taking the maximum rather than the start point matters:
     * the drive itself crosses hills, and the descent that counts is the last one.
     */
    val pressureDropHpa: Float?
        get() {
            if (pressureSamples.isEmpty()) return null
            val minimum = pressureSamples.minOf { it.hPa }
            return pressureSamples.last().hPa - minimum
        }

    /** Rough floors below the reference, at a nominal 3 m per level. */
    val estimatedFloorsDown: Float?
        get() = pressureDropHpa?.let { it / HPA_PER_FLOOR }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("yawDegrees", yawDegrees.toDouble())
        put("actualFloor", actualFloor ?: JSONObject.NULL)
        put("secondsSinceLastFix", secondsSinceLastFix ?: JSONObject.NULL)
        put("pressureDropHpa", pressureDropHpa?.toDouble() ?: JSONObject.NULL)
        put("estimatedFloorsDown", estimatedFloorsDown?.toDouble() ?: JSONObject.NULL)
        put(
            "lastLocation",
            lastLocation?.let {
                JSONObject().put("lat", it.lat).put("lon", it.lon).put("accuracy", it.accuracy.toDouble())
            } ?: JSONObject.NULL,
        )
        put(
            "pressureSamples",
            JSONArray().apply {
                pressureSamples.forEach {
                    put(JSONObject().put("t", it.elapsedMs).put("hPa", it.hPa.toDouble()))
                }
            },
        )
        put("wifi", JSONObject().apply { wifi.forEach { (bssid, level) -> put(bssid, level) } })
        put("connectedWifi", connectedWifi ?: JSONObject.NULL)
        put("wifiScanAgeSeconds", wifiScanAgeSeconds ?: JSONObject.NULL)
        put("cells", JSONObject().apply { cells.forEach { (id, dbm) -> put(id, dbm) } })
    }

    companion object {
        /**
         * About 3 m of air per parking level. Real spacing varies by building, which
         * is exactly what repeat visits are meant to calibrate — this constant is a
         * starting guess, not a claim.
         */
        const val HPA_PER_FLOOR = 0.36f

        fun fromJson(json: JSONObject): ParkingEvent {
            val samples = json.optJSONArray("pressureSamples") ?: JSONArray()
            val wifiJson = json.optJSONObject("wifi") ?: JSONObject()
            return ParkingEvent(
                id = json.getLong("id"),
                startedAt = json.getLong("startedAt"),
                endedAt = json.getLong("endedAt"),
                pressureSamples = (0 until samples.length()).map {
                    val sample = samples.getJSONObject(it)
                    PressureSample(sample.getLong("t"), sample.getDouble("hPa").toFloat())
                },
                yawDegrees = json.optDouble("yawDegrees", 0.0).toFloat(),
                lastLocation = json.optJSONObject("lastLocation")?.let {
                    Fix(it.getDouble("lat"), it.getDouble("lon"), it.getDouble("accuracy").toFloat())
                },
                secondsSinceLastFix = if (json.isNull("secondsSinceLastFix")) null
                else json.getLong("secondsSinceLastFix"),
                wifi = wifiJson.keys().asSequence().associateWith { wifiJson.getInt(it) },
                connectedWifi = if (json.isNull("connectedWifi")) null
                else json.getString("connectedWifi"),
                wifiScanAgeSeconds = if (json.isNull("wifiScanAgeSeconds")) null
                else json.getLong("wifiScanAgeSeconds"),
                cells = (json.optJSONObject("cells") ?: JSONObject()).let { cellJson ->
                    cellJson.keys().asSequence().associateWith { cellJson.getInt(it) }
                },
                actualFloor = if (json.isNull("actualFloor")) null else json.getString("actualFloor"),
            )
        }
    }
}
