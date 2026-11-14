package app.mediadownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service whose only job is to keep the app process alive (and show a progress
 * notification) while a download runs, so Android does not kill it when you leave the app.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = build(this, "Preparing download…", -1)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url != null) {
            val idx = intent.getIntExtra(EXTRA_PRESET, 2).coerceIn(0, PRESETS.size - 1)
            val app = applicationContext
            Thread {
                try { runDirect(app, url, idx) } finally { stop(app) }
            }.start()
        }
        return START_NOT_STICKY
    }

    /** Download started from the share pop-up (the app UI may not even be open). */
    private fun runDirect(ctx: Context, url: String, idx: Int) {
        val res = try {
            Engine.ensureInit(ctx)
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val cookies = File(ctx.filesDir, "cookies.txt").takeIf { it.exists() }?.absolutePath
            val tree = prefs.getString("save_uri", null)?.let { Uri.parse(it) }
            var lastN = 0L
            Engine(ctx, "md-share-${System.currentTimeMillis()}").download(
                url, PRESETS[idx], false, null, null, cookies, tree,
                object : DownloadListener {
                    override fun progress(percent: Float, headline: String, detail: String) {
                        val now = System.currentTimeMillis()
                        if (now - lastN > 1000) { lastN = now; update(ctx, headline, percent.toInt()) }
                    }
                    override fun status(text: String) = update(ctx, text, -1)
                },
            )
        } catch (e: Throwable) {
            DownloadResult(0, false, false, cleanError(e))
        }
        when {
            res.cancelled -> {}
            res.savedCount > 0 -> finished(ctx, "Done \u2014 ${res.savedCount} file(s) saved")
            else -> finished(ctx, "Download failed: ${(res.error ?: "unknown error").take(150)}")
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private const val EXTRA_URL = "url"
        private const val EXTRA_PRESET = "preset"
        private val active = AtomicInteger(0)

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }

        private fun build(ctx: Context, text: String, percent: Int): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Media Downloader")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (percent in 0..100) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
            return b.build()
        }

        fun start(ctx: Context) {
            active.incrementAndGet()
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        fun startDirect(ctx: Context, url: String, presetIndex: Int) {
            active.incrementAndGet()
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, DownloadService::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_PRESET, presetIndex),
            )
        }

        fun update(ctx: Context, text: String, percent: Int) {
            runCatching {
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ctx, text, percent))
            }
        }

        /** Stops the service only when no download is using it any more. */
        fun stop(ctx: Context) {
            if (active.decrementAndGet() <= 0) {
                active.set(0)
                ctx.stopService(Intent(ctx, DownloadService::class.java))
            }
        }

        /** One-off "finished" notification that stays after the service is gone. */
        fun finished(ctx: Context, text: String) {
            runCatching {
                ensureChannel(ctx)
                val open = PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Media Downloader")
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
            }
        }
    }
}
