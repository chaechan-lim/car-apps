package dev.carapps.probe.projected

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import dev.carapps.probe.core.CrashLog
import dev.carapps.probe.core.ReportExport
import dev.carapps.probe.core.ReportStore
import dev.carapps.probe.core.padForSystemBars

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

        // First row, and the first thing to do. The car screen asks for these, but the
        // prompt appears here on the phone — which during a drive is in a pocket with
        // the screen off, so it was never answered and every gated field reported
        // itself as data the car withholds. Granting them sitting still, before
        // driving, is the only way this reliably happens.
        val permissionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(PADDING, PADDING, PADDING, 0)
            addView(button("Grant car permissions") { requestCarPermissions() })
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
            addView(permissionRow)
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

        root.padForSystemBars()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // The self-check needs no car, so there is always something to send — the
        // case worth reporting is usually the one where the probe never ran.
        val carReport = ReportStore(this).read()
        val crash = CrashLog.read(this)
        report = buildString {
            append(ReportExport.environmentHeader(this@PhoneActivity))
            appendLine()
            // First, because a crash explains everything below it and nothing below
            // it explains the crash.
            if (crash != null) {
                appendLine("## LAST CRASH")
                appendLine()
                appendLine(crash)
                appendLine()
            }
            append(SelfCheck.run(this@PhoneActivity))
            appendLine()
            appendLine()
            if (carReport == null) {
                appendLine("## Car report")
                appendLine()
                appendLine("None yet — the probe has not run on a car display.")
                appendLine()
                append(instructions())
            } else {
                append(carReport)
            }
        }
        reportView.text = report
    }

    private fun withReport(action: (String) -> Unit) {
        report?.let(action)
    }

    /**
     * Asks for the car data permissions from the phone.
     *
     * A permission the user has already refused twice is never prompted for again —
     * the request returns immediately, denied. So a refusal that cannot be undone
     * here is sent to the app's settings page, where it still can be.
     */
    private fun requestCarPermissions() {
        val missing = RootScreen.CAR_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All car permissions already granted", Toast.LENGTH_SHORT).show()
            return
        }
        val permanentlyRefused = missing.none { shouldShowRequestPermissionRationale(it) } &&
            askedBefore()
        if (permanentlyRefused) {
            Toast.makeText(
                this,
                "Refused before — enable them under Permissions",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
            return
        }
        rememberAsked()
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CAR_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // onResume rebuilds the report, so the permission block on screen updates
        // itself and the answer is visible without going back to the car.
        onResume()
    }

    private fun askedBefore() =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    private fun rememberAsked() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
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

        BEFORE DRIVING

        Tap "Grant car permissions" above, here, while parked.

        The car screen asks for them too, but the prompt appears
        on the phone — which during a drive is in a pocket with
        the screen off. Unanswered, it stays denied, and every
        field behind CAR_FUEL, CAR_SPEED or CAR_MILEAGE then
        reports as data the car withholds. Check the permission
        block above reads "granted" before reading anything else.

        ONCE IT RUNS

        1. Open "Car Probe" from the car launcher.
        2. If a row says a permission is missing, grant it here
           and tap "Retry" on the car screen.
        3. Tap "Log" on the car screen.
        4. Come back here to copy, share, or file the report.

        Expect several fields to report UNAVAILABLE. That is the
        measurement, not a bug — but only once the permissions
        above are granted.
    """.trimIndent()

    private companion object {
        const val PADDING = 48
        const val REPO = "chaechan-lim/car-apps"
        const val REQUEST_CAR_PERMISSIONS = 1
        const val PREFS = "probe"
        const val KEY_ASKED = "asked_car_permissions"
    }
}
