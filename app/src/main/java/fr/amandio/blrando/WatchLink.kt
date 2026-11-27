package fr.amandio.blrando

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Messages from the watch (see watch/link.js), whatever carries them:
 * Samsung Accessory over Bluetooth on the real watch, adb in debug (DebugFixReceiver).
 *
 *   {t:"hello"}  watch app opened          {t:"bye"}   watch app closed
 *   {t:"screen", on:bool}                   {t:"zoom", delta:±1}
 *   {t:"pan", dx, dy}  map dragged by dx, dy screen pixels (north-up view)
 *   {t:"cmd", action:"start"|"stop"|"fit"|"center"}
 * Each message also carries "id", chosen by the watch page, so several watches can share the phone.
 */
object WatchLink {

    fun onMessage(ctx: Context, msg: JSONObject) {
        // Which watch page sent it (several watches can share the phone); "" for adb tests and old pages
        val id = msg.optString("id")
        when (msg.optString("t")) {
            "hello" -> {
                Session.watchConnected = true
                Session.watchScreens[id] = true
                TrackingService.sync(ctx)
                TrackingService.feed?.requestFrame()
            }
            "bye" -> {
                Session.watchScreens.remove(id)
                Session.watchConnected = Session.watchScreens.isNotEmpty()
                TrackingService.sync(ctx)
            }
            "screen" -> {
                val on = msg.optBoolean("on", true)
                Session.watchScreens[id] = on
                if (on) TrackingService.feed?.requestFrame()
            }
            "zoom" -> TrackingService.feed?.onZoom(msg.optInt("delta"))
            "pan" -> TrackingService.feed?.onPan(msg.optDouble("dx", 0.0), msg.optDouble("dy", 0.0), msg.optInt("seq"), id)
            "cmd" -> command(ctx, msg.optString("action"))
            else -> Log.w("BLRando", "unknown watch message: $msg")
        }
    }

    /** Start / stop from the watch. Starting also turns the lifebuoy on when a track is loaded. */
    private fun command(ctx: Context, action: String) {
        when (action) {
            // View only, nothing else changes
            "fit" -> { TrackingService.feed?.fitView(); return }
            "center" -> { TrackingService.feed?.center(); return }
            "start" -> if (!Session.recording) {
                Session.startRecording()
                if (Session.reference.isNotEmpty()) Session.setGuiding(true)
            }
            "stop" -> if (Session.recording) {
                Session.stopRecording()
                if (Session.guiding) Session.setGuiding(false)
            }
            else -> return
        }
        TrackingService.sync(ctx) // notification, file, GPS
        Session.notifyChanged()
        TrackingService.feed?.requestFrame()
    }
}
