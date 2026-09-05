package dev.carapps.parking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * Runs for the length of a drive and writes one record when the car is left.
 *
 * A foreground service rather than a background job because the recording has to be
 * continuous and has to survive the phone being idle in a pocket: the measurement is
 * a pressure curve, and a curve with gaps where the descent happened is worthless.
 * It starts when the car's Bluetooth connects and stops when it disconnects, so it
 * is only alive while actually driving.
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

    override fun onCreate() {
        super.onCreate()
        sensors = DriveSensors(this)
        settings = Settings(this)
        DebugLog.write(this, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after killing the process.
        // Sensor state is gone, so the honest move is to start a fresh recording
        // rather than carry on as if the earlier samples still existed.
        val action = intent?.action ?: ACTION_START
        DebugLog.write(this, "onStartCommand action=$action restarted=${intent == null}")

        when (action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> {
                finishRecording()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (recording) {
            DebugLog.write(this, "already recording, ignoring start")
            return
        }
        startForeground(NOTIFICATION_ID, notification())
        recording = true
        startedAtElapsed = SystemClock.elapsedRealtime()
        lastGpsFixAtElapsed = 0L
        settings.driveStartedAt = System.currentTimeMillis()
        sensors.start()
        requestLocation()
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
        // Both providers. Satellites alone can take a while to fix in a city and
        // may never fix on a short hop between two underground garages — which is
        // precisely the trip this has to tell apart. The network provider is coarse
        // but answers the only question asked of it: which building.
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
        // Keep the better fix rather than the newest: a coarse network update
        // arriving after the car is already inside would otherwise overwrite the
        // sharp street-level fix taken on the way in.
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
        val samples = sensors.pressureSamples
        val yaw = sensors.yawDegrees
        sensors.stop()
        recording = false
        runCatching {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        }

        if (samples.isEmpty()) {
            // Saved anyway, marked by its empty curve. A record with nothing in it is
            // evidence that the drive was never seen, and dropping it would erase the
            // only sign that the trigger is broken.
            DebugLog.write(this, "finishing with NO pressure samples — drive start was missed")
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
        DebugLog.write(
            this,
            "recorded ${samples.size} samples, entryRise=${event.entryRiseHpa} " +
                "wholeDrive=${event.wholeDriveRiseHpa} gpsLostAt=${event.lastGpsFixElapsedMs}",
        )
        notifyParked(event)
    }

    private fun notification(): Notification {
        createChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_title))
            .setContentText(getString(R.string.recording_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

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
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                PARKED_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.parked_title))
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(open)
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
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    override fun onDestroy() {
        DebugLog.write(this, "service destroyed (recording=$recording)")
        sensors.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.carapps.parking.START"
        const val ACTION_STOP = "dev.carapps.parking.STOP"

        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 1
        private const val PARKED_NOTIFICATION_ID = 2
        private const val LOCATION_INTERVAL_MS = 10_000L
        private const val LOCATION_DISTANCE_M = 20f
        private const val FIX_STALE_MS = 60_000L

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)

        /**
         * Failures are recorded rather than swallowed. Since Android 12 a foreground
         * service cannot generally be started from the background, and a Bluetooth
         * broadcast is one of the exemptions — but if that exemption does not apply
         * here, the throw is the explanation for a drive that was never recorded, and
         * it must not disappear into a catch block.
         */
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
