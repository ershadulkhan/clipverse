package app.clipt.autosend

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.clipt.R
import app.clipt.data.Api
import app.clipt.data.LoopGuard
import app.clipt.prefs
import app.clipt.ui.MainActivity
import app.clipt.work.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * OPT-IN "Auto-send copies" mode. This is the one part of Clipverse that runs in the background,
 * because Android (10+) only lets the focused app read the clipboard.
 *
 * How it notices a copy (the technique KDE Connect and ClipCascade use):
 *  1. We register a clipboard listener. When any app copies, Android tries to notify us,
 *     refuses because we're in the background, and logs "Denying clipboard access to app.clipt".
 *  2. A single blocked `logcat` reader (needs READ_LOGS, granted once over ADB) sees that line.
 *  3. We add an invisible 1×1 overlay window (needs "Display over other apps"); the moment it
 *     gets focus we are allowed to read the clipboard, then the window is removed.
 * CPU use is near zero: the logcat read blocks until a matching line arrives.
 */
class AutoSendService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var logcat: Process? = null
    private var overlay: View? = null
    private var lastTrigger = 0L
    @Volatile private var watching = false
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        // On some devices (or while Clipverse is in front) the read is allowed directly.
        readAndSend(fromListener = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        clipboard.addPrimaryClipChangedListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.autoSend || !prefs.isPaired || !prefs.syncEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) startLogWatcher()
        return START_STICKY
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(listener)
        logcat?.destroy()
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Auto-send copies", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Clipverse sends what you copy to your other devices"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Auto-send is on")
            .setContentText("Text you copy goes to your other devices")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /** Blocks on `logcat` output, so it costs nothing until a line arrives. */
    private fun startLogWatcher() {
        if (checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_LOGS not granted; only foreground copies will be detected")
            return
        }
        watching = true
        scope.launch {
            try {
                // -T 1: start from now, don't replay old lines.
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "-v", "brief", "ClipboardService:*", "*:S"))
                logcat = p
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    for (line in lines) {
                        if (line.contains("clipboard", ignoreCase = true) && line.contains(packageName)) {
                            main.post { onCopyDetected() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "logcat watcher stopped", e)
            } finally {
                watching = false
            }
        }
    }

    private fun onCopyDetected() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 400) return // one copy can log several lines
        lastTrigger = now
        showFocusOverlay()
    }

    /** A focusable, invisible 1×1 window: having window focus is what makes the read legal. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showFocusOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java)
        val view = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) {
                    readAndSend(fromListener = false)
                    removeOverlay()
                }
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(view, params); overlay = view }
        // Never leave the window around if focus doesn't arrive.
        main.postDelayed({ removeOverlay() }, 1500)
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
    }

    private fun readAndSend(fromListener: Boolean) {
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (text.isBlank() || text.length > 1_000_000 || LoopGuard.isKnown(text)) return
        LoopGuard.remember(text)
        Log.i(TAG, "copy detected (${if (fromListener) "listener" else "overlay"}), ${text.length} chars")
        scope.launch {
            try {
                Api(prefs).sendText(text)
            } catch (e: Exception) {
                UploadWorker.enqueueText(applicationContext, text, silent = true) // retried when online
            }
        }
    }

    companion object {
        private const val TAG = "CliptAutoSend"
        private const val CHANNEL = "auto_send"
        private const val NOTIFICATION_ID = 42

        fun hasLogAccess(context: Context) =
            context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

        fun hasOverlay(context: Context) = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AutoSendService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoSendService::class.java))
        }
    }
}
