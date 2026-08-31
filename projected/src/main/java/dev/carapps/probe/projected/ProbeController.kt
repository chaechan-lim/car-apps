package dev.carapps.probe.projected

import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.core.content.ContextCompat
import dev.carapps.probe.core.CarAppLibraryProbe
import dev.carapps.probe.core.ProbeSnapshot
import dev.carapps.probe.core.ReportExport
import dev.carapps.probe.core.ReportStore

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
                autoSave(carContext, newSnapshot)
                listeners.toList().forEach { it() }
            }
        }
        probe?.start()
    }

    fun stop() {
        // A last write with the settled values, since this is where a drive ends.
        snapshot?.let { lastSaved = 0L; save(savedContext, it) }
        probe?.stop()
        probe = null
        snapshot = null
        savedContext = null
    }

    /**
     * Persists without anyone touching the car screen.
     *
     * While the car is moving the host blocks interactions — tapping Log to save a
     * report is exactly the kind of thing it refuses — so requiring a tap meant the
     * report could only be captured parked, which is not when the interesting data
     * exists. Saving on a timer removes the interaction instead of fighting the
     * restriction.
     */
    private fun autoSave(carContext: CarContext, newSnapshot: ProbeSnapshot) {
        savedContext = carContext
        val now = SystemClock.elapsedRealtime()
        if (now - lastSaved < AUTO_SAVE_INTERVAL_MS) return
        lastSaved = now
        save(carContext, newSnapshot)
    }

    private fun save(context: CarContext?, newSnapshot: ProbeSnapshot) {
        val target = context ?: return
        runCatching {
            ReportStore(target).write(
                ReportExport.environmentHeader(target) + "\n" + newSnapshot.toReport()
            )
        }
    }

    private var savedContext: CarContext? = null
    private var lastSaved = 0L
    private const val AUTO_SAVE_INTERVAL_MS = 15_000L

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}
