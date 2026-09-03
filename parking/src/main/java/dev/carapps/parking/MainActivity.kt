package dev.carapps.parking

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.carapps.probe.core.ReportExport
import dev.carapps.probe.core.padForSystemBars
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Setup, the recorded drives, and a way to type in what floor it really was.
 *
 * The labelling is the point of this screen. Every other number here is measured
 * automatically; the floor is the one thing only the driver knows, and without it
 * the recordings cannot be checked against anything.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: TextView
    private val store by lazy { EventStore(this) }
    private val settings by lazy { Settings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        content = TextView(this).apply {
            setPadding(PADDING, 0, PADDING, PADDING)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(PADDING, PADDING, PADDING, 0)
            addView(button("Car") { pickCar() })
            addView(button("Label") { labelLatest() })
            addView(button("Share") { startActivity(ReportExport.shareIntent(store.exportJson())) })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons)
            addView(
                ScrollView(this@MainActivity).apply { addView(content) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        root.padForSystemBars()
        setContentView(root)

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val events = store.read()
        content.text = buildString {
            appendLine("Car: ${settings.carName ?: "not set — tap Car"}")
            appendLine("Recorded drives: ${events.size}")
            appendLine("Labelled: ${events.count { it.actualFloor != null }}")
            appendLine()
            if (events.isEmpty()) {
                appendLine("Nothing yet.")
                appendLine()
                appendLine("Pick the car's Bluetooth device, then drive.")
                appendLine("Recording starts when it connects and the")
                appendLine("record is written when it disconnects.")
                appendLine()
                appendLine("After parking, type in the floor you")
                appendLine("actually ended up on. That label is what")
                appendLine("makes the pressure curve mean anything.")
                return@buildString
            }
            events.forEach { event ->
                appendLine("─".repeat(34))
                appendLine(timestamp(event.endedAt))
                appendLine("  actual floor : ${event.actualFloor ?: "— not labelled —"}")
                appendLine("  pressure rise: ${format(event.pressureDropHpa)} hPa")
                appendLine("  floors down  : ${format(event.estimatedFloorsDown)}")
                appendLine("  yaw          : ${event.yawDegrees.toInt()}° (${ramps(event.yawDegrees)})")
                appendLine("  samples      : ${event.pressureSamples.size}")
                appendLine("  wifi APs     : ${event.wifi.size}")
                appendLine("  joined wifi  : ${event.connectedWifi ?: "none"}")
                appendLine("  last fix     : ${fix(event)}")
            }
        }
    }

    private fun ramps(yaw: Float): String {
        val turns = Math.abs(yaw) / 360f
        return "%.1f turns".format(turns)
    }

    private fun fix(event: ParkingEvent) = event.lastLocation?.let {
        "%.5f, %.5f ±%.0fm, %ss old".format(
            it.lat, it.lon, it.accuracy, event.secondsSinceLastFix ?: 0,
        )
    } ?: "none"

    private fun format(value: Float?) = value?.let { "%.2f".format(it) } ?: "—"

    private fun timestamp(millis: Long) =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

    /** Bonded devices only: the car is already paired, and scanning would be noise. */
    private fun pickCar() {
        if (!hasBluetoothPermission()) {
            Toast.makeText(this, "Grant Bluetooth permission first", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val devices = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
        if (devices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices", Toast.LENGTH_LONG).show()
            return
        }
        val names = devices.map { runCatching { it.name }.getOrNull() ?: it.address }
        AlertDialog.Builder(this)
            .setTitle("Which device is the car?")
            .setItems(names.toTypedArray()) { _, index ->
                settings.carAddress = devices[index].address
                settings.carName = names[index]
                render()
            }
            .show()
    }

    private fun labelLatest() {
        val target = store.read().firstOrNull { it.actualFloor == null }
        if (target == null) {
            Toast.makeText(this, "Nothing left to label", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "B3, 1F, rooftop…"
        }
        AlertDialog.Builder(this)
            .setTitle("Floor on ${timestamp(target.endedAt)}")
            .setMessage("Estimated ${format(target.estimatedFloorsDown)} floors down.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                store.setActualFloor(target.id, input.text.toString().trim())
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private companion object {
        const val PADDING = 48
    }
}
