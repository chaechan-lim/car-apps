package dev.carapps.probe.projected

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Android Auto needs a launcher activity on the phone, but the probe itself only
 * runs once the head unit connects. This screen exists to say so.
 */
class PhoneActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 15f
            text = """
                Car Probe (Android Auto)

                This app runs on the car display, not here.

                1. Enable Developer settings in the Android Auto app
                   (tap the version number 10 times).
                2. Turn on "Unknown sources".
                3. Connect to the head unit, or start the
                   Desktop Head Unit (DHU).
                4. Open "Car Probe" from the car launcher.

                Tap "Log" on the car screen to write the full
                report to logcat:

                    adb logcat -s CarProbe

                Expect several fields to report UNAVAILABLE.
                That is the measurement, not a bug.
            """.trimIndent()
        }

        setContentView(ScrollView(this).apply { addView(text) })
    }
}
