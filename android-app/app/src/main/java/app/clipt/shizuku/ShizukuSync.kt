package app.clipt.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import app.clipt.BuildConfig
import app.clipt.prefs
import rikka.shizuku.Shizuku

/**
 * App-side controller for the Shizuku clipboard watcher. Works only while the Shizuku app's
 * service is running (it must be restarted by the user after a reboot on non-rooted phones).
 */
object ShizukuSync {
    private const val TAG = "CliptShizuku"
    const val REQUEST_CODE = 4242

    @Volatile var watcher: IClipWatcher? = null
        private set

    private var appContext: Context? = null

    /** What the user wants. Connecting to a watcher that shouldn't run makes it exit. */
    @Volatile private var wantRunning = true

    private var args: Shizuku.UserServiceArgs? = null

    /**
     * The watcher is a daemon, so it survives app updates. Its version is the APK's install
     * time: after every update Shizuku sees a new version and restarts it with the new code.
     */
    private fun args(context: Context): Shizuku.UserServiceArgs = args ?: run {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ClipWatcherService::class.java.name))
            .daemon(true) // keeps watching after the Clipverse app process is gone
            .processNameSuffix("clipwatch")
            .debuggable(BuildConfig.DEBUG)
            .version(((installed / 1000) % Int.MAX_VALUE).toInt())
            .also { args = it }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val w = IClipWatcher.Stub.asInterface(binder) ?: return
            if (!wantRunning) {
                // Paused or auto-send off: tell the watcher process to exit, then forget it.
                runCatching { w.destroy() }
                appContext?.let { ctx -> runCatching { Shizuku.unbindUserService(args(ctx), this, true) } }
                watcher = null
                return
            }
            watcher = w
            appContext?.let { configure(it, w) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            watcher = null
        }
    }

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)

    /**
     * Runs [action] once Shizuku's binder has arrived. Right after our process starts the binder
     * isn't there yet, and acting too early would leave a running watcher unreachable.
     */
    fun whenReady(action: () -> Unit) {
        if (isRunning()) { action(); return }
        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                action()
            }
        }
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }
    }

    /** Shizuku app installed and its service running. */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        isRunning() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Shows Shizuku's own "Allow Clipverse?" dialog. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Starts (or re-configures) the watcher. No-op if Shizuku isn't ready. */
    fun start(context: Context): Boolean {
        if (!hasPermission()) return false
        wantRunning = true
        appContext = context.applicationContext
        watcher?.let { configure(context, it); return true }
        return runCatching { Shizuku.bindUserService(args(context), connection); true }
            .onFailure { Log.w(TAG, "bind failed", it) }.getOrDefault(false)
    }

    /**
     * Stops the watcher daemon, waiting for Shizuku if needed. The daemon outlives our process, so
     * we connect to it (Shizuku starts nothing new if it's already running) and tell it to exit.
     */
    fun stop(context: Context) {
        wantRunning = false
        val app = context.applicationContext
        appContext = app
        whenReady {
            if (!hasPermission()) return@whenReady
            val w = watcher
            if (w != null) {
                runCatching { w.destroy() }
                runCatching { Shizuku.unbindUserService(args(app), connection, true) }
                watcher = null
            } else {
                runCatching { Shizuku.bindUserService(args(app), connection) } // → onServiceConnected → exit
            }
        }
    }

    fun status(): String? = runCatching { watcher?.status() }.getOrNull()

    private fun configure(context: Context, w: IClipWatcher) {
        val p = context.prefs
        runCatching { w.configure(p.serverUrl, p.token, p.deviceId, p.deviceName) }
            .onFailure { Log.w(TAG, "configure failed", it) }
    }
}
