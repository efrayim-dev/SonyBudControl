package com.budcontrol.sony.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.service.SonyConnectionService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class PhoneWearListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneWearListener"
    }

    private var sonyService: SonyConnectionService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sonyService = (binder as SonyConnectionService.LocalBinder).service
            bound = true
            Log.i(TAG, "Bound to SonyConnectionService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sonyService = null
            bound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        val intent = Intent(this, SonyConnectionService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            try { unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val svc = sonyService ?: run {
            Log.w(TAG, "SonyConnectionService not bound, ignoring ${messageEvent.path}")
            return
        }

        Log.i(TAG, "Received: ${messageEvent.path}")

        when (messageEvent.path) {
            WearPaths.CMD_CYCLE_ANC -> svc.cycleAncMode()

            WearPaths.CMD_ANC -> {
                val modeName = String(messageEvent.data)
                val mode = try {
                    SonyCommands.AncMode.valueOf(modeName)
                } catch (_: Exception) {
                    Log.w(TAG, "Unknown ANC mode: $modeName")
                    return
                }
                svc.setAncMode(mode)
            }

            WearPaths.CMD_REFRESH -> svc.refreshStatus()

            WearPaths.CMD_DISCONNECT -> svc.disconnect()

            else -> Log.w(TAG, "Unknown command path: ${messageEvent.path}")
        }
    }
}
