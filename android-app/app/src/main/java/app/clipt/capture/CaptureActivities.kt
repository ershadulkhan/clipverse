package app.clipt.capture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.clipt.data.Api
import app.clipt.data.ImageCompressor
import app.clipt.data.LoopGuard
import app.clipt.prefs
import app.clipt.ui.MainActivity
import app.clipt.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * "Wake, act, die": each of these activities is invisible, does one thing, and calls
 * finishAndRemoveTask() so nothing lingers in recents or RAM. Network work is handed to an
 * expedited WorkManager job so the activity can close in a few milliseconds.
 */

private fun Activity.requirePairing(): Boolean {
    if (prefs.isPaired) return true
    Toast.makeText(this, "Pair Clipverse with your computer first", Toast.LENGTH_SHORT).show()
    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    finishAndRemoveTask()
    return false
}

/** Text-selection toolbar → "Send to Clipverse". */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requirePairing()) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNullOrBlank()) UploadWorker.enqueueText(this, text)
        // Returning no result leaves the selected text unchanged in the source app.
        setResult(RESULT_CANCELED)
        finishAndRemoveTask()
    }
}

/** System share sheet → Clipverse (text, links, one or many images). */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requirePairing()) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val isImage = intent.type?.startsWith("image/") == true
        if (isImage && uris.isNotEmpty()) {
            // The URI grant ends with this activity, so compress into our cache first (off the main thread).
            val fast = prefs.fastImages
            lifecycleScope.launch {
                val prepared = withContext(Dispatchers.IO) {
                    uris.take(10).mapNotNull { runCatching { ImageCompressor.prepare(applicationContext, it, fast) }.getOrNull() }
                }
                if (prepared.isEmpty()) Toast.makeText(applicationContext, "Couldn't read that image", Toast.LENGTH_SHORT).show()
                prepared.forEachIndexed { i, p -> UploadWorker.enqueueImage(applicationContext, p, silent = i < prepared.lastIndex) }
                finishAndRemoveTask()
            }
            return
        }
        val text = buildString {
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }?.let {
                val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                // Browsers share "Title" + "URL"; keep just the URL when the title is already in it.
                if (!body.contains(it)) append(it).append('\n')
            }
            append(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        }.trim()
        if (text.isNotEmpty()) UploadWorker.enqueueText(this, text)
        else Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }
}

/**
 * Android 10+ only lets the focused app read the clipboard. This transparent window takes
 * focus for a moment, reads it, hands the upload to WorkManager and disappears.
 * Used by the Quick Settings tile and the "Send clipboard" shortcut.
 */
class ClipboardReadActivity : ComponentActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requirePairing()) return
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled || isFinishing) return
        handled = true
        val cm = getSystemService(ClipboardManager::class.java)
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri
        val mime = uri?.let { contentResolver.getType(it) }
        when {
            uri != null && mime?.startsWith("image/") == true -> {
                val fast = prefs.fastImages
                lifecycleScope.launch {
                    val prepared = withContext(Dispatchers.IO) {
                        runCatching { ImageCompressor.prepare(applicationContext, uri, fast) }.getOrNull()
                    }
                    if (prepared != null) UploadWorker.enqueueImage(applicationContext, prepared)
                    else Toast.makeText(applicationContext, "Couldn't read the copied image", Toast.LENGTH_SHORT).show()
                    finishAndRemoveTask()
                }
                return
            }
            else -> {
                val text = item?.coerceToText(this)?.toString()
                if (!text.isNullOrBlank()) UploadWorker.enqueueText(this, text)
                else Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }
        finishAndRemoveTask()
    }
}

/**
 * Pulls the newest clip from your other devices straight into the clipboard in one network
 * burst. Launched by the home-screen widget, the launcher shortcut and push notifications.
 */
class CopyLatestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requirePairing()) return
        val clipId = intent.getStringExtra(EXTRA_CLIP_ID)
        val api = Api(prefs)
        lifecycleScope.launch {
            try {
                val clip = if (clipId != null) api.get(clipId) else api.latest(excludeSelf = true)
                val cm = getSystemService(ClipboardManager::class.java)
                when {
                    clip == null -> toast("Nothing to copy yet")
                    clip.isImage -> {
                        val ext = clip.mime?.substringAfter('/')?.replace("jpeg", "jpg") ?: "png"
                        val file = api.downloadImage(clip.id, File(cacheDir, "shared/clipt-latest.$ext"))
                        val uri = FileProvider.getUriForFile(this@CopyLatestActivity, "$packageName.files", file)
                        cm.setPrimaryClip(ClipData.newUri(contentResolver, "Clipverse image", uri))
                        toast("Image from ${clip.deviceName} copied ✓")
                    }
                    else -> {
                        LoopGuard.remember(clip.content)
                        cm.setPrimaryClip(ClipData.newPlainText("Clipverse", clip.content.orEmpty()))
                        toast("Copied from ${clip.deviceName} ✓")
                    }
                }
            } catch (e: Exception) {
                toast("Clipverse: ${e.message ?: "couldn't connect"}")
            }
            finishAndRemoveTask()
        }
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_CLIP_ID = "clip_id"
    }
}
