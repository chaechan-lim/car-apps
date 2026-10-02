package dev.carapps.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpinetTest {

    /** Opinet's documented example, as JSON. Note POLL_DIV_CO, not the documented CD. */
    private val example = """
        {"RESULT":{"OIL":[
          {"UNI_ID":"A0009907","POLL_DIV_CO":"GSC","OS_NM":"에너지플러스허브 삼방주유소","PRICE":1725,"DISTANCE":885.4,"GIS_X_COOR":313828.81720,"GIS_Y_COOR":545078.98990},
          {"UNI_ID":"A0010269","POLL_DIV_CO":"SOL","OS_NM":"극동유화㈜ 개나리주유소","PRICE":1745,"DISTANCE":912.7,"GIS_X_COOR":315589.00000,"GIS_Y_COOR":544735.00000},
          {"UNI_ID":"A0009974","POLL_DIV_CD":"GSC","OS_NM":"지에스칼텍스㈜에너지플러스허브GS타워","PRICE":1887,"DISTANCE":329.8,"GIS_X_COOR":314996.15900,"GIS_Y_COOR":544938.50758}
        ]}}
    """.trimIndent()

    /** Byte for byte what a bad key produced: HTTP 200, padding, and nothing. */
    private val badKeyResponse = "\n\n                                \n\n \n\n\n{\"RESULT\":  \n\n{\"OIL\":[ \n \n" +
        "                                          \n                                              \n]} }" +
        "                                                  \n \n \n"

    @Test
    fun parsesTheDocumentedExample() {
        val stations = OpinetClient.parse(example)
        assertEquals(3, stations.size)
        assertEquals("GS칼텍스", stations[0].brand)
        assertEquals("S-OIL", stations[1].brand)
        assertEquals(1725, stations[0].price)
        // Either spelling of the brand field.
        assertEquals("GS칼텍스", stations[2].brand)
        assertEquals(37.502702, stations[2].lat, 0.00001)
    }

    @Test
    fun badKeyLooksLikeAnEmptyAreaAndParsesAsEmpty() {
        assertTrue(OpinetClient.parse(badKeyResponse).isEmpty())
    }

    @Test
    fun singleStationAsObjectIsAccepted() {
        val one = """{"RESULT":{"OIL":{"UNI_ID":"X","POLL_DIV_CD":"SKE","OS_NM":"한 곳","PRICE":1900,"GIS_X_COOR":314996.159,"GIS_Y_COOR":544938.508}}}"""
        assertEquals("SK에너지", OpinetClient.parse(one).single().brand)
    }

    @Test
    fun garbageIsAnErrorNotAnEmptyList() {
        listOf("<html>error</html>", "{\"SOMETHING\":1}", "").forEach { body ->
            try {
                OpinetClient.parse(body)
                fail("expected an error for: $body")
            } catch (expected: OpinetClient.OpinetException) {
                // The point: never silently empty.
            }
        }
    }

    @Test
    fun emptyAnswerIsOnlyBelievedOnceTheKeyIsShownToWork() {
        val none = StationSearch.search(fetch = { emptyList() }, keyWorks = { true }, lat = 37.5, lon = 127.0)
        assertEquals(SearchResult.NoneNearby, none)

        val badKey = StationSearch.search(fetch = { emptyList() }, keyWorks = { false }, lat = 37.5, lon = 127.0)
        assertTrue(badKey is SearchResult.Failed)
        assertTrue((badKey as SearchResult.Failed).reason.contains("key"))
    }

    @Test
    fun networkFailureIsReportedWithItsReason() {
        val result = StationSearch.search(
            fetch = { throw java.io.IOException("timeout") },
            keyWorks = { true },
            lat = 37.5,
            lon = 127.0,
        )
        assertEquals(SearchResult.Failed("timeout"), result)
    }

    @Test
    fun rankedNearestFirstWithTheCheapestMarked() {
        val stations = OpinetClient.parse(example)
        // From the request point Opinet used in its example.
        val here = Katec.toWgs84(314681.8, 544837.0)
        val ranked = StationSearch.rank(stations, here.lat, here.lon)

        assertEquals("지에스칼텍스㈜에너지플러스허브GS타워", ranked.first().station.name)
        assertTrue(ranked.zipWithNext().all { (a, b) -> a.distanceMeters <= b.distanceMeters })
        // Our distance agrees with Opinet's own figure to within a few metres.
        assertEquals(329.8, ranked.first().distanceMeters, 5.0)
        assertEquals(listOf(1725), ranked.filter { it.cheapest }.map { it.station.price })
    }

    @Test
    fun alertsOnceBelowTheThresholdOnly() {
        assertFalse(LowFuelAlert.shouldAlert(145_000.0, 60, alreadyAlerted = false))
        assertTrue(LowFuelAlert.shouldAlert(59_000.0, 60, alreadyAlerted = false))
        assertFalse(LowFuelAlert.shouldAlert(59_000.0, 60, alreadyAlerted = true))
        assertFalse(LowFuelAlert.shouldAlert(null, 60, alreadyAlerted = false))
        // Zero is the car not knowing, not the car being empty.
        assertFalse(LowFuelAlert.shouldAlert(0.0, 60, alreadyAlerted = false))
    }
}
