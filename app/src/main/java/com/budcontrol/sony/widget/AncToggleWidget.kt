package com.budcontrol.sony.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.budcontrol.sony.R
import com.budcontrol.sony.service.SonyConnectionService

/**
 * 1x1 widget: tap to cycle ANC → Ambient → Off.
 * Shows current mode icon and label.
 */
class AncToggleWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_ANC_CYCLE = "com.budcontrol.sony.ACTION_ANC_CYCLE"
        private const val PREFS_KEY = "anc_widget_mode"

        fun updateAllWidgets(context: Context, modeName: String?) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            if (modeName != null) {
                prefs.edit().putString(PREFS_KEY, modeName).apply()
            }
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, AncToggleWidget::class.java))
            val intent = Intent(context, AncToggleWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateWidget(context, mgr, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ANC_CYCLE) {
            val serviceIntent = Intent(context, SonyConnectionService::class.java).apply {
                action = SonyConnectionService.ACTION_CYCLE_ANC
            }
            context.startService(serviceIntent)

            // Cycle the stored mode for immediate visual feedback
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val current = prefs.getString(PREFS_KEY, "OFF") ?: "OFF"
            val next = when (current) {
                "NOISE_CANCELING" -> "AMBIENT_SOUND"
                "AMBIENT_SOUND" -> "OFF"
                else -> "NOISE_CANCELING"
            }
            prefs.edit().putString(PREFS_KEY, next).apply()

            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, AncToggleWidget::class.java))
            ids.forEach { id -> updateWidget(context, mgr, id) }
        }
    }

    private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString(PREFS_KEY, "OFF") ?: "OFF"

        val (iconRes, label) = when (mode) {
            "NOISE_CANCELING" -> R.drawable.ic_noise_cancel to "NC"
            "AMBIENT_SOUND" -> R.drawable.ic_ambient to "Ambient"
            else -> R.drawable.ic_anc_off to "Off"
        }

        val views = RemoteViews(context.packageName, R.layout.widget_anc_toggle).apply {
            setImageViewResource(R.id.widget_anc_icon, iconRes)
            setTextViewText(R.id.widget_anc_label, label)

            val pendingIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, AncToggleWidget::class.java).apply { action = ACTION_ANC_CYCLE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setOnClickPendingIntent(R.id.widget_anc_root, pendingIntent)
        }

        mgr.updateAppWidget(widgetId, views)
    }
}
