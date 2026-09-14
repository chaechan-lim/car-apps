package dev.carapps.parking

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Groups drives by the place they ended.
 *
 * Note first what is *not* a reason to group. Absolute pressure differs between two
 * places at the same height — weather, terrain, a fixed offset per sensor — but the
 * estimate is a difference measured inside one drive, so all three cancel and no
 * comparison across drives is ever made on absolute pressure.
 *
 * What can differ is metres per level, which is a property of a building rather than
 * of the air. That was the reason for grouping, and measuring it deflated the case:
 * the three garages here with enough drives to fit came out at 0.50, 0.50 and 0.54
 * hPa per level. Fitting each separately scored worse than one shared constant, so
 * [FloorModel] keeps the grouping but blends a site's own drives toward the global
 * figure, and a site has to earn its way out of it.
 *
 * The grouping still pays for itself twice: it is how a site accumulates evidence at
 * all, and it is what makes "the usual floor here" a question the app can answer.
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
         * The floor most often parked on here, ignoring one drive.
         *
         * This is the rival to the whole barometer idea, and on the drives recorded so
         * far it very nearly wins: people park on the same floor of the same garage,
         * and a lookup table needs no sensor at all. It is kept as a predictor so its
         * score stays visible beside the measured one, and as context — an estimate
         * that disagrees with the usual floor is worth saying out loud, because an
         * unusual floor is exactly when someone forgets where they parked.
         */
        fun usualDepthExcluding(id: Long?): Int? {
            val depths = events
                .filter { it.id != id }
                .mapNotNull { depthBelowGround(it.actualFloor.orEmpty()) }
            if (depths.isEmpty()) return null
            return depths.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
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
