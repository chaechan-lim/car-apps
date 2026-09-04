package dev.carapps.parking

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Records on disk, newest first. Small enough that a single file beats a database. */
class EventStore(context: Context) {

    private val file = File(context.filesDir, "parking-events.json")

    fun add(event: ParkingEvent) {
        val events = read().toMutableList()
        events.add(0, event)
        write(events.take(MAX_EVENTS))
    }

    fun read(): List<ParkingEvent> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull {
            runCatching { ParkingEvent.fromJson(array.getJSONObject(it)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun setActualFloor(id: Long, floor: String) {
        write(read().map { if (it.id == id) it.copy(actualFloor = floor) else it })
    }

    fun exportJson(): String = JSONArray().apply {
        read().forEach { put(it.toJson()) }
    }.toString(2)

    private fun write(events: List<ParkingEvent>) {
        runCatching {
            file.writeText(JSONArray().apply { events.forEach { put(it.toJson()) } }.toString())
        }
    }

    private companion object {
        const val MAX_EVENTS = 200
    }
}

/** Which Bluetooth device counts as the car, and nothing else. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("parking", Context.MODE_PRIVATE)

    var carAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_ADDRESS, value).apply()

    var carName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    /**
     * Kept outside the service so a process death mid-drive does not take the start
     * time with it — the first recordings came back with a start time of zero.
     */
    var driveStartedAt: Long
        get() = prefs.getLong(KEY_STARTED, 0L)
        set(value) = prefs.edit().putLong(KEY_STARTED, value).apply()

    private companion object {
        const val KEY_ADDRESS = "car_address"
        const val KEY_NAME = "car_name"
        const val KEY_STARTED = "drive_started_at"
    }
}

internal fun JSONObject.keysList(): List<String> = keys().asSequence().toList()
