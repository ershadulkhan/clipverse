package app.clipt.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Tiny settings store. SharedPreferences is lazy-loaded once per process and costs nothing
 * while idle. The file is excluded from backups so the sync token never leaves the device.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("clipt", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    val serverUrl: String get() = sp.getString(K_URL, "").orEmpty()
    val token: String get() = sp.getString(K_TOKEN, "").orEmpty()
    val isPaired: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    val deviceId: String
        get() = sp.getString(K_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { id ->
            sp.edit { putString(K_DEVICE_ID, id) }
        }

    var deviceName: String
        get() = sp.getString(K_DEVICE_NAME, null) ?: detectDeviceName(appContext)
        set(value) = sp.edit { putString(K_DEVICE_NAME, value.trim().take(64)) }

    /** true = compress to WebP before upload (default, saves radio time), false = original bytes. */
    var fastImages: Boolean
        get() = sp.getBoolean(K_FAST_IMAGES, true)
        set(value) = sp.edit { putBoolean(K_FAST_IMAGES, value) }

    /** Put text pushed from other devices straight onto this phone's clipboard. */
    var autoReceive: Boolean
        get() = sp.getBoolean(K_AUTO_RECEIVE, true)
        set(value) = sp.edit { putBoolean(K_AUTO_RECEIVE, value) }

    /** Opt-in background mode that uploads whatever is copied on this phone. */
    var autoSend: Boolean
        get() = sp.getBoolean(K_AUTO_SEND, false)
        set(value) = sp.edit { putBoolean(K_AUTO_SEND, value) }

    /** True once the user (not a default) has chosen on/off for auto-send. */
    val autoSendChosen: Boolean get() = sp.contains(K_AUTO_SEND)

    /** Set when the user switches push off, so we don't switch it back on by default. */
    var pushOptOut: Boolean
        get() = sp.getBoolean(K_PUSH_OPT_OUT, false)
        set(value) = sp.edit { putBoolean(K_PUSH_OPT_OUT, value) }

    /** We asked Shizuku for permission once automatically; never nag again. */
    var shizukuAsked: Boolean
        get() = sp.getBoolean(K_SHIZUKU_ASKED, false)
        set(value) = sp.edit { putBoolean(K_SHIZUKU_ASKED, value) }

    /** Master switch: when off, nothing syncs automatically (manual sending still works). */
    var syncEnabled: Boolean
        get() = sp.getBoolean(K_SYNC, true)
        set(value) = sp.edit { putBoolean(K_SYNC, value) }

    /** "system" | "light" | "dark" | "amoled" */
    var themeMode: String
        get() = sp.getString(K_THEME, "system") ?: "system"
        set(value) = sp.edit { putString(K_THEME, value) }

    var pushEnabled: Boolean
        get() = sp.getBoolean(K_PUSH, false)
        set(value) = sp.edit { putBoolean(K_PUSH, value) }

    var fcmConfig: FcmConfig?
        get() = sp.getString(K_FCM, null)?.let { runCatching { json.decodeFromString(FcmConfig.serializer(), it) }.getOrNull() }
        set(value) = sp.edit { putString(K_FCM, value?.let { json.encodeToString(FcmConfig.serializer(), it) }) }

    fun pair(url: String, token: String) = sp.edit {
        putString(K_URL, normalizeUrl(url))
        putString(K_TOKEN, token.trim())
    }

    fun unpair() = sp.edit {
        remove(K_URL); remove(K_TOKEN); remove(K_FCM); putBoolean(K_PUSH, false); putBoolean(K_AUTO_SEND, false)
    }

    companion object {
        private const val K_URL = "server_url"
        private const val K_TOKEN = "token"
        private const val K_DEVICE_ID = "device_id"
        private const val K_DEVICE_NAME = "device_name"
        private const val K_FAST_IMAGES = "fast_images"
        private const val K_PUSH = "push_enabled"
        private const val K_AUTO_RECEIVE = "auto_receive"
        private const val K_THEME = "theme_mode"
        private const val K_SYNC = "sync_enabled"
        private const val K_PUSH_OPT_OUT = "push_opt_out"
        private const val K_SHIZUKU_ASKED = "shizuku_asked"
        private const val K_AUTO_SEND = "auto_send"
        private const val K_FCM = "fcm_config"

        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun normalizeUrl(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
            return s.trimEnd('/')
        }

        /** Parses `clipt://pair?u=<url>&t=<token>` from the desktop QR code. */
        fun parsePairingLink(raw: String): Pair<String, String>? {
            val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
            if ((uri.scheme != "clipverse" && uri.scheme != "clipt") || uri.host != "pair") return null
            val u = uri.getQueryParameter("u")?.takeIf { it.isNotBlank() } ?: return null
            val t = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() } ?: return null
            return normalizeUrl(u) to t
        }

        /** e.g. "yourname-K7Q4MX": the default deployment host collapses to just the account. */
        fun displayPairCode(serverUrl: String, code: String): String {
            val host = runCatching { Uri.parse(serverUrl).authority }.getOrNull().orEmpty()
            val label = Regex("^clipt\\.([^.]+)\\.workers\\.dev$").find(host)?.groupValues?.get(1) ?: host
            return "$label-$code"
        }

        /** Parses "<account or host>-<6 chars>" into (server URL, one-time code). */
        fun parsePairCode(input: String): Pair<String, String>? {
            val t = input.trim()
            val i = t.lastIndexOf('-')
            if (i < 1) return null
            val label = t.substring(0, i).trim().lowercase()
            val code = t.substring(i + 1).filter { it.isLetterOrDigit() }.uppercase()
            if (code.length != 6 || !Regex("^[a-z0-9.:-]+$").matches(label)) return null
            val host = if ('.' in label || ':' in label) label else "clipt.$label.workers.dev"
            val proto = if (host.startsWith("localhost") || host.startsWith("127.")) "http" else "https"
            return "$proto://$host" to code
        }

        /**
         * "Galaxy S24", "Pixel 9 Pro", "vivo X200 FE"… Prefers the user-visible device name,
         * falling back to manufacturer + model.
         */
        fun detectDeviceName(context: Context): String {
            val userName = runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.trim()
            if (!userName.isNullOrBlank() && !userName.equals(Build.MODEL, ignoreCase = true)) return userName.take(64)
            // Many OEMs (vivo, Xiaomi…) put the marketing name ("vivo X200 FE") only in the Bluetooth name.
            // Newer Android may block this read; fall through quietly if so.
            val btName = runCatching {
                Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            }.getOrNull()?.trim()
            if (!btName.isNullOrBlank() && !btName.contains(Build.MODEL, ignoreCase = true)) return btName.take(64)
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").take(64)
        }
    }
}
