package fr.amandio.blrando

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.ceil

/**
 * Pins for the ends of the reference track: a car at the start, a checkered flag at the end.
 * White round badge with a tail whose tip is the point. Same drawing as watch/index.html (#carMark, #flagMark),
 * in a 32 x 42 box.
 */
object TrackIcons {
    const val W = 32f
    const val H = 42f

    enum class Kind { CAR, FLAG }

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeJoin = Paint.Join.ROUND
    }
    private val carBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E5AA8.toInt() }
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    private val wheelRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    private val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
    }

    private val tail = Path().apply { moveTo(10f, 26f); lineTo(16f, 40f); lineTo(22f, 26f); close() }
    private val cabin = Path().apply { moveTo(9f, 16f); lineTo(11.5f, 10.5f); lineTo(20.5f, 10.5f); lineTo(24f, 16f); close() }
    private val window1 = Path().apply { moveTo(11.8f, 15f); lineTo(13.2f, 12f); lineTo(15.6f, 12f); lineTo(15.6f, 15f); close() }
    private val window2 = Path().apply { moveTo(17f, 15f); lineTo(17f, 12f); lineTo(19.8f, 12f); lineTo(21.6f, 15f); close() }

    /** Pin [heightPx] high; its tip (the point) is the middle of the bottom edge. */
    fun bitmap(kind: Kind, heightPx: Int): Bitmap {
        val s = heightPx / H
        val bmp = Bitmap.createBitmap(ceil(W * s).toInt(), heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(s, s)
        draw(c, kind)
        return bmp
    }

    fun draw(c: Canvas, kind: Kind) {
        // Badge: tail then circle, so the circle hides the tail's top edge
        c.drawPath(tail, white)
        c.drawPath(tail, edge)
        c.drawCircle(16f, 16f, 14f, white)
        c.drawCircle(16f, 16f, 14f, edge)
        if (kind == Kind.CAR) car(c) else flag(c)
    }

    private fun car(c: Canvas) {
        c.drawPath(cabin, carBody)
        c.drawRoundRect(RectF(5f, 15f, 27f, 21f), 2f, 2f, carBody)
        c.drawPath(window1, white)
        c.drawPath(window2, white)
        for (x in floatArrayOf(10f, 22f)) {
            c.drawCircle(x, 21.5f, 2.6f, wheel)
            c.drawCircle(x, 21.5f, 2.6f, wheelRim)
        }
    }

    private fun flag(c: Canvas) {
        // 4 x 3 checkers, 3.5 x 3.33 each, from (10, 7) to (24, 17)
        val cw = 3.5f; val ch = 10f / 3
        for (i in 0 until 4) for (j in 0 until 3) {
            if ((i + j) % 2 == 0) c.drawRect(10f + i * cw, 7f + j * ch, 10f + (i + 1) * cw, 7f + (j + 1) * ch, black)
        }
        c.drawRect(10f, 7f, 24f, 17f, edge)
        c.drawLine(10f, 7f, 10f, 26f, pole)
    }
}
