package app.clipt.shizuku

import android.content.ClipData
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.json.JSONObject
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Clipboard watcher that Shizuku runs as the shell user (uid 2000) in its own small process.
 *
 * The shell user holds READ_CLIPBOARD_IN_BACKGROUND, so it may register a clipboard listener
 * and read new copies while any other app is in front — something a normal app can't do
 * since Android 10. It's purely event-driven: no polling, no timers, no wake locks.
 *
 * Runs in "daemon" mode, so it keeps working when the Clipverse app itself isn't running.
 * Anything Clipverse put on the clipboard itself (label starting with "Clipverse") is ignored, so
 * clips arriving from your computer are never echoed back.
 */
class ClipWatcherService : IClipWatcher.Stub() {

    @Volatile private var serverUrl = ""
    @Volatile private var token = ""
    @Volatile private var deviceId = ""
    @Volatile private var deviceName = ""
    @Volatile private var lastText: String? = null
    @Volatile private var state = "starting"

    private val uploads = Executors.newSingleThreadExecutor()
    private var clipboard: Any? = null
    private var getPrimaryClip: Method? = null

    /** Our Binder receives IOnPrimaryClipChangedListener callbacks (hidden interface). */
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                runCatching { onClipChanged() }.onFailure { Log.w(TAG, "read failed", it) }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    init {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            val cb = Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            clipboard = cb
            getPrimaryClip = cb.javaClass.methods.first { it.name == "getPrimaryClip" }
            val listener = Class.forName("android.content.IOnPrimaryClipChangedListener\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, callback)
            val add = cb.javaClass.methods.first { it.name == "addPrimaryClipChangedListener" }
            add.invoke(cb, *args(add, listener))
            // Baseline: don't upload whatever was already on the clipboard.
            lastText = readText()?.second
            state = "listening"
        } catch (e: Throwable) {
            state = "error: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "could not start", e)
        }
    }

    /**
     * Fills a hidden-API call whose signature varies by Android version: the first String is our
     * package, other Strings null, ints 0 (user 0, default device), booleans false.
     */
    private fun args(m: Method, first: Any?): Array<Any?> {
        var firstString = true
        return m.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 && first != null -> first
                t == String::class.java -> if (firstString) { firstString = false; SHELL } else null
                t == Int::class.javaPrimitiveType -> 0
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
    }

    /** Returns (label, text) of the current clip, or null. */
    private fun readText(): Pair<String, String>? {
        val m = getPrimaryClip ?: return null
        val clip = m.invoke(clipboard, *args(m, null)) as? ClipData ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).text?.toString() ?: return null
        return (clip.description?.label?.toString() ?: "") to text
    }

    private fun onClipChanged() {
        val (label, text) = readText() ?: return
        if (label.startsWith("Clipverse") || label.startsWith("Clipt")) { lastText = text; return } // written by Clipverse itself
        if (text.isBlank() || text == lastText || text.length > 1_000_000) return
        lastText = text
        if (serverUrl.isEmpty() || token.isEmpty()) return
        uploads.execute { upload(text) }
    }

    private fun upload(text: String) {
        val body = JSONObject()
            .put("content", text)
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .put("device_platform", "android")
            .toString().toByteArray()
        for (attempt in 0 until 3) {
            try {
                val conn = URL("$serverUrl/v1/clips").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) { state = "listening · last sent at ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"; return }
                if (code in 400..499) { state = "server refused (HTTP $code)"; return }
            } catch (e: Exception) {
                state = "offline, retrying"
            }
            Thread.sleep(2000L shl attempt)
        }
    }

    override fun configure(serverUrl: String, token: String, deviceId: String, deviceName: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.token = token
        this.deviceId = deviceId
        this.deviceName = deviceName
    }

    override fun status(): String = state

    override fun destroy() {
        exitProcess(0)
    }

    companion object {
        private const val TAG = "CliptWatcher"
        private const val SHELL = "com.android.shell"
    }
}
