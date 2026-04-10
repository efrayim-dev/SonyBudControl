package com.budcontrol.sony.wear

import android.content.Context
import android.util.Log
import com.budcontrol.sony.bluetooth.ConnectionStatus
import com.budcontrol.sony.bluetooth.DeviceState
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.tasks.await

class PhoneStateSync(private val context: Context) {

    companion object {
        private const val TAG = "PhoneStateSync"
    }

    private val dataClient = Wearable.getDataClient(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startSyncing(stateFlow: StateFlow<DeviceState>) {
        scope.launch {
            stateFlow.collectLatest { state ->
                syncState(state)
            }
        }
    }

    private suspend fun syncState(state: DeviceState) {
        try {
            val putDataReq = PutDataMapRequest.create(WearPaths.STATE_PATH).apply {
                dataMap.putBoolean("connected", state.connectionStatus == ConnectionStatus.CONNECTED)
                dataMap.putString("deviceName", state.deviceName ?: "")
                dataMap.putString("ancMode", state.ancMode.name)
                dataMap.putInt("ambientLevel", state.ambientLevel)
                dataMap.putInt("batteryLeft", state.batteryLeft)
                dataMap.putInt("batteryRight", state.batteryRight)
                dataMap.putInt("batteryCase", state.batteryCase)
                dataMap.putString("eqPreset", state.eqPreset.displayName)
                dataMap.putBoolean("speakToChat", state.speakToChat)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(putDataReq).await()
            Log.d(TAG, "State synced to watch")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync state: ${e.message}")
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
