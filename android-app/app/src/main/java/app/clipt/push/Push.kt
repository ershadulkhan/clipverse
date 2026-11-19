package app.clipt.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.clipt.CliptApp
import app.clipt.R
import app.clipt.capture.CopyLatestActivity
import app.clipt.data.Api
import app.clipt.data.FcmConfig
import app.clipt.data.LoopGuard
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import app.clipt.prefs
import app.clipt.ui.MainActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Optional, opt-in push. Firebase is initialised at runtime from the *paired server's*
 * public config, so the open-source build needs no google-services.json. FCM rides on the
 * Play Services connection the phone already keeps open — Clipverse adds no socket of its own.
 */
object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun ensureFirebase(context: Context, cfg: FcmConfig): Boolean = runCatching {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(cfg.projectId)
                    .setApplicationId(cfg.appId)
                    .setApiKey(cfg.apiKey)
                    .setGcmSenderId(cfg.senderId)
                    .build(),
            )
        }
        true
    }.getOrDefault(false)

    /** Turns push on: fetch token and register it with the server. */
    suspend fun enable(context: Context): Boolean {
        val prefs = context.prefs
        val cfg = prefs.fcmConfig ?: return false
        if (!ensureFirebase(context, cfg)) return false
        return runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = true
            val token = messaging.token.await()
            Api(prefs).registerDevice(token)
            prefs.pushEnabled = true
        }.isSuccess
    }

    suspend fun disable(context: Context) {
        val prefs = context.prefs
        prefs.pushEnabled = false
        val cfg = prefs.fcmConfig ?: return
        if (!ensureFirebase(context, cfg)) return
        runCatching {
            FirebaseMessaging.getInstance().isAutoInitEnabled = false
            FirebaseMessaging.getInstance().deleteToken().await()
            Api(prefs).registerDevice("")
        }
    }

    fun onNewToken(context: Context, token: String) {
        val prefs = context.prefs
        if (!prefs.isPaired || !prefs.pushEnabled) return
        scope.launch { runCatching { Api(prefs).registerDevice(token) } }
    }

    fun showClipNotification(context: Context, clipId: String, deviceName: String, type: String, preview: String) {
        val copyIntent = Intent(context, CopyLatestActivity::class.java)
            .putExtra(CopyLatestActivity.EXTRA_CLIP_ID, clipId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val copyPi = PendingIntent.getActivity(context, clipId.hashCode(), copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openPi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(context, CliptApp.CHANNEL_CLIPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFEB0028.toInt())
            .setContentTitle("New clip from $deviceName")
            .setContentText(if (type == "image") "Image — tap Copy to put it on your clipboard" else preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (type == "image") "Image" else preview))
            .setContentIntent(copyPi)
            .addAction(0, "Copy", copyPi)
            .addAction(0, "Open Clipverse", openPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(clipId.hashCode(), notification) }
    }
}

/** Receives the high-priority data message the Worker sends when a desktop shares a clip. */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val id = d["clip_id"] ?: return
        if (!prefs.syncEnabled) return // automatic sync paused on this phone
        val type = d["type"] ?: "text"
        val from = d["device_name"] ?: "your computer"
        if (type == "text" && prefs.autoReceive) {
            // Android lets apps *write* the clipboard from the background, so the text is
            // ready to paste anywhere — no tap needed. Long text isn't in the push; fetch it.
            val text = d["content"].takeUnless { it.isNullOrEmpty() }
                ?: runCatching { runBlocking { withTimeout(8000) { Api(prefs).get(id)?.content } } }.getOrNull()
            if (!text.isNullOrEmpty()) {
                LoopGuard.remember(text)
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Clipverse · $from", text))
                return
            }
        }
        Push.showClipNotification(this, id, from, type, d["preview"].orEmpty())
    }

    override fun onNewToken(token: String) = Push.onNewToken(this, token)
}
