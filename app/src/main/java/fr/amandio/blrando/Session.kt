package fr.amandio.blrando

import android.location.Location
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/** [segStart]: first point after a GPS jump, not joined to the previous one. */
class TrailPoint(val point: GeoPoint, val time: Long, val segStart: Boolean = false)

/**
 * State shared by the tracking service and the UI. Two independent switches:
 *  - [guiding] ("Fil"): watch the distance to the reference track, alert when straying;
 *  - [recording] ("Enreg."): breadcrumb + counters, written to a new GPX file.
 * Everything runs on the main thread (location callbacks use the main looper).
 */
object Session {
    /** Fixes worse than this are ignored for heading, trail and off-track checks. */
    private const val MAX_ACCURACY_M = 25f
    /** Minimum spacing of trail points, to avoid "balls of wool" during breaks. */
    private const val MIN_STEP_M = 5.0
    /** Above 30 km/h a move is a GPS jump or a vehicle, not walking (even running downhill). */
    private const val MAX_SPEED_MS = 30.0 / 3.6
    /** No GPS fix for this long while tracking: the GPS is considered lost. */
    const val GPS_LOST_MS = 60_000L
    /** Heading is computed over at least this distance, so GPS jitter doesn't spin the map. */
    private const val HEADING_BASE_M = 20.0
    /** Off-track alert above this distance from the reference... */
    const val OFF_TRACK_M = 50.0
    /** ...and back on track below this one (hysteresis, no flapping around the limit). */
    const val BACK_ON_TRACK_M = 30.0

    // ---- switches

    var guiding = false
        private set
    var recording = false
        private set
    /** Watch app open and talking to us: it needs the position, so GPS runs too. */
    var watchConnected = false
    /**
     * Screen on/off of each watch page (key: id chosen by the page, see watch/link.js), and its last request.
     * Several watches can share the phone (two hikers): images are drawn while at least one screen is on.
     */
    val watchScreens = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val watchPolls = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** A watch screen is on: map images are only drawn and sent then (battery). True until a watch says otherwise. */
    val watchScreenOn: Boolean
        get() {
            val now = System.currentTimeMillis()
            // A page that stopped asking (closed, watch off), or never asked (adb tests), no longer counts
            val live = watchScreens.filterKeys { id -> watchPolls[id]?.let { now - it < WatchFeed.GONE_MS } ?: false }
            return live.isEmpty() || live.values.any { it }
        }

    /** The GPS service must run while either switch is on, or while the watch app is open. */
    val active get() = guiding || recording || watchConnected

    /** Last fix received by the tracking service, and when the service started listening. */
    var lastServiceFix = 0L
        private set
    var serviceStart = 0L

    /** Tracking, but no GPS fix for [GPS_LOST_MS] (tunnel, gorge, GPS switched off...). */
    fun gpsLost(now: Long = System.currentTimeMillis()): Boolean =
        active && serviceStart > 0 && now - maxOf(lastServiceFix, serviceStart) > GPS_LOST_MS

    // ---- recording

    var startTime = 0L
        private set
    /** File name without extension, AAAAMMJJ_HHMM. */
    var recordName = ""
        private set
    val trail = ArrayList<TrailPoint>()
    var distanceM = 0.0
        private set
    /** GPX file of the current or last recording, for sharing it once stopped. */
    var lastRecordUri: android.net.Uri? = null

    // ---- position

    var position: GeoPoint? = null
        private set
    /** Walking direction in degrees from north, null until we have moved enough. */
    var heading: Float? = null
        private set
    private var headingTime = 0L
    private val recent = ArrayDeque<GeoPoint>()
    /** Phone compass (true north), set by the screen while it is visible. */
    var compassHeading: Float? = null

    /**
     * Direction shown by the arrow, like Gaia GPS: the GPS course while walking,
     * the phone's compass when standing still (the phone is then in hand, pointing ahead).
     */
    val displayHeading: Float?
        get() {
            val walking = heading != null && System.currentTimeMillis() - headingTime < WALKING_MS
            return if (walking) heading else compassHeading ?: heading
        }
    private const val WALKING_MS = 15_000L

    /** Map orientation, shared by the phone screen and the watch: true = walking direction up. */
    var headingUp = false
    /**
     * Center of the watch map when it doesn't follow the hiker (moved by a finger, or "englober"),
     * north up; null = follows the hiker.
     */
    var watchCenter: GeoPoint? = null
    /** Zoom shared by the phone and the watch (phone pinch or watch bezel). */
    var watchZoom = 15.0
    /** Bezel step: a third of a zoom level, so 3 clicks = one whole level (a whole level doubles the scale). */
    const val ZOOM_STEP = 1.0 / 3

    /** Zoom rounded to the bezel step (15.3333 -> 15.333333333333334, the same value every time). */
    fun roundZoom(z: Double) = Math.round(z / ZOOM_STEP) * ZOOM_STEP
    /** Bumped each time the watch changes its view (bezel, pan, fit, center), so the phone map follows. */
    var watchViewSeq = 0
    /** Bumped by "centrer" (watch or phone): both devices follow the hiker again. */
    var recenterSeq = 0
    /** Watch zoom applied by "centrer" (the phone adds its zoom offset). */
    const val CENTER_ZOOM = 15.0

    /** What "englober" shows: the reference track, else the recorded trail. */
    fun fitPoints(): List<GeoPoint> = reference.flatten().ifEmpty { trail.map { it.point } }

    /**
     * Start of the reference track (car) and its end (checkered flag), the end being null for a loop:
     * when it comes back within [LOOP_M] of the start. Null without a reference track.
     */
    fun referenceEnds(): Pair<GeoPoint, GeoPoint?>? {
        val start = reference.firstOrNull { it.isNotEmpty() }?.first() ?: return null
        val end = reference.last { it.isNotEmpty() }.last()
        return start to end.takeIf { start.distanceToAsDouble(it) > LOOP_M }
    }

    const val LOOP_M = 150.0

    // ---- reference and guiding

    var reference: List<List<GeoPoint>> = emptyList()
    var referenceName: String? = null
    /** Distance to the reference track, null when not guiding or unknown. */
    var distanceToReference: Double? = null
        private set
    var offTrack = false
        private set
    /** Called by the service to vibrate on off-track / back-on-track transitions. */
    var offTrackListener: ((Boolean) -> Unit)? = null

    // ---- listeners

    private val listeners = LinkedHashSet<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun notifyChanged() = listeners.toList().forEach { it() }

    fun setGuiding(on: Boolean) {
        guiding = on
        distanceToReference = null
        offTrack = false
        if (on) position?.let { checkReference(it) }
        notifyChanged()
    }

    fun startRecording() {
        recording = true
        startTime = System.currentTimeMillis()
        recordName = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(startTime))
        trail.clear()
        distanceM = 0.0
        position?.let { trail.add(TrailPoint(it, startTime)) }
        notifyChanged()
    }

    fun stopRecording() {
        recording = false
        notifyChanged()
    }

    /** Time since the start of the recording, breaks included. */
    fun elapsedMs(): Long = if (recording) System.currentTimeMillis() - startTime else 0L

    /** [fromService] fixes feed trail and guiding; UI fixes only move the arrow while idle. */
    fun onFix(loc: Location, fromService: Boolean) {
        if (active && !fromService) return
        if (fromService) lastServiceFix = System.currentTimeMillis()
        val p = GeoPoint(loc.latitude, loc.longitude, loc.altitude)
        position = p
        if (loc.accuracy <= MAX_ACCURACY_M) {
            updateHeading(p)
            if (recording && fromService) addToTrail(p, loc.accuracy, loc.time)
            if (guiding && fromService) checkReference(p)
        }
        notifyChanged()
    }

    private fun checkReference(p: GeoPoint) {
        if (reference.isEmpty()) return
        val d = distanceToPolylines(p, reference)
        distanceToReference = d
        val newOff = if (offTrack) d > BACK_ON_TRACK_M else d > OFF_TRACK_M
        if (newOff != offTrack) {
            offTrack = newOff
            offTrackListener?.invoke(newOff)
        }
    }

    private fun addToTrail(p: GeoPoint, accuracy: Float, time: Long) {
        val last = trail.lastOrNull()
        if (last == null) {
            trail.add(TrailPoint(p, time))
            return
        }
        val d = last.point.distanceToAsDouble(p)
        val dt = (time - last.time).coerceAtLeast(1L) / 1000.0
        if (d / dt > MAX_SPEED_MS) {
            // Faster than any hiker (GPS jump, car...): new segment, the jump is not distance walked
            trail.add(TrailPoint(p, time, segStart = true))
            return
        }
        if (d >= maxOf(MIN_STEP_M, accuracy / 2.0)) {
            trail.add(TrailPoint(p, time))
            distanceM += d
        }
    }

    private fun updateHeading(p: GeoPoint) {
        val moved = recent.isEmpty() || recent.last().distanceToAsDouble(p) >= 3.0
        if (!moved) return // standing still: keep the previous heading
        recent.addLast(p)
        while (recent.size > 40) recent.removeFirst()
        // Most recent point that is far enough behind us
        for (i in recent.indices.reversed()) {
            val q = recent[i]
            if (q.distanceToAsDouble(p) >= HEADING_BASE_M) {
                heading = q.bearingTo(p).toFloat()
                headingTime = System.currentTimeMillis()
                return
            }
        }
        // Not enough movement yet: keep the previous heading (frozen while standing)
    }

    /** Shortest distance in meters from [p] to the polylines (local flat projection, fine at hiking scale). */
    fun distanceToPolylines(p: GeoPoint, lines: List<List<GeoPoint>>): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.latitude))
        val ky = 110_574.0
        var best = Double.MAX_VALUE
        for (line in lines) {
            for (i in 0 until line.size - 1) {
                val ax = (line[i].longitude - p.longitude) * kx
                val ay = (line[i].latitude - p.latitude) * ky
                val bx = (line[i + 1].longitude - p.longitude) * kx
                val by = (line[i + 1].latitude - p.latitude) * ky
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d < best) best = d
            }
        }
        return best
    }
}
