package dev.carapps.probe.projected

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Everything that can be established about why the car might be ignoring this app,
 * without the car being involved.
 *
 * This exists because the interesting failure is invisible: the host decides not to
 * list the app inside its own process, logs the reason there, and nothing reaches
 * us. An app can only read its own log, so the rejection itself is out of reach —
 * but its likely causes are all inspectable from here.
 *
 * The install source is the point of the exercise. Android Auto only runs templated
 * apps installed from a trusted source, and `adb install -i` can set
 * `installingPackageName` while leaving `initiatingPackageName` as the shell. Seeing
 * both side by side says whether that workaround took hold.
 */
object SelfCheck {

    fun run(context: Context): String = buildString {
        appendLine("## Self-check (phone only, no car involved)")
        appendLine()
        appendInstallSource(context)
        appendLine()
        appendCarAppServiceResolution(context)
        appendLine()
        appendManifestMetadata(context)
        appendLine()
        appendAndroidAuto(context)
        appendLine()
        appendPermissions(context)
    }

    private fun StringBuilder.appendInstallSource(context: Context) {
        appendLine("[install source]")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            val installer = runCatching {
                context.packageManager.getInstallerPackageName(context.packageName)
            }.getOrNull()
            appendLine("  installerPackageName  = ${installer ?: "null (sideloaded)"}")
            return
        }
        val info = runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName)
        }.getOrNull()
        if (info == null) {
            appendLine("  unavailable")
            return
        }
        // initiatingPackageName is the one adb cannot forge: an adb install leaves it
        // as the shell no matter what -i says.
        appendLine("  initiatingPackageName = ${info.initiatingPackageName ?: "null"}")
        appendLine("  installingPackageName = ${info.installingPackageName ?: "null"}")
        appendLine("  originatingPackageName= ${info.originatingPackageName ?: "null"}")
        val trusted = info.installingPackageName == PLAY_STORE
        appendLine("  looks Play-installed?   $trusted")
    }

    private fun StringBuilder.appendCarAppServiceResolution(context: Context) {
        appendLine("[CarAppService resolution]")
        val pm = context.packageManager

        val bare = Intent(CAR_APP_SERVICE_ACTION).setPackage(context.packageName)
        appendLine("  action only        -> ${pm.queryIntentServices(bare, 0).size} match(es)")

        for (category in listOf(CATEGORY_IOT, CATEGORY_POI)) {
            val intent = Intent(CAR_APP_SERVICE_ACTION)
                .setPackage(context.packageName)
                .addCategory(category)
            val count = pm.queryIntentServices(intent, 0).size
            appendLine("  ${category.substringAfterLast('.').padEnd(18)} -> $count match(es)")
        }
    }

    private fun StringBuilder.appendManifestMetadata(context: Context) {
        appendLine("[manifest meta-data]")
        val metaData = runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
        }.getOrNull()
        if (metaData == null) {
            appendLine("  unavailable")
            return
        }
        val minLevel = if (metaData.containsKey("androidx.car.app.minCarApiLevel")) {
            metaData.getInt("androidx.car.app.minCarApiLevel").toString()
        } else {
            "MISSING"
        }
        appendLine("  minCarApiLevel     = $minLevel")
        appendLine("  theme              = ${if (metaData.containsKey("androidx.car.app.theme")) "present" else "MISSING"}")
        appendLine(
            "  gms.car.application= " +
                if (metaData.containsKey("com.google.android.gms.car.application")) "present" else "MISSING"
        )
    }

    private fun StringBuilder.appendAndroidAuto(context: Context) {
        appendLine("[Android Auto]")
        val info = runCatching {
            context.packageManager.getPackageInfo(ANDROID_AUTO, 0)
        }.getOrNull()
        if (info == null) {
            // Also what a missing <queries> entry looks like, so do not read this as
            // proof that Android Auto is absent.
            appendLine("  not visible to this app")
            return
        }
        appendLine("  version = ${info.versionName}")
    }

    private fun StringBuilder.appendPermissions(context: Context) {
        appendLine("[permissions]")
        val debuggable =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        appendLine("  debuggable build   = $debuggable")
        listOf(
            "com.google.android.gms.permission.CAR_FUEL",
            "com.google.android.gms.permission.CAR_SPEED",
            "com.google.android.gms.permission.CAR_MILEAGE",
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ).forEach { permission ->
            val granted =
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            appendLine("  ${permission.substringAfterLast('.').padEnd(18)} = ${if (granted) "granted" else "denied"}")
        }
    }

    private const val PLAY_STORE = "com.android.vending"
    private const val ANDROID_AUTO = "com.google.android.projection.gearhead"
    private const val CAR_APP_SERVICE_ACTION = "androidx.car.app.CarAppService"
    private const val CATEGORY_IOT = "androidx.car.app.category.IOT"
    private const val CATEGORY_POI = "androidx.car.app.category.POI"
}
