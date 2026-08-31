package dev.carapps.probe.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches an uncaught exception and leaves the stack trace on disk for the next
 * launch to report.
 *
 * A crash on the car display is close to opaque: the screen simply returns to the
 * launcher, and the only account of it lives in a logcat nobody is attached to.
 * Persisting the trace means the phone screen can show what happened afterwards,
 * which is the difference between a report that can be acted on and one that says
 * the app stopped.
 */
object CrashLog {

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            // Always hand back to the platform handler: swallowing it would leave
            // the process in a broken state instead of dying cleanly, and would
            // also hide the crash from Play's own reporting.
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? = runCatching {
        val file = file(context)
        if (file.exists()) file.readText().ifBlank { null } else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { writer ->
            PrintWriter(writer).use(throwable::printStackTrace)
        }
        file(context).writeText(
            buildString {
                appendLine("time   : ${timestamp()}")
                appendLine("thread : ${thread.name}")
                appendLine()
                append(trace)
            }
        )
    }

    private fun timestamp() =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")
}
