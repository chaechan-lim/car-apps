package dev.carapps.parking

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a measured climb into a floor.
 *
 * The conversion is learned from whatever drives have been labelled, and defaults to
 * [DEFAULT_HPA_PER_LEVEL] until there are any. That number is not a textbook value:
 * it is the mean of the labelled drives recorded so far, which came out well above
 * the 0.36 hPa that three metres of air would give. Real garages put more than three
 * metres between levels once the ramp runs are counted.
 *
 * A per-garage constant was tried first and measured worse — 73% against 85% —
 * because the three garages with enough drives to fit came out at 0.50, 0.50 and
 * 0.54 hPa per level, near enough to identical that fitting each one separately only
 * added the noise of its own few drives. So a site's own drives are blended toward
 * the global figure with a heavy prior ([SITE_PRIOR_WEIGHT]): a site has to
 * accumulate real evidence before it moves the answer, which is what a garage with
 * genuinely unusual spacing would eventually do.
 */
class FloorModel private constructor(
    private val globalHpaPerLevel: Float,
    private val siteHpaPerLevel: Map<String, Pair<Float, Int>>,
    val calibrationDrives: Int,
) {

    /** hPa per level to use at [siteName], blended toward the global figure. */
    fun hPaPerLevel(siteName: String?): Float {
        val (siteValue, count) = siteName?.let { siteHpaPerLevel[it] } ?: return globalHpaPerLevel
        return (count * siteValue + SITE_PRIOR_WEIGHT * globalHpaPerLevel) /
            (count + SITE_PRIOR_WEIGHT)
    }

    /**
     * Levels below ground, or 0 for a surface park. Null when nothing was measured.
     *
     * The satellite gate decides underground or not, and pressure only decides how
     * deep. Handing the whole decision to pressure put half the surface parks
     * underground.
     */
    fun levelsDown(event: ParkingEvent, siteName: String?): Int? {
        val rise = event.descentRiseHpa ?: return null
        val levels = rise / hPaPerLevel(siteName)
        return when {
            event.wentUnderground == false -> 0
            // No satellite marker at all: pressure alone, and it has to be worth half
            // a level before it counts as a descent.
            event.wentUnderground == null && levels < 0.5f -> 0
            else -> levels.roundToInt().coerceAtLeast(1)
        }
    }

    /** "B4", "surface", or null — the form the estimate is worth showing in. */
    fun label(event: ParkingEvent, siteName: String?): String? =
        levelsDown(event, siteName)?.let { if (it == 0) "surface" else "B$it" }

    /**
     * How the model scores against the drives that carry a floor typed in by hand.
     *
     * Each drive is predicted from a model fitted without it, so this is what the app
     * would have said before being told the answer.
     */
    data class Accuracy(val exact: Int, val withinOne: Int, val total: Int)

    companion object {

        /**
         * The mean of every labelled drive recorded so far, as a starting point.
         *
         * 0.52 hPa is about four and a half metres per level. That is more than a
         * storey of air, and it is what the measurements say: the drop from street to
         * the first level is deeper than the gaps between levels, and the ramp run is
         * part of it.
         */
        const val DEFAULT_HPA_PER_LEVEL = 0.52f

        /** How many drives of global evidence a site's own fit has to outweigh. */
        const val SITE_PRIOR_WEIGHT = 10

        fun fit(sites: List<ParkingSites.Site>, excludeId: Long? = null): FloorModel {
            val ratios = mutableListOf<Float>()
            val perSite = mutableMapOf<String, MutableList<Float>>()

            sites.forEach { site ->
                site.events.forEach { event ->
                    if (event.id == excludeId) return@forEach
                    val depth = ParkingSites.depthBelowGround(event.actualFloor.orEmpty())
                    val rise = event.descentRiseHpa
                    if (depth == null || depth < 1 || rise == null) return@forEach
                    val ratio = rise / depth
                    ratios += ratio
                    perSite.getOrPut(site.name) { mutableListOf() } += ratio
                }
            }

            val global = if (ratios.isEmpty()) {
                DEFAULT_HPA_PER_LEVEL
            } else {
                ratios.average().toFloat()
            }
            return FloorModel(
                globalHpaPerLevel = global,
                siteHpaPerLevel = perSite.mapValues { (_, values) ->
                    values.average().toFloat() to values.size
                },
                calibrationDrives = ratios.size,
            )
        }

        /**
         * Leave-one-out accuracy over the labelled drives.
         *
         * Refitting without each drive is the only honest version. A model that has
         * been told the answer reproduces it, and the first version of this screen
         * would have reported a precision the app did not have.
         */
        fun accuracy(sites: List<ParkingSites.Site>): Accuracy {
            var exact = 0
            var withinOne = 0
            var total = 0
            sites.forEach { site ->
                site.events.forEach { event ->
                    val actual = ParkingSites.depthBelowGround(event.actualFloor.orEmpty())
                        ?: return@forEach
                    val predicted = fit(sites, excludeId = event.id).levelsDown(event, site.name)
                        ?: return@forEach
                    total++
                    if (predicted == actual) exact++
                    if (abs(predicted - actual) <= 1) withinOne++
                }
            }
            return Accuracy(exact, withinOne, total)
        }
    }
}
