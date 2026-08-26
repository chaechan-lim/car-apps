package dev.carapps.probe.automotive

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.carapps.probe.core.ReportExport
import dev.carapps.probe.core.ReportStore
import dev.carapps.probe.core.padForSystemBars

/**
 * Sideload-and-look diagnostic. Renders the full CarPropertyManager scan so the
 * gap between "the SDK declares this property" and "this app may read it" is
 * visible on the car screen, with the same text mirrored to logcat.
 *
 * Not distraction-optimised and deliberately not published — this is a bench tool.
 */
class PropertyDumpActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private var report: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            setPadding(32, 16, 32, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 16, 32, 0)
            addView(button(getString(R.string.rescan)) { runScan() })
            addView(
                button("Copy") {
                    withReport {
                        ReportExport.copyToClipboard(this@PropertyDumpActivity, it)
                        Toast.makeText(this@PropertyDumpActivity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            addView(button("Share") { withReport { startActivity(ReportExport.shareIntent(it)) } })
            addView(
                button("Issue") {
                    withReport {
                        startActivity(
                            ReportExport.githubIssueIntent(
                                repo = REPO,
                                title = "AAOS vehicle property scan",
                                report = it,
                            )
                        )
                    }
                }
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actions)
            addView(
                ScrollView(this@PropertyDumpActivity).apply { addView(output) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }

        root.padForSystemBars()
        setContentView(root)

        requestMissingPermissions()
        runScan()
    }

    private fun runScan() {
        output.text = getString(R.string.scanning)
        // The scan touches the binder for every property; keep it off the main thread.
        Thread {
            val report = ReportExport.environmentHeader(this) + "\n" +
                VehiclePropertyScanner(this).scan().toReport()
            report.lineSequence().forEach { Log.i(TAG, it) }
            ReportStore(this).write(report)
            runOnUiThread {
                this.report = report
                output.text = report
            }
        }.start()
    }

    private fun withReport(action: (String) -> Unit) {
        val current = report
        if (current == null) {
            Toast.makeText(this, "Scan has not finished yet", Toast.LENGTH_SHORT).show()
            return
        }
        action(current)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun requestMissingPermissions() {
        val wanted = arrayOf(
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY_PORTS",
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) runScan()
    }

    private companion object {
        const val TAG = "CarProbe"
        const val REQUEST_CODE = 1001
        const val REPO = "chaechan-lim/car-apps"
    }
}
