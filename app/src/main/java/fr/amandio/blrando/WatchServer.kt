package fr.amandio.blrando

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Link with the watch over Wi-Fi: the watch's web browser opens http://<phone>:PORT.
 * Only through the phone's own hotspot: on a shared Wi-Fi (home, office, hotel) anyone could
 * otherwise see the map with our position and start or stop the recording.
 *
 *   GET /w                 -> redirect to the watch interface
 *   GET /watch/<file>      -> watch interface (index.html, style.css, main.js, link.js), bundled as assets
 *   GET /frame, /beat      -> last map frame / last heartbeat (JSON, see WatchFeed)
 *   GET /msg?b=<base64>    -> message from the watch (JSON), handled by WatchLink
 */
object WatchServer {
    const val PORT = 8765
    private const val TAG = "BLRando"

    @Volatile var lastFrame: String = "{}"
    @Volatile var lastBeat: String = "{}"

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(ctx: Context) {
        if (server != null) return
        val app = ctx.applicationContext
        val s = try { ServerSocket(PORT) } catch (e: Exception) { Log.e(TAG, "watch server: ${e.message}"); return }
        server = s
        Thread({
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                if (!fromHotspot(sock)) { try { sock.close() } catch (_: Exception) {}; continue }
                pool.execute { handle(app, sock) }
            }
        }, "watch-server").apply { isDaemon = true; start() }
        Log.i(TAG, "watch server on ${url() ?: "?"}")
    }

    /** Hotspot interfaces (ap_br_swlan0, swlan0...). Not wlan0 (a Wi-Fi we joined) nor rmnet (mobile data). */
    private fun isHotspot(name: String) = name.startsWith("ap") || name.startsWith("swlan")

    /**
     * Request that reached us through the hotspot. Loopback is accepted too: adb forward
     * (tests from the PC) arrives that way, and only apps on the phone itself can use it.
     */
    private fun fromHotspot(sock: Socket): Boolean {
        val local = sock.localAddress ?: return false
        if (local.isLoopbackAddress) return true
        val name = try { NetworkInterface.getByInetAddress(local)?.name } catch (_: Exception) { null }
        return name != null && isHotspot(name)
    }

    /** Address to type in the watch browser, null while the hotspot is off. */
    fun url(): String? {
        val ip = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && isHotspot(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
            ?: return null
        // The home page: short to type, and the watch browser can bookmark it
        return "http://$ip:$PORT"
    }

    private fun handle(ctx: Context, sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                while (true) { val l = readLine(input) ?: break; if (l.isEmpty()) break } // skip headers
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val (route, query) = path.split("?", limit = 2).let { it[0] to it.getOrNull(1) }
                val out = s.getOutputStream()
                when {
                    // Home: a plain page the watch browser can bookmark, with a button to the map
                    route == "/" -> home(ctx, out)
                    route == "/w" || route == "/w/" -> redirect(out, "/watch/index.html")
                    route == "/frame" -> { wakeFeed(ctx, pageId(query)); send(out, 200, "application/json", lastFrame.toByteArray()) }
                    route == "/beat" -> { wakeFeed(ctx, pageId(query)); send(out, 200, "application/json", lastBeat.toByteArray()) }
                    route == "/msg" -> {
                        val b = query?.split("&")?.firstOrNull { it.startsWith("b=") }?.substring(2)
                        if (b != null) {
                            val json = JSONObject(String(Base64.decode(URLDecoder.decode(b, "UTF-8"), Base64.DEFAULT)))
                            main.post { WatchLink.onMessage(ctx, json) }
                        }
                        send(out, 204, "text/plain", ByteArray(0))
                    }
                    route.startsWith("/watch/") -> asset(ctx, out, route.removePrefix("/watch/"))
                    else -> send(out, 404, "text/plain", "not found".toByteArray())
                }
            } catch (e: Exception) {
                Log.w(TAG, "watch server request: ${e.message}")
            }
        }
    }

    @Volatile private var lastWake = 0L
    /** Last time the watch asked for /frame or /beat. */
    @Volatile var lastPoll = 0L

    /**
     * The watch is polling but nothing feeds it (BLRando was reinstalled or killed while the watch
     * page stayed open, so its "hello" is long gone): act as if it had just said hello.
     */
    private fun wakeFeed(ctx: Context, id: String) {
        val now = System.currentTimeMillis()
        lastPoll = now
        Session.watchPolls[id] = now
        if (TrackingService.feed != null) return
        if (now - lastWake < 10_000) return
        lastWake = now
        main.post { WatchLink.onMessage(ctx, JSONObject().put("t", "hello").put("id", id)) }
    }

    /** "id" parameter of a poll (/frame?id=...): which watch page asks; "" for old pages. */
    private fun pageId(query: String?): String =
        query?.split("&")?.firstOrNull { it.startsWith("id=") }?.substring(3)?.take(32) ?: ""

    private fun asset(ctx: Context, out: OutputStream, name: String) {
        if (name.contains("..") || name.isEmpty()) return send(out, 404, "text/plain", ByteArray(0))
        val bytes = try { ctx.assets.open("watch/$name").use { it.readBytes() } } catch (_: Exception) {
            return send(out, 404, "text/plain", "not found".toByteArray())
        }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
        send(out, 200, type, bytes)
    }

    /** Home page with its texts in the phone's language. */
    private fun home(ctx: Context, out: OutputStream) {
        val html = ctx.assets.open("watch/home.html").use { it.readBytes() }.toString(Charsets.UTF_8)
            .replace("{{open_map}}", ctx.getString(R.string.home_open_map))
            .replace("{{bookmark}}", ctx.getString(R.string.home_bookmark))
        send(out, 200, "text/html; charset=utf-8", html.toByteArray())
    }

    private fun redirect(out: OutputStream, to: String) {
        out.write("HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; else -> "Not Found" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}
