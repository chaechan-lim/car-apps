package dev.carapps.parking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the monitor back after a reboot.
 *
 * BOOT_COMPLETED is one of the few broadcasts still allowed to start a foreground
 * service from the background, which is what makes it the right place: without it
 * the monitor would stay down until the app happened to be opened, and the first
 * drive after a restart would go unrecorded.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings(context).carAddress == null) return
        DebugLog.write(context, "boot — restarting monitor")
        DriveRecorderService.ensureRunning(context)
    }
}
