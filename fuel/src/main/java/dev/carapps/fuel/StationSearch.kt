package dev.carapps.fuel

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A station as seen from where the car is now. */
data class Nearby(
    val station: Station,
    val distanceMeters: Double,
    /** The lowest price among the stations found, ties included. */
    val cheapest: Boolean,
)

/**
 * The three things a search can come back as, kept apart on purpose.
 *
 * An empty list from Opinet is not an answer by itself (see [OpinetClient]), so
 * "nothing nearby" is only ever reported after the key has been shown to work.
 */
sealed interface SearchResult {
    data class Found(val stations: List<Nearby>) : SearchResult
    data object NoneNearby : SearchResult
    data class Failed(val reason: String) : SearchResult
}

object StationSearch {

    fun search(
        client: OpinetClient,
        lat: Double,
        lon: Double,
        product: FuelProduct,
    ): SearchResult = search(
        fetch = { client.around(lat, lon, product) },
        keyWorks = { client.verifyKey() },
        lat = lat,
        lon = lon,
    )

    /** The decision itself, apart from the network, so it can be tested. */
    fun search(
        fetch: () -> List<Station>,
        keyWorks: () -> Boolean,
        lat: Double,
        lon: Double,
    ): SearchResult = try {
        val stations = fetch()
        when {
            stations.isNotEmpty() -> SearchResult.Found(rank(stations, lat, lon))
            keyWorks() -> SearchResult.NoneNearby
            else -> SearchResult.Failed(
                "Opinet returned nothing even for central Gangnam — the API key " +
                    "is most likely wrong or not yet active",
            )
        }
    } catch (e: Exception) {
        SearchResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    /**
     * Nearest first, with the distance measured from where the car is now.
     *
     * Opinet does send a distance, but from the point that was queried, and the
     * car has usually moved on by the time anyone reads the list.
     */
    fun rank(stations: List<Station>, lat: Double, lon: Double): List<Nearby> {
        val lowest = stations.filter { it.price > 0 }.minOfOrNull { it.price }
        return stations
            .map { Nearby(it, distanceMeters(lat, lon, it.lat, it.lon), it.price == lowest) }
            .sortedBy { it.distanceMeters }
    }

    /** Great-circle distance; flat-earth would do over 5 km, but this costs nothing. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    private const val EARTH_RADIUS_M = 6_371_008.8
}

/**
 * When to speak up without being asked.
 *
 * Android Auto lets a POI app post a notification only "when relevant to the
 * driver's needs" (quality rule IN-1), and an app that nags gets turned off by the
 * driver long before it gets rejected by the reviewer. So: once per drive, and only
 * when the range the car itself reports has dropped below the threshold. Fuel
 * percentage is not used — the car's range already folds in how it has been driven.
 */
object LowFuelAlert {

    fun shouldAlert(rangeMeters: Double?, thresholdKm: Int, alreadyAlerted: Boolean): Boolean {
        if (alreadyAlerted || rangeMeters == null || rangeMeters <= 0) return false
        return rangeMeters < thresholdKm * 1000.0
    }
}
