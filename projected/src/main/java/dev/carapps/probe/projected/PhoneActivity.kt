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

        IF THIS APP IS NOT IN THE CAR LAUNCHER

        Sideloading does not work for this kind of app. Android
        Auto only runs templated apps installed from a trusted
        source, and its "Unknown sources" developer setting does
        not cover them — that toggle applies to media, messaging
        and parked apps only. So a sideloaded APK installs fine
        and is then ignored by the car, with no error anywhere.

        Two ways around it:

        - Desktop Head Unit (DHU) on a computer. Sideloading
          works there, so it verifies the app without a car.
        - Google Play Internal App Sharing or an Internal Test
          Track. Neither goes through review, and an app
          installed that way counts as trusted.

        ONCE IT RUNS

        1. Open "Car Probe" from the car launcher.
        2. Tap "Log" on the car screen.
        3. Come back here to copy, share, or file the report.

        Expect several fields to report UNAVAILABLE. That is the
        measurement, not a bug.
    """.trimIndent()

    private companion object {
        const val PADDING = 48
        const val REPO = "chaechan-lim/car-apps"
    }
}
