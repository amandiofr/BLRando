package fr.amandio.blrando

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val cfg = Configuration.getInstance()
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        // Tile servers require an identifying user agent
        cfg.userAgentValue = "BLRando/0.1"
        // Tiles in filesDir (not cacheDir) so Android doesn't purge them before a hike
        val base = File(filesDir, "osmdroid")
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles")
        cfg.tileFileSystemCacheMaxBytes = 1024L * 1024 * 1024
        cfg.tileFileSystemCacheTrimBytes = 900L * 1024 * 1024
        // Topo maps barely change: keep cached tiles 30 days before refreshing
        cfg.expirationOverrideDuration = 30L * 24 * 3600 * 1000

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Suivi de randonnée", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL = "tracking"

        val OPEN_TOPO_MAP = XYTileSource(
            "OpenTopoMap", 0, 17, 256, ".png",
            arrayOf(
                "https://a.tile.opentopomap.org/",
                "https://b.tile.opentopomap.org/",
                "https://c.tile.opentopomap.org/",
            ),
            "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)",
        )
    }
}
