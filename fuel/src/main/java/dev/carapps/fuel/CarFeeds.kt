package dev.carapps.fuel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.EnergyLevel
import androidx.core.content.ContextCompat

/**
 * Where the car is and how much range it has left, for as long as the session lives.
 *
 * Owned by the session rather than a screen so it keeps running while the driver
 * is in the navigation app — the low-range notice is useless if it only works while
 * this app is on screen.
 *
 * Position prefers the car's own GPS, which the probe measured at 1.4 m accuracy
 * on this car, and falls back to the phone's when the car stays silent. Range is
 * the car's figure, read from Android Auto.
 *
 * Two lessons from the probe are built in rather than learned again. A
 * subscription taken without its permission is silently dead and stays dead after
 * a grant, so [restart] exists and is called on every grant. And removing a
 * listener for a property the host never supported throws, inside teardown where a
 * throw kills the process, so every removal is wrapped.
 */
class CarFeeds(private val carContext: CarContext) {

    var carLocation: Location? = null
        private set
    private var carLocationAt = 0L

    var phoneLocation: Location? = null
        private set

    var fuelPercent: Float? = null
        private set
    var rangeMeters: Float? = null
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private var running = false

    /** The car's fix if it is recent, else the phone's. */
    val location: Location?
        get() {
            val fresh = SystemClock.elapsedRealtime() - carLocationAt < CAR_FIX_STALE_MS
            return if (carLocation != null && fresh) carLocation else phoneLocation ?: carLocation
        }

    val missingPermissions: List<String>
        get() = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(carContext, it) != PackageManager.PERMISSION_GRANTED
        }

    private val executor get() = ContextCompat.getMainExecutor(carContext)

    private val hardware: CarHardwareManager
        get() = carContext.getCarService(CarHardwareManager::class.java)

    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { data ->
        fuelPercent = data.fuelPercent.valueIfSuccess()
        rangeMeters = data.rangeRemainingMeters.valueIfSuccess()
        publish()
    }

    private val carLocationListener = OnCarDataAvailableListener<CarHardwareLocation> { data ->
        data.location.valueIfSuccess()?.let {
            carLocation = it
            carLocationAt = SystemClock.elapsedRealtime()
            publish()
        }
    }

    private val phoneLocationListener = LocationListener { fix ->
        val current = phoneLocation
        if (current == null || fix.accuracy <= current.accuracy || fix.time - current.time > 30_000) {
            phoneLocation = fix
            publish()
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun start() {
        if (running) return
        running = true
        val granted = missingPermissions.isEmpty()
        if (!granted) return

        val carInfo = hardware.carInfo
        runCatching { carInfo.addEnergyLevelListener(executor, energyListener) }
        runCatching {
            hardware.carSensors.addCarHardwareLocationListener(
                CarSensors.UPDATE_RATE_NORMAL, executor, carLocationListener,
            )
        }
        startPhoneLocation()
    }

    fun stop() {
        if (!running) return
        running = false
        val carInfo = runCatching { hardware.carInfo }.getOrNull()
        runCatching { carInfo?.removeEnergyLevelListener(energyListener) }
        runCatching { hardware.carSensors.removeCarHardwareLocationListener(carLocationListener) }
        runCatching { locationManager().removeUpdates(phoneLocationListener) }
    }

    /** Takes every subscription out again — call after any permission grant. */
    fun restart() {
        stop()
        start()
        publish()
    }

    @SuppressLint("MissingPermission") // start() returns early unless every permission is held
    private fun startPhoneLocation() {
        val manager = locationManager()
        // Something to show straight away, before any fresh fix arrives.
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { phoneLocation = it }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, PHONE_FIX_INTERVAL_MS, 50f, phoneLocationListener)
            }
        }
    }

    private fun locationManager() =
        carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun publish() {
        listeners.toList().forEach { it() }
    }

    private fun <T : Any> CarValue<T>.valueIfSuccess(): T? =
        if (status == CarValue.STATUS_SUCCESS) value else null

    companion object {
        val REQUIRED_PERMISSIONS = listOf(
            "com.google.android.gms.permission.CAR_FUEL",
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        private const val CAR_FIX_STALE_MS = 30_000L
        private const val PHONE_FIX_INTERVAL_MS = 15_000L
    }
}
