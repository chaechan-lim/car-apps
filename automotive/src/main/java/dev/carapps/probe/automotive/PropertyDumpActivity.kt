package dev.carapps.probe.automotive

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * Sideload-and-look diagnostic. Renders the full CarPropertyManager scan so the
 * gap between "the SDK declares this property" and "this app may read it" is
 * visible on the car screen, with the same text mirrored to logcat.
 *
 * Not distraction-optimised and deliberately not published — this is a bench tool.
 */
class PropertyDumpActivity : AppCompatActivity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            setPadding(32, 16, 32, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val rescan = Button(this).apply {
            text = getString(R.string.rescan)
            setOnClickListener { runScan() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                rescan,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.END
                    marginStart = 32
                    marginEnd = 32
                    topMargin = 16
                },
            )
            addView(
                ScrollView(this@PropertyDumpActivity).apply { addView(output) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }

        setContentView(root)

        requestMissingPermissions()
        runScan()
    }

    private fun runScan() {
        output.text = getString(R.string.scanning)
        // The scan touches the binder for every property; keep it off the main thread.
        Thread {
            val report = VehiclePropertyScanner(this).scan().toReport()
            report.lineSequence().forEach { Log.i(TAG, it) }
            runOnUiThread { output.text = report }
        }.start()
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
    }
}
