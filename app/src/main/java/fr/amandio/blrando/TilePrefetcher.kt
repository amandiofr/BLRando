package fr.amandio.blrando

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Downloads, ahead of a hike, the map tiles in a corridor around the reference track,
 * so the map works without network in the mountains. Silent: no message to the user.
 *
 * Gentle on the tile server (volunteer-run OpenTopoMap):
 *  - one tile at a time, with a pause between requests;
 *  - only tiles missing from the cache;
 *  - a cap on the total number of tiles (the most detailed zoom is dropped first);
 *  - stops when the server answers 429 (too many requests) or 403 (blocked).
 */
object TilePrefetcher {
    private const val TAG = "BLRando"
    private const val MIN_ZOOM = 12
    private const val MAX_ZOOM = 17
    /** Corridor half-width on each side of the track. */
    private const val CORRIDOR_M = 400.0
    private const val MAX_TILES = 2500
    private const val PAUSE_MS = 300L

    @Volatile private var job: Thread? = null

    fun start(ctx: Context, lines: List<List<GeoPoint>>) {
        val app = ctx.applicationContext
        job?.interrupt()
        job = Thread { run(app, lines) }.apply { isDaemon = true; start() }
    }

    private fun run(ctx: Context, lines: List<List<GeoPoint>>) {
        val source = App.OPEN_TOPO_MAP as OnlineTileSourceBase
        if (!online(ctx)) return
        val writer = SqlTileWriter()
        try {
            // Tiles to cover, coarse zooms first; drop the finest zooms if over the cap
            var zoomMax = MAX_ZOOM
            var wanted: List<Long>
            while (true) {
                wanted = (MIN_ZOOM..zoomMax).flatMap { tilesFor(lines, it) }
                if (wanted.size <= MAX_TILES || zoomMax <= 14) break
                zoomMax--
            }
            val missing = wanted.filter { !writer.exists(source, it) }
            if (missing.isEmpty()) return
            var done = 0
            for (index in missing) {
                if (Thread.currentThread().isInterrupted) return
                when (download(source, writer, index)) {
                    Result.OK -> done++
                    Result.STOP -> {
                        Log.w(TAG, "tile server refused, prefetch stopped at $done/${missing.size}")
                        return
                    }
                    Result.FAILED -> {}
                }
                Thread.sleep(PAUSE_MS)
            }
            Log.i(TAG, "prefetch done: $done/${missing.size} tiles")
        } catch (_: InterruptedException) {
            // A new track was loaded: its own download takes over
        } finally {
            writer.onDetach()
        }
    }

    internal enum class Result { OK, FAILED, STOP }

    /** Downloads one tile into the cache (also used by the watch renderer for missing tiles). */
    internal fun download(source: OnlineTileSourceBase, writer: SqlTileWriter, index: Long): Result {
        val conn = URL(source.getTileURLString(index)).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", Configuration.getInstance().userAgentValue)
            when (conn.responseCode) {
                200 -> {
                    val expires = System.currentTimeMillis() + 30L * 24 * 3600 * 1000
                    conn.inputStream.use { writer.saveFile(source, index, it, expires) }
                    Result.OK
                }
                429, 403 -> Result.STOP
                else -> Result.FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "tile ${MapTileIndex.toString(index)}: ${e.message}")
            Result.FAILED
        } finally {
            conn.disconnect()
        }
    }

    /** Tile indexes at [zoom] within [CORRIDOR_M] of the polylines. */
    private fun tilesFor(lines: List<List<GeoPoint>>, zoom: Int): Set<Long> {
        val n = 1 shl zoom
        val tiles = LinkedHashSet<Long>()
        for (line in lines) {
            if (line.isEmpty()) continue
            val lat = line[0].latitude
            val tileM = 40_075_016.0 * cos(Math.toRadians(lat)) / n
            val r = ceil(CORRIDOR_M / tileM).toInt()
            // Walk the line in steps of half a tile so long straight segments leave no gap
            val step = tileM / 2
            var prev: GeoPoint? = null
            for (p in line) {
                val q = prev
                val pts = if (q == null) listOf(p) else {
                    val d = q.distanceToAsDouble(p)
                    val k = maxOf(1, ceil(d / step).toInt())
                    (1..k).map { i ->
                        val t = i.toDouble() / k
                        GeoPoint(q.latitude + (p.latitude - q.latitude) * t, q.longitude + (p.longitude - q.longitude) * t)
                    }
                }
                for (g in pts) {
                    val x = floor((g.longitude + 180.0) / 360.0 * n).toInt()
                    val latR = Math.toRadians(g.latitude)
                    val y = floor((1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n).toInt()
                    for (dx in -r..r) for (dy in -r..r) {
                        val tx = x + dx
                        val ty = y + dy
                        if (tx in 0 until n && ty in 0 until n) tiles.add(MapTileIndex.getTileIndex(zoom, tx, ty))
                    }
                }
                prev = p
            }
        }
        return tiles
    }

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

}
