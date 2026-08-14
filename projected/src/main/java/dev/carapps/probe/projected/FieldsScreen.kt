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

/** Per-group detail: every field with its live value and its status. */
class FieldsScreen(
    carContext: CarContext,
    private val group: String,
) : Screen(carContext), DefaultLifecycleObserver {

    private val onProbeUpdate: () -> Unit = { invalidate() }

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
        val fields = ProbeController.snapshot?.fields.orEmpty().filter { it.group == group }
        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

        val listBuilder = ItemList.Builder()
        if (fields.isEmpty()) {
            listBuilder.setNoItemsMessage("No fields probed yet")
        } else {
            fields.take(limit).forEach { field ->
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(field.label)
                        .addText(field.status.display)
                        .addText(field.value)
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(group)
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }
}
