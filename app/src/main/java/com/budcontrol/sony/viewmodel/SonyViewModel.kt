package com.budcontrol.sony.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.budcontrol.sony.bluetooth.DeviceState
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.service.SonyConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SonyViewModel(application: Application) : AndroidViewModel(application) {

    private var service: SonyConnectionService? = null
    private var bound = false
    private var pendingAutoConnect = false

    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    private val _showDevicePicker = MutableStateFlow(false)
    val showDevicePicker: StateFlow<Boolean> = _showDevicePicker.asStateFlow()

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as SonyConnectionService.LocalBinder).service
            service = svc
            bound = true

            viewModelScope.launch {
                svc.state.collect { state -> _deviceState.value = state }
            }

            if (pendingAutoConnect) {
                pendingAutoConnect = false
                autoConnectLast()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, SonyConnectionService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        if (bound) {
            try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        super.onCleared()
    }

    // ── Device picker ─────────────────────────────────────────────

    fun showDevicePicker() {
        _pairedDevices.value = service?.findPairedDevices() ?: emptyList()
        _showDevicePicker.value = true
    }

    fun dismissDevicePicker() {
        _showDevicePicker.value = false
    }

    fun connectToDevice(device: BluetoothDevice) {
        val ctx = getApplication<Application>()
        ctx.getSharedPreferences("sony_bud_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_device_address", device.address)
            .apply()

        service?.connect(device)
        _showDevicePicker.value = false
    }

    fun disconnect() {
        service?.disconnect()
    }

    fun release() {
        service?.release()
    }

    fun reconnect() {
        service?.reconnect()
    }

    fun autoConnectLast() {
        val svc = service
        if (svc == null) {
            pendingAutoConnect = true
            return
        }

        val ctx = getApplication<Application>()
        val prefs = ctx.getSharedPreferences("sony_bud_prefs", Context.MODE_PRIVATE)
        val lastAddress = prefs.getString("last_device_address", null) ?: return
        val devices = svc.findPairedDevices()
        val device = devices.find { it.address == lastAddress } ?: return
        svc.connect(device)
    }

    // ── Controls ──────────────────────────────────────────────────

    fun setAncMode(mode: SonyCommands.AncMode) {
        service?.setAncMode(mode)
    }

    fun cycleAncMode() {
        service?.cycleAncMode()
    }

    fun setAmbientLevel(level: Int) = service?.setAmbientLevel(level)
    fun setFocusOnVoice(enabled: Boolean) = service?.setFocusOnVoice(enabled)
    fun setEqPreset(preset: SonyCommands.EqPreset) = service?.setEqPreset(preset)
    fun setCustomEq(bands: IntArray) = service?.setCustomEq(bands)
    fun setSpeakToChat(enabled: Boolean) = service?.setSpeakToChat(enabled)
    fun setWideAreaTap(enabled: Boolean) = service?.setWideAreaTap(enabled)
    fun setButtonModes(left: SonyCommands.ButtonMode, right: SonyCommands.ButtonMode) =
        service?.setButtonModes(left, right)

    fun refreshStatus() {
        service?.refreshStatus()
    }
}
