package com.budcontrol.sony.wear

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WearDeviceState(
    val connected: Boolean = false,
    val deviceName: String = "",
    val ancMode: String = "OFF",
    val ambientLevel: Int = 0,
    val batteryLeft: Int = -1,
    val batteryRight: Int = -1,
    val batteryCase: Int = -1,
    val eqPreset: String = "Off",
    val speakToChat: Boolean = false
) {
    val batteryAvg: Int
        get() {
            val levels = listOf(batteryLeft, batteryRight).filter { it >= 0 }
            return if (levels.isEmpty()) -1 else levels.average().toInt()
        }
}

class WearViewModel(application: Application) : AndroidViewModel(application),
    DataClient.OnDataChangedListener {

    companion object {
        private const val TAG = "WearVM"
    }

    private val commandSender = WearCommandSender(application)
    private val dataClient = Wearable.getDataClient(application)

    private val _state = MutableStateFlow(WearDeviceState())
    val state: StateFlow<WearDeviceState> = _state.asStateFlow()

    init {
        dataClient.addListener(this)
    }

    override fun onCleared() {
        dataClient.removeListener(this)
        super.onCleared()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == WearPaths.STATE_PATH
            ) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                _state.value = WearDeviceState(
                    connected = dataMap.getBoolean("connected", false),
                    deviceName = dataMap.getString("deviceName", ""),
                    ancMode = dataMap.getString("ancMode", "OFF"),
                    ambientLevel = dataMap.getInt("ambientLevel", 0),
                    batteryLeft = dataMap.getInt("batteryLeft", -1),
                    batteryRight = dataMap.getInt("batteryRight", -1),
                    batteryCase = dataMap.getInt("batteryCase", -1),
                    eqPreset = dataMap.getString("eqPreset", "Off"),
                    speakToChat = dataMap.getBoolean("speakToChat", false)
                )
                Log.i(TAG, "State updated from phone: ${_state.value}")
            }
        }
    }

    fun cycleAnc() = viewModelScope.launch {
        commandSender.sendCommand(WearPaths.CMD_CYCLE_ANC)
    }

    fun setAncMode(mode: String) = viewModelScope.launch {
        commandSender.sendCommand(WearPaths.CMD_ANC, mode.toByteArray())
    }

    fun refresh() = viewModelScope.launch {
        commandSender.sendCommand(WearPaths.CMD_REFRESH)
    }
}
