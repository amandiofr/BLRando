package fr.amandio.blrando

import android.content.Context
import android.widget.Toast

/**
 * Toast messages, one at a time: a new message replaces the one on screen at once, instead of
 * queueing behind it (several taps would otherwise scroll old messages for a long time). Main thread.
 */
object Toasts {
    private var current: Toast? = null

    fun show(ctx: Context, msg: String, long: Boolean = true) {
        current?.cancel()
        current = Toast.makeText(ctx.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
            .also { it.show() }
    }
}
