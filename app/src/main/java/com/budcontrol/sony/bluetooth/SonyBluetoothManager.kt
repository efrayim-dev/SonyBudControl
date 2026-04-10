package com.budcontrol.sony.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
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

/**
 * Manages Bluetooth RFCOMM connection to Sony WF-1000XM5 earbuds
 * using the V2 protocol derived from Gadgetbridge.
 */
@SuppressLint("MissingPermission")
class SonyBluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "SonyBT"

        // WF-1000XM4/XM5 V2 control UUID — the KEY fix.
        // This is the RFCOMM service for the proprietary control channel.
        private val SONY_V2_UUID = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2")

        // Older V1 UUIDs (WH-1000XM3, etc.)
        private val SONY_V1_UUID_A = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")
        private val SONY_V1_UUID_B = UUID.fromString("ba69e0f5-16e3-2db3-ad46-68503e20cc96")

        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val SKIP_UUIDS = setOf(
            "0000110b", "0000110a", "0000110c", "0000110d", "0000110e",
            "0000111e", "0000111f",
            "00001108", "00001105",
            "00001200",
            "00001800", "00001801",
            "00000000",
        )

        private const val MAX_RETRY_ATTEMPTS = 2
        private const val RECONNECT_DELAY_MS = 5000L
        private const val READ_BUFFER_SIZE = 2048
        private const val SDP_TIMEOUT_MS = 5000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val btAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var connectJob: Job? = null
    private var autoReconnect = true
    private var targetDevice: BluetoothDevice? = null
    private var protocolVersion = 0

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    // ── Device discovery ───────────────────────────────────────────

    fun findPairedSonyDevices(): List<BluetoothDevice> {
        val adapter = btAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices
                .filter { device ->
                    val name = device.name?.lowercase() ?: ""
                    name.contains("sony") || name.contains("wf-") ||
                        name.contains("wh-") || name.contains("linkbuds") ||
                        name.contains("xm")
                }
                .sortedByDescending { it.name?.contains("XM", ignoreCase = true) == true }
        } catch (e: SecurityException) {
            Log.e(TAG, "No Bluetooth permission", e)
            emptyList()
        }
    }

    // ── Connection orchestration ───────────────────────────────────

    fun connect(device: BluetoothDevice) {
        connectJob?.cancel()
        targetDevice = device
        autoReconnect = true
        connectJob = scope.launch { connectSequence(device) }
    }

    fun disconnect() {
        autoReconnect = false
        connectJob?.cancel()
        targetDevice = null
        closeAll()
        _state.value = DeviceState()
    }

    /**
     * Connection strategy optimized for WF-1000XM5:
     *  1. RFCOMM on V2 UUID (956c7b26) — the XM4/XM5 control channel
     *  2. RFCOMM on V1 UUIDs
     *  3. RFCOMM on any non-audio SDP-discovered UUIDs
     *  4. Reflection fallback on common channels
     *
     * BLE GATT is skipped: Sony TWS earbuds expose their control protocol
     * over classic RFCOMM, not BLE GATT.
     */
    private suspend fun connectSequence(device: BluetoothDevice) {
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.CONNECTING,
            deviceName = device.name,
            deviceAddress = device.address,
            lastError = null,
            connectAttempt = _state.value.connectAttempt + 1
        )

        btAdapter?.cancelDiscovery()

        // ── Priority 1: V2 UUID (XM4/XM5) ─────────────────────────
        updateStatus("Connecting (V2 protocol)…")
        val v2sock = tryRfcomm(device, SONY_V2_UUID, secure = true)
            ?: tryRfcomm(device, SONY_V2_UUID, secure = false)
        if (v2sock != null) {
            onConnected(v2sock, device, "RFCOMM V2 (956c7b26)")
            return
        }

        // ── Priority 2: V1 UUIDs ──────────────────────────────────
        updateStatus("Trying V1 protocol…")
        for (uuid in listOf(SONY_V1_UUID_A, SONY_V1_UUID_B)) {
            val sock = tryRfcomm(device, uuid, secure = true)
                ?: tryRfcomm(device, uuid, secure = false)
            if (sock != null) {
                onConnected(sock, device, "RFCOMM V1 (${uuid.toString().take(8)})")
                return
            }
        }

        // ── Priority 3: SDP-discovered UUIDs ──────────────────────
        updateStatus("Scanning services…")
        val discoveredUuids = discoverUuids(device)
        val candidates = discoveredUuids.filter { uuid ->
            val short = uuid.toString().take(8).lowercase()
            short !in SKIP_UUIDS &&
                uuid != SONY_V2_UUID && uuid != SONY_V1_UUID_A &&
                uuid != SONY_V1_UUID_B && uuid != SPP_UUID
        }

        for (uuid in candidates) {
            updateStatus("RFCOMM ${uuid.toString().take(8)}…")
            val sock = tryRfcomm(device, uuid, secure = true)
                ?: tryRfcomm(device, uuid, secure = false)
            if (sock != null) {
                onConnected(sock, device, "RFCOMM SDP (${uuid.toString().take(8)})")
                return
            }
        }

        // ── Priority 4: Reflection fallback ───────────────────────
        updateStatus("Direct channel…")
        for (channel in listOf(1, 2, 3, 5, 10)) {
            val sock = tryReflection(device, channel)
            if (sock != null) {
                onConnected(sock, device, "RFCOMM ch=$channel")
                return
            }
        }

        // ── All failed ────────────────────────────────────────────
        val msg = buildString {
            append("Could not connect to ${device.name}.\n")
            append("Tried V2 UUID + V1 UUIDs + ${candidates.size} SDP services + 5 channels.\n")
            append("Make sure earbuds are on, paired, and Sony app is closed.")
        }
        Log.e(TAG, msg)
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            lastError = msg
        )

        if (autoReconnect && _state.value.connectAttempt < MAX_RETRY_ATTEMPTS) {
            delay(RECONNECT_DELAY_MS)
            if (autoReconnect) {
                _state.value = _state.value.copy(connectionStatus = ConnectionStatus.RECONNECTING)
                connectSequence(device)
            }
        }
    }

    // ── SDP discovery ─────────────────────────────────────────────

    private suspend fun discoverUuids(device: BluetoothDevice): List<UUID> {
        val cached = device.uuids?.map { it.uuid } ?: emptyList()
        if (cached.isNotEmpty()) return cached

        val result = CompletableDeferred<List<UUID>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_UUID) {
                    val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (d?.address == device.address) {
                        val uuids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                                ?.map { it.uuid }
                        } else {
                            @Suppress("DEPRECATION")
                            (intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID) as? Array<*>)
                                ?.filterIsInstance<ParcelUuid>()
                                ?.map { it.uuid }
                        }
                        result.complete(uuids ?: emptyList())
                    }
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID))

        return try {
            device.fetchUuidsWithSdp()
            withTimeout(SDP_TIMEOUT_MS) { result.await() }
        } catch (e: Exception) {
            Log.w(TAG, "SDP discovery failed: ${e.message}")
            emptyList()
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    // ── RFCOMM connection ─────────────────────────────────────────

    private fun tryRfcomm(device: BluetoothDevice, uuid: UUID, secure: Boolean): BluetoothSocket? {
        return try {
            closeSocket()
            val sock = if (secure) {
                device.createRfcommSocketToServiceRecord(uuid)
            } else {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            }
            sock.connect()
            Log.i(TAG, "RFCOMM ok: ${if (secure) "sec" else "insec"} $uuid")
            sock
        } catch (e: IOException) {
            Log.w(TAG, "RFCOMM fail: ${uuid.toString().take(8)} ${e.message}")
            null
        } catch (e: SecurityException) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryReflection(device: BluetoothDevice, channel: Int): BluetoothSocket? {
        return try {
            closeSocket()
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val sock = m.invoke(device, channel) as BluetoothSocket
            sock.connect()
            Log.i(TAG, "RFCOMM reflection ch=$channel ok")
            sock
        } catch (e: Exception) {
            null
        }
    }

    // ── Post-connect flow ─────────────────────────────────────────

    private suspend fun onConnected(sock: BluetoothSocket, device: BluetoothDevice, method: String) {
        socket = sock
        inputStream = sock.inputStream
        outputStream = sock.outputStream
        SonyMessage.resetSeq()

        Log.i(TAG, "Connected: $method")
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.CONNECTED,
            lastError = null,
            connectAttempt = 0,
            connectMethod = method,
            protocolResponses = 0,
            lastRawHex = null
        )

        startReadLoop()

        scope.launch {
            delay(200)
            performHandshake()
        }
    }

    /**
     * Sony protocol initialization:
     *  1. Send init request [0x00, 0x00] as COMMAND_1
     *  2. Wait for reply — 4B payload = V1, 8B = V2
     *  3. Request all status (ANC, battery, EQ)
     */
    private suspend fun performHandshake() {
        try {
            Log.i(TAG, "Sending init request")
            sendBytes(SonyCommands.initRequest())

            delay(1000)

            if (_state.value.protocolResponses > 0) {
                Log.i(TAG, "Init OK (V$protocolVersion), ${_state.value.protocolResponses} responses. Requesting status.")
                requestAllStatus()
            } else {
                Log.w(TAG, "No init response, trying direct status requests")
                sendBytes(SonyCommands.getNcAsmStatus())
                delay(500)
                sendBytes(SonyCommands.getBatteryDual())
                delay(500)

                if (_state.value.protocolResponses == 0) {
                    _state.value = _state.value.copy(
                        lastError = "Connected but no protocol response.\nMethod: ${_state.value.connectMethod}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Handshake error: ${e.message}")
        }
    }

    // ── Read loop ─────────────────────────────────────────────────

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

                    val chunk = buffer.copyOf(bytesRead)
                    val hex = chunk.joinToString(" ") { "%02X".format(it) }
                    Log.i(TAG, "RX (${bytesRead}B): $hex")

                    accum.addAll(chunk.toList())
                    val accumulated = accum.toByteArray()
                    val frames = SonyMessage.extractFrames(accumulated)

                    if (frames.isNotEmpty()) {
                        val lastEnd = findLastIndex(accumulated, SonyMessage.END_MARKER)
                        accum.clear()
                        if (lastEnd >= 0 && lastEnd + 1 < accumulated.size) {
                            accum.addAll(accumulated.drop(lastEnd + 1))
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

    // ── Frame handling ─────────────────────────────────────────────

    private suspend fun handleFrame(frameBytes: ByteArray) {
        val hex = frameBytes.joinToString(" ") { "%02X".format(it) }
        Log.i(TAG, "Frame (${frameBytes.size}B): $hex")

        val frame = SonyMessage.parseFrame(frameBytes)
        if (frame == null) {
            Log.w(TAG, "Unparseable frame")
            _state.value = _state.value.copy(
                protocolResponses = _state.value.protocolResponses + 1,
                lastRawHex = hex.take(60)
            )
            return
        }

        _state.value = _state.value.copy(
            protocolResponses = _state.value.protocolResponses + 1,
            lastRawHex = "type=${"%02X".format(frame.messageType)} ${frame.payload.size}B"
        )

        if (frame.isAck) {
            SonyMessage.updateSeqFromAck(frame.seqNum)
            return
        }

        sendBytes(SonyMessage.buildAck(frame.seqNum))

        val response = SonyParser.parse(frame) ?: return

        when (response) {
            is SonyParser.ParsedResponse.InitReply -> {
                protocolVersion = response.protocolVersion
                Log.i(TAG, "Protocol version: V$protocolVersion")
            }
            is SonyParser.ParsedResponse.NcAsm ->
                _state.value = _state.value.withNcAsm(response.state)
            is SonyParser.ParsedResponse.Battery ->
                _state.value = _state.value.withBattery(response.state)
            is SonyParser.ParsedResponse.Equalizer ->
                _state.value = _state.value.withEq(response.state)
            is SonyParser.ParsedResponse.SpeakToChat ->
                _state.value = _state.value.copy(speakToChat = response.enabled)
            is SonyParser.ParsedResponse.Ack -> {}
            is SonyParser.ParsedResponse.Unknown ->
                Log.d(TAG, "Unknown: 0x${"%02X".format(response.payloadType)} ${response.data.size}B")
        }
    }

    private fun onDisconnected() {
        closeAll()
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
                    connectSequence(it)
                }
            }
        }
    }

    // ── Public command methods ────────────────────────────────────

    fun setAncMode(mode: SonyCommands.AncMode) {
        scope.launch {
            sendBytes(SonyCommands.setAncMode(mode, _state.value.ambientLevel, _state.value.focusOnVoice))
        }
    }

    fun cycleAncMode() {
        val next = when (_state.value.ancMode) {
            SonyCommands.AncMode.NOISE_CANCELING -> SonyCommands.AncMode.AMBIENT_SOUND
            SonyCommands.AncMode.WIND_NOISE_REDUCTION -> SonyCommands.AncMode.AMBIENT_SOUND
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
            delay(200)
            sendBytes(SonyCommands.getBatteryDual())
            delay(200)
            sendBytes(SonyCommands.getBatteryCase())
            delay(200)
            sendBytes(SonyCommands.getEqStatus())
            delay(200)
            sendBytes(SonyCommands.getSpeakToChatStatus())
        }
    }

    // ── I/O ───────────────────────────────────────────────────────

    private suspend fun sendBytes(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val hex = data.joinToString(" ") { "%02X".format(it) }
                Log.i(TAG, "TX (${data.size}B): $hex")
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Write failed: ${e.message}")
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────

    private fun closeSocket() {
        readJob?.cancel()
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        inputStream = null
        outputStream = null
        socket = null
    }

    private fun closeAll() {
        closeSocket()
    }

    fun destroy() {
        autoReconnect = false
        connectJob?.cancel()
        closeAll()
        scope.cancel()
    }

    private fun updateStatus(msg: String) {
        _state.value = _state.value.copy(lastError = msg)
    }

    private fun findLastIndex(array: ByteArray, target: Byte): Int {
        for (i in array.lastIndex downTo 0) {
            if (array[i] == target) return i
        }
        return -1
    }
}
