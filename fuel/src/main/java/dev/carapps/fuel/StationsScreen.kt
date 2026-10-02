package dev.carapps.fuel

import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The whole car-side experience: open it, and the nearest stations that sell the
 * right fuel are already on the map, nearest first, with the car's range in the
 * title. Tap one and the navigation app takes over.
 *
 * No search box and nothing to type, which is the point. A navigation app can find
 * premium petrol too; it just makes the driver ask, and then check each result.
 */
class StationsScreen(
    carContext: CarContext,
    private val feeds: CarFeeds,
    private val settings: FuelSettings,
) : Screen(carContext), DefaultLifecycleObserver {

    private var result: SearchResult? = null
    private var searching = false
    private var searchedFrom: Location? = null
    private var searchedAt = 0L
    private var lastTitle: String? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val onFeed: () -> Unit = {
        if (shouldSearch()) {
            search()
        } else {
            // Range changes constantly; redraw only when what is shown would change.
            val title = title()
            if (title != lastTitle) invalidate()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        feeds.addListener(onFeed)
        if (shouldSearch()) search()
    }

    override fun onStop(owner: LifecycleOwner) {
        feeds.removeListener(onFeed)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        io.shutdownNow()
    }

    /**
     * Once on arrival, then again only after real movement. Opinet's free key is
     * 300 requests a day, and re-querying at every fix would spend that before
     * lunch.
     */
    private fun shouldSearch(): Boolean {
        if (searching) return false
        val here = feeds.location ?: return false
        if (settings.apiKey.isBlank() || feeds.missingPermissions.isNotEmpty()) return false
        val from = searchedFrom ?: return true
        val moved = here.distanceTo(from)
        val since = SystemClock.elapsedRealtime() - searchedAt
        return moved > RESEARCH_DISTANCE_M && since > RESEARCH_INTERVAL_MS
    }

    private fun search() {
        val here = feeds.location ?: return
        searching = true
        searchedFrom = here
        searchedAt = SystemClock.elapsedRealtime()
        invalidate()
        val client = settings.client()
        val product = settings.product
        io.execute {
            val found = StationSearch.search(client, here.latitude, here.longitude, product)
            main.post {
                result = found
                searching = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template = try {
        buildTemplate()
    } catch (t: Throwable) {
        // A template the host rejects would otherwise leave a blank screen.
        message("화면 오류", "${t.javaClass.simpleName}: ${t.message}")
    }

    private fun buildTemplate(): Template {
        val missing = feeds.missingPermissions
        if (missing.isNotEmpty()) {
            return MessageTemplate.Builder(
                "연료·위치 권한이 필요합니다. 폰에서 이 앱을 열어 \"권한 허용\"을 눌러 주세요.",
            )
                .setHeader(header(settings.product.label))
                .addAction(
                    Action.Builder()
                        .setTitle("여기서 요청")
                        .setOnClickListener {
                            carContext.requestPermissions(missing) { granted, _ ->
                                if (granted.isNotEmpty()) feeds.restart()
                                invalidate()
                            }
                        }
                        .build(),
                )
                .build()
        }
        if (settings.apiKey.isBlank()) {
            return message(
                settings.product.label,
                "오피넷 API 키가 없습니다. 폰에서 이 앱을 열어 키를 입력해 주세요.",
            )
        }

        val title = title().also { lastTitle = it }
        val here = feeds.location
        val current = result
        if (here == null || current == null) {
            return PlaceListMapTemplate.Builder()
                .setTitle(title)
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()
        }

        return when (current) {
            is SearchResult.Found -> placeList(title, current.stations, here)
            SearchResult.NoneNearby -> message(
                title,
                "반경 ${OpinetClient.MAX_RADIUS / 1000}km 안에 ${settings.product.label}를 파는 주유소가 없습니다.",
                retry = true,
            )
            is SearchResult.Failed -> message(title, current.reason, retry = true)
        }
    }

    private fun placeList(title: String, found: List<Nearby>, here: Location): Template {
        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
        // Distances recomputed from where the car is now, not where it was when the
        // list was fetched.
        val stations = StationSearch.rank(found.map { it.station }, here.latitude, here.longitude)

        val items = ItemList.Builder()
        stations.take(limit).forEachIndexed { index, nearby ->
            val station = nearby.station
            val detail = buildString {
                append("  · ")
                if (station.price > 0) append("%,d원 · ".format(Locale.KOREA, station.price))
                append(station.brand)
                if (nearby.cheapest && stations.size > 1) append(" · 최저가")
            }
            val text = SpannableString(detail).apply {
                // The first character is replaced by the distance, rendered by the
                // host in the driver's own units.
                setSpan(DistanceSpan.create(distance(nearby.distanceMeters)), 0, 1, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
            }
            items.addItem(
                Row.Builder()
                    .setTitle(station.name)
                    .addText(text)
                    .setMetadata(
                        Metadata.Builder()
                            .setPlace(
                                Place.Builder(CarLocation.create(station.lat, station.lon))
                                    .setMarker(PlaceMarker.Builder().setLabel("${index + 1}").build())
                                    .build(),
                            )
                            .build(),
                    )
                    .setOnClickListener { navigate(station) }
                    .build(),
            )
        }

        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setCurrentLocationEnabled(true)
            .setItemList(items.build())
            .setOnContentRefreshListener { search() }
            .build()
    }

    /** "고급휘발유 · 23% · 145km" — what the car reports, next to what it needs. */
    private fun title(): String = buildString {
        append(settings.product.label)
        feeds.fuelPercent?.let { append(" · ${it.toInt()}%") }
        feeds.rangeMeters?.let { append(" · ${(it / 1000).toInt()}km") }
    }

    private fun distance(meters: Double): Distance =
        if (meters < 1000) {
            Distance.create(Math.round(meters / 10.0) * 10.0, Distance.UNIT_METERS)
        } else {
            Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        }

    /** Hands the destination to whatever navigation app the driver uses. */
    private fun navigate(station: Station) {
        try {
            carContext.startCarApp(
                Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:${station.lat},${station.lon}")),
            )
        } catch (e: Exception) {
            CarToast.makeText(carContext, "내비 앱을 열 수 없음: ${e.message}", CarToast.LENGTH_LONG).show()
        }
    }

    private fun message(title: String, text: String, retry: Boolean = false): Template =
        MessageTemplate.Builder(text)
            .setHeader(header(title))
            .apply {
                if (retry) {
                    addAction(
                        Action.Builder()
                            .setTitle("다시 찾기")
                            .setOnClickListener { search() }
                            .build(),
                    )
                }
            }
            .build()

    private fun header(title: String) = Header.Builder()
        .setTitle(title)
        .setStartHeaderAction(Action.APP_ICON)
        .build()

    private companion object {
        const val RESEARCH_DISTANCE_M = 3000f
        const val RESEARCH_INTERVAL_MS = 120_000L
    }
}
