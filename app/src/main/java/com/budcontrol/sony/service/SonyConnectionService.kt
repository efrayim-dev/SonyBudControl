package com.budcontrol.sony.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.budcontrol.sony.MainActivity
import com.budcontrol.sony.R
import com.budcontrol.sony.bluetooth.ConnectionStatus
import com.budcontrol.sony.bluetooth.DeviceState
import com.budcontrol.sony.bluetooth.SonyBluetoothManager
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.wear.PhoneStateSync
import com.budcontrol.sony.widget.AncToggleWidget
import com.budcontrol.sony.widget.QuickControlWidget
import com.budcontrol.sony.widget.StatusBarWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

class SonyConnectionService : Service() {

    companion object {
        const val CHANNEL_ID = "sony_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_CYCLE_ANC = "com.budcontrol.sony.ACTION_CYCLE_ANC"
        const val ACTION_DISCONNECT = "com.budcontrol.sony.ACTION_DISCONNECT"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
    }

    inner class LocalBinder : Binder() {
        val service: SonyConnectionService get() = this@SonyConnectionService
    }

    private val binder = LocalBinder()
    private lateinit var btManager: SonyBluetoothManager
    private lateinit var appMonitor: SonyAppMonitor
    private lateinit var stateSync: PhoneStateSync
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val state: StateFlow<DeviceState> get() = btManager.state

    override fun onCreate() {
        super.onCreate()
        btManager = SonyBluetoothManager(applicationContext)
        appMonitor = SonyAppMonitor(applicationContext)
        stateSync = PhoneStateSync(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(DeviceState()))

        appMonitor.onSonyAppForeground = { btManager.releaseConnection() }
        appMonitor.onSonyAppBackground = { btManager.reconnect() }
        appMonitor.start()

        stateSync.startSyncing(btManager.state)

        scope.launch {
            btManager.state.collectLatest { deviceState ->
                updateNotification(deviceState)
                syncWidgets(deviceState)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CYCLE_ANC -> btManager.cycleAncMode()
            ACTION_DISCONNECT -> {
                btManager.disconnect()
                stopSelf()
            }
            QuickControlWidget.ACTION_SET_AMBIENT -> {
                val level = intent.getIntExtra("ambient_level", 10)
                btManager.setAmbientLevel(level)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        stateSync.destroy()
        appMonitor.destroy()
        btManager.destroy()
        super.onDestroy()
    }

    // ── Public API for bound clients ────────────────────────────────

    fun findPairedDevices() = btManager.findPairedSonyDevices()

    fun connect(device: BluetoothDevice) = btManager.connect(device)

    fun disconnect() = btManager.disconnect()

    fun release() = btManager.releaseConnection()

    fun reconnect() = btManager.reconnect()

    fun setAncMode(mode: SonyCommands.AncMode) = btManager.setAncMode(mode)

    fun cycleAncMode() = btManager.cycleAncMode()

    fun setAmbientLevel(level: Int) = btManager.setAmbientLevel(level)

    fun setFocusOnVoice(enabled: Boolean) = btManager.setFocusOnVoice(enabled)

    fun setEqPreset(preset: SonyCommands.EqPreset) = btManager.setEqPreset(preset)

    fun setCustomEq(bands: IntArray) = btManager.setCustomEq(bands)

    fun setSpeakToChat(enabled: Boolean) = btManager.setSpeakToChat(enabled)

    fun setWideAreaTap(enabled: Boolean) = btManager.setWideAreaTap(enabled)

    fun setButtonModes(left: SonyCommands.ButtonMode, right: SonyCommands.ButtonMode) =
        btManager.setButtonModes(left, right)

    fun refreshStatus() = btManager.requestAllStatus()

    // ── Notification ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Earbuds Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows connection status and quick controls"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(state: DeviceState): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cycleAncIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SonyConnectionService::class.java).apply { action = ACTION_CYCLE_ANC },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = when (state.connectionStatus) {
            ConnectionStatus.CONNECTED -> {
                val battery = if (state.batteryAvg >= 0) " • ${state.batteryAvg}%" else ""
                "${state.ancMode.displayName}$battery"
            }
            ConnectionStatus.CONNECTING -> "Connecting…"
            ConnectionStatus.RECONNECTING -> "Reconnecting…"
            ConnectionStatus.RELEASED -> "Released for Sony app"
            ConnectionStatus.DISCONNECTED -> "Disconnected"
        }

        val title = state.deviceName ?: "Sony Earbuds"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_notification, "Cycle ANC", cycleAncIntent)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(state: DeviceState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun syncWidgets(state: DeviceState) {
        val ctx = applicationContext
        AncToggleWidget.updateAllWidgets(ctx, state.ancMode.name)
        QuickControlWidget.updateState(ctx, state.ancMode.name, state.batteryAvg, state.ambientLevel)
        StatusBarWidget.updateState(
            ctx,
            mode = state.ancMode.name,
            batteryLeft = state.batteryLeft,
            batteryRight = state.batteryRight,
            batteryCase = state.batteryCase,
            ambient = state.ambientLevel,
            connected = state.connectionStatus == ConnectionStatus.CONNECTED
        )
    }
}
