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
            ids.forEach { id -> updateWidget(context, mgr, id) }
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val mode = prefs.getString(PREFS_KEY, "OFF") ?: "OFF"

            val (iconRes, label, labelColor) = when (mode) {
                "NOISE_CANCELING" -> Triple(R.drawable.ic_noise_cancel, "NC", R.color.anc_green)
                "WIND_NOISE_REDUCTION" -> Triple(R.drawable.ic_noise_cancel, "Wind", R.color.ambient_blue)
                "AMBIENT_SOUND" -> Triple(R.drawable.ic_ambient, "Ambient", R.color.amber_primary)
                else -> Triple(R.drawable.ic_anc_off, "Off", R.color.anc_off_gray)
            }

            val views = RemoteViews(context.packageName, R.layout.widget_anc_toggle).apply {
                setImageViewResource(R.id.widget_anc_icon, iconRes)
                setTextViewText(R.id.widget_anc_label, label)
                setTextColor(R.id.widget_anc_label, context.getColor(labelColor))

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

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateWidget(context, mgr, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ANC_CYCLE) {
            context.startService(
                Intent(context, SonyConnectionService::class.java).apply {
                    action = SonyConnectionService.ACTION_CYCLE_ANC
                }
            )
        }
    }
}
