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

    /**
     * Which garage this was, named by hand. Null until then.
     *
     * The floor label alone cannot be calibrated against anything, because levels are
     * spaced differently in different buildings. A name ties the drive to the other
     * drives that share a ramp, and only the driver knows that two coarse fixes a
     * hundred metres apart are the same place.
     */
    val site: String? = null,
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
     * The samples with single-reading spikes taken out.
     *
     * The estimator below works from an extreme rather than an average, which makes
     * it sensitive to exactly the sort of one-sample jump a door closing produces. A
     * three-wide median removes those without touching a real ramp, which lasts tens
     * of samples.
     */
    val smoothed: List<Sample>
        get() = pressureSamples.mapIndexed { index, sample ->
            if (index == 0 || index == pressureSamples.lastIndex) {
                sample
            } else {
                val window = pressureSamples.subList(index - 1, index + 2).map { it.hPa }
                sample.copy(hPa = median(window))
            }
        }

    /**
     * The deepest point in the last [windowMs] of the recording, and the shallowest
     * point before it.
     *
     * The recording does not end where the car stops. It ends when the car's
     * Bluetooth drops, and that can be minutes later, with the phone already walking
     * up out of the garage — a climb back up the stairs cancels the drive down the
     * ramp exactly, which is how one B5F park measured 2.26 hPa and another, in the
     * same garage, measured 0.00.
     *
     * So the arrival is not the last sample: it is the highest pressure reached. The
     * entrance is the lowest pressure before that. Everything after the peak is the
     * walk out, and is deliberately ignored.
     */
    fun descent(windowMs: Long): Descent? {
        val samples = smoothed
        if (samples.size < 2) return null
        val end = samples.last().elapsedMs
        val window = samples.filter { it.elapsedMs >= end - windowMs }
        if (window.size < 2) return null

        val peakIndex = window.indices.maxBy { window[it].hPa }
        val approach = window.subList(0, peakIndex + 1)
        val low = approach.minBy { it.hPa }
        val peak = window[peakIndex]
        return Descent(
            riseHpa = peak.hPa - low.hPa,
            yawDeg = peak.yawDeg - low.yawDeg,
            startedBeforeEndMs = end - low.elapsedMs,
            arrivedBeforeEndMs = end - peak.elapsedMs,
        )
    }

    /**
     * One reading of the descent.
     *
     * [arrivedBeforeEndMs] is the diagnostic that matters most: it is how long the
     * car's Bluetooth stayed up after the car stopped, and until this was visible the
     * estimate looked like a broken barometer rather than a misplaced end point.
     */
    data class Descent(
        val riseHpa: Float,
        val yawDeg: Float,
        val startedBeforeEndMs: Long,
        val arrivedBeforeEndMs: Long,
    )

    val descentRiseHpa: Float? get() = descent(RAMP_WINDOW_MS)?.riseHpa
    val descentRiseLongHpa: Float? get() = descent(LONG_RAMP_WINDOW_MS)?.riseHpa
    val descentFloorsDown: Float? get() = descentRiseHpa?.let { it / HPA_PER_FLOOR }

    /**
     * Yaw turned between the entrance and the deepest point.
     *
     * The second dimension, and the one that tells a ramp from a hill: a barometer
     * cannot distinguish driving down into a garage from driving down a slope, but a
     * garage is reached by spiralling and a road is not.
     */
    val descentYawDeg: Float? get() = descent(RAMP_WINDOW_MS)?.yawDeg

    /** How long before the recording ended the climb began. */
    val descentStartedBeforeEndMs: Long? get() = descent(RAMP_WINDOW_MS)?.startedBeforeEndMs

    /** How long the car's radio stayed up after the car reached its lowest point. */
    val arrivedBeforeEndMs: Long? get() = descent(RAMP_WINDOW_MS)?.arrivedBeforeEndMs

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
        put("site", site ?: JSONObject.NULL)
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
        put("arrivedBeforeEndMs", arrivedBeforeEndMs ?: JSONObject.NULL)
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
                site = if (json.isNull("site")) null else json.getString("site"),
            )
        }
    }
}
