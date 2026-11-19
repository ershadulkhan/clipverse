package app.clipt.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.clipt.R
import app.clipt.capture.CopyLatestActivity

/**
 * 1×1 "Copy latest" widget. It never refreshes itself (updatePeriodMillis = 0) — tapping it
 * launches [CopyLatestActivity], which does a single fetch and exits.
 */
class CopyLatestWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, CopyLatestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, R.layout.widget_copy_latest).apply {
            setOnClickPendingIntent(R.id.widget_root, pi)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
