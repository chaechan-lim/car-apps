package dev.carapps.fuel

import android.app.Application
import dev.carapps.probe.core.CrashLog

/**
 * Installs the crash handler before anything else runs. The car screen and the
 * phone screen share this process, so a crash raised while driving is still on
 * disk when the phone screen is opened afterwards — the first moment anyone can
 * read it.
 */
class FuelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
