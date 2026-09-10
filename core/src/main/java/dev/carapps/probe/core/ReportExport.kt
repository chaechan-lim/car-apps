package dev.carapps.probe.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder

/**
 * Turns a report into something that can leave the phone.
 *
 * Deliberately no GitHub API client: posting an issue directly would mean shipping
 * a write-scoped token inside the APK, where anyone who downloads it can pull it
 * back out. [githubIssueIntent] instead opens GitHub's own "new issue" form with
 * the body prefilled, so the post is made by whoever is signed in on the phone and
 * no credential ever ships.
 */
object ReportExport {

    /**
     * GitHub renders the prefilled body through a URL query parameter, and long
     * URLs get rejected or silently cut. Anything past this goes out via [shareIntent].
     */
    const val MAX_ISSUE_BODY = 6000

    fun environmentHeader(context: Context): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("App: ${appVersion(context)}")
        appendLine("Android Auto: ${packageVersion(context, ANDROID_AUTO_PACKAGE) ?: "not installed"}")
    }

    fun copyToClipboard(context: Context, report: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Car Probe report", report))
    }

    fun shareIntent(report: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Car Probe report")
            putExtra(Intent.EXTRA_TEXT, report)
        }.let { Intent.createChooser(it, "Share report") }

    /**
     * Shares a report as a file rather than as intent text.
     *
     * [shareIntent] carries the whole report in an intent extra, which crosses a
     * Binder transaction with a hard limit around a megabyte. A recording that
     * holds every pressure sample of every drive passes that limit easily, and the
     * failure is a process kill at the moment of tapping Share — no dialog, no
     * exception the caller can catch. A file goes out by reference, so size stops
     * mattering; the text extra keeps only the first lines, as a preview for apps
     * that show one.
     *
     * Requires a FileProvider under the authority "<packageName>.reports" pointing
     * at @xml/report_paths, which this module supplies.
     */
    fun shareFileIntent(context: Context, fileName: String, report: String): Intent =
        shareFileIntent(context, fileName) { it.append(report) }

    /**
     * The streaming form. [write] is handed the file's writer and appends the report
     * to it a piece at a time, so a report larger than comfortable never exists as a
     * single string — the other way this export could have been killing the process.
     */
    fun shareFileIntent(context: Context, fileName: String, write: (Appendable) -> Unit): Intent {
        val directory = File(context.cacheDir, "reports").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, fileName)
        file.bufferedWriter().use(write)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share report") }
    }

    fun githubIssueIntent(repo: String, title: String, report: String): Intent {
        val body = if (report.length <= MAX_ISSUE_BODY) {
            report
        } else {
            report.take(MAX_ISSUE_BODY) + "\n…truncated, use Share for the full report"
        }
        val url = "https://github.com/$repo/issues/new" +
            "?title=${encode(title)}" +
            "&body=${encode("```\n$body\n```")}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun appVersion(context: Context): String =
        packageVersion(context, context.packageName) ?: "unknown"

    private fun packageVersion(context: Context, packageName: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrElse { if (it is PackageManager.NameNotFoundException) null else null }

    private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
}
