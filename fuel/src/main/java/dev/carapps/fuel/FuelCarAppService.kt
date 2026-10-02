package dev.carapps.fuel

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

class FuelCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            // The Desktop Head Unit and sideloaded builds.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = FuelSession()
}

/**
 * Holds the car feeds and the low-range notice for the whole drive, so both keep
 * running while the driver is in the navigation app rather than only while this
 * app's screen is showing.
 */
class FuelSession : Session(), DefaultLifecycleObserver {

    private lateinit var feeds: CarFeeds
    private lateinit var notifier: LowFuelNotifier

    override fun onCreateScreen(intent: Intent): Screen {
        val settings = FuelSettings(carContext)
        feeds = CarFeeds(carContext).also { it.start() }
        notifier = LowFuelNotifier(carContext, feeds, settings) {
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.also { it.start() }
        lifecycle.addObserver(this)
        return StationsScreen(carContext, feeds, settings)
    }

    /** A tap on the low-range notice: bring the list back to the front. */
    override fun onNewIntent(intent: Intent) {
        carContext.getCarService(ScreenManager::class.java).popToRoot()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        notifier.stop()
        feeds.stop()
    }
}
