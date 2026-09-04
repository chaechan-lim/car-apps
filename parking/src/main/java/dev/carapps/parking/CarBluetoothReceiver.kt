package dev.carapps.parking

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Turns the car radio coming and going into drive boundaries.
 *
 * Nothing else identifies a drive this cleanly. Activity recognition guesses from
 * motion and mistakes buses for cars; the car's own Bluetooth is present exactly
 * when the engine is on, and its disconnect is the moment of walking away — which
 * is the moment worth recording.
 *
 * Every event is logged, matching or not. A connect that never arrives and a
 * connect that arrives from a device we ignore look identical from the recording,
 * and the first drives recorded showed a disconnect with no connect before it.
 */
class CarBluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.substringAfterLast('.') ?: return
        val device = intent.getParcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
        val address = device?.address
        val carAddress = Settings(context).carAddress

        if (carAddress == null) {
            DebugLog.write(context, "$action from $address — no car set, ignoring")
            return
        }
        if (address == null || !address.equals(carAddress, ignoreCase = true)) {
            DebugLog.write(context, "$action from $address — not the car")
            return
        }

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                DebugLog.write(context, "CONNECTED — starting recorder")
                DriveRecorderService.start(context)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                DebugLog.write(context, "DISCONNECTED — finishing recorder")
                DriveRecorderService.stop(context)
            }
        }
    }

    private fun Intent.getParcelableExtraCompat(name: String): BluetoothDevice? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
}
