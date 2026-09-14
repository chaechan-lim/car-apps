package dev.carapps.parking

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a measured climb into a floor.
 *
 * The conversion is learned from whatever drives have been labelled and defaults to
 * [DEFAULT_HPA_PER_LEVEL] until there are any. That number is not a textbook value:
 * it is the mean of the drives recorded so far, and it came out well above the
 * 0.36 hPa that three metres of air would give. Real garages put more than a storey
 * between levels once the ramp runs are counted.
 *
 * One constant, not one per garage. Per-garage calibration was tried twice and
 * measured worse both times — 83% against 89% on 35 labelled drives — because the
 * garages with enough drives to fit came out at 0.50, 0.51 and 0.54 hPa per level.
 * Buildings differ in principle; these did not, and fitting each one separately only
 * added the noise of its own few drives. If a garage with genuinely unusual spacing
 * ever shows up, this is the place to bring that back — with enough drives from it to
 * prove the case.
 */
class FloorModel private constructor(
    val hPaPerLevel: Float,
    val calibrationDrives: Int,
) {

    /**
     * Levels below ground, or 0 for a surface park. Null when nothing was measured.
     *
     * The satellite gate decides underground or not, and pressure only decides how
     * deep. Handing the whole decision to pressure put over half the surface parks
     * underground: a road running downhill into a destination is indistinguishable
     * from a ramp.
     */
    fun levelsDown(event: ParkingEvent): Int? {
        val rise = event.descentRiseHpa ?: return null
        val levels = rise / hPaPerLevel
        return when {
            event.wentUnderground == false -> 0
            // No satellite marker at all: pressure alone, and it has to be worth half
            // a level before it counts as a descent.
            event.wentUnderground == null && levels < 0.5f -> 0
            else -> levels.roundToInt().coerceAtLeast(1)
        }
    }

    /** "B4", "surface", or null — the form the estimate is worth showing in. */
    fun label(event: ParkingEvent): String? = levelsDown(event)?.let { floorName(it) }

    companion object {

        /**
         * The mean of every labelled drive so far, as a starting point.
         *
         * 0.52 hPa is about four and a half metres per level, which is more than a
         * storey of air. The drop from street to the first level is deeper than the
         * gaps between levels, and the ramp run is part of it.
         */
        const val DEFAULT_HPA_PER_LEVEL = 0.52f

        fun floorName(levels: Int) = if (levels == 0) "surface" else "B$levels"

        fun fit(sites: List<ParkingSites.Site>, excludeId: Long? = null): FloorModel {
            val ratios = sites.flatMap { it.events }.mapNotNull { event ->
                if (event.id == excludeId) return@mapNotNull null
                val depth = ParkingSites.depthBelowGround(event.actualFloor.orEmpty())
                if (depth == null || depth < 1) return@mapNotNull null
                event.descentRiseHpa?.let { it / depth }
            }
            return FloorModel(
                hPaPerLevel = if (ratios.isEmpty()) DEFAULT_HPA_PER_LEVEL else ratios.average().toFloat(),
                calibrationDrives = ratios.size,
            )
        }

        /**
         * How the barometer scores, and how a lookup table scores beside it.
         *
         * The second column is the point. On the drives recorded so far, predicting
         * "whatever floor you usually take here" is right 30 times out of 35, against
         * the barometer's 31 — which means the headline accuracy mostly measures a
         * habit, not a sensor. The barometer earns its place only on the drives that
         * break the habit, and those are exactly the drives where a person is confused
         * about where they parked. Keeping both numbers on screen is the only way to
         * see which is true as more drives arrive.
         */
        fun score(sites: List<ParkingSites.Site>): Scores {
            var exact = 0
            var withinOne = 0
            var habitExact = 0
            var habitTotal = 0
            var brokeHabit = 0
            var brokeHabitRight = 0
            var total = 0

            sites.forEach { site ->
                site.events.forEach { event ->
                    val actual = ParkingSites.depthBelowGround(event.actualFloor.orEmpty())
                        ?: return@forEach
                    // Refitted without this drive: a model shown the answer reproduces it.
                    val predicted = fit(sites, excludeId = event.id).levelsDown(event)
                        ?: return@forEach
                    total++
                    if (predicted == actual) exact++
                    if (abs(predicted - actual) <= 1) withinOne++

                    val habit = site.usualDepthExcluding(event.id)
                    if (habit != null) {
                        habitTotal++
                        if (habit == actual) habitExact++
                        if (habit != actual) {
                            brokeHabit++
                            if (predicted == actual) brokeHabitRight++
                        }
                    }
                }
            }
            return Scores(
                total = total,
                exact = exact,
                withinOne = withinOne,
                habitTotal = habitTotal,
                habitExact = habitExact,
                brokeHabit = brokeHabit,
                brokeHabitRight = brokeHabitRight,
            )
        }
    }

    /**
     * [brokeHabitRight] out of [brokeHabit] is the number that says whether any of
     * this is worth shipping: the drives where the usual floor was the wrong answer.
     */
    data class Scores(
        val total: Int,
        val exact: Int,
        val withinOne: Int,
        val habitTotal: Int,
        val habitExact: Int,
        val brokeHabit: Int,
        val brokeHabitRight: Int,
    )
}
