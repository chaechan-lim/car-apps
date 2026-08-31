package dev.carapps.probe.core

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Compass
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.EvStatus
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import java.util.concurrent.Executor

/**
 * Probes every field the Car App Library exposes and reports the status of each.
 *
 * Used by the `:projected` (Android Auto) app. The API also exists on Android
 * Automotive OS, but there it is a thin wrapper over `CarPropertyManager`, which
 * the `:automotive` module scans in full — so this probe is deliberately not wired
 * up on that side.
 *
 * Google documents a few platform differences that are worth keeping in mind when
 * reading results, though none of them are verified here:
 *
 *  - `Mileage` is documented as unavailable to AAOS apps installed from Google Play,
 *    but available on Android Auto.
 *  - `CarSensors` returns STATUS_UNIMPLEMENTED on AAOS; use SensorManager there.
 *  - `ExteriorDimensions` is AAOS-only and needs Car App API level 7.
 *
 * Everything else is folklore until measured on a real head unit.
 */
class CarAppLibraryProbe(
    private val carContext: CarContext,
    private val executor: Executor,
    private val onSnapshot: (ProbeSnapshot) -> Unit,
) {
    private val fields = linkedMapOf<String, CarField>()
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var emitScheduled = false
    private var started = false

    private val carHardware: CarHardwareManager
        get() = carContext.getCarService(CarHardwareManager::class.java)

    // Listeners are held as properties so they can be handed back to remove*().
    private val energyLevelListener = OnCarDataAvailableListener<EnergyLevel> { data ->
        put(GROUP_ENERGY, "batteryPercent", data.batteryPercent, "%")
        put(GROUP_ENERGY, "fuelPercent", data.fuelPercent, "%")
        put(GROUP_ENERGY, "rangeRemainingMeters", data.rangeRemainingMeters, "m")
        put(GROUP_ENERGY, "energyIsLow", data.energyIsLow)
        put(GROUP_ENERGY, "distanceDisplayUnit", data.distanceDisplayUnit)
        put(GROUP_ENERGY, "fuelVolumeDisplayUnit", data.fuelVolumeDisplayUnit)
        emit()
    }

    private val speedListener = OnCarDataAvailableListener<Speed> { data ->
        put(GROUP_SPEED, "rawSpeedMetersPerSecond", data.rawSpeedMetersPerSecond, "m/s")
        put(GROUP_SPEED, "displaySpeedMetersPerSecond", data.displaySpeedMetersPerSecond, "m/s")
        put(GROUP_SPEED, "speedDisplayUnit", data.speedDisplayUnit)
        emit()
    }

    private val mileageListener = OnCarDataAvailableListener<Mileage> { data ->
        put(GROUP_MILEAGE, "odometerMeters", data.odometerMeters, "m")
        put(GROUP_MILEAGE, "distanceDisplayUnit", data.distanceDisplayUnit)
        emit()
    }

    private val tollListener = OnCarDataAvailableListener<TollCard> { data ->
        put(GROUP_TOLL, "cardState", data.cardState)
        emit()
    }

    private val accelerometerListener = OnCarDataAvailableListener<Accelerometer> { data ->
        put(GROUP_SENSORS, "accelerometer.forces", data.forces, "x,y,z")
        emit()
    }

    private val gyroscopeListener = OnCarDataAvailableListener<Gyroscope> { data ->
        put(GROUP_SENSORS, "gyroscope.rotations", data.rotations, "x,y,z")
        emit()
    }

    private val compassListener = OnCarDataAvailableListener<Compass> { data ->
        put(GROUP_SENSORS, "compass.orientations", data.orientations, "deg")
        emit()
    }

    private val locationListener = OnCarDataAvailableListener<CarHardwareLocation> { data ->
        put(GROUP_SENSORS, "carHardwareLocation", data.location)
        emit()
    }

    private val evStatusListener = OnCarDataAvailableListener<EvStatus> { data ->
        put(GROUP_EV, "evChargePortOpen", data.evChargePortOpen)
        put(GROUP_EV, "evChargePortConnected", data.evChargePortConnected)
        emit()
    }

    fun start() {
        if (started) return
        started = true

        seedPlaceholders()
        emitNow()

        val carInfo = carHardware.carInfo

        // One-shot fetches.
        carInfo.fetchModel(executor) { data ->
            put(GROUP_MODEL, "manufacturer", data.manufacturer)
            put(GROUP_MODEL, "name", data.name)
            put(GROUP_MODEL, "year", data.year)
            emit()
        }
        carInfo.fetchEnergyProfile(executor) { data ->
            put(GROUP_MODEL, "evConnectorTypes", data.evConnectorTypes)
            put(GROUP_MODEL, "fuelTypes", data.fuelTypes)
            emit()
        }

        // ExteriorDimensions needs a newer host than the rest; say so rather than
        // leaving the row looking like the car declined to answer.
        if (carContext.carAppApiLevel >= EXTERIOR_DIMENSIONS_API_LEVEL) {
            carInfo.fetchExteriorDimensions(executor) { data ->
                put(GROUP_MODEL, "exteriorDimensions", data.exteriorDimensions, "mm")
                emit()
            }
        } else {
            mark(GROUP_MODEL, "exteriorDimensions", FieldStatus.UNSUPPORTED_HOST)
        }

        // Continuous listeners.
        carInfo.addEnergyLevelListener(executor, energyLevelListener)
        carInfo.addSpeedListener(executor, speedListener)
        carInfo.addMileageListener(executor, mileageListener)
        carInfo.addTollListener(executor, tollListener)
        carInfo.addEvStatusListener(executor, evStatusListener)

        val sensors = carHardware.carSensors
        sensors.addAccelerometerListener(CarSensors.UPDATE_RATE_NORMAL, executor, accelerometerListener)
        sensors.addGyroscopeListener(CarSensors.UPDATE_RATE_NORMAL, executor, gyroscopeListener)
        sensors.addCompassListener(CarSensors.UPDATE_RATE_NORMAL, executor, compassListener)
        sensors.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_NORMAL, executor, locationListener)

        // Nothing obliges the host to answer, and several fields on a typical car
        // never do. Without a deadline those rows sit on "waiting" forever, which
        // reads as the app hanging rather than as the car declining.
        timeoutHandler.postDelayed(::markSilentFields, RESPONSE_DEADLINE_MS)
    }

    /** Turns "still waiting" into a stated result once the deadline passes. */
    private fun markSilentFields() {
        var changed = false
        fields.entries.forEach { (key, field) ->
            if (field.status == FieldStatus.NOT_PROBED) {
                fields[key] = field.copy(status = FieldStatus.NO_RESPONSE)
                changed = true
            }
        }
        if (changed) emit()
    }

    fun stop() {
        if (!started) return
        started = false
        timeoutHandler.removeCallbacksAndMessages(null)
        emitScheduled = false

        val carInfo = carHardware.carInfo
        carInfo.removeEnergyLevelListener(energyLevelListener)
        carInfo.removeSpeedListener(speedListener)
        carInfo.removeMileageListener(mileageListener)
        carInfo.removeTollListener(tollListener)
        carInfo.removeEvStatusListener(evStatusListener)

        val sensors = carHardware.carSensors
        sensors.removeAccelerometerListener(accelerometerListener)
        sensors.removeGyroscopeListener(gyroscopeListener)
        sensors.removeCompassListener(compassListener)
        sensors.removeCarHardwareLocationListener(locationListener)
    }

    /**
     * Fields start as NOT_PROBED so the UI distinguishes "the platform never called
     * us back" from "the platform answered UNAVAILABLE". Those mean different things:
     * the first is usually a missing permission, the second is a missing signal.
     */
    private fun seedPlaceholders() {
        fun seed(group: String, name: String, note: String = "") {
            val key = "$group/$name"
            fields[key] = CarField(group, name, FieldStatus.NOT_PROBED, "—", note)
        }
        seed(GROUP_MODEL, "manufacturer")
        seed(GROUP_MODEL, "name")
        seed(GROUP_MODEL, "year")
        seed(GROUP_MODEL, "evConnectorTypes")
        seed(GROUP_MODEL, "fuelTypes")
        seed(GROUP_MODEL, "exteriorDimensions", "mm")
        seed(GROUP_EV, "evChargePortOpen")
        seed(GROUP_EV, "evChargePortConnected")
        seed(GROUP_ENERGY, "batteryPercent", "%")
        seed(GROUP_ENERGY, "fuelPercent", "%")
        seed(GROUP_ENERGY, "rangeRemainingMeters", "m")
        seed(GROUP_ENERGY, "energyIsLow")
        seed(GROUP_ENERGY, "distanceDisplayUnit")
        seed(GROUP_ENERGY, "fuelVolumeDisplayUnit")
        seed(GROUP_SPEED, "rawSpeedMetersPerSecond", "m/s")
        seed(GROUP_SPEED, "displaySpeedMetersPerSecond", "m/s")
        seed(GROUP_SPEED, "speedDisplayUnit")
        seed(GROUP_MILEAGE, "odometerMeters", "m")
        seed(GROUP_MILEAGE, "distanceDisplayUnit")
        seed(GROUP_TOLL, "cardState")
        seed(GROUP_SENSORS, "accelerometer.forces", "x,y,z")
        seed(GROUP_SENSORS, "gyroscope.rotations", "x,y,z")
        seed(GROUP_SENSORS, "compass.orientations", "deg")
        seed(GROUP_SENSORS, "carHardwareLocation")
    }

    private fun <T : Any> put(group: String, name: String, carValue: CarValue<T>, note: String = "") {
        val key = "$group/$name"
        val existing = fields[key]
        fields[key] = CarField(
            group = group,
            name = name,
            status = carValue.status.toFieldStatus(),
            value = carValue.value?.render() ?: "—",
            note = note.ifEmpty { existing?.note.orEmpty() },
        )
    }

    private fun mark(group: String, name: String, status: FieldStatus) {
        val key = "$group/$name"
        fields[key]?.let { fields[key] = it.copy(status = status) }
    }

    /**
     * Coalesces updates instead of publishing one per callback.
     *
     * Speed and the motion sensors fire many times a second, and forwarding each
     * one drove a redraw of the car screen that fast, which is not just wasteful —
     * a list rebuilding continuously never stays still long enough to be tapped.
     */
    private fun emit() {
        if (emitScheduled) return
        emitScheduled = true
        timeoutHandler.postDelayed(
            {
                emitScheduled = false
                emitNow()
            },
            EMIT_INTERVAL_MS,
        )
    }

    private fun emitNow() {
        onSnapshot(ProbeSnapshot(platform = platformLabel(), fields = fields.values.toList()))
    }

    private fun platformLabel(): String {
        val kind = if (carContext.packageManager.hasSystemFeature(FEATURE_AUTOMOTIVE)) {
            "Android Automotive OS"
        } else {
            "Android Auto (projected)"
        }
        return "$kind · Car App API level ${carContext.carAppApiLevel}"
    }

    private companion object {
        const val FEATURE_AUTOMOTIVE = "android.hardware.type.automotive"

        /** fetchExteriorDimensions was added at Car App API level 7. */
        const val EXTERIOR_DIMENSIONS_API_LEVEL = 7
        const val RESPONSE_DEADLINE_MS = 12_000L
        const val EMIT_INTERVAL_MS = 1_000L

        const val GROUP_MODEL = "Model / EnergyProfile"
        const val GROUP_EV = "EvStatus"
        const val GROUP_ENERGY = "EnergyLevel"
        const val GROUP_SPEED = "Speed"
        const val GROUP_MILEAGE = "Mileage"
        const val GROUP_TOLL = "TollCard"
        const val GROUP_SENSORS = "CarSensors"
    }
}

private fun Int.toFieldStatus(): FieldStatus = when (this) {
    CarValue.STATUS_SUCCESS -> FieldStatus.SUCCESS
    CarValue.STATUS_UNAVAILABLE -> FieldStatus.UNAVAILABLE
    CarValue.STATUS_UNKNOWN -> FieldStatus.UNKNOWN
    CarValue.STATUS_UNIMPLEMENTED -> FieldStatus.UNIMPLEMENTED
    else -> FieldStatus.ERROR
}

private fun Any.render(): String = when (this) {
    is List<*> -> joinToString(", ") { it?.toString() ?: "null" }
    is FloatArray -> joinToString(", ")
    is IntArray -> joinToString(", ")
    else -> toString()
}
