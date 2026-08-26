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

    /**
     * The host never called back at all, even to say unavailable. Distinct from
     * UNAVAILABLE, which is an answer — this is silence, and without a deadline it
     * is indistinguishable from a probe that is still running.
     */
    NO_RESPONSE("✖ NO RESPONSE"),

    /** Needs a newer Car App API level than this host offers. */
    UNSUPPORTED_HOST("— needs newer host"),

    ERROR("! ERROR"),
}

/** A full snapshot, ordered for display and easy to serialise into a report. */
data class ProbeSnapshot(
    val platform: String,
    val fields: List<CarField>,
) {
    val availableCount: Int get() = fields.count { it.status == FieldStatus.SUCCESS }
    val pendingCount: Int get() = fields.count { it.status == FieldStatus.NOT_PROBED }

    /** One line that answers "did this car give the data or not". */
    val verdict: String
        get() {
            val refused = fields.count {
                it.status == FieldStatus.UNAVAILABLE ||
                    it.status == FieldStatus.UNIMPLEMENTED ||
                    it.status == FieldStatus.NO_RESPONSE
            }
            return "$availableCount given · $refused not given" +
                if (pendingCount > 0) " · $pendingCount waiting" else ""
        }

    fun toReport(): String = buildString {
        appendLine("# Car data probe — $platform")
        appendLine("# $verdict")
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
