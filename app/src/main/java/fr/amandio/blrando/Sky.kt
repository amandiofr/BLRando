package fr.amandio.blrando

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun and moon seen from a place, for the marks on the watch rim. Formulas from SunCalc
 * (Vladimir Agafonkin, BSD), precise to a fraction of a degree: plenty for a mark on a rim.
 */
object Sky {
    /** [azimuth] in degrees from north (clockwise), [altitude] in degrees above the horizon. */
    data class Body(val azimuth: Double, val altitude: Double)

    /**
     * [phase]: 0 = new moon, 0.25 = first quarter, 0.5 = full moon, 0.75 = last quarter.
     * [fraction]: lit part of the disc, 0..1.
     */
    data class Moon(val body: Body, val phase: Double, val fraction: Double)

    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val E = RAD * 23.4397 // obliquity of the Earth

    private fun days(ms: Long) = ms / DAY_MS - 0.5 + J1970 - J2000

    private fun rightAscension(l: Double, b: Double) = atan2(sin(l) * cos(E) - tan(b) * sin(E), cos(l))
    private fun declination(l: Double, b: Double) = asin(sin(b) * cos(E) + cos(b) * sin(E) * sin(l))
    private fun siderealTime(d: Double, lw: Double) = RAD * (280.16 + 360.9856235 * d) - lw

    /** Azimuth from north, from SunCalc's azimuth that is measured from south. */
    private fun body(h: Double, phi: Double, dec: Double): Body {
        val az = atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi))
        val alt = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
        return Body(((az / RAD + 180) % 360 + 360) % 360, alt / RAD)
    }

    private class Coords(val ra: Double, val dec: Double, val dist: Double = 0.0)

    private fun sunCoords(d: Double): Coords {
        val m = RAD * (357.5291 + 0.98560028 * d)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        return Coords(rightAscension(l, 0.0), declination(l, 0.0))
    }

    private fun moonCoords(d: Double): Coords {
        val l = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val lon = l + RAD * 6.289 * sin(m)
        val lat = RAD * 5.128 * sin(f)
        return Coords(rightAscension(lon, lat), declination(lon, lat), 385001 - 20905 * cos(m))
    }

    fun sun(ms: Long, lat: Double, lon: Double): Body {
        val d = days(ms)
        val c = sunCoords(d)
        return body(siderealTime(d, -RAD * lon) - c.ra, RAD * lat, c.dec)
    }

    /**
     * Sunset (upper limb on the horizon, with refraction) of the solar day around [ms], in epoch ms,
     * or null when the sun doesn't set (polar day or night).
     */
    fun sunset(ms: Long, lat: Double, lon: Double): Long? {
        val j0 = 0.0009
        val lw = -RAD * lon
        val phi = RAD * lat
        val d = days(ms)
        val n = Math.round(d - j0 - lw / (2 * PI)).toDouble()
        val ds = j0 + lw / (2 * PI) + n
        val m = RAD * (357.5291 + 0.98560028 * ds)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        val dec = declination(l, 0.0)
        val cosW = (sin(-0.833 * RAD) - sin(phi) * sin(dec)) / (cos(phi) * cos(dec))
        if (cosW < -1 || cosW > 1) return null
        val a = j0 + (acos(cosW) + lw) / (2 * PI) + n
        val jSet = J2000 + a + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        return Math.round((jSet + 0.5 - J1970) * DAY_MS)
    }

    fun moon(ms: Long, lat: Double, lon: Double): Moon {
        val d = days(ms)
        val m = moonCoords(d)
        val pos = body(siderealTime(d, -RAD * lon) - m.ra, RAD * lat, m.dec)
        // Illumination
        val s = sunCoords(d)
        val sunDist = 149_598_000.0
        val phi = acos(sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra))
        val inc = atan2(sunDist * sin(phi), m.dist - sunDist * cos(phi))
        val angle = atan2(cos(s.dec) * sin(s.ra - m.ra),
            sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra))
        val phase = 0.5 + 0.5 * inc * (if (angle < 0) -1 else 1) / PI
        return Moon(pos, phase, (1 + cos(inc)) / 2)
    }
}
