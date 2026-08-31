package dev.carapps.probe.projected

import android.app.Application
import dev.carapps.probe.core.CrashLog

/**
 * Exists to install the crash handler before anything else runs.
 *
 * The car screen and the phone screen share this process, so a crash raised while
 * driving is still on disk when the phone app is opened afterwards — which is the
 * only moment anyone can actually read it.
 */
class ProbeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
