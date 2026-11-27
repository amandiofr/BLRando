package fr.amandio.blrando

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the latest release of the public repo amandiofr/BLRando (same idea as BLBrowser).
 * Checked when the app is opened for the first time of the day, never while recording (installing restarts the app).
 * Release builds only: a debug build is signed with another key and could not take the update anyway.
 */
object AppUpdater {
    private const val API_URL = "https://api.github.com/repos/amandiofr/BLRando/releases/latest"
    private const val PREFS = "update"
    private const val LAST_CHECK_DAY = "lastCheckDay"
    private const val ACTION_STATUS = "fr.amandio.blrando.INSTALL_STATUS"

    fun checkDaily(activity: Activity) {
        if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        if (Session.recording) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // First opening of the day (calendar day), whether or not the check works
        val today = java.time.LocalDate.now().toString()
        if (prefs.getString(LAST_CHECK_DAY, null) == today) return
        prefs.edit().putString(LAST_CHECK_DAY, today).apply()
        Thread {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                val release = try { JSONObject(conn.inputStream.bufferedReader().readText()) } finally { conn.disconnect() }
                val latest = release.getString("tag_name").removePrefix("v")
                val current = currentVersion(activity)
                if (!isNewerVersion(latest, current)) return@Thread
                val assets = release.getJSONArray("assets")
                val apkUrl = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                val page = release.getString("html_url")
                val notes = release.optString("body").trim()
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed && !Session.recording) {
                        offer(activity, latest, current, notes, apkUrl, page)
                    }
                }
            } catch (e: Exception) {
                Log.w("BLRando", "update check failed: $e") // no network: try again next time
            }
        }.start()
    }

    private fun offer(activity: Activity, latest: String, current: String, notes: String, apkUrl: String?, page: String) {
        val text = "La version $latest est disponible (tu as la $current)." + if (notes.isNotEmpty()) "\n\n$notes" else ""
        AlertDialog.Builder(activity)
            .setTitle("Nouvelle version de BLRando")
            .setMessage(text)
            .setPositiveButton("Mettre à jour") { _, _ ->
                if (apkUrl != null) install(activity, apkUrl, page) else openPage(activity, page)
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    /**
     * Streams the APK into a PackageInstaller session. Android asks the user to confirm (and, the first
     * time, to allow BLRando to install apps); once BLRando installed itself, Android 12+ may skip it.
     */
    private fun install(activity: Activity, apkUrl: String, page: String) {
        val app = activity.applicationContext
        Toast.makeText(app, "Téléchargement de la mise à jour…", Toast.LENGTH_SHORT).show()
        registerStatusReceiver(app)
        Thread {
            val installer = app.packageManager.packageInstaller
            var sessionId = -1
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                        setRequestUpdateOwnership(true)
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val conn = URL(apkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    try {
                        session.openWrite("base.apk", 0, conn.contentLengthLong).use { out ->
                            conn.inputStream.use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    } finally { conn.disconnect() }
                    val status = PendingIntent.getBroadcast(app, sessionId,
                        Intent(ACTION_STATUS).setPackage(app.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(status.intentSender)
                }
            } catch (e: Exception) {
                Log.w("BLRando", "self-update failed: $e")
                if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) { }
                // Last resort: the release page, where the APK can be downloaded by hand
                activity.runOnUiThread { openPage(activity, page) }
            }
        }.start()
    }

    private var receiverRegistered = false

    private fun registerStatusReceiver(app: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(statusReceiver, filter)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the app is being replaced
                else -> Toast.makeText(context, "Mise à jour impossible : " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erreur inconnue"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openPage(activity: Activity, page: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
        } catch (_: Exception) { }
    }

    private fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** True when [candidate] ("1.0.10") is strictly newer than [current] ("1.0.9"); missing parts count as 0. */
    fun isNewerVersion(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }
}
