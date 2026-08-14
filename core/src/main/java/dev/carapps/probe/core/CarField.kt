package dev.carapps.probe.core

/**
 * One probed vehicle data point.
 *
 * The whole point of this app is [status]: the Car App Library happily hands back
 * a value object for every field, but on most head units a good number of them
 * carry STATUS_UNAVAILABLE or STATUS_UNIMPLEMENTED. Which ones actually resolve
 * to SUCCESS varies per car, per model year and per platform, and Google does not
 * publish a matrix — so we measure it.
 */
data class CarField(
    val group: String,
    val name: String,
    val status: FieldStatus,
    val value: String,
    /** Unit or extra context, e.g. "m/s" or "raw". */
    val note: String = "",
) {
    val label: String get() = if (note.isEmpty()) name else "$name ($note)"
}

enum class FieldStatus(val display: String) {
    SUCCESS("✔ SUCCESS"),
    UNAVAILABLE("✖ UNAVAILABLE"),
    UNKNOWN("? UNKNOWN"),
    UNIMPLEMENTED("∅ UNIMPLEMENTED"),
    NOT_PROBED("… waiting"),
    ERROR("! ERROR"),
}

/** A full snapshot, ordered for display and easy to serialise into a report. */
data class ProbeSnapshot(
    val platform: String,
    val fields: List<CarField>,
) {
    val availableCount: Int get() = fields.count { it.status == FieldStatus.SUCCESS }

    fun toReport(): String = buildString {
        appendLine("# Car data probe — $platform")
        appendLine("# $availableCount / ${fields.size} fields available")
        appendLine()
        var lastGroup = ""
        for (field in fields) {
            if (field.group != lastGroup) {
                appendLine()
                appendLine("[${field.group}]")
                lastGroup = field.group
            }
            appendLine("  ${field.label.padEnd(34)} ${field.status.display.padEnd(18)} ${field.value}")
        }
    }
}
