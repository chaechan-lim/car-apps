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

    /** Pressure in hPa and cumulative yaw, sampled through the drive. */
    val pressureSamples: List<Sample>,

    /** Cumulative yaw over the whole drive. Mostly road curves; see [yawSinceEntry]. */
    val yawDegrees: Float,

    /** Best fix of the drive — which building, not which floor. */
    val lastLocation: Fix?,
    val secondsSinceLastFix: Long?,

    /**
     * When satellites were last seen, measured from the start of the drive.
     *
     * This is the entry marker. Network fixes keep arriving underground from cell
     * towers, so only a satellite fix going stale marks the ramp — mixing the two
     * providers is what made the first recordings unreadable.
     */
    val lastGpsFixElapsedMs: Long?,

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
    data class Sample(val elapsedMs: Long, val hPa: Float, val yawDeg: Float)
    data class Fix(val lat: Double, val lon: Double, val accuracy: Float)

    /**
     * Pressure rise measured over the whole drive.
     *
     * Kept only for comparison. It reads terrain, not floors: a drive down from
     * higher ground registers a large rise having never left the surface, which is
     * how a ground-floor park first came back estimated at eight levels down.
     */
    val wholeDriveRiseHpa: Float?
        get() {
            if (pressureSamples.isEmpty()) return null
            return pressureSamples.last().hPa - pressureSamples.minOf { it.hPa }
        }

    /** Samples from the moment satellites were lost — the descent, without the terrain. */
    val samplesSinceEntry: List<Sample>
        get() {
            val entry = lastGpsFixElapsedMs ?: return emptyList()
            return pressureSamples.filter { it.elapsedMs >= entry }
        }

    /**
     * Pressure rise since entering the structure, in hPa. The floor signal proper.
     *
     * Measured from the last satellite fix rather than the drive's high point, so
     * hills along the way cancel out and only what happened under the roof is left.
     */
    val entryRiseHpa: Float?
        get() {
            val segment = samplesSinceEntry
            if (segment.size < 2) return null
            return segment.last().hPa - segment.first().hPa
        }

    /** Rough levels below the entrance, at a nominal 3 m each. */
    val estimatedFloorsDown: Float?
        get() = entryRiseHpa?.let { it / HPA_PER_FLOOR }

    /**
     * How long before parking satellites were lost, in ms.
     *
     * Measured against the last sample rather than the wall clock, so it stays right
     * on records whose start time was lost to a process death. This is the number
     * that says whether [entryRiseHpa] had anything to measure: several drives kept
     * a fix until seconds before stopping, and their entry slice is a couple of
     * samples of a parked car.
     */
    val gpsLostBeforeEndMs: Long?
        get() {
            val lost = lastGpsFixElapsedMs ?: return null
            val end = pressureSamples.lastOrNull()?.elapsedMs ?: return null
            return (end - lost).coerceAtLeast(0)
        }

    /** How long the drive lasted, taken from the samples for the same reason. */
    val durationMs: Long?
        get() = pressureSamples.lastOrNull()?.elapsedMs

    /**
     * Pressure once the car has stopped, with the last seconds averaged.
     *
     * A single final sample is a coin toss: the door opening and the phone being
     * picked up both move the reading, and the descent is measured against this.
     */
    val settledHpa: Float?
        get() {
            if (pressureSamples.isEmpty()) return null
            val end = pressureSamples.last().elapsedMs
            val tail = pressureSamples.filter { it.elapsedMs >= end - SETTLE_WINDOW_MS }
            return median(tail.ifEmpty { listOf(pressureSamples.last()) }.map { it.hPa })
        }

    /**
     * Rise from the lowest pressure within [windowMs] of parking up to the stop.
     *
     * This is the estimator that does not need GPS. The descent into a garage is the
     * last sustained climb of a drive, so measuring from the tail's high point
     * (lowest pressure) forward reads the ramp regardless of when the satellites
     * happened to drop out — the marker that turned out to fire anywhere from zero
     * to three minutes before the car stopped.
     *
     * The window is the whole assumption: too short clips a slow queue down the
     * ramp, too long swallows the hill before the building. Two are reported side by
     * side rather than one being chosen in advance.
     */
    fun tailRiseHpa(windowMs: Long): Float? {
        val settled = settledHpa ?: return null
        val end = pressureSamples.last().elapsedMs
        val low = pressureSamples
            .filter { it.elapsedMs >= end - windowMs }
            .minOfOrNull { it.hPa } ?: return null
        return settled - low
    }

    val descentRiseHpa: Float? get() = tailRiseHpa(RAMP_WINDOW_MS)
    val descentRiseLongHpa: Float? get() = tailRiseHpa(LONG_RAMP_WINDOW_MS)
    val descentFloorsDown: Float? get() = descentRiseHpa?.let { it / HPA_PER_FLOOR }

    /**
     * Yaw turned over the same window the descent is measured in.
     *
     * The second dimension, and the one that tells a ramp from a hill: a barometer
     * cannot distinguish driving down into a garage from driving down a slope, but
     * a garage is reached by spiralling and a road is not.
     */
    val descentYawDeg: Float?
        get() {
            if (pressureSamples.isEmpty()) return null
            val end = pressureSamples.last().elapsedMs
            val window = pressureSamples.filter { it.elapsedMs >= end - RAMP_WINDOW_MS }
            if (window.size < 2) return null
            return window.last().yawDeg - window.first().yawDeg
        }

    /**
     * How long before stopping the climb began, in ms.
     *
     * Reads as a sanity check on the window: a value pinned to the window's own
     * length means the descent started earlier than the window can see.
     */
    val descentStartedBeforeEndMs: Long?
        get() {
            if (pressureSamples.isEmpty()) return null
            val end = pressureSamples.last().elapsedMs
            val low = pressureSamples
                .filter { it.elapsedMs >= end - LONG_RAMP_WINDOW_MS }
                .minByOrNull { it.hPa } ?: return null
            return end - low.elapsedMs
        }

    /** Yaw accumulated after entry only, where a spiral ramp actually shows up. */
    val yawSinceEntry: Float?
        get() {
            val segment = samplesSinceEntry
            if (segment.size < 2) return null
            return segment.last().yawDeg - segment.first().yawDeg
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("yawDegrees", yawDegrees.toDouble())
        put("actualFloor", actualFloor ?: JSONObject.NULL)
        put("secondsSinceLastFix", secondsSinceLastFix ?: JSONObject.NULL)
        put("wholeDriveRiseHpa", wholeDriveRiseHpa?.toDouble() ?: JSONObject.NULL)
        put("entryRiseHpa", entryRiseHpa?.toDouble() ?: JSONObject.NULL)
        put("estimatedFloorsDown", estimatedFloorsDown?.toDouble() ?: JSONObject.NULL)
        put("yawSinceEntry", yawSinceEntry?.toDouble() ?: JSONObject.NULL)
        put("lastGpsFixElapsedMs", lastGpsFixElapsedMs ?: JSONObject.NULL)
        put("gpsLostBeforeEndMs", gpsLostBeforeEndMs ?: JSONObject.NULL)
        put("durationMs", durationMs ?: JSONObject.NULL)
        put("descentRiseHpa", descentRiseHpa?.toDouble() ?: JSONObject.NULL)
        put("descentRiseLongHpa", descentRiseLongHpa?.toDouble() ?: JSONObject.NULL)
        put("descentFloorsDown", descentFloorsDown?.toDouble() ?: JSONObject.NULL)
        put("descentStartedBeforeEndMs", descentStartedBeforeEndMs ?: JSONObject.NULL)
        put("descentYawDeg", descentYawDeg?.toDouble() ?: JSONObject.NULL)
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
                    put(
                        JSONObject()
                            .put("t", it.elapsedMs)
                            .put("hPa", it.hPa.toDouble())
                            .put("yaw", it.yawDeg.toDouble())
                    )
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

        /** The car is stopped over this; averaging it removes the door-opening blip. */
        const val SETTLE_WINDOW_MS = 30_000L

        /** A ramp taken at walking pace: five levels in four minutes. */
        const val RAMP_WINDOW_MS = 240_000L

        /** The same ramp behind a queue on a Saturday. */
        const val LONG_RAMP_WINDOW_MS = 480_000L

        private fun median(values: List<Float>): Float =
            values.sorted()[values.size / 2]

        fun fromJson(json: JSONObject): ParkingEvent {
            val samples = json.optJSONArray("pressureSamples") ?: JSONArray()
            val wifiJson = json.optJSONObject("wifi") ?: JSONObject()
            return ParkingEvent(
                id = json.getLong("id"),
                startedAt = json.getLong("startedAt"),
                endedAt = json.getLong("endedAt"),
                pressureSamples = (0 until samples.length()).map {
                    val sample = samples.getJSONObject(it)
                    Sample(
                        sample.getLong("t"),
                        sample.getDouble("hPa").toFloat(),
                        sample.optDouble("yaw", 0.0).toFloat(),
                    )
                },
                yawDegrees = json.optDouble("yawDegrees", 0.0).toFloat(),
                lastLocation = json.optJSONObject("lastLocation")?.let {
                    Fix(it.getDouble("lat"), it.getDouble("lon"), it.getDouble("accuracy").toFloat())
                },
                secondsSinceLastFix = if (json.isNull("secondsSinceLastFix")) null
                else json.getLong("secondsSinceLastFix"),
                lastGpsFixElapsedMs = if (json.isNull("lastGpsFixElapsedMs")) null
                else json.getLong("lastGpsFixElapsedMs"),
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
