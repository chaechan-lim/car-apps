package dev.carapps.probe.projected

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.carapps.probe.core.ReportExport
import dev.carapps.probe.core.ReportStore

/**
 * Phone-side companion. The probe runs on the car display; this screen exists to
 * explain that, and to get the resulting report off the phone once it has run.
 */
class PhoneActivity : AppCompatActivity() {

    private lateinit var reportView: TextView
    private var report: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reportView = TextView(this).apply {
            setPadding(PADDING, 0, PADDING, PADDING)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(PADDING, PADDING, PADDING, 0)
            addView(
                button("Copy") {
                    withReport {
                        ReportExport.copyToClipboard(this@PhoneActivity, it)
                        Toast.makeText(this@PhoneActivity, "Report copied", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            addView(button("Share") { withReport { startActivity(ReportExport.shareIntent(it)) } })
            addView(
                button("GitHub issue") {
                    withReport {
                        startActivity(
                            ReportExport.githubIssueIntent(
                                repo = REPO,
                                title = "Car data probe report",
                                report = it,
                            )
                        )
                    }
                }
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons)
            addView(
                ScrollView(this@PhoneActivity).apply { addView(reportView) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        report = ReportStore(this).read()
        reportView.text = report ?: instructions()
    }

    private fun withReport(action: (String) -> Unit) {
        val current = report
        if (current == null) {
            Toast.makeText(this, "No report yet — run the probe on the car first", Toast.LENGTH_LONG)
                .show()
            return
        }
        action(current)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun instructions() = """
        Car Probe (Android Auto)

        No report yet. The probe runs on the car display, not here.

        1. Android Auto settings on the phone: tap the version
           10 times to unlock Developer settings, then enable
           "Unknown sources".
        2. Connect to the head unit.
        3. Open "Car Probe" from the car launcher.
        4. Tap "Log" on the car screen.
        5. Come back here to copy, share, or file the report.

        Expect several fields to report UNAVAILABLE. That is the
        measurement, not a bug.

        Note: this screen can only export what the probe collected.
        If the app never appears on the car screen at all, the host
        rejected it before it ran, and only the host's own log says
        why:

            adb logcat | grep -iE "carapp|gearhead|dev\.carapps"
    """.trimIndent()

    private companion object {
        const val PADDING = 48
        const val REPO = "chaechan-lim/car-apps"
    }
}
