package fr.amandio.blrando

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File

/**
 * Produces what the watch receives, from the tracking service (so it goes on with the phone screen off):
 *  - while the watch screen is on: a map frame every [PERIOD_MS] (or [LOW_BATT_PERIOD_MS] when the
 *    phone battery is low), and at once after a zoom change;
 *  - always: a small heartbeat without image every [BEAT_MS], so the watch can raise its alarms
 *    (link lost, GPS lost, off track, low battery) even with its screen off.
 *
 * Until the Bluetooth link exists, the sink writes the last frame to files/watch/ (debug).
 */
class WatchFeed(private val ctx: Context) {

    var sink: (JSONObject) -> Unit = { json ->
        // Served to the watch browser by WatchServer, and saved for the adb debug bridge
        val text = json.toString()
        if (json.optString("t") == "frame") WatchServer.lastFrame = text else WatchServer.lastBeat = text
        saveForDebug(json)
    }

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("watch-render").apply { start() }
    private val worker = Handler(thread.looper)
    private val renderer = WatchRenderer()
    /** Main thread only. */
    private var busy = false
    private var pending = false

    private val loop = object : Runnable {
        override fun run() {
            if (Session.watchScreenOn) requestFrame()
            main.postDelayed(this, if (phoneBattery() in 0 until LOW_BATT) LOW_BATT_PERIOD_MS else PERIOD_MS)
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            // The watch page stopped asking (watch gone, browser closed...): don't keep the GPS on for it.
            // It comes back by itself at its next request (WatchServer.wakeFeed).
            if (Session.watchConnected && System.currentTimeMillis() - WatchServer.lastPoll > GONE_MS) {
                Session.watchConnected = false
                TrackingService.sync(ctx)
            }
            sink(JSONObject().apply { put("t", "beat"); status(this) })
            main.postDelayed(this, BEAT_MS)
        }
    }

    fun start() {
        main.removeCallbacks(loop)
        main.removeCallbacks(beat)
        main.post(loop)
        main.postDelayed(beat, BEAT_MS)
    }

    fun stop() {
        main.removeCallbacks(loop)
        main.removeCallbacks(beat)
        thread.quitSafely()
    }

    private val installTime: Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
    } catch (_: Exception) { 0L }

    /** Phone battery in percent, -1 if unknown. */
    private fun phoneBattery(): Int = try {
        ctx.getSystemService(android.os.BatteryManager::class.java)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    /** Status fields shared by frames and heartbeats. Main thread. */
    private fun status(o: JSONObject) {
        val min = Session.elapsedMs() / 60000
        o.put("rec", Session.recording)
        o.put("dist", WatchRenderer.km(Session.distanceM))
        o.put("dur", "${min / 60} h ${"%02d".format(min % 60)}")
        o.put("off", (if (Session.guiding && Session.offTrack) Session.distanceToReference?.toInt() else null) ?: JSONObject.NULL)
        o.put("guiding", Session.guiding)
        o.put("hasRef", Session.reference.isNotEmpty())
        o.put("gpsLost", Session.gpsLost())
        o.put("phoneBatt", phoneBattery())
        // Same direction as the phone arrow: GPS course while walking, phone compass when standing still
        o.put("heading", Session.displayHeading?.let { Math.round(it) } ?: JSONObject.NULL)
        // Changes with each install of BLRando: the watch page reloads itself to get the new version
        o.put("ver", installTime)
    }

    /**
     * "Englober" from the watch or the phone: the whole reference track (or the trail), north up.
     * The phone follows with its fixed zoom offset, which makes the track fill its screen too.
     */
    fun fitView(): Boolean {
        val fit = WatchRenderer.fit(listOf(Session.fitPoints())) ?: return false
        Session.watchCenter = fit.first
        Session.watchZoom = fit.second.coerceIn(3.0, MAX_ZOOM)
        viewChanged()
        return true
    }

    /** From the watch: map dragged by (dx, dy) screen pixels. The view stops following the hiker. */
    fun onPan(dx: Double, dy: Double, seq: Int) {
        val c = Session.watchCenter ?: Session.position?.let { GeoPoint(it.latitude, it.longitude) } ?: return
        Session.watchCenter = WatchRenderer.offset(c, Session.watchZoom, -dx, -dy)
        panSeq = seq
        viewChanged()
    }

    /** "Centrer" from the watch or the phone: back to following the hiker, at the usual walking zoom. */
    fun center() {
        Session.watchCenter = null
        Session.watchZoom = Session.CENTER_ZOOM
        Session.recenterSeq++
        viewChanged()
    }

    /** The watch view changed: new frame, and the phone map follows. */
    private fun viewChanged() {
        Session.watchViewSeq++
        requestFrame()
        Session.notifyChanged()
    }

    /** Last pan applied, echoed in frames: the watch drops its finger offset once a frame includes it. */
    private var panSeq = 0

    /** From the watch: bezel turned. Zooms around what the watch shows (the hiker, or the moved view). */
    fun onZoom(delta: Int) {
        Session.watchZoom = Session.roundZoom(Session.watchZoom + delta * Session.ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)
        viewChanged()
    }

    /** Main thread: copy what the renderer needs, then render in the background. */
    fun requestFrame() {
        val pos = Session.position ?: return
        // A frame is being drawn: draw again right after it, with the latest zoom
        if (busy) { pending = true; return }
        busy = true
        pending = false
        val here = GeoPoint(pos.latitude, pos.longitude)
        val free = Session.watchCenter
        val snap = WatchRenderer.Snapshot(
            center = free ?: here,
            heading = Session.heading,
            headingUp = false, // the watch turns the map itself, with its compass
            zoom = Session.watchZoom,
            reference = Session.reference,
            trail = splitSegments(),
            // Moved view: the hiker is not at the center, the phone draws the position on the image
            marker = if (free != null) here else null,
            markerHeading = Session.displayHeading,
        )
        // "fit" (= free view) tells the watch: north up, no arrow at the center
        val st = JSONObject().also { status(it); it.put("fit", free != null); it.put("panSeq", panSeq) }
        worker.post {
            try {
                val json = renderer.frame(snap) {
                    val keys = st.keys()
                    while (keys.hasNext()) { val k = keys.next(); put(k, st.get(k)) }
                }
                main.post { sink(json) }
            } catch (e: Exception) {
                Log.e("BLRando", "watch frame failed", e)
            } finally {
                main.post {
                    busy = false
                    if (pending) requestFrame()
                }
            }
        }
    }

    private fun splitSegments(): List<List<GeoPoint>> {
        val out = ArrayList<ArrayList<GeoPoint>>()
        for (tp in Session.trail) {
            if (out.isEmpty() || tp.segStart) out.add(ArrayList())
            out.last().add(tp.point)
        }
        return out
    }

    private fun saveForDebug(json: JSONObject) {
        try {
            val dir = File(ctx.filesDir, "watch").apply { mkdirs() }
            // Write then rename, so a reader never sees a half-written file
            val name = if (json.optString("t") == "frame") "frame.json" else "beat.json"
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(File(dir, name))
        } catch (e: Exception) {
            Log.w("BLRando", "cannot save watch frame: ${e.message}")
        }
    }

    companion object {
        const val PERIOD_MS = 5_000L
        const val LOW_BATT_PERIOD_MS = 15_000L
        const val BEAT_MS = 10_000L
        /** No request from the watch for this long: it is gone. */
        const val GONE_MS = 120_000L
        /** Phone battery level (%) below which frames are spaced out. */
        const val LOW_BATT = 15
        const val MIN_ZOOM = 10.0
        const val MAX_ZOOM = 17.0
    }
}
