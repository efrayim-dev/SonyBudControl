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

class StatusBarWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_ANC = "com.budcontrol.sony.ACTION_SB_ANC"
        const val ACTION_AMBIENT_UP = "com.budcontrol.sony.ACTION_SB_AMBIENT_UP"
        const val ACTION_AMBIENT_DOWN = "com.budcontrol.sony.ACTION_SB_AMBIENT_DOWN"

        private const val KEY_MODE = "sb_widget_mode"
        private const val KEY_BATTERY_L = "sb_widget_bat_l"
        private const val KEY_BATTERY_R = "sb_widget_bat_r"
        private const val KEY_BATTERY_C = "sb_widget_bat_c"
        private const val KEY_AMBIENT = "sb_widget_ambient"
        private const val KEY_CONNECTED = "sb_widget_connected"

        fun updateState(
            context: Context,
            mode: String?,
            batteryLeft: Int? = null,
            batteryRight: Int? = null,
            batteryCase: Int? = null,
            ambient: Int? = null,
            connected: Boolean? = null
        ) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit()
            mode?.let { prefs.putString(KEY_MODE, it) }
            batteryLeft?.let { prefs.putInt(KEY_BATTERY_L, it) }
            batteryRight?.let { prefs.putInt(KEY_BATTERY_R, it) }
            batteryCase?.let { prefs.putInt(KEY_BATTERY_C, it) }
            ambient?.let { prefs.putInt(KEY_AMBIENT, it) }
            connected?.let { prefs.putBoolean(KEY_CONNECTED, it) }
            prefs.apply()

            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, StatusBarWidget::class.java))
            ids.forEach { id -> updateWidget(context, mgr, id) }
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_MODE, "OFF") ?: "OFF"
            val batL = prefs.getInt(KEY_BATTERY_L, -1)
            val batR = prefs.getInt(KEY_BATTERY_R, -1)
            val batC = prefs.getInt(KEY_BATTERY_C, -1)
            val ambient = prefs.getInt(KEY_AMBIENT, 10)
            val connected = prefs.getBoolean(KEY_CONNECTED, false)

            val modeIcon = when (mode) {
                "NOISE_CANCELING" -> R.drawable.ic_noise_cancel
                "WIND_NOISE_REDUCTION" -> R.drawable.ic_noise_cancel
                "AMBIENT_SOUND" -> R.drawable.ic_ambient
                else -> R.drawable.ic_anc_off
            }

            val modeLabel = when (mode) {
                "NOISE_CANCELING" -> "NC"
                "WIND_NOISE_REDUCTION" -> "Wind"
                "AMBIENT_SOUND" -> "AMB"
                else -> "Off"
            }

            fun batText(v: Int) = if (v >= 0) "$v%" else "—"
            fun batColor(v: Int) = when {
                v < 0 -> R.color.anc_off_gray
                v > 50 -> R.color.anc_green
                v > 20 -> R.color.amber_primary
                else -> R.color.battery_red
            }

            val views = RemoteViews(context.packageName, R.layout.widget_status_bar).apply {
                setImageViewResource(R.id.sb_anc_icon, modeIcon)
                setTextViewText(R.id.sb_anc_label, modeLabel)

                setTextViewText(R.id.sb_battery_left, batText(batL))
                setTextColor(R.id.sb_battery_left, context.getColor(batColor(batL)))
                setTextViewText(R.id.sb_battery_right, batText(batR))
                setTextColor(R.id.sb_battery_right, context.getColor(batColor(batR)))
                setTextViewText(R.id.sb_battery_case, batText(batC))
                setTextColor(R.id.sb_battery_case, context.getColor(batColor(batC)))

                setTextViewText(R.id.sb_ambient_level, "$ambient")

                setOnClickPendingIntent(R.id.sb_anc_area, makePending(context, ACTION_ANC, 20))
                setOnClickPendingIntent(R.id.sb_ambient_up, makePending(context, ACTION_AMBIENT_UP, 21))
                setOnClickPendingIntent(R.id.sb_ambient_down, makePending(context, ACTION_AMBIENT_DOWN, 22))
                setOnClickPendingIntent(R.id.sb_root, PendingIntent.getActivity(
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
                Intent(context, StatusBarWidget::class.java).apply { this.action = action },
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
                        action = QuickControlWidget.ACTION_SET_AMBIENT
                        putExtra("ambient_level", newLevel)
                    }
                )

                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, StatusBarWidget::class.java))
                ids.forEach { id -> updateWidget(context, mgr, id) }
            }
        }
    }
}
