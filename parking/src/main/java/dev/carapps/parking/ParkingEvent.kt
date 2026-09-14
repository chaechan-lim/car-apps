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
     * Pressure rise from the last satellite fix to the end of the recording.
     *
     * Superseded by [descent] and kept only as a record of a wrong idea: satellites
     * turned out to fall silent anywhere from twelve seconds to six minutes before the
     * car stopped, so this measured a whole ramp on one drive and a parked car on the
     * next.
     */
    val entryRiseHpa: Float?
        get() {
            val segment = samplesSinceEntry
            if (segment.size < 2) return null
            return segment.last().hPa - segment.first().hPa
        }

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
     * The climb into the parking level: where the car arrived, and where it went in.
     *
     * Two end points had to be found rather than assumed, and the recordings decided
     * both.
     *
     * The arrival is not the last sample. The recording ends when the car's Bluetooth
     * drops, which can be minutes after the car stopped and after the walk up out of
     * the garage — and a climb up the stairs cancels the drive down the ramp exactly,
     * which is how one B5F park measured 2.26 hPa and another, in the same garage,
     * measured 0.00. So the arrival is the highest pressure reached, searched over the
     * last [PEAK_WINDOW_MS] only: a search over ten minutes instead found the start of
     * a short drive that had begun in a deeper garage than it ended in.
     *
     * The entrance is not a fixed time earlier either. One garage's descent takes
     * ninety seconds and another's four hundred, so a fixed window clipped one or
     * swallowed the hill before the other. Instead the walk back from the arrival
     * continues while the climb holds, and stops where pressure turns back down by
     * more than [CLIMB_TOLERANCE_HPA] — the point where the car was last going down
     * rather than up.
     */
    val descent: Descent? by lazy(LazyThreadSafetyMode.NONE) { computeDescent() }

    private fun computeDescent(): Descent? {
        val samples = smoothed
        if (samples.size < 3) return null
        val end = samples.last().elapsedMs

        val tail = samples.indices.filter { samples[it].elapsedMs >= end - PEAK_WINDOW_MS }
        if (tail.size < 2) return null
        val deepest = tail.max { samples[it].hPa }
        val arrival = samples[deepest]

        var lowest = deepest
        var index = deepest
        while (index >= 0 && arrival.elapsedMs - samples[index].elapsedMs <= MAX_CLIMB_MS) {
            val hPa = samples[index].hPa
            if (hPa < samples[lowest].hPa) {
                lowest = index
            } else if (hPa > samples[lowest].hPa + CLIMB_TOLERANCE_HPA) {
                break
            }
            index--
        }
        val entrance = samples[lowest]

        return Descent(
            riseHpa = arrival.hPa - entrance.hPa,
            yawDeg = arrival.yawDeg - entrance.yawDeg,
            climbMs = arrival.elapsedMs - entrance.elapsedMs,
            arrivedBeforeEndMs = end - arrival.elapsedMs,
        )
    }

    /** The last index holding the largest value, so a plateau resolves to its end. */
    private inline fun List<Int>.max(value: (Int) -> Float): Int {
        var best = first()
        forEach { if (value(it) >= value(best)) best = it }
        return best
    }

    /**
     * One reading of the climb.
     *
     * [arrivedBeforeEndMs] is the diagnostic that matters most: it is how long the
     * car's Bluetooth stayed up after the car reached its lowest point, and until that
     * was visible the estimate looked like a broken barometer rather than a misplaced
     * end point.
     */
    data class Descent(
        val riseHpa: Float,
        val yawDeg: Float,
        val climbMs: Long,
        val arrivedBeforeEndMs: Long,
    )

    val descentRiseHpa: Float? get() = descent?.riseHpa

    /**
     * Whether the car went under a roof at all, decided by how long satellites stayed
     * silent before the recording ended.
     *
     * Pressure cannot answer this on its own: over half the surface parks recorded a
     * climb of half a level or more in their last minutes, because a road that runs
     * downhill into a destination looks exactly like a ramp. Satellite silence does
     * answer it — across the labelled drives, surface parks fell silent for at most
     * 64 seconds and underground parks for at least 101, one exception each way.
     *
     * Null when no satellite fix was ever seen, which leaves only the pressure.
     */
    val wentUnderground: Boolean?
        get() = gpsLostBeforeEndMs?.let { it >= UNDERGROUND_GPS_SILENCE_MS }

    /**
     * Yaw turned between the entrance and the deepest point.
     *
     * Kept as a second dimension, though it has not earned a place in the estimate: a
     * barometer cannot tell a ramp from a hill, but several garages here descend in a
     * straight line and read almost no turn at all.
     */
    val descentYawDeg: Float? get() = descent?.yawDeg

    /** How long the climb itself took — ninety seconds in one garage, four hundred in another. */
    val climbSeconds: Long? get() = descent?.let { it.climbMs / 1000 }

    /** How long the car's radio stayed up after the car reached its lowest point. */
    val arrivedBeforeEndMs: Long? get() = descent?.arrivedBeforeEndMs

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
        put("yawSinceEntry", yawSinceEntry?.toDouble() ?: JSONObject.NULL)
        put("lastGpsFixElapsedMs", lastGpsFixElapsedMs ?: JSONObject.NULL)
        put("gpsLostBeforeEndMs", gpsLostBeforeEndMs ?: JSONObject.NULL)
        put("wentUnderground", wentUnderground ?: JSONObject.NULL)
        put("durationMs", durationMs ?: JSONObject.NULL)
        put("descentRiseHpa", descentRiseHpa?.toDouble() ?: JSONObject.NULL)
        put("climbSeconds", climbSeconds ?: JSONObject.NULL)
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
         * How far back to look for the deepest point.
         *
         * Short, because parking is the last thing that happens. Ten minutes of
         * searching found the beginning of a ten-minute drive that had started in a
         * deeper garage than it finished in, and called that the arrival.
         */
        const val PEAK_WINDOW_MS = 120_000L

        /**
         * How far pressure may fall back before the climb counts as over.
         *
         * Half a hPa is roughly one level. Below that the walk back stopped inside
         * long ramps at the dip between two turns; above it, it ran out of the garage
         * and back onto the road.
         */
        const val CLIMB_TOLERANCE_HPA = 0.50f

        /** A hard stop on the walk back. No garage ramp takes twenty minutes. */
        const val MAX_CLIMB_MS = 1_200_000L

        /**
         * Satellite silence that means a roof.
         *
         * Fitted, not guessed: of the labelled drives, surface parks went silent for
         * at most 64 s and underground parks for at least 101 s, with a single
         * exception on each side.
         */
        const val UNDERGROUND_GPS_SILENCE_MS = 90_000L

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
