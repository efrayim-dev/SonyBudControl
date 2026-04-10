package com.budcontrol.sony.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors whether the Sony Sound Connect app is in the foreground.
 * Uses UsageStatsManager which requires PACKAGE_USAGE_STATS permission
 * (granted by the user in Settings > Usage Access).
 */
class SonyAppMonitor(private val context: Context) {

    companion object {
        private const val TAG = "SonyAppMonitor"
        const val SONY_PACKAGE = "com.sony.songpal.mdr"
        private const val POLL_INTERVAL_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null

    private val _sonyAppInForeground = MutableStateFlow(false)
    val sonyAppInForeground: StateFlow<Boolean> = _sonyAppInForeground.asStateFlow()

    var onSonyAppForeground: (() -> Unit)? = null
    var onSonyAppBackground: (() -> Unit)? = null

    fun start() {
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "Usage stats permission not granted, monitor inactive")
            return
        }
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var wasForeground = false
            while (isActive) {
                val isForeground = isSonyAppForeground()
                _sonyAppInForeground.value = isForeground

                if (isForeground && !wasForeground) {
                    Log.i(TAG, "Sony app entered foreground")
                    onSonyAppForeground?.invoke()
                } else if (!isForeground && wasForeground) {
                    Log.i(TAG, "Sony app left foreground")
                    onSonyAppBackground?.invoke()
                }

                wasForeground = isForeground
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isSonyAppForeground(): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false

        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5000, now)
        var lastForegroundPackage: String? = null
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForegroundPackage = event.packageName
            }
        }

        return lastForegroundPackage == SONY_PACKAGE
    }
}
