package dev.carapps.fuel

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The one unprompted thing this app does: when the car's range drops below the
 * threshold, find the nearest station that sells the right fuel and say so on the
 * car screen, once per drive.
 *
 * Only while the session is alive. Android Auto starts a template app when the
 * driver opens it and has no way to start one on its own, so this works on drives
 * where the app was opened at least once — the honest limit of the platform, not
 * something a setting can lift.
 *
 * Suppressed while the app itself is on screen: the list is already showing the
 * answer, and a notification on top of it would only be noise.
 */
class LowFuelNotifier(
    private val carContext: CarContext,
    private val feeds: CarFeeds,
    private val settings: FuelSettings,
    private val isForeground: () -> Boolean,
) {
    private var alerted = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val onFeed: () -> Unit = { check() }

    fun start() {
        createChannel()
        feeds.addListener(onFeed)
    }

    fun stop() {
        feeds.removeListener(onFeed)
        io.shutdownNow()
    }

    private fun check() {
        if (!LowFuelAlert.shouldAlert(feeds.rangeMeters?.toDouble(), settings.alertThresholdKm, alerted)) return
        // Spent even if nothing is posted: the driver looking at the list has seen it.
        alerted = true
        if (isForeground()) return

        val here = feeds.location
        val rangeKm = ((feeds.rangeMeters ?: 0f) / 1000).toInt()
        val product = settings.product
        if (here == null || settings.apiKey.isBlank()) {
            post("주행가능 ${rangeKm}km", "${product.label} 주유소를 보려면 탭하세요")
            return
        }
        val client = settings.client()
        io.execute {
            val found = StationSearch.search(client, here.latitude, here.longitude, product)
            val text = when (found) {
                is SearchResult.Found -> found.stations.first().let {
                    val km = "%.1fkm".format(Locale.US, it.distanceMeters / 1000)
                    val price = if (it.station.price > 0) " · %,d원".format(Locale.KOREA, it.station.price) else ""
                    "${it.station.name} $km$price"
                }
                SearchResult.NoneNearby -> "반경 5km 안에 ${product.label} 주유소 없음"
                is SearchResult.Failed -> "탭해서 확인하세요"
            }
            main.post { post("주행가능 ${rangeKm}km — ${product.label}", text) }
        }
    }

    private fun post(title: String, text: String) {
        // Notifications refused on the phone would otherwise vanish without a trace.
        if (!NotificationManagerCompat.from(carContext).areNotificationsEnabled()) return

        val open = CarPendingIntent.getCarApp(
            carContext,
            0,
            Intent(Intent.ACTION_VIEW).setComponent(ComponentName(carContext, FuelCarAppService::class.java)),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(carContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_fuel)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .extend(
                CarAppExtender.Builder()
                    .setSmallIcon(R.drawable.ic_fuel)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build(),
            )
        runCatching { CarNotificationManager.from(carContext).notify(NOTIFICATION_ID, builder) }
    }

    private fun createChannel() {
        runCatching {
            CarNotificationManager.from(carContext).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(carContext.getString(R.string.alert_channel))
                    .build(),
            )
        }
    }

    private companion object {
        const val CHANNEL = "low_fuel"
        const val NOTIFICATION_ID = 1
    }
}
