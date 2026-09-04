package dev.carapps.parking

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A durable trace of the trigger, because the trigger is the part that failed.
 *
 * The first recordings came back with a parked event but no drive behind it: the
 * disconnect was heard and the connect was not. Which of the possible reasons that
 * is — the broadcast never arriving, the service being refused a foreground start
 * from the background, or the process being killed mid-drive and restarted with its
 * state gone — cannot be told apart from the recording itself, since all three
 * produce the same empty result.
 *
 * So every trigger and every service transition is written down as it happens.
 * Logcat would do the same job, but only for someone holding a cable at the moment
 * it goes wrong, and this fails in a car park.
 */
object DebugLog {

    fun write(context: Context, line: String) {
        Log.i(TAG, line)
        runCatching {
            val file = file(context)
            val stamped = "${timestamp()}  $line"
            val existing = if (file.exists()) file.readLines() else emptyList()
            val kept = (existing + stamped).takeLast(MAX_LINES)
            file.writeText(kept.joinToString("\n"))
        }
    }

    fun read(context: Context): List<String> = runCatching {
        val file = file(context)
        if (file.exists()) file.readLines() else emptyList()
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun timestamp() =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun file(context: Context) = File(context.filesDir, "trigger-log.txt")

    private const val TAG = "Parking"
    private const val MAX_LINES = 300
}
