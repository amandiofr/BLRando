package fr.amandio.blrando

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.util.LruCache
import org.json.JSONObject
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Draws the watch image: a map centered on the hiker, with the reference track and the
 * recorded trail, north up. The watch turns it with its own compass and draws the arrow
 * itself, from the "heading" field.
 *
 * Works with the screen off (no MapView): tiles come from osmdroid's cache, missing ones
 * are downloaded one by one. Must run on a background thread.
 */
class WatchRenderer {

    private val source = App.OPEN_TOPO_MAP as OnlineTileSourceBase
    private val writer = SqlTileWriter()
    /** Decoded tiles, so consecutive frames don't re-read the database. */
    private val tiles = LruCache<Long, Bitmap>(48)
    /** Tiles that failed recently (no network...), not retried for a minute. */
    private val failed = HashMap<Long, Long>()

    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD81B60.toInt(); style = Paint.Style.STROKE; strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    class Snapshot(
        val center: GeoPoint,
        val heading: Float?,
        val headingUp: Boolean,
        /** Fractional zoom: tiles of the whole level, drawn larger for the fraction. */
        val zoom: Double,
        val reference: List<List<GeoPoint>>,
        val trail: List<List<GeoPoint>>,
        /** Image side in pixels. 360 is enough even when the watch rotates it: the screen is the inscribed circle. */
        val size: Int = SIZE,
        /** Position drawn on the image (fit view, where the hiker is not at the center), or null. */
        val marker: GeoPoint? = null,
        /** Direction of the [marker] arrow, degrees from north; null = unknown, points north. */
        val markerHeading: Float? = null,
    )

    // Position arrow on a moved view: same arrowhead as the watch page (watch/index.html, #hiker)
    private val markerDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9C4561A.toInt() }
    private val markerLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9F0843A.toInt() }
    private val markerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A3A0E.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f; strokeJoin = Paint.Join.ROUND
    }
    private val markerLeft = Path().apply { moveTo(0f, -22f); lineTo(-17f, 16f); lineTo(0f, 6f); close() }
    private val markerRight = Path().apply { moveTo(0f, -22f); lineTo(0f, 6f); lineTo(17f, 16f); close() }
    private val markerOutline = Path().apply {
        moveTo(0f, -22f); lineTo(17f, 16f); lineTo(0f, 6f); lineTo(-17f, 16f); close()
    }

    /** JSON frame for the watch (see watch/link.js), or null when there is no position yet. */
    fun frame(s: Snapshot, extra: JSONObject.() -> Unit): JSONObject {
        val jpeg = render(s)
        return JSONObject().apply {
            put("t", "frame")
            put("img", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            put("zoom", s.zoom)
            // Map drawn north up; the watch turns it with its own compass.
            // heading = walking direction in degrees from north (GPS), null = unknown
            put("northUp", !s.headingUp)
            extra() // status fields, heading included
        }
    }

    fun render(s: Snapshot): ByteArray {
        val size = s.size
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0xE8, 0xE4, 0xD8)) // neutral paper when tiles are missing

        val z = floor(s.zoom).toInt()
        val worldTile = TILE_PX * Math.pow(2.0, s.zoom - z)
        val n = 1 shl z
        val cx = worldX(s.center.longitude, n, worldTile)
        val cy = worldY(s.center.latitude, n, worldTile)
        val rotation = if (s.headingUp) -(s.heading ?: 0f) else 0f

        c.save()
        c.translate(size / 2f, size / 2f)
        c.rotate(rotation)

        // Tiles covering the rotated square: radius = half diagonal
        val r = size * 0.72
        val tx0 = floor((cx - r) / worldTile).toInt()
        val tx1 = floor((cx + r) / worldTile).toInt()
        val ty0 = floor((cy - r) / worldTile).toInt().coerceAtLeast(0)
        val ty1 = floor((cy + r) / worldTile).toInt().coerceAtMost(n - 1)
        val dst = Rect()
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            val wrapped = ((tx % n) + n) % n
            val bitmap = tile(MapTileIndex.getTileIndex(z, wrapped, ty)) ?: continue
            val left = (tx * worldTile - cx).toInt()
            val top = (ty * worldTile - cy).toInt()
            dst.set(left, top, left + worldTile.toInt() + 1, top + worldTile.toInt() + 1)
            c.drawBitmap(bitmap, null, dst, tilePaint)
        }

        drawLines(c, s.reference, n, worldTile, cx, cy, refPaint)
        drawLines(c, s.trail, n, worldTile, cx, cy, trailPaint)
        s.marker?.let {
            val x = (worldX(it.longitude, n, worldTile) - cx).toFloat()
            val y = (worldY(it.latitude, n, worldTile) - cy).toFloat()
            c.save()
            c.translate(x, y)
            // The moved view is north up: rotate by the heading itself (north when unknown)
            c.rotate(s.markerHeading ?: 0f)
            c.drawPath(markerLeft, markerDark)
            c.drawPath(markerRight, markerLight)
            c.drawPath(markerOutline, markerEdge)
            c.restore()
        }
        c.restore()

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun drawLines(c: Canvas, lines: List<List<GeoPoint>>, n: Int, tilePx: Double, cx: Double, cy: Double, paint: Paint) {
        val path = Path()
        for (line in lines) {
            var first = true
            for (p in line) {
                val x = (worldX(p.longitude, n, tilePx) - cx).toFloat()
                val y = (worldY(p.latitude, n, tilePx) - cy).toFloat()
                if (first) path.moveTo(x, y) else path.lineTo(x, y)
                first = false
            }
        }
        c.drawPath(path, paint)
    }

    private fun tile(index: Long): Bitmap? {
        tiles.get(index)?.let { return it }
        val now = System.currentTimeMillis()
        failed[index]?.let { if (now - it < 60_000) return null }
        var d = try { writer.loadTile(source, index) } catch (_: Exception) { null }
        if (d == null && TilePrefetcher.download(source, writer, index) == TilePrefetcher.Result.OK) {
            d = try { writer.loadTile(source, index) } catch (_: Exception) { null }
        }
        val b = (d as? BitmapDrawable)?.bitmap
        if (b == null) failed[index] = now else { failed.remove(index); tiles.put(index, b) }
        return b
    }

    companion object {
        const val SIZE = 360
        /** Tiles drawn larger than their 256 px so labels stay readable on the watch. */
        const val TILE_PX = 256.0 * 1.4
        const val JPEG_QUALITY = 70

        fun worldX(lon: Double, n: Int, tile: Double) = (lon + 180.0) / 360.0 * n * tile

        fun worldY(lat: Double, n: Int, tile: Double): Double {
            val r = Math.toRadians(lat)
            return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n * tile
        }

        fun km(m: Double) = String.format(Locale.US, "%.1f km", m / 1000)

        /** [c] moved by (dx, dy) pixels of the watch image at [zoom] (north up, y down). */
        fun offset(c: GeoPoint, zoom: Double, dx: Double, dy: Double): GeoPoint {
            val world = TILE_PX * Math.pow(2.0, zoom)
            val x = worldX(c.longitude, 1, world) + dx
            val y = (worldY(c.latitude, 1, world) + dy).coerceIn(0.0, world)
            val lon = ((x / world * 360.0 - 180.0) + 540.0) % 360.0 - 180.0
            val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * y / world))))
            return GeoPoint(lat, lon)
        }

        /** Pixels the fitted track may span: the square inscribed in the round screen, with a margin. */
        const val FIT_PX = 230.0

        /** Center and fractional zoom showing all the lines on the round watch screen, or null if empty. */
        fun fit(lines: List<List<GeoPoint>>): Pair<GeoPoint, Double>? {
            val pts = lines.flatten()
            if (pts.isEmpty()) return null
            // World coordinates at zoom 0 (one tile of TILE_PX for the whole world)
            val xs = pts.map { worldX(it.longitude, 1, TILE_PX) }
            val ys = pts.map { worldY(it.latitude, 1, TILE_PX) }
            val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min()).coerceAtLeast(1e-9)
            val zoom = (ln(FIT_PX / span) / ln(2.0)).coerceIn(3.0, WatchFeed.MAX_ZOOM)
            val mx = (xs.max() + xs.min()) / 2
            val my = (ys.max() + ys.min()) / 2
            val lon = mx / TILE_PX * 360.0 - 180.0
            val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * my / TILE_PX))))
            return GeoPoint(lat, lon) to zoom
        }
    }
}
