package dev.carapps.probe.core

import android.content.Context
import java.io.File

/**
 * Persists the most recent report to disk.
 *
 * The probe runs inside a car session, but the report is read on the phone, often
 * after the car has been disconnected and the session torn down. Holding it in
 * memory would lose it exactly when someone goes looking for it.
 */
class ReportStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun write(report: String) {
        runCatching { file.writeText(report) }
    }

    fun read(): String? = runCatching {
        if (file.exists()) file.readText().ifBlank { null } else null
    }.getOrNull()

    private companion object {
        const val FILE_NAME = "last-report.txt"
    }
}
