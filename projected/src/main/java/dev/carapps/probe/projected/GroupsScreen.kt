package dev.carapps.probe.projected

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.carapps.probe.core.FieldStatus
import dev.carapps.probe.core.ReportExport
import dev.carapps.probe.core.ReportStore

/**
 * Root screen: one row per data group, showing how many of its fields the head
 * unit actually answered. Drilling in keeps every list under the host's content
 * limit and keeps the app within the "five screens or fewer" guideline (AC-1).
 */
class GroupsScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val onProbeUpdate: () -> Unit = { invalidate() }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        ProbeController.addListener(onProbeUpdate)
        // Start before asking for permissions, not after. Gating the probe on the
        // permission callback meant that if the prompt was never answered — it
        // appears on the phone, which the driver may not be looking at — nothing
        // ever emitted a snapshot and the car screen sat on a spinner forever.
        // Starting first seeds every field as "waiting", so there is always a list.
        ProbeController.start(carContext)
        requestCarPermissions()
    }

    override fun onStop(owner: LifecycleOwner) {
        ProbeController.removeListener(onProbeUpdate)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        ProbeController.stop()
    }

    override fun onGetTemplate(): Template {
        // CarHardwareManager arrived in Car App API level 3. The manifest declares
        // level 1 so that older hosts still launch the app and can report this,
        // rather than filtering it out of the launcher with no explanation.
        if (carContext.carAppApiLevel < REQUIRED_API_LEVEL) {
            return MessageTemplate.Builder(
                "This host is Car App API level ${carContext.carAppApiLevel}. " +
                    "Vehicle data needs level $REQUIRED_API_LEVEL, so there is nothing to probe."
            )
                .setHeader(
                    Header.Builder()
                        .setTitle(carContext.getString(R.string.app_name))
                        .setStartHeaderAction(Action.APP_ICON)
                        .build()
                )
                .build()
        }

        val snapshot = ProbeController.snapshot
            ?: return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle(carContext.getString(R.string.app_name))
                        .setStartHeaderAction(Action.APP_ICON)
                        .build()
                )
                .setLoading(true)
                .build()

        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

        val groups = snapshot.fields.groupBy { it.group }
        val listBuilder = ItemList.Builder()

        // One row short of the limit when truncating, so the last row can say so
        // rather than a group vanishing without trace.
        val truncated = groups.size > limit
        val shown = if (truncated) limit - 1 else groups.size

        groups.entries.take(shown).forEach { (group, fields) ->
            val given = fields.count { it.status == FieldStatus.SUCCESS }
            val waiting = fields.count { it.status == FieldStatus.NOT_PROBED }
            val summary = if (waiting > 0) {
                "$given given · $waiting waiting"
            } else {
                "$given of ${fields.size} given"
            }
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(group)
                    .addText(summary)
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(FieldsScreen(carContext, group)) }
                    .build()
            )
        }

        if (truncated) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("${groups.size - shown} more group(s)")
                    .addText("This host caps the list — tap Log for the full report")
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(snapshot.verdict)
                    .setStartHeaderAction(Action.APP_ICON)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Log")
                            .setOnClickListener { dumpReport() }
                            .build()
                    )
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }

    /**
     * The car screen is a poor place to read a 21-field table, so the full report
     * goes to logcat where it can be captured verbatim:
     *   adb logcat -s CarProbe
     */
    private fun dumpReport() {
        val snapshot = ProbeController.snapshot ?: return
        val report = ReportExport.environmentHeader(carContext) + "\n" + snapshot.toReport()

        report.lineSequence().forEach { Log.i(TAG, it) }
        // Persisted as well as logged: the session is usually gone by the time
        // anyone opens the phone app to send it anywhere.
        ReportStore(carContext).write(report)

        CarToast.makeText(
            carContext,
            "Report saved — open Car Probe on the phone to send it",
            CarToast.LENGTH_LONG,
        ).show()
    }

    private fun requestCarPermissions() {
        if (carContext.carAppApiLevel < REQUIRED_API_LEVEL) {
            Log.w(TAG, "host is API level ${carContext.carAppApiLevel}, skipping probe")
            return
        }
        val permissions = listOf(
            "com.google.android.gms.permission.CAR_FUEL",
            "com.google.android.gms.permission.CAR_SPEED",
            "com.google.android.gms.permission.CAR_MILEAGE",
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        // Android Auto surfaces this prompt on the phone, not the head unit.
        carContext.requestPermissions(permissions) { granted, rejected ->
            Log.i(TAG, "permissions granted=$granted rejected=$rejected")
            // The probe is already running; a granted permission simply makes more
            // fields start answering. A rejected one is a result in itself.
            invalidate()
        }
    }

    private companion object {
        const val TAG = "CarProbe"

        /** CarHardwareManager, and therefore everything this app measures. */
        const val REQUIRED_API_LEVEL = 3
    }
}
