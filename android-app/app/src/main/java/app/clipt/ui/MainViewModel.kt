package app.clipt.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clipt.data.Api
import app.clipt.data.Clip
import app.clipt.data.ClipCache
import app.clipt.data.ImageCompressor
import app.clipt.data.LoopGuard
import app.clipt.autosend.AutoSendService
import app.clipt.shizuku.ShizukuSync
import app.clipt.data.Prefs
import app.clipt.data.redeemPairCode
import app.clipt.prefs
import app.clipt.push.Push
import app.clipt.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Filter(val label: String) { All("All"), Text("Text"), Images("Images"), Pinned("Pinned") }

data class UiState(
    val paired: Boolean = false,
    val clips: List<Clip> = emptyList(),
    val filter: Filter = Filter.All,
    val query: String = "",
    val refreshing: Boolean = false,
    val error: String? = null,
    val deviceName: String = "",
    val serverUrl: String = "",
    val fastImages: Boolean = true,
    val pushAvailable: Boolean = false,
    val pushEnabled: Boolean = false,
    val retentionDays: Int = 30,
    val autoReceive: Boolean = true,
    val autoSend: Boolean = false,
    val hasOverlay: Boolean = false,
    val hasLogAccess: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuAllowed: Boolean = false,
    val watcherStatus: String? = null,
    val themeMode: String = "system",
    val syncEnabled: Boolean = true,
    val pairCode: String? = null,
    val pairCodeExpires: Long = 0,
) {
    val visible: List<Clip>
        get() {
            val q = query.trim().lowercase()
            return clips.filter { c ->
                when (filter) {
                    Filter.All -> true
                    Filter.Text -> !c.isImage
                    Filter.Images -> c.isImage
                    Filter.Pinned -> c.isPinned
                } && (q.isEmpty() || c.content.orEmpty().lowercase().contains(q) || c.deviceName.lowercase().contains(q))
            }
        }
}

/**
 * All network activity is user-initiated: opening the app, pull-to-refresh, or an action.
 * There is no timer, no observer and no background sync.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.prefs
    private val cache = ClipCache(app)
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events.asStateFlow()

    private fun snapshot() = UiState(
        paired = prefs.isPaired,
        deviceName = prefs.deviceName,
        serverUrl = prefs.serverUrl,
        fastImages = prefs.fastImages,
        pushAvailable = prefs.fcmConfig != null,
        pushEnabled = prefs.pushEnabled,
        autoReceive = prefs.autoReceive,
        themeMode = prefs.themeMode,
        syncEnabled = prefs.syncEnabled,
        autoSend = prefs.autoSend,
        hasOverlay = AutoSendService.hasOverlay(getApplication()),
        hasLogAccess = AutoSendService.hasLogAccess(getApplication()),
    )

    /** Called on resume: permissions may have changed in system Settings. */
    fun refreshPermissions() = _state.update {
        it.copy(
            hasOverlay = AutoSendService.hasOverlay(getApplication()),
            hasLogAccess = AutoSendService.hasLogAccess(getApplication()),
            shizukuInstalled = ShizukuSync.isInstalled(getApplication()),
            shizukuRunning = ShizukuSync.isRunning(),
            shizukuAllowed = ShizukuSync.hasPermission(),
            watcherStatus = ShizukuSync.status(),
        )
    }

    /**
     * Starts whichever copy-detection method this phone supports. Shizuku is preferred: it reads
     * copies directly as the shell user, with no Clipverse background service. Without Shizuku we
     * fall back to the log-watching service (doesn't work on phones that mute system logs).
     */
    fun ensureAutoSendRunning() {
        val app = getApplication<Application>()
        // Default: with Shizuku installed, auto-send is on unless the user turned it off.
        if (!prefs.autoSendChosen && prefs.isPaired && ShizukuSync.isInstalled(app)) {
            prefs.autoSend = true
            _state.update { it.copy(autoSend = true) }
        }
        if (!prefs.autoSend || !prefs.isPaired || !prefs.syncEnabled) {
            // Should be off: make sure nothing is left running (e.g. a watcher from before an update).
            AutoSendService.stop(app)
            if (ShizukuSync.isInstalled(app)) ShizukuSync.stop(app)
            return
        }
        if (ShizukuSync.isInstalled(app)) {
            // Shizuku present: always use it (even if its binder hasn't arrived yet).
            AutoSendService.stop(app)
            ShizukuSync.whenReady {
                when {
                    !prefs.autoSend || !prefs.syncEnabled -> Unit
                    ShizukuSync.hasPermission() -> ShizukuSync.start(app)
                    !prefs.shizukuAsked -> { prefs.shizukuAsked = true; requestShizuku() } // once, automatically
                }
                refreshPermissions()
            }
        } else {
            AutoSendService.start(app)
        }
        viewModelScope.launch { kotlinx.coroutines.delay(1500); refreshPermissions() }
    }

    fun requestShizuku() {
        if (!ShizukuSync.isRunning()) { message("Open the Shizuku app and start it first"); return }
        ShizukuSync.requestPermission { granted ->
            if (granted) ensureAutoSendRunning() else message("Shizuku permission denied")
            refreshPermissions()
        }
    }

    fun setAutoReceive(on: Boolean) {
        prefs.autoReceive = on
        _state.update { it.copy(autoReceive = on) }
    }

    fun setAutoSend(on: Boolean) {
        prefs.autoSend = on
        _state.update { it.copy(autoSend = on) }
        if (on) {
            val app = getApplication<Application>()
            if (ShizukuSync.isInstalled(app)) {
                // Wait for Shizuku's binder (it may not have arrived yet), then ask for permission if needed.
                ShizukuSync.whenReady { if (ShizukuSync.hasPermission()) ensureAutoSendRunning() else requestShizuku() }
            } else {
                ensureAutoSendRunning()
            }
        } else {
            ShizukuSync.stop(getApplication<Application>())
            AutoSendService.stop(getApplication<Application>())
            refreshPermissions()
        }
    }

    init {
        viewModelScope.launch {
            val cached = cache.read()
            if (cached.isNotEmpty()) _state.update { it.copy(clips = cached) }
            refresh()
        }
    }

    fun message(text: String) { _events.value = text }
    fun consumeMessage() { _events.value = null }

    private fun api() = Api(prefs)

    fun refresh() {
        if (!prefs.isPaired) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            // Pick up server-side changes such as push being enabled, without re-pairing.
            runCatching { api().config() }.getOrNull()?.let { cfg ->
                prefs.fcmConfig = cfg.fcm
                _state.update { it.copy(pushAvailable = cfg.fcm != null, retentionDays = cfg.retentionDays) }
                // Push is what makes computer → phone automatic, so it's on by default whenever the
                // server supports it (no notification permission is needed to fill the clipboard).
                if (cfg.fcm != null && !prefs.pushEnabled && !prefs.pushOptOut) {
                    if (Push.enable(getApplication<Application>())) _state.update { it.copy(pushEnabled = true) }
                }
            }
            try {
                val list = api().list(limit = 100)
                _state.update { it.copy(clips = list.clips, error = null, refreshing = false) }
                cache.write(list.clips)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't connect", refreshing = false) }
            }
        }
    }

    fun setFilter(f: Filter) = _state.update { it.copy(filter = f) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    private fun upsert(clip: Clip) = _state.update { s ->
        s.copy(clips = (listOf(clip) + s.clips.filterNot { it.id == clip.id }).sortedByDescending { it.createdAt })
    }

    // ---------- Pairing ----------

    fun pair(url: String, token: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val candidate = Api(prefs, Prefs.normalizeUrl(url), token.trim())
            try {
                val cfg = candidate.config()
                prefs.pair(url, token)
                prefs.fcmConfig = cfg.fcm
                runCatching { api().registerDevice() }
                _state.value = snapshot().copy(retentionDays = cfg.retentionDays)
                message("Paired ✓")
                refresh()               // also switches push on when the server supports it
                ensureAutoSendRunning() // and auto-send (via Shizuku) when available
                onDone(true)
            } catch (e: Exception) {
                message("Couldn't pair: ${e.message}")
                onDone(false)
            }
        }
    }

    /** Accepts a one-time code ("account-K7Q4MX") or a clipverse://pair link (QR or pasted). */
    fun pairFromLink(input: String, onDone: (Boolean) -> Unit = {}) {
        Prefs.parsePairingLink(input)?.let { (url, token) -> pair(url, token, onDone); return }
        val code = Prefs.parsePairCode(input)
        if (code == null) { message("That isn't a Clipverse pairing code (e.g. yourname-K7Q4MX)"); onDone(false); return }
        viewModelScope.launch {
            try {
                val res = redeemPairCode(code.first, code.second)
                pair(res.serverUrl, res.token, onDone)
            } catch (e: Exception) {
                message("Couldn't pair: ${e.message}")
                onDone(false)
            }
        }
    }

    /** Master switch: pauses or resumes all automatic syncing on this phone. */
    fun setSyncEnabled(on: Boolean) {
        prefs.syncEnabled = on
        _state.update { it.copy(syncEnabled = on) }
        if (on) {
            ensureAutoSendRunning()
            message("Automatic sync on")
        } else {
            ShizukuSync.stop(getApplication<Application>())
            AutoSendService.stop(getApplication<Application>())
            message("Automatic sync paused")
        }
        refreshPermissions()
    }

    fun setThemeMode(mode: String) {
        prefs.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    /** Shows a one-time code (10 min) that another phone or computer can type to join. */
    fun createPairCode() {
        viewModelScope.launch {
            try {
                val pc = api().createPairCode()
                _state.update { it.copy(pairCode = Prefs.displayPairCode(prefs.serverUrl, pc.code), pairCodeExpires = pc.expiresAt) }
            } catch (e: Exception) {
                message("Couldn't create a code: ${e.message}")
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            if (prefs.pushEnabled) runCatching { Push.disable(getApplication<Application>()) }
            AutoSendService.stop(getApplication<Application>())
            ShizukuSync.stop(getApplication<Application>())
            prefs.unpair()
            cache.clear()
            _state.value = snapshot()
        }
    }

    // ---------- Settings ----------

    fun setDeviceName(name: String) {
        if (name.isBlank()) return
        prefs.deviceName = name
        _state.update { it.copy(deviceName = prefs.deviceName) }
        viewModelScope.launch { runCatching { api().registerDevice() } }
    }

    fun setFastImages(fast: Boolean) {
        prefs.fastImages = fast
        _state.update { it.copy(fastImages = fast) }
    }

    fun setPush(enabled: Boolean) {
        viewModelScope.launch {
            prefs.pushOptOut = !enabled
            if (enabled) {
                val ok = Push.enable(getApplication<Application>())
                if (!ok) message("Couldn't enable push — check your Firebase setup")
            } else {
                Push.disable(getApplication<Application>())
            }
            _state.update { it.copy(pushEnabled = prefs.pushEnabled) }
        }
    }

    // ---------- Sending (app is in the foreground, so we upload directly) ----------

    fun sendText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                upsert(api().sendText(text))
                message("Sent to Clipverse ✓")
            } catch (e: Exception) {
                UploadWorker.enqueueText(getApplication<Application>(), text) // retried when back online
                message("Offline — will send when connected")
            }
        }
    }

    fun sendClipboard() {
        val cm = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri
        val ctx = getApplication<Application>()
        if (uri != null && ctx.contentResolver.getType(uri)?.startsWith("image/") == true) {
            sendImages(listOf(uri))
            return
        }
        val text = item?.coerceToText(ctx)?.toString()
        if (text.isNullOrBlank()) message("Clipboard is empty") else sendText(text)
    }

    fun sendImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            message(if (uris.size == 1) "Sending image…" else "Sending ${uris.size} images…")
            var sent = 0
            var queued = 0
            for (uri in uris.take(10)) {
                try {
                    val prepared = withContext(Dispatchers.IO) { ImageCompressor.prepare(ctx, uri, prefs.fastImages) }
                    try {
                        upsert(api().sendImage(prepared.file, prepared.mime))
                        prepared.file.delete()
                        sent++
                    } catch (e: Exception) {
                        UploadWorker.enqueueImage(ctx, prepared) // retried when back online
                        queued++
                    }
                } catch (e: Exception) {
                    message("Couldn't read image: ${e.message}")
                }
            }
            when {
                queued > 0 -> message("Offline — $queued image(s) will send when connected")
                sent > 0 -> message("Sent to Clipverse ✓")
            }
        }
    }

    // ---------- Clip actions ----------

    fun copy(clip: Clip, onDone: () -> Unit = {}) {
        val ctx = getApplication<Application>()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        if (!clip.isImage) {
            LoopGuard.remember(clip.content)
            cm.setPrimaryClip(ClipData.newPlainText("Clipverse", clip.content.orEmpty()))
            onDone()
            return
        }
        viewModelScope.launch {
            try {
                val uri = shareableImage(clip)
                cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "Clipverse image", uri))
                onDone()
            } catch (e: Exception) {
                message("Couldn't copy image: ${e.message}")
            }
        }
    }

    fun share(clip: Clip, launch: (Intent) -> Unit) {
        if (!clip.isImage) {
            launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, clip.content), null))
            return
        }
        viewModelScope.launch {
            try {
                val uri = shareableImage(clip)
                val send = Intent(Intent.ACTION_SEND).setType(clip.mime ?: "image/*")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launch(Intent.createChooser(send, null))
            } catch (e: Exception) {
                message("Couldn't share image: ${e.message}")
            }
        }
    }

    /** Saves an image clip to Pictures/Clipverse (MediaStore — no storage permission needed). */
    fun download(clip: Clip) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val tmp = api().downloadImage(clip.id, File(ctx.cacheDir, "shared/dl-${clip.id}"))
                withContext(Dispatchers.IO) {
                    val ext = clip.mime?.substringAfter('/')?.replace("jpeg", "jpg") ?: "png"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "clipt-${clip.id.take(8)}.$ext")
                        put(MediaStore.Images.Media.MIME_TYPE, clip.mime ?: "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Clipverse")
                    }
                    val resolver = ctx.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused")
                    resolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                }
                message("Saved to Pictures/Clipverse ✓")
            } catch (e: Exception) {
                message("Couldn't save: ${e.message}")
            }
        }
    }

    private suspend fun shareableImage(clip: Clip): Uri {
        val ctx = getApplication<Application>()
        val ext = clip.mime?.substringAfter('/')?.replace("jpeg", "jpg") ?: "png"
        val file = File(ctx.cacheDir, "shared/clipt-${clip.id.take(8)}.$ext")
        if (!file.exists()) api().downloadImage(clip.id, file)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    }

    fun togglePin(clip: Clip) {
        upsert(clip.copy(isPinned = !clip.isPinned))
        viewModelScope.launch {
            try {
                api().setPinned(clip.id, !clip.isPinned)?.let(::upsert)
                message(if (clip.isPinned) "Unpinned" else "Pinned — kept forever")
            } catch (e: Exception) {
                upsert(clip)
                message("Couldn't update: ${e.message}")
            }
        }
    }

    fun delete(clip: Clip) {
        _state.update { s -> s.copy(clips = s.clips.filterNot { it.id == clip.id }) }
        viewModelScope.launch {
            try {
                api().delete(clip.id)
                cache.write(_state.value.clips)
            } catch (e: Exception) {
                upsert(clip)
                message("Couldn't delete: ${e.message}")
            }
        }
    }

    fun imageUrl(clip: Clip) = api().imageUrl(clip.id)
    fun authHeader() = api().authHeader()
}
