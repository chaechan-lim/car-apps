package dev.carapps.parking

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Turns the car radio coming and going into drive boundaries.
 *
 * Nothing else identifies a drive this cleanly. Activity recognition guesses from
 * motion and mistakes buses for cars; the car's own Bluetooth is present exactly
 * when the engine is on, and its disconnect is the moment of walking away — which
 * is the moment worth recording.
 */
class CarBluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
        val address = device?.address ?: return
        val carAddress = Settings(context).carAddress ?: return
        if (!address.equals(carAddress, ignoreCase = true)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i(TAG, "car connected")
                DriveRecorderService.start(context)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "car disconnected")
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

    private companion object {
        const val TAG = "Parking"
    }
}
