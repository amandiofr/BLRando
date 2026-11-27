package fr.amandio.blrando

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import android.view.HapticFeedbackConstants
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sinh
import kotlin.math.tan

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var bannerText: TextView
    private lateinit var recDot: View
    private lateinit var offTrackView: TextView
    private lateinit var north: ImageButton
    private lateinit var btnRecenter: ImageButton
    private lateinit var btnBuoy: ImageButton
    private lateinit var btnRecord: ImageButton

    private val refLines = ArrayList<Polyline>()
    /** One polyline per recorded segment (a GPS jump starts a new one). */
    private val trailLines = ArrayList<Polyline>()
    /** Car and checkered flag at the ends of the reference track. */
    private val endMarkers = ArrayList<Marker>()
    private lateinit var posOverlay: PositionOverlay
    private var trailDrawn = 0

    private var follow = true
    private var blink: ObjectAnimator? = null
    private var pendingAfterPermission: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateBanner()
            handler.postDelayed(this, 1000)
        }
    }
    private val uiLocationListener = LocationListener { Session.onFix(it, fromService = false) }

    /** Phone compass, smoothed, corrected to true north. Only while the screen is visible. */
    private val compassListener = object : SensorEventListener {
        private val rot = FloatArray(9)
        private val orient = FloatArray(3)
        private var smoothed: Float? = null
        private var lastPush = 0L

        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, orient)
            var az = Math.toDegrees(orient[0].toDouble()).toFloat()
            Session.position?.let {
                az += GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(),
                    System.currentTimeMillis()).declination
            }
            // Circular low-pass filter: no jitter, no jump across 0°/360°
            val prev = smoothed
            val next = if (prev == null) az else prev + 0.15f * (((az - prev + 540f) % 360f) - 180f)
            smoothed = (next + 360f) % 360f
            val now = System.currentTimeMillis()
            val old = Session.compassHeading
            if (now - lastPush > 100 && (old == null || abs(((smoothed!! - old + 540f) % 360f) - 180f) > 1.5f)) {
                lastPush = now
                Session.compassHeading = smoothed
                Session.notifyChanged()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val sessionListener: () -> Unit = { onSessionChanged() }

    private val refFile get() = File(filesDir, "reference.gpx")
    private val prefs get() = getSharedPreferences("ui", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        bannerText = findViewById(R.id.bannerText)
        recDot = findViewById(R.id.recDot)
        offTrackView = findViewById(R.id.offTrack)
        north = findViewById(R.id.north)
        btnRecenter = findViewById(R.id.btnRecenter)
        btnBuoy = findViewById(R.id.btnBuoy)
        btnRecord = findViewById(R.id.btnRecord)

        applyInsets()
        setupMap()
        // The watch browser connects to us over Wi-Fi (or the phone's hotspot)
        WatchServer.start(this)

        btnRecenter.setOnClickListener { recenter() }
        findViewById<ImageButton>(R.id.btnFit).setOnClickListener { fitAll() }
        findViewById<ImageButton>(R.id.btnTrace).apply {
            setOnClickListener { pickReference() }
            setOnLongClickListener { askRemoveReference(); true }
        }
        btnBuoy.setOnClickListener { toggleGuiding() }
        btnRecord.setOnClickListener { onRecordButton() }
        north.setOnClickListener { toggleOrientation() }
        // Tap on the time banner: the address to open in the watch browser
        findViewById<View>(R.id.banner).setOnClickListener { showWatchAddress() }

        // The process may outlive the screen (GPS service running): redraw what Session still holds
        if (Session.reference.isEmpty()) loadReference(fitAfter = false) else drawReference()
        handleIncoming(intent)
        // The watch may start tracking with the phone in the pocket: get what it needs up front
        if (!hasLocationPermission()) requestPermissionsNow() else {
            askBatteryExemption()
            // New version on GitHub? At the first opening of the day, not on the very first launch (permissions first)
            AppUpdater.checkDaily(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        goFullScreen()
        map.onResume()
        Session.addListener(sessionListener)
        getSystemService(SensorManager::class.java).let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sm.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        startUiLocation()
        onSessionChanged()
        handler.post(ticker)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        Session.removeListener(sessionListener)
        getSystemService(SensorManager::class.java).unregisterListener(compassListener)
        // Screen off or app hidden: the phone is in the pocket, its compass means nothing
        Session.compassHeading = null
        getSystemService(LocationManager::class.java).removeUpdates(uiLocationListener)
        map.mapCenter.let {
            prefs.edit().putFloat("lat", it.latitude.toFloat()).putFloat("lon", it.longitude.toFloat())
                .putFloat("zoom", map.zoomLevelDouble.toFloat()).apply()
        }
        map.onPause()
        super.onPause()
    }

    // ---------------------------------------------------------------- full screen

    /** Same as BLBrowser: status and navigation bars hidden, a swipe shows them briefly. */
    private fun goFullScreen() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Bars are hidden, but the camera hole is still there: keep the banner below it. */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        val banner = findViewById<View>(R.id.banner)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout()).top
            (banner.layoutParams as ViewGroup.MarginLayoutParams).topMargin = top + dp(8f).toInt()
            (offTrackView.layoutParams as ViewGroup.MarginLayoutParams).topMargin = top + dp(70f).toInt()
            banner.requestLayout()
            insets
        }
    }

    // ---------------------------------------------------------------- map

    private fun dp(v: Float) = v * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        map.setTileSource(App.OPEN_TOPO_MAP)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        // Raster tiles are tiny on a 3x screen: scale them up so labels stay readable
        map.isTilesScaledToDpi = true
        map.tilesScaleFactor = 0.75f
        map.minZoomLevel = 4.0
        map.maxZoomLevel = 19.0
        map.isVerticalMapRepetitionEnabled = false
        map.controller.setZoom(prefs.getFloat("zoom", 15f).toDouble())
        map.controller.setCenter(GeoPoint(prefs.getFloat("lat", 48.58f).toDouble(), prefs.getFloat("lon", 7.75f).toDouble()))
        map.post { syncWatchZoom() } // after layout: the zoom offset needs the map size
        // The watch follows the phone's zoom (minus the fixed offset, see zoomOffset); the bezel adjusts it after
        map.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                // Moved by a finger (fling included): the watch shows the same place once the map stops
                if (userPanned) {
                    handler.removeCallbacks(pushPan)
                    handler.postDelayed(pushPan, 300)
                }
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                syncWatchZoom()
                return false
            }
        })

        map.overlays.add(CopyrightOverlay(this))
        // Long press on the map: Google Maps itinerary to that point
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.itinerary_question)
                    .setPositiveButton(R.string.itinerary) { _, _ -> openItinerary(p) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return true
            }
        }))
        posOverlay = PositionOverlay(dp(17f))
        map.overlays.add(posOverlay)

        // Dragging with one finger = exploring: stop following
        var downX = 0f
        var downY = 0f
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        map.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                MotionEvent.ACTION_MOVE -> if (ev.pointerCount == 1 && hypot(ev.x - downX, ev.y - downY) > slop) {
                    if (follow) setFollow(false)
                    userPanned = true
                }
            }
            false
        }
    }

    /** The phone map was dragged by a finger: its scroll events are sent on to the watch. */
    private var userPanned = false

    /** Phone map dragged: the watch shows the same place (free view, north up), like a pan on the watch. */
    private val pushPan = Runnable {
        userPanned = false
        val c = map.mapCenter
        Session.watchCenter = GeoPoint(c.latitude, c.longitude)
        seenWatchCenter = Session.watchCenter // our own change: nothing to mirror back
        TrackingService.feed?.requestFrame()
    }

    /** Last watch view change applied to the phone map. */
    private var seenWatchSeq = Session.watchViewSeq
    private var seenWatchCenter = Session.watchCenter
    private var seenRecenter = Session.recenterSeq

    /** "Centrer" on the phone: both devices follow the hiker at the centering zoom. */
    private fun recenter() {
        if (TrackingService.feed?.center() != null) return // followWatchView applies it here
        map.controller.setZoom(Session.CENTER_ZOOM + zoomOffset())
        setFollow(true)
    }

    /** Bezel, pan, fit or center on the watch: the phone map shows the same place at the same zoom. */
    private fun followWatchView() {
        if (Session.watchViewSeq == seenWatchSeq) return
        // The zoom offset needs the map size
        if (map.width == 0) { map.post { followWatchView() }; return }
        seenWatchSeq = Session.watchViewSeq
        map.controller.setZoom(Session.watchZoom + zoomOffset())
        val c = Session.watchCenter
        if (c != null) {
            // The moved watch view is north up
            follow = false
            map.mapOrientation = 0f
            map.controller.setCenter(c)
        } else if (seenWatchCenter != null || Session.recenterSeq != seenRecenter) {
            // "Centrer" (on either device): the phone follows the hiker again too
            follow = true
        }
        seenRecenter = Session.recenterSeq
        seenWatchCenter = c
    }

    /** Phone zoom changed (pinch...): the watch follows, minus the zoom offset, rounded to the bezel step. */
    private fun syncWatchZoom() {
        if (map.width == 0) return // offset unknown yet
        val raw = Session.roundZoom(map.zoomLevelDouble - zoomOffset())
        // Same zoom as the watch already (e.g. a wide "englober" set by the watch): nothing to do
        if (raw == Session.roundZoom(Session.watchZoom)) return
        val z = raw.coerceIn(WatchFeed.MIN_ZOOM, WatchFeed.MAX_ZOOM)
        if (z != Session.watchZoom) {
            Session.watchZoom = z
            TrackingService.feed?.requestFrame()
        }
    }

    /**
     * Phone zoom minus watch zoom: the phone map shows the same area across its width as the watch
     * across its "englober" circle, so a track filling the watch fills the phone too.
     */
    private fun zoomOffset(): Double {
        val availW = map.width - 2 * dp(24f)
        if (availW <= 0) return 0.0
        return log2(availW / org.osmdroid.util.TileSystem.getTileSize()) -
            log2(WatchRenderer.FIT_PX / WatchRenderer.TILE_PX)
    }

    /** Address of the watch page (Wi-Fi or the phone's hotspot). */
    private fun showWatchAddress() {
        val url = WatchServer.url()
        AlertDialog.Builder(this)
            .setTitle(R.string.watch_title)
            .setMessage(
                if (url != null) getString(R.string.watch_address, url.removePrefix("http://"))
                else getString(R.string.hotspot_off)
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Opens Google Maps with an itinerary from here to [p] (any maps app if Google Maps is missing). */
    private fun openItinerary(p: GeoPoint) {
        val dest = String.format(Locale.US, "%.6f,%.6f", p.latitude, p.longitude)
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$dest")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$dest?q=$dest")))
            } catch (_: android.content.ActivityNotFoundException) {
                toast(getString(R.string.no_maps_app))
            }
        }
    }

    /** Following: our position stays exactly at the center of the screen. */
    private fun setFollow(f: Boolean) {
        follow = f
        if (f) onSessionChanged()
    }

    private fun newTrailLine(): Polyline = Polyline().apply {
        infoWindow = null
        outlinePaint.color = 0xFF1565C0.toInt()
        outlinePaint.strokeWidth = dp(5f)
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        // Under the track end pins and the arrow, above the reference
        map.overlays.add(map.overlays.indexOf(endMarkers.firstOrNull() ?: posOverlay), this)
        trailLines.add(this)
    }

    private fun drawTrail() {
        val trail = Session.trail
        if (trail.size < trailDrawn) {
            trailLines.forEach { map.overlays.remove(it) }
            trailLines.clear()
            trailDrawn = 0
        }
        for (i in trailDrawn until trail.size) {
            val tp = trail[i]
            val line = if (trailLines.isEmpty() || tp.segStart) newTrailLine() else trailLines.last()
            line.addPoint(tp.point)
        }
        trailDrawn = trail.size
    }

    private fun onSessionChanged() {
        followWatchView()
        drawTrail()

        posOverlay.position = Session.position
        posOverlay.heading = Session.displayHeading
        val pos = Session.position
        if (follow && pos != null) {
            if (Session.headingUp) Session.displayHeading?.let { map.mapOrientation = -it }
            map.controller.setCenter(pos)
        }
        updateButtons()
        updateBanner()
        map.invalidate()
    }

    /**
     * Frames the reference track only: our position, waypoints and a recording started elsewhere
     * (at home, in the car) would otherwise zoom out to the whole region.
     * Without a reference: the recorded trail, else our position.
     */
    private fun fitAll() {
        val pts = ArrayList<GeoPoint>()
        Session.reference.forEach { pts.addAll(it) }
        if (pts.isEmpty()) Session.trail.forEach { pts.add(it.point) }
        if (pts.isEmpty()) Session.position?.let { pts.add(it) }
        if (pts.isEmpty()) return
        setFollow(false)
        map.mapOrientation = 0f
        if (pts.size == 1) {
            map.controller.animateTo(pts[0])
            return
        }
        // With the watch service running: the shared fit, same view on both screens (followWatchView applies it)
        map.post { if (TrackingService.feed?.fitView() != true) frame(pts) }
    }

    /** Zoom showing all [pts] on the phone map, or null before layout. */
    private fun fitZoom(pts: List<GeoPoint>): Double? {
        if (pts.isEmpty()) return null
        val bb = BoundingBox.fromGeoPointsSafe(pts)
        // Small margin only: the floating buttons may cover the track's edges
        val availW = map.width - 2 * dp(24f)
        val availH = map.height - 2 * dp(24f)
        if (availW <= 0 || availH <= 0) return null
        val tile = org.osmdroid.util.TileSystem.getTileSize()
        val lonFrac = (bb.lonEast - bb.lonWest) / 360.0
        fun mercY(lat: Double) = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        val latFrac = (mercY(bb.latNorth) - mercY(bb.latSouth)) / (2 * Math.PI)
        val zx = if (lonFrac > 0) log2(availW / (lonFrac * tile)) else 18.0
        val zy = if (latFrac > 0) log2(availH / (latFrac * tile)) else 18.0
        return minOf(zx, zy, 17.0).coerceAtLeast(map.minZoomLevel)
    }

    /**
     * Centers [pts] on screen and zooms so they fill the space left by the controls
     * (osmdroid's zoomToBoundingBox leaves it much too small with scaled tiles).
     */
    private fun frame(pts: List<GeoPoint>) {
        val zoom = fitZoom(pts) ?: return
        val bb = BoundingBox.fromGeoPointsSafe(pts)
        fun mercY(lat: Double) = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        map.controller.setZoom(zoom)
        map.controller.setCenter(GeoPoint(
            Math.toDegrees(atan(sinh((mercY(bb.latNorth) + mercY(bb.latSouth)) / 2))),
            (bb.lonEast + bb.lonWest) / 2,
        ))
    }

    // ---------------------------------------------------------------- banner and buttons

    private fun updateBanner() {
        val now = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
        bannerText.text = if (Session.recording) {
            val km = String.format(Locale.US, "%.1f km", Session.distanceM / 1000)
            val min = Session.elapsedMs() / 60000
            val dur = String.format(Locale.US, "%d h %02d", min / 60, min % 60)
            "$km · $dur · $now"
        } else now

        if (Session.guiding && Session.offTrack) {
            offTrackView.text = getString(R.string.off_track, Session.distanceToReference?.toInt()?.toString() ?: "?")
            offTrackView.visibility = View.VISIBLE
        } else {
            offTrackView.visibility = View.GONE
        }
    }

    private fun updateButtons() {
        btnBuoy.backgroundTintList = if (Session.guiding) ColorStateList.valueOf(ACTIVE) else null
        // The icon tells the mode: N/S needle = north up, arrow tip = walking direction up
        north.setImageResource(if (Session.headingUp) R.drawable.ic_heading_up else R.drawable.ic_north)

        // Idle: record. Recording: stop.
        btnRecord.setImageResource(if (Session.recording) R.drawable.ic_stop else R.drawable.ic_record)
        btnRecord.contentDescription = getString(if (Session.recording) R.string.stop_recording else R.string.record)
        // No new reference while recording: one button less
        findViewById<View>(R.id.btnTrace).visibility = if (Session.recording) View.GONE else View.VISIBLE

        if (Session.recording) {
            recDot.visibility = View.VISIBLE
            if (blink == null) blink = ObjectAnimator.ofFloat(recDot, View.ALPHA, 1f, 0.2f).apply {
                duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            blink?.cancel()
            blink = null
            recDot.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- orientation

    private fun toggleOrientation() {
        Session.headingUp = !Session.headingUp
        if (!Session.headingUp) map.mapOrientation = 0f
        toast(getString(if (Session.headingUp) R.string.map_heading_up else R.string.map_north_up))
        onSessionChanged()
    }

    // ---------------------------------------------------------------- lifebuoy and recording

    /** Lifebuoy: alert when we stray from the reference track. Independent from recording. */
    private fun toggleGuiding() {
        if (Session.guiding) {
            Session.setGuiding(false)
            afterSwitch()
            toast(getString(R.string.buoy_off))
            return
        }
        if (Session.reference.isEmpty()) {
            toast(getString(R.string.no_track))
            return
        }
        withLocation {
            Session.setGuiding(true)
            afterSwitch()
            toast(getString(R.string.buoy_on))
        }
    }

    /** Confirmation without a question: the button text says it all. */
    private fun confirm(action: Int, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Record button: start (new file AAAAMMJJ_HHMM.gpx), or stop while recording. Independent from the lifebuoy. */
    private fun onRecordButton() {
        if (Session.recording) { onStopButton(); return }
        confirm(R.string.record) {
            withLocation {
                Session.startRecording()
                afterSwitch()
                setFollow(true)
            }
        }
    }

    private fun onStopButton() {
        if (!Session.recording) return
        confirm(R.string.stop) {
            val name = Session.recordName
            val enough = Session.trail.size >= 2
            val km = String.format(Locale.getDefault(), "%.1f", Session.distanceM / 1000)
            val uri = Session.lastRecordUri
            Session.stopRecording()
            afterSwitch() // the service writes the final file as it stops
            if (!enough || uri == null) {
                toast(getString(R.string.track_too_short))
                return@confirm
            }
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.track_saved, km, name))
                .setPositiveButton(R.string.share) { _, _ -> shareTrack(uri, name) }
                .setNegativeButton(R.string.ok, null)
                .show()
        }
    }

    /** Share sheet with the GPX file (mail, WhatsApp, Drive, Gaia...). */
    private fun shareTrack(uri: Uri, name: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.track_subject, name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_track)))
    }

    private fun afterSwitch() {
        TrackingService.sync(this)
        if (Session.active) getSystemService(LocationManager::class.java).removeUpdates(uiLocationListener)
        else startUiLocation()
    }

    // ---------------------------------------------------------------- reference track

    private fun pickReference() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            REQ_OPEN,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { importReference(it) }
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            importReference(uri)
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun importReference(uri: Uri) {
        Thread {
            try {
                val tmp = File(filesDir, "reference.tmp")
                contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val track = tmp.inputStream().use { Gpx.parse(it) }
                if (track.segments.isEmpty()) {
                    tmp.delete()
                    runOnUiThread { toast(getString(R.string.no_track_in_file)) }
                    return@Thread
                }
                tmp.renameTo(refFile)
                runOnUiThread { showReference(track, fitAfter = true) }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.unreadable_file, e.message ?: "")) }
            }
        }.start()
    }

    private fun loadReference(fitAfter: Boolean) {
        if (!refFile.exists()) return
        Thread {
            val track = try { refFile.inputStream().use { Gpx.parse(it) } } catch (_: Exception) { null }
            if (track != null) runOnUiThread { showReference(track, fitAfter) }
        }.start()
    }

    private fun showReference(track: Gpx.Track, fitAfter: Boolean) {
        Session.reference = track.segments
        Session.referenceName = track.name
        if (Session.guiding) Session.setGuiding(true) // re-check against the new track
        drawReference()
        if (fitAfter) {
            // Just imported: frame it and fetch its map for offline use
            toast(getString(R.string.track_loaded, track.name ?: getString(R.string.unnamed)))
            fitAll()
            TilePrefetcher.start(this, track.segments)
        }
    }

    private fun drawReference() {
        refLines.forEach { map.overlays.remove(it) }
        refLines.clear()
        for (seg in Session.reference) {
            val line = Polyline().apply {
                infoWindow = null
                outlinePaint.color = 0xFFD81B60.toInt()
                outlinePaint.strokeWidth = dp(6f)
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                setPoints(seg)
            }
            refLines.add(line)
            map.overlays.add(1, line) // under the trail and the arrow
        }
        drawEnds()
        map.invalidate()
    }

    /** Car at the start of the reference track, checkered flag at its end (none for a loop). */
    private fun drawEnds() {
        endMarkers.forEach { map.overlays.remove(it) }
        endMarkers.clear()
        val (start, end) = Session.referenceEnds() ?: return
        fun pin(p: GeoPoint, kind: TrackIcons.Kind) = Marker(map).apply {
            position = p
            icon = BitmapDrawable(resources, TrackIcons.bitmap(kind, dp(42f).toInt()))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
            setOnMarkerClickListener { _, _ -> false } // taps go to the map
        }
        // The flag first, so the car is on top when they are close
        end?.let { endMarkers.add(pin(it, TrackIcons.Kind.FLAG)) }
        endMarkers.add(pin(start, TrackIcons.Kind.CAR))
        // Above the trail, under the arrow
        endMarkers.forEach { map.overlays.add(map.overlays.indexOf(posOverlay), it) }
    }

    private fun askRemoveReference() {
        if (Session.reference.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_track_question, Session.referenceName ?: getString(R.string.unnamed)))
            .setPositiveButton(R.string.remove) { _, _ ->
                refFile.delete()
                refLines.forEach { map.overlays.remove(it) }
                refLines.clear()
                endMarkers.forEach { map.overlays.remove(it) }
                endMarkers.clear()
                Session.reference = emptyList()
                Session.referenceName = null
                if (Session.guiding) {
                    Session.setGuiding(false)
                    afterSwitch()
                }
                map.invalidate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- location and permissions

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsNow() {
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            REQ_PERM,
        )
    }

    private fun withLocation(action: () -> Unit) {
        if (hasLocationPermission()) {
            action()
            askBatteryExemption()
        } else {
            pendingAfterPermission = { action(); askBatteryExemption() }
            requestPermissionsNow()
        }
    }

    /**
     * One UI puts background apps to sleep: the GPS could stop in the middle of a hike.
     * Asked once per launch, when tracking starts, until the user accepts.
     */
    private var batteryAsked = false

    private fun askBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (batteryAsked || pm.isIgnoringBatteryOptimizations(packageName)) {
            askBackgroundLocation()
            return
        }
        batteryAsked = true
        AlertDialog.Builder(this)
            .setMessage(R.string.battery_question)
            .setPositiveButton(R.string.allow) { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (_: android.content.ActivityNotFoundException) {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton(R.string.later, null)
            .setOnDismissListener { askBackgroundLocation() }
            .show()
    }

    /**
     * "Allow all the time" location: without it Android refuses to start the GPS when the watch asks for it
     * while the phone is in the pocket. Asked once per launch until granted.
     */
    private var backgroundAsked = false

    private fun askBackgroundLocation() {
        if (backgroundAsked || !hasLocationPermission() ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        backgroundAsked = true
        AlertDialog.Builder(this)
            .setMessage(R.string.location_always_question)
            .setPositiveButton(R.string.continue_) { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (hasLocationPermission()) {
            startUiLocation()
            pendingAfterPermission?.invoke() ?: askBatteryExemption()
        } else {
            toast(getString(R.string.location_needed))
        }
        pendingAfterPermission = null
    }

    @SuppressLint("MissingPermission")
    private fun startUiLocation() {
        if (!hasLocationPermission() || Session.active) return
        val lm = getSystemService(LocationManager::class.java)
        if (Session.position == null) {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                ?.let { Session.onFix(it, fromService = false) }
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, uiLocationListener, Looper.getMainLooper())
    }

    private fun toast(msg: String) = Toasts.show(this, msg)

    companion object {
        private const val REQ_OPEN = 1
        private const val REQ_PERM = 2
        private const val REQ_BG = 3
        /** Background of a round button whose function is on. */
        private const val ACTIVE = 0xFF1565C0.toInt()
    }
}
