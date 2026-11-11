package net.k1lllagt.andraxng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import net.k1lllagt.andraxng.App
import net.k1lllagt.andraxng.MainActivity
import net.k1lllagt.andraxng.R
import net.k1lllagt.andraxng.core.Fmt
import net.k1lllagt.andraxng.core.InstallCancelled
import net.k1lllagt.andraxng.core.InstallState
import net.k1lllagt.andraxng.core.Paths
import net.k1lllagt.andraxng.core.Profiles
import net.k1lllagt.andraxng.core.RootfsInstaller
import net.k1lllagt.andraxng.core.SessionHub
import java.io.File

/**
 * Foreground service that (a) keeps the process alive while terminal sessions run and
 * (b) hosts the rootfs install worker so a long extraction survives the app going to the background.
 * Always starts with startForegroundService() and calls startForeground() on every command.
 */
class SessionService : Service() {
    private var holding = false
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var installThread: Thread? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ANDRAX-NG sessions", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Required within 5 seconds of startForegroundService(), whatever the action is.
        startForeground(NOTIF_ID, buildNotification(null, -1))
        when (intent?.action) {
            ACTION_HOLD -> {
                holding = true
                updateWakeLock()
            }
            ACTION_INSTALL -> {
                updateWakeLock()
                startInstall(intent)
            }
            ACTION_CANCEL_INSTALL -> InstallState.cancel.set(true)
            ACTION_RELEASE -> {
                holding = false
                maybeStop()
            }
            ACTION_STOP -> {
                if (installRunning()) InstallState.cancel.set(true)
                SessionHub.onStopRequested?.invoke()
                holding = false
                maybeStop()
            }
            else -> maybeStop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun installRunning() = installThread?.isAlive == true

    private fun startInstall(intent: Intent) {
        if (installRunning()) return
        val profile = Profiles.byId(intent.getStringExtra(EXTRA_PROFILE) ?: "")
        if (profile == null) {
            InstallState.begin("")
            InstallState.fail("Unknown profile")
            maybeStop()
            return
        }
        // Replacing an installed rootfs under a running session breaks that session.
        // Sessions are not tracked per profile, so any open session blocks a reinstall.
        val alreadyInstalled = File(App.paths.rootfs(profile), Paths.MARKER).exists()
        if (alreadyInstalled && SessionHub.activeSessions > 0) {
            InstallState.begin(profile.id)
            InstallState.fail("Close all terminal sessions before reinstalling ${profile.title}.")
            maybeStop()
            return
        }
        val source = if (intent.getStringExtra(EXTRA_SOURCE) == "import") {
            val uri = intent.getStringExtra(EXTRA_URI)
            if (uri == null) {
                InstallState.begin(profile.id)
                InstallState.fail("No file selected")
                maybeStop()
                return
            }
            RootfsInstaller.Source.Import(Uri.parse(uri), intent.getStringExtra(EXTRA_SHA))
        } else {
            RootfsInstaller.Source.Download
        }

        InstallState.begin(profile.id)
        val t = Thread({
            try {
                RootfsInstaller(applicationContext, App.paths, App.settings, App.log)
                    .install(profile, source, InstallState.cancel) { phase, done, total, detail ->
                        InstallState.progress(phase, done, total, detail)
                        notifyProgress(phase, done, total, detail)
                    }
                InstallState.succeed()
            } catch (e: InstallCancelled) {
                App.log.i("install cancelled")
                InstallState.fail("Cancelled")
            } catch (e: Throwable) {
                App.log.e("install failed", e)
                InstallState.fail(e.message ?: e.toString())
            } finally {
                installThread = null
                Handler(Looper.getMainLooper()).post { maybeStop() }
            }
        }, "andrax-install")
        installThread = t
        t.start()
    }

    private fun notifyProgress(phase: String, done: Long, total: Long, detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        val text = buildString {
            append(phase)
            if (total > 0) append(" ").append(Fmt.bytes(done)).append(" / ").append(Fmt.bytes(total))
            if (detail.isNotEmpty()) append(" - ").append(detail)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, pct))
    }

    private fun maybeStop() {
        if (!holding && !installRunning()) {
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
        }
    }

    private fun updateWakeLock() {
        val wanted = (holding || installRunning()) && App.settings.wakeLock
        if (wanted && wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "andrax-ng:session").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } else if (!wanted) {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: RuntimeException) {
        }
        wakeLock = null
    }

    private fun buildNotification(text: String?, progressPct: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = text ?: if (SessionHub.activeSessions > 0) "${SessionHub.activeSessions} session(s) running" else "Running"
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (installRunning()) "ANDRAX-NG installing" else "ANDRAX-NG")
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (installRunning()) "Cancel" else "Stop", stop).build())
        if (progressPct >= 0) b.setProgress(100, progressPct, false)
        else if (installRunning()) b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val PKG = "net.k1lllagt.andraxng"
        const val ACTION_HOLD = "$PKG.HOLD"
        const val ACTION_RELEASE = "$PKG.RELEASE"
        const val ACTION_STOP = "$PKG.STOP"
        const val ACTION_INSTALL = "$PKG.INSTALL"
        const val ACTION_CANCEL_INSTALL = "$PKG.CANCEL_INSTALL"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SOURCE = "source"   // "download" or "import"
        const val EXTRA_URI = "uri"
        const val EXTRA_SHA = "sha256"
        private const val CHANNEL = "andrax_session"
        private const val NOTIF_ID = 1001

        private fun start(ctx: Context, i: Intent) {
            ctx.startForegroundService(i)
        }

        fun hold(ctx: Context) = start(ctx, Intent(ctx, SessionService::class.java).setAction(ACTION_HOLD))
        fun release(ctx: Context) = start(ctx, Intent(ctx, SessionService::class.java).setAction(ACTION_RELEASE))

        fun install(ctx: Context, profileId: String, import: Boolean, uri: Uri?, sha: String?) {
            val i = Intent(ctx, SessionService::class.java).setAction(ACTION_INSTALL)
                .putExtra(EXTRA_PROFILE, profileId)
                .putExtra(EXTRA_SOURCE, if (import) "import" else "download")
            if (uri != null) i.putExtra(EXTRA_URI, uri.toString())
            if (sha != null) i.putExtra(EXTRA_SHA, sha)
            start(ctx, i)
        }

        fun cancelInstall(ctx: Context) =
            start(ctx, Intent(ctx, SessionService::class.java).setAction(ACTION_CANCEL_INSTALL))
    }
}
