package fr.amandio.blrando

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log

/**
 * Keeps GPS running with the screen off while "Fil" or "Enreg." is on:
 * feeds [Session], vibrates when straying from the reference, writes the GPX file.
 */
class TrackingService : Service(), LocationListener {

    private var listening = false
    private val handler = Handler(Looper.getMainLooper())

    /** GPX file of the recording in progress (Downloads/BLRando/AAAAMMJJ_HHMM.gpx). */
    private var recordUri: Uri? = null

    private val flusher = object : Runnable {
        override fun run() {
            flushRecording()
            handler.postDelayed(this, FLUSH_MS)
        }
    }

    private val reminder = object : Runnable {
        override fun run() {
            if (Session.guiding && Session.offTrack) {
                vibrate(OFF_PATTERN)
                handler.postDelayed(this, REMIND_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Watch frames, produced while the service runs (screen off included). */
    private var watchFeed: WatchFeed? = null

    override fun onCreate() {
        super.onCreate()
        watchFeed = WatchFeed(this).also { it.start(); feed = it }
        WatchServer.start(this)
        Session.offTrackListener = { off ->
            handler.removeCallbacks(reminder)
            if (off) {
                vibrate(OFF_PATTERN)
                handler.postDelayed(reminder, REMIND_MS)
            } else {
                vibrate(BACK_PATTERN)
            }
            updateNotification()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!listening) {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            listening = true
            Session.serviceStart = System.currentTimeMillis()
        }
        syncRecording()
        if (!Session.guiding) handler.removeCallbacks(reminder)
        if (!Session.active) stopSelf()
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) = Session.onFix(location, fromService = true)

    override fun onProviderDisabled(provider: String) {}
    override fun onProviderEnabled(provider: String) {}

    override fun onDestroy() {
        watchFeed?.stop()
        watchFeed = null
        feed = null
        handler.removeCallbacksAndMessages(null)
        finishRecording()
        Session.offTrackListener = null
        getSystemService(LocationManager::class.java).removeUpdates(this)
        listening = false
        Session.serviceStart = 0L
        super.onDestroy()
    }

    // ------------------------------------------------------------ recording file

    private fun syncRecording() {
        if (Session.recording && recordUri == null) {
            recordUri = createFile(Session.recordName)
            Session.lastRecordUri = recordUri
            handler.postDelayed(flusher, FLUSH_MS)
        } else if (!Session.recording && recordUri != null) {
            finishRecording()
        }
    }

    private fun createFile(name: String): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.gpx")
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BLRando")
        }
        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        Log.e(TAG, "cannot create GPX", e)
        null
    }

    /** Rewrites the whole file, so a crash or a killed app loses at most [FLUSH_MS] of track. */
    private fun flushRecording() {
        val uri = recordUri ?: return
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { Gpx.write(it, Session.recordName, Session.trail) }
        } catch (e: Exception) {
            Log.e(TAG, "cannot write GPX", e)
        }
    }

    private fun finishRecording() {
        val uri = recordUri ?: return
        handler.removeCallbacks(flusher)
        if (Session.trail.size < 2) {
            // Nothing walked: don't leave an empty file in Downloads
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        } else {
            flushRecording()
        }
        recordUri = null
    }

    // ------------------------------------------------------------ alerts and notification

    private fun vibrate(pattern: LongArray) {
        getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun updateNotification() {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val parts = ArrayList<String>()
        if (Session.recording) parts.add("● Enregistrement")
        if (Session.guiding) parts.add(
            if (Session.offTrack) "⚠ Hors trace ${Session.distanceToReference?.toInt() ?: "?"} m" else "Bouée active"
        )
        return Notification.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("BLRando")
            .setContentText(parts.joinToString(" · ").ifEmpty { if (Session.watchConnected) "Montre connectée" else "Localisation" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        /** The running service's watch feed (for the bezel zoom and debug), null when stopped. */
        var feed: WatchFeed? = null
            private set
        private const val TAG = "BLRando"
        private const val NOTIF_ID = 1
        private const val FLUSH_MS = 30_000L
        private const val REMIND_MS = 60_000L
        /** Three long pulses: you left the track. */
        private val OFF_PATTERN = longArrayOf(0, 700, 300, 700, 300, 700)
        /** One short pulse: back on track. */
        private val BACK_PATTERN = longArrayOf(0, 200)

        /**
         * Starts, refreshes or stops the service to match [Session.active].
         * Returns false when Android refuses to start it from the background (watch command while the app is
         * not in front, without "allow all the time" location or battery exemption): the user is then told.
         */
        fun sync(ctx: Context): Boolean {
            val intent = Intent(ctx, TrackingService::class.java)
            if (!Session.active) {
                ctx.stopService(intent)
                return true
            }
            return try {
                ctx.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "cannot start tracking from the background", e)
                needsApp(ctx)
                false
            }
        }

        /** Notification asking to open BLRando once on the phone (permissions for watch-started tracking). */
        private fun needsApp(ctx: Context) {
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(ctx, App.CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("BLRando")
                .setContentText("La montre ne peut pas démarrer le suivi : ouvre BLRando sur le téléphone")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_NEEDS_APP, n)
        }

        private const val NOTIF_NEEDS_APP = 2
    }
}
