package com.budcontrol.sony.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.budcontrol.sony.R
import com.budcontrol.sony.MainActivity
import com.budcontrol.sony.service.SonyConnectionService

class QuickControlWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_ANC = "com.budcontrol.sony.ACTION_WIDGET_ANC"
        const val ACTION_AMBIENT_UP = "com.budcontrol.sony.ACTION_WIDGET_AMBIENT_UP"
        const val ACTION_AMBIENT_DOWN = "com.budcontrol.sony.ACTION_WIDGET_AMBIENT_DOWN"
        const val ACTION_SET_AMBIENT = "com.budcontrol.sony.ACTION_WIDGET_SET_AMBIENT"

        private const val KEY_MODE = "qc_widget_mode"
        private const val KEY_BATTERY = "qc_widget_battery"
        private const val KEY_AMBIENT = "qc_widget_ambient"

        fun updateState(context: Context, mode: String?, battery: Int?, ambient: Int?) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit()
            mode?.let { prefs.putString(KEY_MODE, it) }
            battery?.let { prefs.putInt(KEY_BATTERY, it) }
            ambient?.let { prefs.putInt(KEY_AMBIENT, it) }
            prefs.apply()

            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, QuickControlWidget::class.java))
            ids.forEach { id -> updateWidget(context, mgr, id) }
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_MODE, "OFF") ?: "OFF"
            val battery = prefs.getInt(KEY_BATTERY, -1)
            val ambient = prefs.getInt(KEY_AMBIENT, 10)

            val modeLabel = when (mode) {
                "NOISE_CANCELING" -> "Noise Canceling"
                "WIND_NOISE_REDUCTION" -> "Wind NC"
                "AMBIENT_SOUND" -> "Ambient $ambient"
                else -> "Off"
            }

            val modeIcon = when (mode) {
                "NOISE_CANCELING" -> R.drawable.ic_noise_cancel
                "WIND_NOISE_REDUCTION" -> R.drawable.ic_noise_cancel
                "AMBIENT_SOUND" -> R.drawable.ic_ambient
                else -> R.drawable.ic_anc_off
            }

            val batteryText = if (battery >= 0) "$battery%" else "—"

            val views = RemoteViews(context.packageName, R.layout.widget_quick_control).apply {
                setImageViewResource(R.id.qc_mode_icon, modeIcon)
                setTextViewText(R.id.qc_mode_label, modeLabel)
                setTextViewText(R.id.qc_battery_text, batteryText)
                setTextViewText(R.id.qc_ambient_level, "$ambient")

                setOnClickPendingIntent(R.id.qc_mode_area, makePending(context, ACTION_ANC, 10))
                setOnClickPendingIntent(R.id.qc_ambient_up, makePending(context, ACTION_AMBIENT_UP, 11))
                setOnClickPendingIntent(R.id.qc_ambient_down, makePending(context, ACTION_AMBIENT_DOWN, 12))
                setOnClickPendingIntent(R.id.qc_root, PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ))
            }

            mgr.updateAppWidget(widgetId, views)
        }

        private fun makePending(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, QuickControlWidget::class.java).apply { this.action = action },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateWidget(context, mgr, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_ANC -> {
                context.startService(
                    Intent(context, SonyConnectionService::class.java).apply {
                        action = SonyConnectionService.ACTION_CYCLE_ANC
                    }
                )
            }
            ACTION_AMBIENT_UP, ACTION_AMBIENT_DOWN -> {
                val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                val current = prefs.getInt(KEY_AMBIENT, 10)
                val delta = if (intent.action == ACTION_AMBIENT_UP) 2 else -2
                val newLevel = (current + delta).coerceIn(0, 20)
                prefs.edit().putInt(KEY_AMBIENT, newLevel).apply()

                context.startService(
                    Intent(context, SonyConnectionService::class.java).apply {
                        action = ACTION_SET_AMBIENT
                        putExtra("ambient_level", newLevel)
                    }
                )

                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, QuickControlWidget::class.java))
                ids.forEach { id -> updateWidget(context, mgr, id) }
            }
        }
    }
}
