package fr.amandio.blrando

import android.util.Xml
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Gpx {

    class Track(val name: String?, val segments: List<List<GeoPoint>>)

    /** Reads tracks (trkseg) and routes (rte) as polylines. */
    fun parse(input: InputStream): Track {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val segments = ArrayList<MutableList<GeoPoint>>()
        var current: MutableList<GeoPoint>? = null
        var name: String? = null
        var inPoint = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "trkseg", "rte" -> current = ArrayList<GeoPoint>().also { segments.add(it) }
                    "trkpt", "rtept" -> {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            val seg = current ?: ArrayList<GeoPoint>().also { segments.add(it); current = it }
                            seg.add(GeoPoint(lat, lon))
                        }
                        inPoint = true
                    }
                    "name" -> if (name == null && !inPoint) name = parser.nextText().trim().ifEmpty { null }
                }
                XmlPullParser.END_TAG -> when (tag) {
                    "trkpt", "rtept" -> inPoint = false
                    "trkseg", "rte" -> current = null
                }
            }
            event = parser.next()
        }
        return Track(name, segments.filter { it.size >= 2 })
    }

    fun write(out: OutputStream, name: String, points: List<TrailPoint>) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"BLRando\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for ((i, tp) in points.withIndex()) {
            if (tp.segStart && i > 0) sb.append("    </trkseg>\n    <trkseg>\n")
            val p = tp.point
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", p.latitude, p.longitude))
            if (p.altitude != 0.0) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p.altitude))
            sb.append("<time>").append(iso.format(Date(tp.time))).append("</time></trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
