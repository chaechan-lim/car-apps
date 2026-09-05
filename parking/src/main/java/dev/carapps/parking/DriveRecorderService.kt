package dev.carapps.parking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Stays resident and listens for the car itself, rather than being started when the
 * car appears.
 *
 * It was started on demand from a manifest receiver, and drives went missing: the
 * disconnect was always recorded and the connect often was not. The asymmetry gives
 * the reason. Since Android 12 a foreground service generally cannot be started from
 * the background, with a short grace period after the user touches the phone —
 * getting out of the car falls inside that window, getting in, with the phone in a
 * pocket and the screen off, does not.
 *
 * So the service is started once from the app or at boot, when starting it is
 * allowed, and from then on it watches Bluetooth from inside its own process. Both
 * edges of a drive are then handled by something already running, and neither
 * depends on permission to start.
 */
class DriveRecorderService : android.app.Service(), LocationListener {

    private lateinit var sensors: DriveSensors
    private lateinit var settings: Settings
    private var lastFix: Location? = null
    private var lastFixAtElapsed = 0L
    private var startedAtElapsed = 0L

    /**
     * When a satellite fix last arrived, kept apart from [lastFix].
     *
     * Network fixes carry on underground off cell towers, so the best-accuracy fix
     * keeps updating in a basement and cannot mark the entrance. Only GPS going
     * quiet does.
     */
    private var lastGpsFixAtElapsed = 0L
    private var recording = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getDevice() ?: return
            val carAddress = settings.carAddress ?: return
            val label = intent.action?.substringAfterLast('.')
            if (!device.address.equals(carAddress, ignoreCase = true)) {
                DebugLog.write(this@DriveRecorderService, "$label ${device.address} — not the car")
                return
            }
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    DebugLog.write(this@DriveRecorderService, "CONNECTED (in-process)")
                    startRecording()
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    DebugLog.write(this@DriveRecorderService, "DISCONNECTED (in-process)")
                    finishRecording()
                }
            }
        }

        private fun Intent.getDevice(): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
    }

    override fun onCreate() {
        super.onCreate()
        sensors = DriveSensors(this)
        settings = Settings(this)
        registerReceiver(
            bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            RECEIVER_EXPORTED,
        )
        DebugLog.write(this, "monitor created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_ENSURE_RUNNING
        DebugLog.write(this, "onStartCommand action=$action restarted=${intent == null}")
        startForeground(NOTIFICATION_ID, notification())

        when (action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> finishRecording()
            // ACTION_ENSURE_RUNNING just needs the service alive and listening.
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (recording) {
            DebugLog.write(this, "already recording, ignoring start")
            return
        }
        recording = true
        startedAtElapsed = SystemClock.elapsedRealtime()
        lastGpsFixAtElapsed = 0L
        lastFix = null
        lastFixAtElapsed = 0L
        settings.driveStartedAt = System.currentTimeMillis()
        sensors.start()
        requestLocation()
        updateNotification()
        DebugLog.write(this, "recording started, barometer=${sensors.hasBarometer}")
    }

    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLog.write(this, "no location permission")
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Both providers. Satellites alone can take a while to fix in a city and may
        // never fix on a short hop between two underground garages. The network
        // provider is coarse but answers the only question asked of it: which building.
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    LOCATION_INTERVAL_MS,
                    LOCATION_DISTANCE_M,
                    this,
                )
            }.onFailure { DebugLog.write(this, "provider $provider unavailable: ${it.message}") }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixAtElapsed = SystemClock.elapsedRealtime()
        }
        // Keep the better fix rather than the newest: a coarse network update arriving
        // after the car is already inside would otherwise overwrite the sharp
        // street-level fix taken on the way in.
        val current = lastFix
        val staleness = SystemClock.elapsedRealtime() - lastFixAtElapsed
        val replace = current == null ||
            location.accuracy <= current.accuracy ||
            staleness > FIX_STALE_MS
        if (replace) {
            lastFix = location
            lastFixAtElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun finishRecording() {
        if (!recording) {
            // Nothing was running, so there is no drive to write. Previously this
            // produced an empty record that looked like a failed measurement rather
            // than a missed start; the log line says which it was.
            DebugLog.write(this, "stop with no recording in progress — ignoring")
            return
        }
        val samples = sensors.pressureSamples
        val yaw = sensors.yawDegrees
        sensors.stop()
        recording = false
        runCatching {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        }

        val fingerprint = WifiFingerprint(this)
        val event = ParkingEvent(
            id = System.currentTimeMillis(),
            startedAt = settings.driveStartedAt,
            endedAt = System.currentTimeMillis(),
            pressureSamples = samples,
            yawDegrees = yaw,
            lastLocation = lastFix?.let {
                ParkingEvent.Fix(it.latitude, it.longitude, it.accuracy)
            },
            secondsSinceLastFix = if (lastFixAtElapsed == 0L) null
            else (SystemClock.elapsedRealtime() - lastFixAtElapsed) / 1000,
            lastGpsFixElapsedMs = if (lastGpsFixAtElapsed == 0L) null
            else lastGpsFixAtElapsed - startedAtElapsed,
            // Captured here rather than during the drive: the fingerprint that matters
            // is the one at the parked spot.
            wifi = fingerprint.capture(),
            connectedWifi = fingerprint.connectedNetwork(),
            wifiScanAgeSeconds = fingerprint.scanAgeSeconds(),
            cells = CellFingerprint(this).capture(),
        )
        EventStore(this).add(event)
        settings.driveStartedAt = 0L
        updateNotification()
        DebugLog.write(
            this,
            "recorded ${samples.size} samples, entryRise=${event.entryRiseHpa} " +
                "wholeDrive=${event.wholeDriveRiseHpa} gpsLostAt=${event.lastGpsFixElapsedMs}",
        )
        notifyParked(event)
    }

    private fun notification(): Notification {
        createChannel()
        val text = if (recording) {
            getString(R.string.recording_text)
        } else {
            getString(R.string.waiting_text, settings.carName ?: "—")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (recording) getString(R.string.recording_title)
                else getString(R.string.waiting_title)
            )
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification())
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The prompt for ground truth. Without a floor typed in while the memory is
     * fresh, the recording is an unlabelled curve and cannot settle anything.
     */
    private fun notifyParked(event: ParkingEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel()
        val estimate = event.estimatedFloorsDown
        val text = if (estimate == null) {
            getString(R.string.parked_no_estimate)
        } else {
            getString(R.string.parked_estimate, estimate)
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                PARKED_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.parked_title))
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    override fun onDestroy() {
        DebugLog.write(this, "monitor destroyed (recording=$recording)")
        runCatching { unregisterReceiver(bluetoothReceiver) }
        sensors.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ENSURE_RUNNING = "dev.carapps.parking.ENSURE"
        const val ACTION_START = "dev.carapps.parking.START"
        const val ACTION_STOP = "dev.carapps.parking.STOP"

        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 1
        private const val PARKED_NOTIFICATION_ID = 2
        private const val LOCATION_INTERVAL_MS = 10_000L
        private const val LOCATION_DISTANCE_M = 20f
        private const val FIX_STALE_MS = 60_000L

        /** Called where starting is allowed: from the app, or at boot. */
        fun ensureRunning(context: Context) = send(context, ACTION_ENSURE_RUNNING)

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, DriveRecorderService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    DebugLog.write(
                        context,
                        "startForegroundService($action) FAILED: ${it.javaClass.simpleName}: ${it.message}",
                    )
                }
        }
    }
}
