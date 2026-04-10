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

        // Multiple UUIDs to try — order matters (most specific first)
        private val CONNECT_UUIDS = listOf(
            UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA"),  // Sony proprietary
            UUID.fromString("ba2e0d46-0568-3e20-cc96-e3f5169b31d2"),  // Sony alt (some FW)
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),  // Standard SPP
        )

        private const val MAX_RETRY_ATTEMPTS = 3
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
    private var connectJob: Job? = null
    private var autoReconnect = true
    private var targetDevice: BluetoothDevice? = null

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    fun findPairedSonyDevices(): List<BluetoothDevice> {
        val adapter = btAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices
                .filter { device ->
                    val name = device.name?.lowercase() ?: ""
                    name.contains("sony") ||
                        name.contains("wf-") ||
                        name.contains("wh-") ||
                        name.contains("linkbuds") ||
                        name.contains("xm")
                }
                .sortedByDescending { it.name?.contains("XM", ignoreCase = true) == true }
        } catch (e: SecurityException) {
            Log.e(TAG, "No Bluetooth permission", e)
            emptyList()
        }
    }

    fun connect(device: BluetoothDevice) {
        connectJob?.cancel()
        targetDevice = device
        autoReconnect = true
        connectJob = scope.launch { connectWithFallback(device) }
    }

    fun disconnect() {
        autoReconnect = false
        connectJob?.cancel()
        targetDevice = null
        closeSocket()
        _state.value = DeviceState()
    }

    fun reconnect() {
        targetDevice?.let { connect(it) }
    }

    /**
     * Try connecting with each UUID strategy in order.
     * For each UUID: try secure, then insecure, then reflection-based.
     */
    private suspend fun connectWithFallback(device: BluetoothDevice) {
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.CONNECTING,
            deviceName = device.name,
            deviceAddress = device.address,
            lastError = null,
            connectAttempt = _state.value.connectAttempt + 1
        )

        btAdapter?.cancelDiscovery()

        // Strategy 1: Try each UUID with createRfcommSocketToServiceRecord
        for ((idx, uuid) in CONNECT_UUIDS.withIndex()) {
            val label = when (idx) {
                0 -> "Sony proprietary UUID"
                1 -> "Sony alt UUID"
                else -> "Standard SPP UUID"
            }
            Log.i(TAG, "Trying $label: $uuid")
            updateError("Trying $label…")

            val sock = tryConnect(device, uuid, secure = true)
                ?: tryConnect(device, uuid, secure = false)

            if (sock != null) {
                onSocketConnected(sock, device)
                return
            }
        }

        // Strategy 2: Reflection-based RFCOMM channel (bypasses SDP lookup)
        Log.i(TAG, "Trying reflection-based RFCOMM channel 1")
        updateError("Trying direct RFCOMM channel…")
        val reflectionSock = tryReflectionConnect(device)
        if (reflectionSock != null) {
            onSocketConnected(reflectionSock, device)
            return
        }

        // All strategies failed
        val errorMsg = "Could not open RFCOMM to ${device.name}. " +
            "Make sure the earbuds are on and paired."
        Log.e(TAG, errorMsg)
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            lastError = errorMsg
        )

        if (autoReconnect && _state.value.connectAttempt < MAX_RETRY_ATTEMPTS) {
            delay(RECONNECT_DELAY_MS)
            if (autoReconnect) {
                _state.value = _state.value.copy(connectionStatus = ConnectionStatus.RECONNECTING)
                connectWithFallback(device)
            }
        }
    }

    private fun tryConnect(
        device: BluetoothDevice,
        uuid: UUID,
        secure: Boolean
    ): BluetoothSocket? {
        return try {
            closeSocket()
            val sock = if (secure) {
                device.createRfcommSocketToServiceRecord(uuid)
            } else {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            }
            sock.connect()
            Log.i(TAG, "Connected via ${if (secure) "secure" else "insecure"} RFCOMM, UUID=$uuid")
            sock
        } catch (e: IOException) {
            Log.w(TAG, "Failed ${if (secure) "secure" else "insecure"} RFCOMM UUID=$uuid: ${e.message}")
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for RFCOMM: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryReflectionConnect(device: BluetoothDevice): BluetoothSocket? {
        return try {
            closeSocket()
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val sock = method.invoke(device, 1) as BluetoothSocket
            sock.connect()
            Log.i(TAG, "Connected via reflection RFCOMM channel 1")
            sock
        } catch (e: Exception) {
            Log.w(TAG, "Reflection RFCOMM failed: ${e.message}")
            null
        }
    }

    private suspend fun onSocketConnected(sock: BluetoothSocket, device: BluetoothDevice) {
        socket = sock
        inputStream = sock.inputStream
        outputStream = sock.outputStream
        SonyMessage.resetSeq()

        Log.i(TAG, "Successfully connected to ${device.name}")
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.CONNECTED,
            lastError = null,
            connectAttempt = 0
        )

        try {
            sendBytes(SonyCommands.initHandshake())
            delay(500)
            requestAllStatus()
        } catch (e: IOException) {
            Log.w(TAG, "Post-connect handshake failed: ${e.message}")
        }

        startReadLoop()
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
            is SonyParser.ParsedResponse.Ack -> {}
            is SonyParser.ParsedResponse.Unknown -> {
                Log.d(TAG, "Unknown response: 0x${"%02X".format(response.feature)}, " +
                    "${response.data.size} bytes")
            }
        }
    }

    private fun onDisconnected() {
        closeSocket()
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            lastError = "Connection lost"
        )

        if (autoReconnect) {
            scope.launch {
                delay(RECONNECT_DELAY_MS)
                targetDevice?.let {
                    _state.value = _state.value.copy(
                        connectionStatus = ConnectionStatus.RECONNECTING,
                        connectAttempt = 0
                    )
                    connectWithFallback(it)
                }
            }
        }
    }

    private fun updateError(msg: String) {
        _state.value = _state.value.copy(lastError = msg)
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
        scope.launch { sendBytes(SonyCommands.setAmbientSound(level, _state.value.focusOnVoice)) }
    }

    fun setFocusOnVoice(enabled: Boolean) {
        scope.launch { sendBytes(SonyCommands.setAmbientSound(_state.value.ambientLevel, enabled)) }
    }

    fun setWindReduction(enabled: Boolean) {
        scope.launch { sendBytes(SonyCommands.setNoiseCanceling(enabled)) }
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
            delay(150)
            sendBytes(SonyCommands.getBatteryStatus())
            delay(150)
            sendBytes(SonyCommands.getEqStatus())
            delay(150)
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
        connectJob?.cancel()
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
