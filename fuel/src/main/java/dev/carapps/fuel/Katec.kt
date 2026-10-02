package dev.carapps.fuel

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Conversion between GPS coordinates and KATEC, the grid Opinet speaks.
 *
 * KATEC is a transverse Mercator projection on the Bessel 1841 ellipsoid, centred
 * on 38°N 128°E. The projection is the easy half. The hard half is that Bessel and
 * WGS84 are different ellipsoids in different positions, so a GPS latitude and a
 * KATEC latitude for the same spot differ by several hundred metres — enough to
 * put a petrol station on the wrong side of a city block. Skipping the datum shift
 * produces coordinates that look plausible and are wrong.
 *
 * The shift is the seven-parameter Helmert transform most Korean services use with
 * Opinet, in the position-vector convention PROJ applies to `+towgs84`. Both
 * directions agree with pyproj to under a millimetre across the peninsula and
 * Jeju, and Opinet's own example response — a station beside GS Tower in Gangnam —
 * comes out on the right corner.
 */
object Katec {

    data class Point(val x: Double, val y: Double)
    data class LatLon(val lat: Double, val lon: Double)

    fun fromWgs84(lat: Double, lon: Double): Point {
        val wgs = toEcef(Math.toRadians(lat), Math.toRadians(lon), WGS84)
        val bessel = wgs84ToBessel(wgs)
        val (besselLat, besselLon) = fromEcef(bessel, BESSEL)
        return projectForward(besselLat, besselLon)
    }

    fun toWgs84(x: Double, y: Double): LatLon {
        val (besselLat, besselLon) = projectInverse(x, y)
        val wgs = besselToWgs84(toEcef(besselLat, besselLon, BESSEL))
        val (lat, lon) = fromEcef(wgs, WGS84)
        return LatLon(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    // --- ellipsoids and ECEF ---------------------------------------------------

    private class Ellipsoid(val a: Double, f: Double) {
        val e2 = f * (2 - f)
    }

    private val WGS84 = Ellipsoid(6378137.0, 1 / 298.257223563)
    private val BESSEL = Ellipsoid(6377397.155, 1 / 299.1528128)

    private data class Ecef(val x: Double, val y: Double, val z: Double)

    private fun toEcef(lat: Double, lon: Double, e: Ellipsoid): Ecef {
        val n = e.a / sqrt(1 - e.e2 * sin(lat).pow(2))
        return Ecef(
            n * cos(lat) * cos(lon),
            n * cos(lat) * sin(lon),
            n * (1 - e.e2) * sin(lat),
        )
    }

    /** Bowring-style iteration; ten rounds is far past convergence at these heights. */
    private fun fromEcef(p: Ecef, e: Ellipsoid): Pair<Double, Double> {
        val lon = atan2(p.y, p.x)
        val r = hypot(p.x, p.y)
        var lat = atan2(p.z, r * (1 - e.e2))
        repeat(10) {
            val n = e.a / sqrt(1 - e.e2 * sin(lat).pow(2))
            val h = r / cos(lat) - n
            lat = atan2(p.z, r * (1 - e.e2 * n / (n + h)))
        }
        return lat to lon
    }

    // --- datum shift -----------------------------------------------------------

    private const val TX = -115.80
    private const val TY = 474.99
    private const val TZ = 674.11
    private const val ARC_SECOND = Math.PI / 180 / 3600
    private const val RX = 1.16 * ARC_SECOND
    private const val RY = -2.31 * ARC_SECOND
    private const val RZ = -1.63 * ARC_SECOND
    private const val SCALE = 1 + 6.43e-6

    /**
     * Position-vector convention. The rotations are a couple of arc-seconds, which
     * is tens of metres at the Earth's radius, so getting the sign convention wrong
     * is a visible error rather than a rounding one — the unit test would catch it.
     */
    private fun besselToWgs84(p: Ecef) = Ecef(
        TX + SCALE * (p.x - RZ * p.y + RY * p.z),
        TY + SCALE * (RZ * p.x + p.y - RX * p.z),
        TZ + SCALE * (-RY * p.x + RX * p.y + p.z),
    )

    /** The transpose of the small rotation; the second-order error is under a millimetre. */
    private fun wgs84ToBessel(p: Ecef): Ecef {
        val x = p.x - TX
        val y = p.y - TY
        val z = p.z - TZ
        return Ecef(
            (x + RZ * y - RY * z) / SCALE,
            (-RZ * x + y + RX * z) / SCALE,
            (RY * x - RX * y + z) / SCALE,
        )
    }

    // --- transverse Mercator on Bessel (Snyder, USGS PP 1395) -----------------

    private val LAT0 = Math.toRadians(38.0)
    private val LON0 = Math.toRadians(128.0)
    private const val K0 = 0.9999
    private const val FALSE_EASTING = 400000.0
    private const val FALSE_NORTHING = 600000.0

    private fun meridianArc(lat: Double): Double {
        val e2 = BESSEL.e2
        val e4 = e2 * e2
        val e6 = e4 * e2
        return BESSEL.a * (
            (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * lat -
                (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * lat) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * lat) -
                (35 * e6 / 3072) * sin(6 * lat)
            )
    }

    private fun projectForward(lat: Double, lon: Double): Point {
        val e2 = BESSEL.e2
        val ep2 = e2 / (1 - e2)
        val n = BESSEL.a / sqrt(1 - e2 * sin(lat).pow(2))
        val t = tan(lat).pow(2)
        val c = ep2 * cos(lat).pow(2)
        val a = (lon - LON0) * cos(lat)

        val x = K0 * n * (
            a + (1 - t + c) * a.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a.pow(5) / 120
            )
        val y = K0 * (
            meridianArc(lat) - meridianArc(LAT0) + n * tan(lat) * (
                a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a.pow(6) / 720
                )
            )
        return Point(x + FALSE_EASTING, y + FALSE_NORTHING)
    }

    private fun projectInverse(x: Double, y: Double): Pair<Double, Double> {
        val e2 = BESSEL.e2
        val ep2 = e2 / (1 - e2)
        val m = meridianArc(LAT0) + (y - FALSE_NORTHING) / K0
        val mu = m / (BESSEL.a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val footprint = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)

        val c1 = ep2 * cos(footprint).pow(2)
        val t1 = tan(footprint).pow(2)
        val n1 = BESSEL.a / sqrt(1 - e2 * sin(footprint).pow(2))
        val r1 = BESSEL.a * (1 - e2) / (1 - e2 * sin(footprint).pow(2)).pow(1.5)
        val d = (x - FALSE_EASTING) / (n1 * K0)

        val lat = footprint - (n1 * tan(footprint) / r1) * (
            d * d / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lon = LON0 + (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cos(footprint)
        return lat to lon
    }
}
