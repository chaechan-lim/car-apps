package dev.carapps.parking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Collects the two signals that survive underground.
 *
 * The barometer is the floor signal: pressure rises about 0.36 hPa per level down,
 * which is far above the noise floor of a phone sensor. It measures a difference,
 * not an altitude, so losing GPS costs it nothing.
 *
 * The gyroscope counts ramps. Descending a spiral shows up as yaw accumulating in
 * multiples of 360 degrees, which is a second, independent read on how far down the
 * car went.
 */
class DriveSensors(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val samples = mutableListOf<ParkingEvent.Sample>()
    private var startedAtElapsed = 0L
    private var lastSampleAt = 0L
    private var lastGyroAt = 0L

    /** Cumulative yaw. Sign is kept so a ramp down and back up do not read as two ramps. */
    var yawDegrees = 0f
        private set

    val pressureSamples: List<ParkingEvent.Sample> get() = samples.toList()

    fun start() {
        startedAtElapsed = SystemClock.elapsedRealtime()
        lastSampleAt = 0L
        lastGyroAt = 0L
        samples.clear()
        yawDegrees = 0f
        barometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    val hasBarometer: Boolean get() = barometer != null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> onPressure(event)
            Sensor.TYPE_GYROSCOPE -> onGyroscope(event)
        }
    }

    private fun onPressure(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        // The barometer reports far faster than the signal changes, and a drive can
        // last an hour, so thin it to something a phone can hold and a person can read.
        if (lastSampleAt != 0L && now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        lastSampleAt = now
        samples += ParkingEvent.Sample(now - startedAtElapsed, event.values[0], yawDegrees)
    }

    private fun onGyroscope(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (lastGyroAt != 0L) {
            val seconds = (now - lastGyroAt) / 1000f
            // Z is yaw with the phone lying flat; good enough to count ramp turns
            // without tracking the phone's orientation.
            yawDegrees += Math.toDegrees(event.values[2].toDouble()).toFloat() * seconds
        }
        lastGyroAt = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SAMPLE_INTERVAL_MS = 2_000L
    }
}
