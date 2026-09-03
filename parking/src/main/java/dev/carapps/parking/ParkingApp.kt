package dev.carapps.parking

import android.app.Application
import dev.carapps.probe.core.CrashLog

class ParkingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Recording happens with the phone in a pocket and the screen off, so a
        // crash would otherwise leave nothing behind but a missing drive.
        CrashLog.install(this)
    }
}
