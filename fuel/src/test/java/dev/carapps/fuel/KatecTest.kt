package dev.carapps.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Reference values come from pyproj with the KATEC definition Korean services use
 * with Opinet:
 *
 *   +proj=tmerc +lat_0=38 +lon_0=128 +k=0.9999 +x_0=400000 +y_0=600000
 *   +ellps=bessel +towgs84=-115.80,474.99,674.11,1.16,-2.31,-1.63,6.43
 *
 * The points span the mainland and Jeju, so the projection is exercised up to
 * about 1.6 degrees from its central meridian.
 */
class KatecTest {

    private data class Ref(val name: String, val lat: Double, val lon: Double, val x: Double, val y: Double)

    private val refs = listOf(
        Ref("Seoul GS Tower", 37.50196, 127.03702, 315050.858, 544855.610),
        Ref("GV80 probe fix", 37.21168, 127.067034, 317386.810, 512614.220),
        Ref("Busan", 35.1796, 129.0756, 498164.716, 287275.006),
        Ref("Jeju", 33.4996, 126.5312, 263722.204, 101365.130),
        Ref("Gangneung", 37.7519, 128.8761, 477395.526, 572521.892),
        Ref("Mokpo", 34.8118, 126.3922, 253096.891, 247119.939),
    )

    @Test
    fun forwardMatchesPyproj() {
        refs.forEach { ref ->
            val p = Katec.fromWgs84(ref.lat, ref.lon)
            val error = hypot(p.x - ref.x, p.y - ref.y)
            assertTrue("${ref.name}: off by $error m", error < 0.01)
        }
    }

    @Test
    fun inverseMatchesPyproj() {
        refs.forEach { ref ->
            val p = Katec.toWgs84(ref.x, ref.y)
            val error = metres(p.lat, p.lon, ref.lat, ref.lon)
            assertTrue("${ref.name}: off by $error m", error < 0.01)
        }
    }

    /**
     * Opinet's own documented response includes a station beside GS Tower in
     * Gangnam. Without the datum shift it would land several hundred metres away;
     * with it, it lands on the corner where the station is.
     */
    @Test
    fun opinetExampleStationLandsInGangnam() {
        val p = Katec.toWgs84(314996.15900, 544938.50758)
        assertEquals(37.502702, p.lat, 0.00001)
        assertEquals(127.036392, p.lon, 0.00001)
    }

    /**
     * About 5 mm, not zero, and that is correct. The datum shift runs in three
     * dimensions but both ends are two-dimensional, so the ellipsoidal height is
     * dropped on each side and the two normals differ slightly. pyproj's own round
     * trip at GS Tower is 5.5 mm; this matches it.
     */
    @Test
    fun roundTripIsWithinACentimetre() {
        refs.forEach { ref ->
            val there = Katec.fromWgs84(ref.lat, ref.lon)
            val back = Katec.toWgs84(there.x, there.y)
            val error = metres(back.lat, back.lon, ref.lat, ref.lon)
            assertTrue("${ref.name}: $error m", error < 0.01)
        }
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
        hypot((lat1 - lat2) * 111_320, (lon1 - lon2) * 111_320 * cos(Math.toRadians(lat1)))
}
