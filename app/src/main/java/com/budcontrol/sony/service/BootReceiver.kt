package com.budcontrol.sony.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build

/**
 * Restarts the connection service on boot if the user had auto-connect enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("sony_bud_prefs", Context.MODE_PRIVATE)
        val lastDevice = prefs.getString("last_device_address", null) ?: return
        val autoConnect = prefs.getBoolean("auto_connect", true)

        if (autoConnect) {
            val serviceIntent = Intent(context, SonyConnectionService::class.java).apply {
                putExtra(SonyConnectionService.EXTRA_DEVICE_ADDRESS, lastDevice)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
