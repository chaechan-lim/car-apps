package dev.carapps.probe.projected

import androidx.car.app.CarContext
import androidx.core.content.ContextCompat
import dev.carapps.probe.core.CarAppLibraryProbe
import dev.carapps.probe.core.ProbeSnapshot

/**
 * Holds the probe for the lifetime of the car session so the root screen and the
 * per-group detail screens observe the same snapshot instead of each starting
 * their own listeners.
 */
object ProbeController {

    private var probe: CarAppLibraryProbe? = null
    private val listeners = mutableSetOf<() -> Unit>()

    @Volatile
    var snapshot: ProbeSnapshot? = null
        private set

    fun start(carContext: CarContext) {
        if (probe == null) {
            probe = CarAppLibraryProbe(
                carContext = carContext,
                executor = ContextCompat.getMainExecutor(carContext),
            ) { newSnapshot ->
                snapshot = newSnapshot
                listeners.toList().forEach { it() }
            }
        }
        probe?.start()
    }

    fun stop() {
        probe?.stop()
        probe = null
        snapshot = null
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}
