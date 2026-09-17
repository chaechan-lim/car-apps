package dev.carapps.probe.projected

import android.content.pm.PackageManager
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
 * The values themselves, on the first screen.
 *
 * Everything the car actually reports used to live one tap deeper, which put it out
 * of reach exactly when it is interesting: the host blocks navigation while the car
 * is moving, so the readings could only be read parked. Fields that answered are now
 * listed here directly, and the drill-down is kept for the ones that did not.
 */
class RootScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var lastSignature: String? = null

    // Values change constantly, so this redraws about once a second — the rate the
    // probe publishes at. Fast enough to read, slow enough to tap.
    private val onProbeUpdate: () -> Unit = {
        val signature = ProbeController.snapshot?.fields
            ?.joinToString("|") { "${it.name}=${it.status.name}:${it.value}" }
        if (signature != lastSignature) {
            lastSignature = signature
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        ProbeController.addListener(onProbeUpdate)
        runCatching { startProbe() }
            .onFailure { Log.e(TAG, "probe start failed", it) }
    }

    override fun onStop(owner: LifecycleOwner) {
        ProbeController.removeListener(onProbeUpdate)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        ProbeController.stop()
    }

    private fun startProbe() {
        // Start before asking for permissions. Gating the probe on the permission
        // callback meant an unanswered prompt — it appears on the phone, which the
        // driver is not looking at — left the screen with nothing to show.
        ProbeController.start(carContext)
        requestCarPermissions()
    }

    override fun onGetTemplate(): Template = try {
        buildTemplate()
    } catch (t: Throwable) {
        Log.e(TAG, "template build failed", t)
        MessageTemplate.Builder("${t.javaClass.simpleName}: ${t.message}")
            .setHeader(header("Template error"))
            .build()
    }

    private fun buildTemplate(): Template {
        if (carContext.carAppApiLevel < REQUIRED_API_LEVEL) {
            return MessageTemplate.Builder(
                "This host is Car App API level ${carContext.carAppApiLevel}. " +
                    "Vehicle data needs level $REQUIRED_API_LEVEL, so there is nothing to probe."
            ).setHeader(header(carContext.getString(R.string.app_name))).build()
        }

        val snapshot = ProbeController.snapshot
            ?: return ListTemplate.Builder()
                .setHeader(header(carContext.getString(R.string.app_name)))
                .setLoading(true)
                .build()

        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

        val listBuilder = ItemList.Builder()
        val answered = snapshot.fields.filter { it.status == FieldStatus.SUCCESS }

        // One row is always spent on the drill-down, so the readings get the rest.
        val room = (limit - 1).coerceAtLeast(1)
        answered.take(room).forEach { field ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(field.label)
                    .addText(field.value)
                    .build()
            )
        }

        if (answered.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("No readings yet")
                    .addText(
                        if (snapshot.pendingCount > 0) "Waiting for the car to answer"
                        else "This car reported nothing"
                    )
                    .build()
            )
        }

        // On screen, not buried in the log. A field gated behind a permission this
        // app has not been granted looks exactly like a field the car withholds, and
        // the two were confused for weeks — so the thing that tells them apart
        // belongs next to the readings rather than in a report nobody opens while
        // driving.
        val missing = CAR_PERMISSIONS.filter {
            carContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("${missing.size} permission(s) not granted")
                    .addText(
                        missing.joinToString { it.substringAfterLast('.') } +
                            " — answer the prompt on the phone"
                    )
                    .build()
            )
        }

        val hidden = (answered.size - room).coerceAtLeast(0)
        listBuilder.addItem(
            Row.Builder()
                .setTitle("All fields" + if (hidden > 0) " (+$hidden more)" else "")
                .addText("${snapshot.fields.size} probed · park to browse")
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(GroupsScreen(carContext)) }
                .build()
        )

        return ListTemplate.Builder()
            .setHeader(header(snapshot.verdict, logAction = true))
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun header(title: String, logAction: Boolean = false) = Header.Builder()
        .setTitle(title)
        .setStartHeaderAction(Action.APP_ICON)
        .apply {
            if (logAction) {
                addEndHeaderAction(
                    Action.Builder()
                        .setTitle("Log")
                        .setOnClickListener { dumpReport() }
                        .build()
                )
            }
        }
        .build()

    /**
     * The report is written automatically every 15 seconds, so this is only a way to
     * force one early. It stays because it also mirrors the table to logcat.
     */
    private fun dumpReport() {
        val snapshot = ProbeController.snapshot ?: return
        val report = ReportExport.environmentHeader(carContext) + "\n" + snapshot.toReport()
        report.lineSequence().forEach { Log.i(TAG, it) }
        ReportStore(carContext).write(report)
        CarToast.makeText(carContext, "Report saved", CarToast.LENGTH_SHORT).show()
    }

    private fun requestCarPermissions() {
        val permissions = CAR_PERMISSIONS
        val alreadyHeld = permissions.all {
            carContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        // Android Auto surfaces this prompt on the phone, not the head unit.
        carContext.requestPermissions(permissions) { granted, rejected ->
            Log.i(TAG, "permissions granted=$granted rejected=$rejected")
            // Re-subscribe, or the grant changes nothing. The probe was started
            // before the prompt so the screen would have something to show while it
            // went unanswered, and subscriptions taken without permission fail
            // silently and stay failed. Every field gated behind CAR_FUEL,
            // CAR_SPEED or CAR_MILEAGE therefore reported "no response" on a car
            // that supplies it — this app called a permission of its own a property
            // of the vehicle, and it did so in the one report meant to settle what
            // the vehicle supplies.
            if (!alreadyHeld && granted.isNotEmpty()) {
                Log.i(TAG, "re-subscribing now that permissions are held")
                ProbeController.restart(carContext)
            }
            invalidate()
        }
    }

    private companion object {
        const val TAG = "CarProbe"

        /**
         * Android Auto gates car data behind these, and a subscription taken without
         * them fails without saying so. Declared in the manifest as well; this list is
         * what gets requested and what gets checked on screen.
         */
        val CAR_PERMISSIONS = listOf(
            "com.google.android.gms.permission.CAR_FUEL",
            "com.google.android.gms.permission.CAR_SPEED",
            "com.google.android.gms.permission.CAR_MILEAGE",
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )

        /** CarHardwareManager, and therefore everything this app measures. */
        const val REQUIRED_API_LEVEL = 3
    }
}
