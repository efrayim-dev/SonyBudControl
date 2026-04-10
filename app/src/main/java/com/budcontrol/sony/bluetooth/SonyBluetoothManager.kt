package com.budcontrol.sony.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.protocol.SonyMessage
import com.budcontrol.sony.protocol.SonyParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class SonyBluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "SonyBT"
        val SONY_RFCOMM_UUID: UUID = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")
        private const val RECONNECT_DELAY_MS = 5000L
        private const val READ_BUFFER_SIZE = 2048
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager.adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var autoReconnect = true
    private var targetDevice: BluetoothDevice? = null

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /**
     * Find paired Sony headphones/earbuds. Matches common Sony BT names.
     */
    fun findPairedSonyDevices(): List<BluetoothDevice> {
        val adapter = btAdapter ?: return emptyList()
        return adapter.bondedDevices
            .filter { device ->
                val name = device.name?.lowercase() ?: ""
                name.contains("sony") ||
                    name.contains("wf-") ||
                    name.contains("wh-") ||
                    name.contains("linkbuds") ||
                    name.contains("xm")
            }
            .sortedByDescending { it.name?.contains("XM", ignoreCase = true) == true }
    }

    fun connect(device: BluetoothDevice) {
        targetDevice = device
        autoReconnect = true
        scope.launch { connectInternal(device) }
    }

    fun disconnect() {
        autoReconnect = false
        targetDevice = null
        closeSocket()
        _state.value = DeviceState()
    }

    fun reconnect() {
        targetDevice?.let { connect(it) }
    }

    private suspend fun connectInternal(device: BluetoothDevice) {
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.CONNECTING,
            deviceName = device.name,
            deviceAddress = device.address
        )

        try {
            closeSocket()
            SonyMessage.resetSeq()

            socket = device.createRfcommSocketToServiceRecord(SONY_RFCOMM_UUID)
            btAdapter?.cancelDiscovery()
            socket!!.connect()

            inputStream = socket!!.inputStream
            outputStream = socket!!.outputStream

            Log.i(TAG, "Connected to ${device.name}")
            _state.value = _state.value.copy(connectionStatus = ConnectionStatus.CONNECTED)

            sendBytes(SonyCommands.initHandshake())
            delay(300)

            requestAllStatus()
            startReadLoop()
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed: ${e.message}")
            closeSocket()
            _state.value = _state.value.copy(connectionStatus = ConnectionStatus.DISCONNECTED)

            if (autoReconnect) {
                delay(RECONNECT_DELAY_MS)
                _state.value = _state.value.copy(connectionStatus = ConnectionStatus.RECONNECTING)
                connectInternal(device)
            }
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            val accum = mutableListOf<Byte>()
            try {
                while (isActive && socket?.isConnected == true) {
                    val input = inputStream ?: break
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break

                    accum.addAll(buffer.take(bytesRead).map { it })

                    val accumulated = accum.toByteArray()
                    val frames = SonyMessage.extractFrames(accumulated)

                    if (frames.isNotEmpty()) {
                        // Find last END_MARKER position to trim processed bytes
                        val lastEnd = accumulated.lastIndexOf(SonyMessage.END_MARKER)
                        accum.clear()
                        if (lastEnd >= 0 && lastEnd + 1 < accumulated.size) {
                            accum.addAll(accumulated.drop(lastEnd + 1).map { it })
                        }

                        for (frameBytes in frames) {
                            handleFrame(frameBytes)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Read loop ended: ${e.message}")
            }

            onDisconnected()
        }
    }

    private suspend fun handleFrame(frameBytes: ByteArray) {
        val frame = SonyMessage.parseFrame(frameBytes) ?: return

        // Send ACK for non-ACK messages
        if (frame.dataType != SonyMessage.DATA_TYPE_ACK) {
            sendBytes(SonyMessage.buildAck(frame.seqNum))
        }

        val response = SonyParser.parse(frame) ?: return

        when (response) {
            is SonyParser.ParsedResponse.NcAsm -> {
                _state.value = _state.value.withNcAsm(response.state)
            }
            is SonyParser.ParsedResponse.Battery -> {
                _state.value = _state.value.withBattery(response.state)
            }
            is SonyParser.ParsedResponse.Equalizer -> {
                _state.value = _state.value.withEq(response.state)
            }
            is SonyParser.ParsedResponse.SpeakToChat -> {
                _state.value = _state.value.copy(speakToChat = response.enabled)
            }
            is SonyParser.ParsedResponse.Ack -> { /* expected */ }
            is SonyParser.ParsedResponse.Unknown -> {
                Log.d(TAG, "Unknown response feature: 0x${"%02X".format(response.feature)}")
            }
        }
    }

    private fun onDisconnected() {
        closeSocket()
        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.DISCONNECTED)

        if (autoReconnect) {
            scope.launch {
                delay(RECONNECT_DELAY_MS)
                targetDevice?.let {
                    _state.value = _state.value.copy(connectionStatus = ConnectionStatus.RECONNECTING)
                    connectInternal(it)
                }
            }
        }
    }

    // ── Public command methods ──────────────────────────────────────

    fun setAncMode(mode: SonyCommands.AncMode) {
        scope.launch {
            val cmd = when (mode) {
                SonyCommands.AncMode.NOISE_CANCELING -> SonyCommands.setNoiseCanceling(_state.value.windReduction)
                SonyCommands.AncMode.AMBIENT_SOUND -> SonyCommands.setAmbientSound(_state.value.ambientLevel, _state.value.focusOnVoice)
                SonyCommands.AncMode.OFF -> SonyCommands.setAncOff()
            }
            sendBytes(cmd)
        }
    }

    fun cycleAncMode() {
        val next = when (_state.value.ancMode) {
            SonyCommands.AncMode.NOISE_CANCELING -> SonyCommands.AncMode.AMBIENT_SOUND
            SonyCommands.AncMode.AMBIENT_SOUND -> SonyCommands.AncMode.OFF
            SonyCommands.AncMode.OFF -> SonyCommands.AncMode.NOISE_CANCELING
        }
        setAncMode(next)
    }

    fun setAmbientLevel(level: Int) {
        scope.launch {
            sendBytes(SonyCommands.setAmbientSound(level, _state.value.focusOnVoice))
        }
    }

    fun setFocusOnVoice(enabled: Boolean) {
        scope.launch {
            sendBytes(SonyCommands.setAmbientSound(_state.value.ambientLevel, enabled))
        }
    }

    fun setWindReduction(enabled: Boolean) {
        scope.launch {
            sendBytes(SonyCommands.setNoiseCanceling(enabled))
        }
    }

    fun setEqPreset(preset: SonyCommands.EqPreset) {
        scope.launch { sendBytes(SonyCommands.setEqPreset(preset)) }
    }

    fun setCustomEq(bands: IntArray) {
        scope.launch { sendBytes(SonyCommands.setCustomEq(bands)) }
    }

    fun setSpeakToChat(enabled: Boolean) {
        scope.launch { sendBytes(SonyCommands.setSpeakToChat(enabled)) }
    }

    fun requestAllStatus() {
        scope.launch {
            sendBytes(SonyCommands.getNcAsmStatus())
            delay(100)
            sendBytes(SonyCommands.getBatteryStatus())
            delay(100)
            sendBytes(SonyCommands.getEqStatus())
            delay(100)
            sendBytes(SonyCommands.getSpeakToChatStatus())
        }
    }

    // ── Low-level I/O ──────────────────────────────────────────────

    private suspend fun sendBytes(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Send failed: ${e.message}")
            }
        }
    }

    private fun closeSocket() {
        readJob?.cancel()
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        inputStream = null
        outputStream = null
        socket = null
    }

    fun destroy() {
        autoReconnect = false
        closeSocket()
        scope.cancel()
    }

    private fun ByteArray.lastIndexOf(byte: Byte): Int {
        for (i in lastIndex downTo 0) {
            if (this[i] == byte) return i
        }
        return -1
    }
}
