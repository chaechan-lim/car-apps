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
import android.util.Log
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
    private var startedAtWall = 0L
    private var startedAtElapsed = 0L
    private var lastFix: Location? = null
    private var lastFixAtElapsed = 0L

    override fun onCreate() {
        super.onCreate()
        sensors = DriveSensors(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> {
                finishRecording()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startRecording() {
        startForeground(NOTIFICATION_ID, notification())
        startedAtWall = System.currentTimeMillis()
        startedAtElapsed = SystemClock.elapsedRealtime()
        sensors.start()
        requestLocation()
        Log.i(TAG, "recording started, barometer=${sensors.hasBarometer}")
    }

    /**
     * Keeps the last fix from before the roof cut it off.
     *
     * Coarse on purpose: this answers "which building", and the app exists because
     * the interesting part starts once this stops updating. How stale the fix is at
     * parking time is itself a useful number, so it is recorded alongside.
     */
    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                LOCATION_DISTANCE_M,
                this,
            )
        }.onFailure { Log.w(TAG, "location unavailable", it) }
    }

    override fun onLocationChanged(location: Location) {
        lastFix = location
        lastFixAtElapsed = SystemClock.elapsedRealtime()
    }

    private fun finishRecording() {
        val samples = sensors.pressureSamples
        val yaw = sensors.yawDegrees
        sensors.stop()
        runCatching {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .removeUpdates(this)
        }

        val event = ParkingEvent(
            id = System.currentTimeMillis(),
            startedAt = startedAtWall,
            endedAt = System.currentTimeMillis(),
            pressureSamples = samples,
            yawDegrees = yaw,
            lastLocation = lastFix?.let {
                ParkingEvent.Fix(it.latitude, it.longitude, it.accuracy)
            },
            secondsSinceLastFix = if (lastFixAtElapsed == 0L) null
            else (SystemClock.elapsedRealtime() - lastFixAtElapsed) / 1000,
            // Captured here rather than during the drive: the fingerprint that matters
            // is the one at the parked spot.
            wifi = WifiFingerprint(this).capture(),
        )
        EventStore(this).add(event)
        Log.i(TAG, "recorded ${samples.size} samples, drop=${event.pressureDropHpa}")
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
        NotificationManagerCompatShim(this).notify(
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
        sensors.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.carapps.parking.START"
        const val ACTION_STOP = "dev.carapps.parking.STOP"

        private const val TAG = "Parking"
        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 1
        private const val PARKED_NOTIFICATION_ID = 2
        private const val LOCATION_INTERVAL_MS = 10_000L
        private const val LOCATION_DISTANCE_M = 20f

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, DriveRecorderService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start service", it) }
        }
    }
}

/** Thin wrapper so notification posting stays in one place. */
private class NotificationManagerCompatShim(private val context: Context) {
    fun notify(id: Int, notification: Notification) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }
}
