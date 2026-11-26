package fr.amandio.blrando

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Position marker in the style of Gaia GPS: a translucent orange arrowhead, no white outline,
 * its two halves shaded differently along the axis so it looks slightly raised.
 */
class PositionOverlay(private val size: Float) : Overlay() {

    var position: GeoPoint? = null
    /** Degrees from north, null = unknown (draws a dot). */
    var heading: Float? = null

    /** Left facet, in the shade. */
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9C4561A.toInt() }
    /** Right facet, in the light. */
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9F0843A.toInt() }
    /** Thin darker edge, so it stays readable over light map areas. */
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC8A3A0E.toInt(); style = Paint.Style.STROKE; strokeWidth = size * 0.06f
        strokeJoin = Paint.Join.ROUND
    }

    private val tipY = -size * 1.1f
    private val backY = size * 0.8f
    private val notchY = size * 0.3f
    private val halfW = size * 0.85f

    private val leftFacet = Path().apply {
        moveTo(0f, tipY); lineTo(-halfW, backY); lineTo(0f, notchY); close()
    }
    private val rightFacet = Path().apply {
        moveTo(0f, tipY); lineTo(0f, notchY); lineTo(halfW, backY); close()
    }
    private val outline = Path().apply {
        moveTo(0f, tipY); lineTo(halfW, backY); lineTo(0f, notchY); lineTo(-halfW, backY); close()
    }

    override fun draw(c: Canvas, pj: Projection) {
        val p = position ?: return
        val pt = pj.toPixels(p, null)
        c.save()
        c.translate(pt.x.toFloat(), pt.y.toFloat())
        val h = heading
        if (h == null) {
            c.drawCircle(0f, 0f, size * 0.6f, light)
            c.drawCircle(0f, 0f, size * 0.6f, edge)
        } else {
            // The canvas is in map coordinates (north up); osmdroid rotates it with the map
            c.rotate(h)
            c.drawPath(leftFacet, dark)
            c.drawPath(rightFacet, light)
            c.drawPath(outline, edge)
        }
        c.restore()
    }
}
