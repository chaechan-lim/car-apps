package dev.carapps.fuel

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What goes in the tank. Opinet's product codes, and what the driver calls them. */
enum class FuelProduct(val code: String, val label: String) {
    PREMIUM("B034", "고급휘발유"),
    REGULAR("B027", "휘발유"),
    DIESEL("D047", "경유"),
    LPG("K015", "LPG"),
}

data class Station(
    val id: String,
    val name: String,
    val brand: String,
    /** Won per litre. */
    val price: Int,
    val lat: Double,
    val lon: Double,
)

/**
 * Opinet's stations-within-radius endpoint.
 *
 * The one thing to know about this API is how it fails. A missing key, a mistyped
 * key and an expired key all come back as HTTP 200 with an empty station list —
 * exactly what "no station within 5 km sells this" looks like. This project has
 * already lost weeks to failures that read as absent data, so an empty answer is
 * never taken at face value here: [verifyKey] asks a question whose answer cannot
 * be empty, and only then is an empty list believed.
 */
class OpinetClient(
    private val key: String,
    /** Called once per request that actually goes out, for the daily quota count. */
    private val onRequest: () -> Unit = {},
) {

    /** Thrown when the answer cannot be trusted, with the reason in the message. */
    class OpinetException(message: String) : IOException(message)

    fun around(lat: Double, lon: Double, product: FuelProduct, radiusMeters: Int = MAX_RADIUS): List<Station> {
        val katec = Katec.fromWgs84(lat, lon)
        return query(katec.x, katec.y, product, radiusMeters)
    }

    /**
     * True if the key works, judged by asking for ordinary petrol in central Gangnam.
     *
     * Dozens of stations there sell it, so an empty answer to this question can
     * only mean the key was refused. The point is Opinet's own documented example.
     */
    fun verifyKey(): Boolean =
        query(GANGNAM_X, GANGNAM_Y, FuelProduct.REGULAR, MAX_RADIUS).isNotEmpty()

    private fun query(x: Double, y: Double, product: FuelProduct, radiusMeters: Int): List<Station> {
        if (key.isBlank()) throw OpinetException("No Opinet API key set — enter it on the phone")
        onRequest()
        val url = BASE +
            "?out=json" +
            "&x=${"%.3f".format(x)}" +
            "&y=${"%.3f".format(y)}" +
            "&radius=${radiusMeters.coerceIn(1, MAX_RADIUS)}" +
            "&sort=$SORT_BY_DISTANCE" +
            "&prodcd=${product.code}" +
            // The key goes out under both names. Opinet's documentation calls the
            // parameter certkey; the live API only honours code, and answers certkey
            // alone with the same empty list it gives a wrong key. The first real key
            // failed exactly that way while being perfectly valid.
            "&code=${URLEncoder.encode(key.trim(), "UTF-8")}" +
            "&certkey=${URLEncoder.encode(key.trim(), "UTF-8")}"

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw OpinetException("Opinet answered HTTP $code")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val MAX_RADIUS = 5000
        private const val BASE = "https://www.opinet.co.kr/api/aroundAll.do"
        private const val SORT_BY_DISTANCE = 2
        private const val TIMEOUT_MS = 10_000

        // Opinet's documented example point, in KATEC.
        private const val GANGNAM_X = 314681.8
        private const val GANGNAM_Y = 544837.0

        /**
         * Parses a response body.
         *
         * Lenient about shape — the documentation names the brand field POLL_DIV_CD
         * while the example response says POLL_DIV_CO, and a single station may come
         * back as an object rather than a one-element array — but strict about
         * substance: anything that is not the expected envelope throws with the start
         * of the body, rather than becoming an empty list.
         */
        fun parse(body: String): List<Station> {
            val root = try {
                JSONObject(body.trim())
            } catch (e: Exception) {
                throw OpinetException("Not JSON from Opinet: ${body.trim().take(120)}")
            }
            val result = root.optJSONObject("RESULT")
                ?: throw OpinetException("No RESULT in Opinet's answer: ${body.trim().take(120)}")
            val oil: JSONArray = when (val raw = result.opt("OIL")) {
                is JSONArray -> raw
                is JSONObject -> JSONArray().put(raw)
                null -> JSONArray()
                else -> throw OpinetException("Unexpected OIL in Opinet's answer: $raw")
            }

            return (0 until oil.length()).mapNotNull { index ->
                val item = oil.optJSONObject(index) ?: return@mapNotNull null
                val x = item.optDouble("GIS_X_COOR", Double.NaN)
                val y = item.optDouble("GIS_Y_COOR", Double.NaN)
                if (x.isNaN() || y.isNaN()) return@mapNotNull null
                val position = Katec.toWgs84(x, y)
                Station(
                    id = item.optString("UNI_ID"),
                    name = item.optString("OS_NM").trim(),
                    brand = brandName(
                        item.optString("POLL_DIV_CD").ifEmpty { item.optString("POLL_DIV_CO") },
                    ),
                    price = item.optDouble("PRICE", 0.0).toInt(),
                    lat = position.lat,
                    lon = position.lon,
                )
            }
        }

        fun brandName(code: String): String = when (code.trim()) {
            "SKE" -> "SK에너지"
            "GSC" -> "GS칼텍스"
            "HDO" -> "HD현대오일뱅크"
            "SOL" -> "S-OIL"
            "RTE" -> "알뜰"
            "RTX" -> "고속도로 알뜰"
            "NHO" -> "농협 알뜰"
            "E1G" -> "E1"
            "SKG" -> "SK가스"
            "ETC", "" -> "자가상표"
            else -> code
        }
    }
}
