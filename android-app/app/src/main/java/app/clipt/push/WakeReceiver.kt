package app.clipt.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Deliberately does nothing. The Shizuku clipboard watcher sends this broadcast when it finds the
 * app force-stopped (vivo/Xiaomi/Oppo do that when you swipe an app out of Recents). Merely
 * delivering a broadcast takes the app out of the "stopped" state, so Firebase can deliver
 * computer → phone clips again. No process stays running.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
