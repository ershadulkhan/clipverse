package app.clipt.work

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.clipt.data.Api
import app.clipt.data.ApiException
import app.clipt.data.ImageCompressor
import app.clipt.prefs
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One-shot, expedited upload. Runs once when the user shares/copies something, then the
 * process is free to die. Network-constrained so it waits (without polling) if offline.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.prefs
        if (!prefs.isPaired) {
            toast(ctx, "Clipverse isn't paired yet — open the app to set it up")
            cleanup()
            return Result.failure()
        }
        val api = Api(prefs)
        val kind = inputData.getString(KEY_KIND)
        val silent = inputData.getBoolean(KEY_SILENT, false)
        return try {
            when (kind) {
                KIND_TEXT -> {
                    val text = inputData.getString(KEY_TEXT)
                        ?: inputData.getString(KEY_PATH)?.let { File(it).readText() }
                        ?: return Result.failure()
                    api.sendText(text)
                }
                KIND_IMAGE -> {
                    val file = File(inputData.getString(KEY_PATH) ?: return Result.failure())
                    if (!file.exists()) return Result.failure()
                    api.sendImage(file, inputData.getString(KEY_MIME) ?: "image/webp")
                }
                else -> return Result.failure()
            }
            cleanup()
            if (!silent) toast(ctx, "Sent to Clipverse ✓")
            Result.success()
        } catch (e: ApiException) {
            // Client errors won't fix themselves; server errors are retried.
            if (e.code in 400..499) {
                cleanup()
                toast(ctx, "Clipverse: ${e.message}")
                Result.failure()
            } else retryOrFail(ctx, e)
        } catch (e: Exception) {
            retryOrFail(ctx, e)
        }
    }

    private fun retryOrFail(ctx: Context, e: Exception): Result =
        if (runAttemptCount < 3) Result.retry() else {
            cleanup()
            toast(ctx, "Clipverse: couldn't send (${e.message ?: "network error"})")
            Result.failure()
        }

    private fun cleanup() {
        inputData.getString(KEY_PATH)?.let { File(it).delete() }
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_TEXT = "text"
        private const val KEY_PATH = "path"
        private const val KEY_MIME = "mime"
        private const val KEY_SILENT = "silent"
        private const val KIND_TEXT = "text"
        private const val KIND_IMAGE = "image"
        // WorkManager Data is capped at 10 KB; larger text goes through a temp file.
        private const val INLINE_TEXT_LIMIT = 4000

        fun enqueueText(context: Context, text: String, silent: Boolean = false) {
            val data = if (text.length <= INLINE_TEXT_LIMIT) {
                workDataOf(KEY_KIND to KIND_TEXT, KEY_TEXT to text, KEY_SILENT to silent)
            } else {
                val f = File(ImageCompressor.outbox(context), "${UUID.randomUUID()}.txt").apply { writeText(text) }
                workDataOf(KEY_KIND to KIND_TEXT, KEY_PATH to f.absolutePath, KEY_SILENT to silent)
            }
            enqueue(context, data)
        }

        fun enqueueImage(context: Context, prepared: ImageCompressor.Prepared, silent: Boolean = false) {
            enqueue(
                context,
                workDataOf(KEY_KIND to KIND_IMAGE, KEY_PATH to prepared.file.absolutePath, KEY_MIME to prepared.mime, KEY_SILENT to silent),
            )
        }

        private fun enqueue(context: Context, data: androidx.work.Data) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("clipt-upload")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun toast(ctx: Context, msg: String) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
