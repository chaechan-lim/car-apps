package dev.carapps.fuel

import android.content.Context
import java.time.LocalDate

/**
 * The few things only the driver can say, plus the daily request count.
 *
 * The API key lives here, typed in on the phone, and nowhere else. It is not baked
 * into the build: the repository and its releases are public, and a key in an APK
 * is a key anyone can extract — and Opinet's free tier is a single daily quota per
 * key, so a leaked one is an exhausted one.
 */
class FuelSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("fuel", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /**
     * Premium by default, because that is the problem this exists for. The car
     * cannot be asked: it reports a fuel type of "unleaded" whether or not the
     * owner's manual says premium, and the test car also reported diesel.
     */
    var product: FuelProduct
        get() = runCatching { FuelProduct.valueOf(prefs.getString(KEY_PRODUCT, null)!!) }
            .getOrDefault(FuelProduct.PREMIUM)
        set(value) = prefs.edit().putString(KEY_PRODUCT, value.name).apply()

    /** Range below which the car screen speaks up once per drive. */
    var alertThresholdKm: Int
        get() = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD_KM)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value.coerceIn(10, 300)).apply()

    /** Requests sent today. Opinet's free key allows 300 a day. */
    val requestsToday: Int
        get() = if (prefs.getString(KEY_DAY, null) == today()) prefs.getInt(KEY_COUNT, 0) else 0

    fun recordRequest() {
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_COUNT, requestsToday + 1)
            .apply()
    }

    fun client() = OpinetClient(apiKey, onRequest = ::recordRequest)

    private fun today() = LocalDate.now().toString()

    companion object {
        const val DAILY_QUOTA = 300

        /**
         * Sixty kilometres: comfortably more than the distance to any premium
         * station in a built-up area, short enough that it rarely fires on an
         * ordinary day. The test car showed 145 km at 23%.
         */
        const val DEFAULT_THRESHOLD_KM = 60

        private const val KEY_API = "api_key"
        private const val KEY_PRODUCT = "product"
        private const val KEY_THRESHOLD = "threshold_km"
        private const val KEY_DAY = "requests_day"
        private const val KEY_COUNT = "requests_count"
    }
}
