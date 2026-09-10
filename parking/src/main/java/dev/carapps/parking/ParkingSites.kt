package dev.carapps.parking

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Groups drives by the place they ended, and calibrates each place separately.
 *
 * Pressure per level is not a constant of nature. It is a property of a building:
 * an apartment garage stacks levels about three metres apart, a department store
 * closer to four and a half. The same 2.2 hPa is five levels down in one and four
 * in the other, so a single number converting pressure to floors is wrong
 * everywhere except the building it was fitted to.
 *
 * Note what is *not* a problem. Absolute pressure differs between two places at
 * the same altitude, by weather and by terrain, and by a fixed offset per sensor —
 * but the estimate is a difference measured inside one drive, so all three cancel.
 * What survives is the metres-per-level of the specific ramp, and that is what
 * grouping by site measures.
 */
object ParkingSites {

    /**
     * Coarse on purpose. The fix that reaches a basement entrance is often a ±100 m
     * network fix, so a tight radius would split one garage into several.
     */
    const val RADIUS_M = 150.0

    data class Site(val name: String, val events: List<ParkingEvent>) {

        val labelled: List<ParkingEvent> get() = events.filter { it.actualFloor != null }

        val fix: ParkingEvent.Fix? get() = events.firstNotNullOfOrNull { it.lastLocation }

        /**
         * Metres of air per level here, expressed in hPa and measured rather than
         * assumed. Needs at least one labelled underground park to say anything.
         */
        val hPaPerLevel: Float? get() = hPaPerLevelExcluding(null)

        /**
         * The same, fitted without one drive.
         *
         * Applying a constant to the drive that produced it proves nothing — it
         * reproduces the label by construction. Leaving that drive out turns the
         * number into an actual prediction, which is the only version worth checking
         * against the label.
         */
        fun hPaPerLevelExcluding(id: Long?): Float? {
            val ratios = labelled.mapNotNull { event ->
                if (event.id == id) return@mapNotNull null
                val depth = depthBelowGround(event.actualFloor.orEmpty()) ?: return@mapNotNull null
                if (depth < 1) return@mapNotNull null
                event.descentRiseHpa?.let { it / depth }
            }
            return if (ratios.isEmpty()) null else ratios.average().toFloat()
        }

        /** How many labelled drives the calibration above rests on. */
        val calibrationDrives: Int
            get() = labelled.count {
                (depthBelowGround(it.actualFloor.orEmpty()) ?: 0) >= 1 && it.descentRiseHpa != null
            }
    }

    /**
     * Named drives group by name, which is ground truth; the rest fall in with
     * whichever group they are within [RADIUS_M] of, named or not. So naming one
     * drive at home adopts every other drive that ended there, and nothing has to be
     * relabelled by hand.
     */
    fun group(events: List<ParkingEvent>): List<Site> {
        val names = mutableListOf<String?>()
        val groups = mutableListOf<MutableList<ParkingEvent>>()

        events.filter { !it.site.isNullOrBlank() }.forEach { event ->
            val index = names.indexOfFirst { it.equals(event.site, ignoreCase = true) }
            if (index >= 0) {
                groups[index] += event
            } else {
                names += event.site
                groups += mutableListOf(event)
            }
        }

        events.filter { it.site.isNullOrBlank() }.forEach { event ->
            val fix = event.lastLocation
            val nearest = if (fix == null) {
                null
            } else {
                groups.indices
                    .mapNotNull { index -> distanceTo(fix, groups[index])?.let { index to it } }
                    .filter { it.second <= RADIUS_M }
                    .minByOrNull { it.second }
                    ?.first
            }
            if (nearest != null) {
                groups[nearest] += event
            } else {
                names += null
                groups += mutableListOf(event)
            }
        }

        return groups.mapIndexed { index, group ->
            Site(names[index] ?: autoName(index, group), group.sortedByDescending { it.endedAt })
        }
    }

    /**
     * Distance to the closest member, not to an average.
     *
     * A garage entrance and its exit ramp can be a hundred metres apart and coarse
     * fixes scatter further, so a centroid drifts away from both. Taking the closest
     * group rather than the first one in range also stops two garages a hundred
     * metres apart from being chained together by a drive that sits between them.
     */
    private fun distanceTo(fix: ParkingEvent.Fix, group: List<ParkingEvent>): Double? =
        group.mapNotNull { it.lastLocation }
            .minOfOrNull { distanceMeters(fix, it) }

    private fun autoName(index: Int, group: List<ParkingEvent>): String {
        val letter = ('A' + index % 26)
        val fix = group.firstNotNullOfOrNull { it.lastLocation }
            ?: return "site $letter (no fix)"
        return "site $letter %.4f,%.4f".format(fix.lat, fix.lon)
    }

    /**
     * Levels below ground from a hand-typed label, or null if it does not parse.
     *
     * Above ground is zero rather than a negative: the question is how far down to
     * walk, and every floor at or above the entrance is no walk down at all.
     */
    fun depthBelowGround(label: String): Int? {
        BASEMENT.find(label)?.let { return it.groupValues[1].toIntOrNull() }
        return if (GROUND.matches(label.trim())) 0 else null
    }

    /** Flat-earth, which is exact enough over the tens of metres that matter here. */
    fun distanceMeters(a: ParkingEvent.Fix, b: ParkingEvent.Fix): Double {
        val meanLat = Math.toRadians((a.lat + b.lat) / 2)
        return hypot(
            (a.lat - b.lat) * DEGREE_M,
            (a.lon - b.lon) * DEGREE_M * cos(meanLat),
        )
    }

    private const val DEGREE_M = 111_320.0

    private val BASEMENT = Regex("""^\s*(?:b|지하)\s*(\d+)""", RegexOption.IGNORE_CASE)

    private val GROUND = Regex(
        """^\s*(?:\d+\s*(?:f|층)?|지상|rooftop|roof|옥상)\s*$""",
        RegexOption.IGNORE_CASE,
    )
}
