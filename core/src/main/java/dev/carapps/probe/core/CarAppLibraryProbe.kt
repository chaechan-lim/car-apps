package dev.carapps.probe.core

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
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import java.util.concurrent.Executor

/**
 * Probes every field the Car App Library exposes and reports the status of each.
 *
 * Works on both Android Auto (`app-projected`) and Android Automotive OS
 * (`app-automotive`), which is exactly what makes it useful — the same build run
 * on both platforms shows the asymmetries directly. Known ones going in:
 *
 *  - `Mileage` is documented as unavailable to AAOS apps installed from Google Play.
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

    fun start() {
        if (started) return
        started = true

        seedPlaceholders()
        emit()

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

        // Continuous listeners.
        carInfo.addEnergyLevelListener(executor, energyLevelListener)
        carInfo.addSpeedListener(executor, speedListener)
        carInfo.addMileageListener(executor, mileageListener)
        carInfo.addTollListener(executor, tollListener)

        val sensors = carHardware.carSensors
        sensors.addAccelerometerListener(CarSensors.UPDATE_RATE_NORMAL, executor, accelerometerListener)
        sensors.addGyroscopeListener(CarSensors.UPDATE_RATE_NORMAL, executor, gyroscopeListener)
        sensors.addCompassListener(CarSensors.UPDATE_RATE_NORMAL, executor, compassListener)
        sensors.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_NORMAL, executor, locationListener)
    }

    fun stop() {
        if (!started) return
        started = false

        val carInfo = carHardware.carInfo
        carInfo.removeEnergyLevelListener(energyLevelListener)
        carInfo.removeSpeedListener(speedListener)
        carInfo.removeMileageListener(mileageListener)
        carInfo.removeTollListener(tollListener)

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

    private fun emit() {
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

        const val GROUP_MODEL = "Model / EnergyProfile"
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
