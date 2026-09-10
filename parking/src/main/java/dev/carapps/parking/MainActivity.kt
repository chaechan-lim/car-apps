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
import org.json.JSONArray
import org.json.JSONObject
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

        val setupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(PADDING, PADDING, PADDING, 0)
            addView(button("Car") { pickCar() })
            addView(button("Label") { labelLatest() })
            addView(button("Share") { share() })
        }

        // Manual control, because the Bluetooth trigger is exactly what is under
        // suspicion: a drive recorded by hand still produces usable data, and the
        // difference between the two is itself informative.
        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(PADDING, 0, PADDING, 0)
            addView(button("Start") {
                DebugLog.write(this@MainActivity, "manual START")
                DriveRecorderService.start(this@MainActivity)
                render()
            })
            addView(button("Stop") {
                DebugLog.write(this@MainActivity, "manual STOP")
                DriveRecorderService.stop(this@MainActivity)
                render()
            })
            addView(button("Clear log") {
                DebugLog.clear(this@MainActivity)
                render()
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(setupRow)
            addView(manualRow)
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
        // Opening the app is a moment when starting a foreground service is allowed,
        // so it is also the reliable moment to make sure the monitor is up.
        if (settings.carAddress != null) {
            DriveRecorderService.ensureRunning(this)
        }
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
            appendSeparation(events)
            val siteOf = ParkingSites.group(events)
                .flatMap { site -> site.events.map { it.id to site } }
                .toMap()
            events.forEach { event ->
                val site = siteOf[event.id]
                appendLine("─".repeat(34))
                appendLine(timestamp(event.endedAt))
                appendLine("  site         : ${site?.name ?: "—"}")
                appendLine("  actual floor : ${event.actualFloor ?: "— not labelled —"}")
                appendLine("  descent 4min : ${format(event.descentRiseHpa)} hPa → ${levels(event, site)}")
                appendLine("  descent 8min : ${format(event.descentRiseLongHpa)} hPa")
                appendLine("  descent yaw  : ${event.descentYawDeg?.toInt() ?: "—"}° (ramp spiral)")
                appendLine("  climb began  : ${seconds(event.descentStartedBeforeEndMs)} before end")
                appendLine("  deepest point: ${seconds(event.arrivedBeforeEndMs)} before end")
                appendLine("  entry rise   : ${format(event.entryRiseHpa)} hPa (gps-sliced)")
                appendLine("  whole drive  : ${format(event.wholeDriveRiseHpa)} hPa (terrain)")
                appendLine("  yaw in garage: ${event.yawSinceEntry?.toInt() ?: "—"}°")
                appendLine("  yaw total    : ${event.yawDegrees.toInt()}°")
                appendLine("  drive length : ${seconds(event.durationMs)}")
                appendLine("  gps lost     : ${seconds(event.gpsLostBeforeEndMs)} before stopping")
                appendLine("  samples      : ${event.pressureSamples.size}")
                appendLine("  wifi APs     : ${event.wifi.size} (scan ${event.wifiScanAgeSeconds ?: "?"}s old)")
                appendLine("  joined wifi  : ${event.connectedWifi ?: "none"}")
                appendLine("  cells        : ${event.cells.size}")
                appendLine("  last fix     : ${fix(event)}")
            }
            appendLine()
            appendLine("═".repeat(34))
            appendLine("TRIGGER LOG")
            appendLine()
            val log = DebugLog.read(this@MainActivity)
            if (log.isEmpty()) {
                appendLine("(empty — no Bluetooth events seen yet)")
            } else {
                log.asReversed().forEach { appendLine(it) }
            }
        }
    }

    /**
     * Exports as a file.
     *
     * The export is megabytes — every pressure sample of every drive — and putting
     * that in an intent extra killed the process the moment Share was tapped, which
     * is what a Binder transaction over the limit looks like from the outside.
     */
    private fun share() {
        val name = "parking-drives-%s.json".format(
            SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date()),
        )
        runCatching { ReportExport.shareFileIntent(this, name, ::writeExport) }
            .onSuccess { startActivity(it) }
            .onFailure {
                DebugLog.write(this, "share failed: ${it.javaClass.simpleName}: ${it.message}")
                Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                render()
            }
    }

    /** Recordings and the trigger log together: neither explains the other alone. */
    private fun writeExport(out: Appendable) {
        out.append("{\n\"device\": ")
        out.append(JSONObject.quote(ReportExport.environmentHeader(this)))
        out.append(",\n\"triggerLog\": ")
        out.append(JSONArray(DebugLog.read(this)).toString())
        out.append(",\n\"events\": ")
        store.writeJsonTo(out)
        out.append("}\n")
    }

    /**
     * The labelled drives grouped by garage, then by floor.
     *
     * Grouped by garage and not only by floor, because levels are spaced differently
     * in different buildings: pooling every site together compares a three-metre
     * apartment level against a four-and-a-half-metre department store one and makes
     * the ranges overlap for a reason that has nothing to do with the sensor.
     *
     * Within one site this is the whole question in a few lines. Floors are tellable
     * apart here only if two floors' ranges do not touch.
     */
    private fun StringBuilder.appendSeparation(events: List<ParkingEvent>) {
        val sites = ParkingSites.group(events).filter { it.labelled.isNotEmpty() }
        if (sites.isEmpty()) return
        appendLine("═".repeat(34))
        appendLine("BY SITE — hPa per level is measured")
        appendLine("per garage, never assumed.")
        appendLine()
        sites.forEach { site ->
            appendLine("${site.name}  (${site.events.size} drives)")
            val perLevel = site.hPaPerLevel
            appendLine(
                if (perLevel == null) {
                    "  hPa/level: — (no labelled basement park yet)"
                } else {
                    "  hPa/level: %.2f  from %d drive(s)".format(perLevel, site.calibrationDrives)
                },
            )
            appendLine("  floor  n   descent4min   yaw4min")
            site.labelled
                .groupBy { it.actualFloor!!.lowercase() }
                .toSortedMap()
                .forEach { (floor, drives) ->
                    appendLine(
                        "  %-6s %-3d %-13s %s".format(
                            floor,
                            drives.size,
                            range(drives.mapNotNull { it.descentRiseHpa }),
                            range(drives.mapNotNull { it.descentYawDeg?.let(Math::abs) }),
                        ),
                    )
                }
            appendLine()
        }
        appendLine("Sites are guessed from the last fix within")
        appendLine("${ParkingSites.RADIUS_M.toInt()} m. Type a name while labelling to")
        appendLine("correct a split or a wrongly merged one.")
        appendLine()
    }

    /**
     * Levels down, converted with this garage's own spacing where it is known.
     *
     * The nominal 0.36 hPa is a starting guess and is marked as one, because at a
     * site with taller levels it reads a floor too deep — which is the entire reason
     * calibration is per garage.
     */
    private fun levels(event: ParkingEvent, site: ParkingSites.Site?): String {
        val rise = event.descentRiseHpa ?: return "—"
        // Fitted without this drive when this drive is one of the labelled ones, so the
        // figure printed next to a known floor is a prediction and not an echo.
        val perLevel = site?.hPaPerLevelExcluding(event.id.takeIf { event.actualFloor != null })
        return if (perLevel == null || perLevel <= 0f) {
            "%.1f levels (nominal 0.36)".format(rise / ParkingEvent.HPA_PER_FLOOR)
        } else {
            "%.1f levels (site %.2f)".format(rise / perLevel, perLevel)
        }
    }

    private fun range(values: List<Float>): String =
        if (values.isEmpty()) "—" else "%.2f–%.2f".format(values.min(), values.max())

    private fun seconds(millis: Long?) = millis?.let { "${it / 1000}s" } ?: "—"

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
                DriveRecorderService.ensureRunning(this)
                render()
            }
            .show()
    }

    private fun labelLatest() {
        val events = store.read()
        val target = events.firstOrNull { it.actualFloor == null }
        if (target == null) {
            Toast.makeText(this, "Nothing left to label", Toast.LENGTH_SHORT).show()
            return
        }
        val floorInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "B3, 1F, 지하5…"
        }
        val siteInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "which garage — 집, 회사, 스타필드…"
            // Prefilled from whatever this drive already clusters with, so a name typed
            // once spreads to every drive at that garage instead of being retyped.
            setText(suggestedSite(target, events).orEmpty())
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, 0, PADDING, 0)
            addView(floorInput)
            addView(siteInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Floor on ${timestamp(target.endedAt)}")
            .setMessage("Measured ${format(target.descentRiseHpa)} hPa of descent.")
            .setView(fields)
            .setPositiveButton("Save") { _, _ ->
                store.setLabel(
                    target.id,
                    floorInput.text.toString().trim(),
                    siteInput.text.toString().trim(),
                )
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The name already given to a drive that ended at the same place, if there is one. */
    private fun suggestedSite(target: ParkingEvent, events: List<ParkingEvent>): String? {
        val fix = target.lastLocation ?: return null
        return events
            .filter { it.id != target.id && !it.site.isNullOrBlank() }
            .filter { other ->
                other.lastLocation?.let {
                    ParkingSites.distanceMeters(fix, it) <= ParkingSites.RADIUS_M
                } == true
            }
            .minByOrNull { other ->
                ParkingSites.distanceMeters(fix, other.lastLocation!!)
            }
            ?.site
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
