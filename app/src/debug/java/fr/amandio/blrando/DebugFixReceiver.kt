package fr.amandio.blrando

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.util.Base64
import org.json.JSONObject

/**
 * Debug builds only.
 * Fake GPS fix, as if it came from the tracking service:
 *   adb shell am broadcast -a fr.amandio.blrando.DEBUG_FIX -p fr.amandio.blrando --ef lat 48.5834 --ef lon 7.7458
 * Watch bezel (zoom +1 / -1):
 *   adb shell am broadcast -a fr.amandio.blrando.DEBUG_ZOOM -p fr.amandio.blrando --ei delta 1
 * Any watch message (see WatchLink), JSON in base64:
 *   adb shell am broadcast -a fr.amandio.blrando.DEBUG_MSG -p fr.amandio.blrando --es b64 eyJ0IjoiaGVsbG8ifQ==
 */
class DebugFixReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIX -> {
                val loc = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = intent.getFloatExtra("lat", 0f).toDouble()
                    longitude = intent.getFloatExtra("lon", 0f).toDouble()
                    accuracy = 4f
                    // Simulated clock, 10 s per fix: a burst of fixes looks like a walk, not a jump
                    val now = System.currentTimeMillis()
                    fakeTime = if (now - fakeTime > 600_000) now else fakeTime + 10_000
                    time = fakeTime
                }
                Session.onFix(loc, fromService = true)
            }
            ACTION_ZOOM -> TrackingService.feed?.onZoom(intent.getIntExtra("delta", 0))
            ACTION_NOTIF -> testNotification(context, intent.getStringExtra("text") ?: "⚠ Hors trace · 62 m")
            ACTION_MSG -> {
                val b64 = intent.getStringExtra("b64") ?: return
                val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8))
                WatchLink.onMessage(context, json)
            }
        }
    }

    /**
     * Test: does the Tizen watch show a map in a mirrored phone notification?
     * Uses the last watch frame (files/watch/frame.json) as the big picture.
     *   adb shell am broadcast -a fr.amandio.blrando.DEBUG_NOTIF -p fr.amandio.blrando --es text "..."
     */
    private fun testNotification(ctx: Context, text: String) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel("alerts", "Alertes de randonnée", android.app.NotificationManager.IMPORTANCE_HIGH)
        )
        val frame = java.io.File(ctx.filesDir, "watch/frame.json")
        val bmp = try {
            val img = JSONObject(frame.readText()).getString("img")
            val bytes = Base64.decode(img, Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
        val b = android.app.Notification.Builder(ctx, "alerts")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("BLRando")
            .setContentText(text)
            .setAutoCancel(true)
        if (bmp != null) {
            b.setLargeIcon(bmp)
            b.setStyle(android.app.Notification.BigPictureStyle().bigPicture(bmp).setSummaryText(text))
        }
        // New id each time: a brand-new notification, not an update the watch could ignore
        nm.notify(1000 + (System.currentTimeMillis() / 1000 % 100000).toInt(), b.build())
    }

    companion object {
        const val ACTION_NOTIF = "fr.amandio.blrando.DEBUG_NOTIF"
        private var fakeTime = 0L
        const val ACTION_FIX = "fr.amandio.blrando.DEBUG_FIX"
        const val ACTION_ZOOM = "fr.amandio.blrando.DEBUG_ZOOM"
        const val ACTION_MSG = "fr.amandio.blrando.DEBUG_MSG"
    }
}
