package com.budcontrol.sony.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.budcontrol.sony.accessibility.SonyAccessibilityService
import com.budcontrol.sony.accessibility.SonyAppController
import com.budcontrol.sony.bluetooth.ConnectionStatus
import com.budcontrol.sony.bluetooth.DeviceState
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.service.SonyConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    private val _sonyAppInstalled = MutableStateFlow(false)
    val sonyAppInstalled: StateFlow<Boolean> = _sonyAppInstalled.asStateFlow()

    val accessibilityEnabled: StateFlow<SonyAccessibilityService?> = SonyAccessibilityService.running

    // True when we should use the accessibility-based approach
    val useAccessibility: Boolean
        get() = SonyAccessibilityService.isRunning() && _sonyAppInstalled.value

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
        val ctx = getApplication<Application>()
        _sonyAppInstalled.value = SonyAppController.isSonyAppInstalled(ctx)
        bindService()

        viewModelScope.launch {
            SonyAccessibilityService.readState.collect { sonyState ->
                if (sonyState.lastScanTime > 0) {
                    _deviceState.value = _deviceState.value.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        ancMode = sonyState.ancMode ?: _deviceState.value.ancMode,
                        batteryLeft = if (sonyState.batteryLeft >= 0) sonyState.batteryLeft else _deviceState.value.batteryLeft,
                        batteryRight = if (sonyState.batteryRight >= 0) sonyState.batteryRight else _deviceState.value.batteryRight,
                        batteryCase = if (sonyState.batteryCase >= 0) sonyState.batteryCase else _deviceState.value.batteryCase,
                        connectMethod = "Sony App (Accessibility)"
                    )
                }
            }
        }
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

    // ── Setup helpers ──────────────────────────────────────────────

    fun openAccessibilitySettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun refreshSetupState() {
        val ctx = getApplication<Application>()
        _sonyAppInstalled.value = SonyAppController.isSonyAppInstalled(ctx)
    }

    // ── Device picker (for BT direct mode) ─────────────────────────

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

    fun autoConnectLast() {
        if (useAccessibility) {
            _deviceState.value = _deviceState.value.copy(
                connectionStatus = ConnectionStatus.CONNECTED,
                deviceName = "Sony Earbuds",
                connectMethod = "Sony App (Accessibility)",
                lastError = null
            )
            return
        }

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

    // ── Controls (accessibility or BT direct) ──────────────────────

    fun setAncMode(mode: SonyCommands.AncMode) {
        if (useAccessibility) {
            SonyAppController.setAncMode(getApplication(), mode)
        } else {
            service?.setAncMode(mode)
        }
    }

    fun cycleAncMode() {
        if (useAccessibility) {
            val next = when (_deviceState.value.ancMode) {
                SonyCommands.AncMode.NOISE_CANCELING -> SonyCommands.AncMode.AMBIENT_SOUND
                SonyCommands.AncMode.AMBIENT_SOUND -> SonyCommands.AncMode.OFF
                SonyCommands.AncMode.OFF -> SonyCommands.AncMode.NOISE_CANCELING
            }
            setAncMode(next)
        } else {
            service?.cycleAncMode()
        }
    }

    fun setAmbientLevel(level: Int) = service?.setAmbientLevel(level)
    fun setFocusOnVoice(enabled: Boolean) = service?.setFocusOnVoice(enabled)
    fun setWindReduction(enabled: Boolean) = service?.setWindReduction(enabled)
    fun setEqPreset(preset: SonyCommands.EqPreset) = service?.setEqPreset(preset)
    fun setCustomEq(bands: IntArray) = service?.setCustomEq(bands)
    fun setSpeakToChat(enabled: Boolean) = service?.setSpeakToChat(enabled)

    fun refreshStatus() {
        if (useAccessibility) {
            SonyAppController.readState(getApplication())
        } else {
            service?.refreshStatus()
        }
    }
}
