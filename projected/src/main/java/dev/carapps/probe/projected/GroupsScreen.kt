package dev.carapps.probe.projected

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.carapps.probe.core.FieldStatus

/**
 * Every field grouped by the API it came from, including the ones that answered
 * nothing.
 *
 * Reached from [RootScreen] and mostly usable parked, since the host restricts
 * navigation once the car moves. Nothing here is needed while driving: the live
 * readings are on the root screen and the full table is written to disk anyway.
 */
class GroupsScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var lastSignature: String? = null

    // Counts only, so this settles within seconds and then stops redrawing.
    private val onProbeUpdate: () -> Unit = {
        val signature = ProbeController.snapshot?.fields
            ?.joinToString("|") { "${it.group}=${it.status.name}" }
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
    }

    override fun onStop(owner: LifecycleOwner) {
        ProbeController.removeListener(onProbeUpdate)
    }

    override fun onGetTemplate(): Template {
        val snapshot = ProbeController.snapshot
            ?: return ListTemplate.Builder()
                .setHeader(header("All fields"))
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
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(group)
                    .addText(
                        if (waiting > 0) "$given given · $waiting waiting"
                        else "$given of ${fields.size} given"
                    )
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(FieldsScreen(carContext, group)) }
                    .build()
            )
        }

        if (truncated) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("${groups.size - shown} more group(s)")
                    .addText("This host caps the list — the saved report has all of them")
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(header(snapshot.verdict))
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun header(title: String) = Header.Builder()
        .setTitle(title)
        .setStartHeaderAction(Action.BACK)
        .build()
}
