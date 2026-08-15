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
        requestCarPermissionsThenStart()
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

        groups.entries.take(limit).forEach { (group, fields) ->
            val available = fields.count { it.status == FieldStatus.SUCCESS }
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(group)
                    .addText("$available / ${fields.size} available")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(FieldsScreen(carContext, group)) }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("${snapshot.availableCount} / ${snapshot.fields.size} available")
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
        snapshot.toReport().lineSequence().forEach { Log.i(TAG, it) }
        CarToast.makeText(carContext, "Report written to logcat (tag: $TAG)", CarToast.LENGTH_LONG)
            .show()
    }

    private fun requestCarPermissionsThenStart() {
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
            // Start regardless: a rejected permission is itself a result worth
            // seeing, and it shows up as UNAVAILABLE rather than a crash.
            ProbeController.start(carContext)
            invalidate()
        }
    }

    private companion object {
        const val TAG = "CarProbe"

        /** CarHardwareManager, and therefore everything this app measures. */
        const val REQUIRED_API_LEVEL = 3
    }
}
