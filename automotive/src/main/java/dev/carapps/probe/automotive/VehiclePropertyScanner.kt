package dev.carapps.probe.automotive

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import java.lang.reflect.Modifier

/**
 * Enumerates every property ID declared by [VehiclePropertyIds] and reports which
 * of them this app can actually read.
 *
 * Three outcomes matter and they are different things:
 *  - READABLE   — the property is in getPropertyList() and returned a value.
 *  - NO_ACCESS  — the ID exists in the SDK but not in getPropertyList(), i.e. the
 *                 permission guarding it is OEM-signature or simply not granted.
 *  - NO_VALUE   — permitted, but the vehicle answered with an error or null.
 */
class VehiclePropertyScanner(private val context: Context) {

    enum class Access { READABLE, NO_ACCESS, NO_VALUE }

    data class PropertyResult(
        val name: String,
        val propertyId: Int,
        val access: Access,
        val value: String,
        val areaCount: Int = 0,
    )

    data class ScanResult(
        val results: List<PropertyResult>,
        val error: String? = null,
    ) {
        val readable: Int get() = results.count { it.access == Access.READABLE }
        val permitted: Int get() = results.count { it.access != Access.NO_ACCESS }

        fun toReport(): String = buildString {
            appendLine("# AAOS CarPropertyManager scan")
            if (error != null) {
                appendLine("# ERROR: $error")
                return@buildString
            }
            appendLine("# ${results.size} property IDs known to the SDK")
            appendLine("# $permitted permitted to this app")
            appendLine("# $readable returned a value")
            appendLine()
            // Readable first — that is the shortlist any real feature has to live within.
            results.sortedBy { it.access.ordinal }.forEach {
                appendLine("${it.access.name.padEnd(10)} ${it.name.padEnd(46)} ${it.value}")
            }
        }
    }

    fun scan(): ScanResult {
        val car = try {
            Car.createCar(context)
        } catch (t: Throwable) {
            return ScanResult(emptyList(), "Car service unavailable: ${t.message}")
        } ?: return ScanResult(emptyList(), "Car.createCar() returned null")

        return try {
            val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                ?: return ScanResult(emptyList(), "CarPropertyManager unavailable")

            val permitted: Map<Int, CarPropertyConfig<*>> =
                manager.propertyList.associateBy { it.propertyId }

            val results = knownPropertyIds().map { (name, id) ->
                val config = permitted[id]
                if (config == null) {
                    PropertyResult(name, id, Access.NO_ACCESS, "—")
                } else {
                    readProperty(manager, config, name, id)
                }
            }
            ScanResult(results)
        } catch (t: Throwable) {
            ScanResult(emptyList(), "Scan failed: ${t.message}")
        } finally {
            runCatching { car.disconnect() }
        }
    }

    private fun readProperty(
        manager: CarPropertyManager,
        config: CarPropertyConfig<*>,
        name: String,
        id: Int,
    ): PropertyResult {
        val areaId = config.areaIds.firstOrNull() ?: 0
        return try {
            @Suppress("UNCHECKED_CAST")
            val propertyValue = manager.getProperty(
                config.propertyType as Class<Any>,
                id,
                areaId,
            )
            val rendered = propertyValue?.value?.render()
            if (rendered == null) {
                PropertyResult(name, id, Access.NO_VALUE, "null", config.areaIds.size)
            } else {
                PropertyResult(name, id, Access.READABLE, rendered, config.areaIds.size)
            }
        } catch (t: Throwable) {
            PropertyResult(name, id, Access.NO_VALUE, "${t.javaClass.simpleName}: ${t.message}", config.areaIds.size)
        }
    }

    /**
     * VehiclePropertyIds has no public enumeration, so the constants are read off
     * the class. This also means the list automatically tracks whichever SDK level
     * the app was compiled against.
     */
    private fun knownPropertyIds(): List<Pair<String, Int>> =
        VehiclePropertyIds::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.name to field.getInt(null)
                }.getOrNull()
            }
            .filter { it.second != 0 }
            .sortedBy { it.first }
}

private fun Any.render(): String = when (this) {
    is IntArray -> joinToString(", ")
    is FloatArray -> joinToString(", ")
    is LongArray -> joinToString(", ")
    is Array<*> -> joinToString(", ") { it?.toString() ?: "null" }
    is List<*> -> joinToString(", ") { it?.toString() ?: "null" }
    else -> toString()
}
