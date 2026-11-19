package app.clipt.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares an image for upload, entirely on-device, before any network activity:
 *  - Fast mode: hardware-accelerated ImageDecoder downsample (≤ 2560 px long edge) → WebP.
 *    A 6 MB camera JPEG typically becomes ~400 KB, which keeps the 4G/5G modem awake
 *    for a fraction of the time — the single biggest battery win for image sync.
 *  - Original mode: copies the bytes unchanged.
 *
 * Output goes to cacheDir/outbox so the upload worker can read it after the sharing
 * activity (and its temporary URI permission) is gone.
 */
object ImageCompressor {
    private const val MAX_EDGE = 2560
    private const val QUALITY = 82
    private val PASSTHROUGH = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/heic", "image/avif")

    data class Prepared(val file: File, val mime: String)

    fun outbox(context: Context) = File(context.cacheDir, "outbox").apply { mkdirs() }

    fun prepare(context: Context, uri: Uri, fast: Boolean): Prepared {
        val resolver = context.contentResolver
        val srcMime = resolver.getType(uri)?.lowercase() ?: "image/jpeg"

        // GIFs keep their animation; unknown formats are re-encoded.
        if (!fast && srcMime in PASSTHROUGH || srcMime == "image/gif") {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(srcMime) ?: "img"
            val out = File(outbox(context), "${UUID.randomUUID()}.$ext")
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read image" }
                out.outputStream().use { input.copyTo(it) }
            }
            return Prepared(out, srcMime)
        }

        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (fast && longest > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / longest
                decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
            }
            // Software allocation so the bitmap can be compressed.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        val out = File(outbox(context), "${UUID.randomUUID()}.webp")
        out.outputStream().use { stream ->
            val format = if (fast) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP_LOSSLESS
            bitmap.compress(format, if (fast) QUALITY else 100, stream)
        }
        bitmap.recycle()
        return Prepared(out, "image/webp")
    }
}
