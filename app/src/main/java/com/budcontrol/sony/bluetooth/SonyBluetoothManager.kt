package com.budcontrol.sony.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
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

        // ── BLE GATT UUIDs (Sony uses these for the control channel) ────
        private val BLE_SERVICE_UUIDS = listOf(
            UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA"),
            UUID.fromString("0000fe89-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("23bb7500-7084-11e4-82f8-0800200c9a66"),
        )

        // Common Sony GATT characteristic patterns — write + notify
        private val BLE_CHAR_WRITE_UUIDS = listOf(
            UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA"),
            UUID.fromString("0000fe8a-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("23bb7503-7084-11e4-82f8-0800200c9a66"),
        )
        private val BLE_CHAR_NOTIFY_UUIDS = listOf(
            UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA"),
            UUID.fromString("0000fe8b-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("23bb7504-7084-11e4-82f8-0800200c9a66"),
        )
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // ── Classic RFCOMM UUIDs ────────────────────────────────────────
        private val RFCOMM_UUIDS = listOf(
            UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA"),
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        )

        private const val MAX_RETRY_ATTEMPTS = 2
        private const val RECONNECT_DELAY_MS = 5000L
        private const val READ_BUFFER_SIZE = 2048
        private const val BLE_MTU = 512
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager.adapter

    // RFCOMM state
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    // BLE GATT state
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var bleConnected = false
    private val bleAccum = mutableListOf<Byte>()

    // Shared state
    private var connectJob: Job? = null
    private var autoReconnect = true
    private var targetDevice: BluetoothDevice? = null
    private var connectionMode: ConnectionMode = ConnectionMode.NONE

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    enum class ConnectionMode { NONE, BLE, RFCOMM }

    // ── Device discovery ───────────────────────────────────────────

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

    // ── Connection orchestration ───────────────────────────────────

    fun connect(device: BluetoothDevice) {
        connectJob?.cancel()
        targetDevice = device
        autoReconnect = true
        connectJob = scope.launch { connectAll(device) }
    }

    fun disconnect() {
        autoReconnect = false
        connectJob?.cancel()
        targetDevice = null
        closeAll()
        _state.value = DeviceState()
    }

    /**
     * Primary connect flow: try BLE GATT first (newer models), then RFCOMM (older).
     */
    private suspend fun connectAll(device: BluetoothDevice) {
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.CONNECTING,
            deviceName = device.name,
            deviceAddress = device.address,
            lastError = null,
            connectAttempt = _state.value.connectAttempt + 1
        )

        btAdapter?.cancelDiscovery()

        // ── Phase 1: BLE GATT ──────────────────────────────────────
        updateError("Connecting via Bluetooth LE…")
        Log.i(TAG, "Phase 1: Attempting BLE GATT to ${device.name}")

        val bleResult = tryBleConnect(device)
        if (bleResult) {
            Log.i(TAG, "BLE GATT connected successfully")
            return
        }

        // ── Phase 2: Classic RFCOMM ────────────────────────────────
        updateError("BLE failed, trying classic Bluetooth…")
        Log.i(TAG, "Phase 2: Attempting RFCOMM to ${device.name}")

        for (uuid in RFCOMM_UUIDS) {
            val sock = tryRfcommConnect(device, uuid, secure = true)
                ?: tryRfcommConnect(device, uuid, secure = false)
            if (sock != null) {
                onRfcommConnected(sock, device)
                return
            }
        }

        // Reflection fallback
        val reflSock = tryReflectionConnect(device)
        if (reflSock != null) {
            onRfcommConnected(reflSock, device)
            return
        }

        // ── All failed ─────────────────────────────────────────────
        val errorMsg = "Could not connect to ${device.name}.\n" +
            "Tried BLE GATT + classic RFCOMM.\n" +
            "Make sure earbuds are on, paired, and no other app holds the connection."
        Log.e(TAG, errorMsg)
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            lastError = errorMsg
        )

        if (autoReconnect && _state.value.connectAttempt < MAX_RETRY_ATTEMPTS) {
            delay(RECONNECT_DELAY_MS)
            if (autoReconnect) {
                _state.value = _state.value.copy(connectionStatus = ConnectionStatus.RECONNECTING)
                connectAll(device)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  BLE GATT
    // ══════════════════════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "BLE onConnectionStateChange: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "BLE connected, requesting MTU $BLE_MTU")
                    gatt.requestMtu(BLE_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "BLE disconnected (status=$status)")
                    if (bleConnected) {
                        bleConnected = false
                        onDisconnected()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "BLE MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "BLE service discovery failed: status=$status")
                return
            }
            Log.i(TAG, "BLE services discovered: ${gatt.services.size} services")
            for (svc in gatt.services) {
                Log.d(TAG, "  Service: ${svc.uuid} (${svc.characteristics.size} chars)")
                for (ch in svc.characteristics) {
                    Log.d(TAG, "    Char: ${ch.uuid} props=${ch.properties}")
                }
            }
            findSonyCharacteristics(gatt)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleBleData(data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleBleData(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "BLE write failed: status=$status")
            }
        }
    }

    private suspend fun tryBleConnect(device: BluetoothDevice): Boolean {
        closeGatt()
        bleConnected = false
        bleAccum.clear()

        val result = CompletableDeferred<Boolean>()

        val wrappedCallback = object : BluetoothGattCallback() {
            private var servicesFound = false

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                gattCallback.onConnectionStateChange(g, status, newState)
                if (newState == BluetoothProfile.STATE_DISCONNECTED && !servicesFound) {
                    result.complete(false)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                gattCallback.onMtuChanged(g, mtu, status)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                gattCallback.onServicesDiscovered(g, status)
                servicesFound = writeCharacteristic != null
                result.complete(servicesFound)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                gattCallback.onCharacteristicChanged(g, c)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
            ) {
                gattCallback.onCharacteristicChanged(g, c, value)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
            ) {
                gattCallback.onCharacteristicWrite(g, c, status)
            }
        }

        withContext(Dispatchers.Main) {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, wrappedCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, wrappedCallback)
            }
        }

        val connected = try {
            withTimeout(12_000) { result.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "BLE connect timed out")
            false
        }

        if (connected) {
            bleConnected = true
            connectionMode = ConnectionMode.BLE
            _state.value = _state.value.copy(
                connectionStatus = ConnectionStatus.CONNECTED,
                lastError = null,
                connectAttempt = 0
            )
            SonyMessage.resetSeq()
            scope.launch {
                delay(500)
                sendBytes(SonyCommands.initHandshake())
                delay(500)
                requestAllStatus()
            }
        } else {
            closeGatt()
        }

        return connected
    }

    private fun findSonyCharacteristics(g: BluetoothGatt) {
        writeCharacteristic = null
        notifyCharacteristic = null

        // Strategy 1: match known Sony service UUIDs
        for (svcUuid in BLE_SERVICE_UUIDS) {
            val svc = g.getService(svcUuid) ?: continue
            Log.i(TAG, "Found known Sony service: $svcUuid")

            for (wUuid in BLE_CHAR_WRITE_UUIDS) {
                writeCharacteristic = svc.getCharacteristic(wUuid)
                if (writeCharacteristic != null) break
            }
            for (nUuid in BLE_CHAR_NOTIFY_UUIDS) {
                notifyCharacteristic = svc.getCharacteristic(nUuid)
                if (notifyCharacteristic != null) break
            }

            // If exact UUID match failed, find chars by properties
            if (writeCharacteristic == null || notifyCharacteristic == null) {
                findCharsByProperties(svc)
            }
            if (writeCharacteristic != null) break
        }

        // Strategy 2: scan ALL services for writable + notifiable chars
        if (writeCharacteristic == null) {
            Log.i(TAG, "No known Sony service found, scanning all services…")
            for (svc in g.services) {
                findCharsByProperties(svc)
                if (writeCharacteristic != null) {
                    Log.i(TAG, "Found chars in service: ${svc.uuid}")
                    break
                }
            }
        }

        // Enable notifications
        if (notifyCharacteristic != null) {
            enableNotifications(g, notifyCharacteristic!!)
        } else if (writeCharacteristic != null) {
            // Some Sony devices use same char for write + notify
            val props = writeCharacteristic!!.properties
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                notifyCharacteristic = writeCharacteristic
                enableNotifications(g, writeCharacteristic!!)
            }
        }

        if (writeCharacteristic != null) {
            Log.i(TAG, "BLE ready: write=${writeCharacteristic!!.uuid} notify=${notifyCharacteristic?.uuid}")
        } else {
            Log.w(TAG, "No suitable BLE characteristics found")
        }
    }

    private fun findCharsByProperties(svc: BluetoothGattService) {
        for (ch in svc.characteristics) {
            val props = ch.properties
            val canWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

            if (canWrite && writeCharacteristic == null) writeCharacteristic = ch
            if (canNotify && notifyCharacteristic == null) notifyCharacteristic = ch
        }
    }

    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(CCCD_UUID)
        if (desc != null) {
            val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            @Suppress("DEPRECATION")
            desc.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
            Log.i(TAG, "BLE notifications enabled on ${ch.uuid}")
        }
    }

    private fun handleBleData(data: ByteArray) {
        bleAccum.addAll(data.toList())

        val accumulated = bleAccum.toByteArray()
        val frames = SonyMessage.extractFrames(accumulated)

        if (frames.isNotEmpty()) {
            val lastEnd = accumulated.lastIndexOf(SonyMessage.END_MARKER)
            bleAccum.clear()
            if (lastEnd >= 0 && lastEnd + 1 < accumulated.size) {
                bleAccum.addAll(accumulated.drop(lastEnd + 1))
            }
            for (frameBytes in frames) {
                scope.launch { handleFrame(frameBytes) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Classic RFCOMM (fallback for older models)
    // ══════════════════════════════════════════════════════════════

    private fun tryRfcommConnect(device: BluetoothDevice, uuid: UUID, secure: Boolean): BluetoothSocket? {
        return try {
            closeSocket()
            val sock = if (secure) {
                device.createRfcommSocketToServiceRecord(uuid)
            } else {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            }
            sock.connect()
            Log.i(TAG, "RFCOMM connected: ${if (secure) "secure" else "insecure"} UUID=$uuid")
            sock
        } catch (e: IOException) {
            Log.w(TAG, "RFCOMM failed: UUID=$uuid ${e.message}")
            null
        } catch (e: SecurityException) {
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
            Log.i(TAG, "RFCOMM connected via reflection channel 1")
            sock
        } catch (e: Exception) {
            Log.w(TAG, "RFCOMM reflection failed: ${e.message}")
            null
        }
    }

    private suspend fun onRfcommConnected(sock: BluetoothSocket, device: BluetoothDevice) {
        socket = sock
        inputStream = sock.inputStream
        outputStream = sock.outputStream
        connectionMode = ConnectionMode.RFCOMM
        SonyMessage.resetSeq()

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
            Log.w(TAG, "RFCOMM handshake failed: ${e.message}")
        }

        startRfcommReadLoop()
    }

    private fun startRfcommReadLoop() {
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
                Log.w(TAG, "RFCOMM read loop ended: ${e.message}")
            }
            onDisconnected()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Shared protocol handling
    // ══════════════════════════════════════════════════════════════

    private suspend fun handleFrame(frameBytes: ByteArray) {
        val frame = SonyMessage.parseFrame(frameBytes) ?: return

        if (frame.dataType != SonyMessage.DATA_TYPE_ACK) {
            sendBytes(SonyMessage.buildAck(frame.seqNum))
        }

        val response = SonyParser.parse(frame) ?: return

        when (response) {
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
                Log.d(TAG, "Unknown: 0x${"%02X".format(response.feature)} ${response.data.size}B")
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
                    connectAll(it)
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
            delay(200)
            sendBytes(SonyCommands.getBatteryStatus())
            delay(200)
            sendBytes(SonyCommands.getEqStatus())
            delay(200)
            sendBytes(SonyCommands.getSpeakToChatStatus())
        }
    }

    // ── I/O dispatch (BLE or RFCOMM) ───────────────────────────────

    private suspend fun sendBytes(data: ByteArray) {
        when (connectionMode) {
            ConnectionMode.BLE -> sendBle(data)
            ConnectionMode.RFCOMM -> sendRfcomm(data)
            ConnectionMode.NONE -> {}
        }
    }

    private fun sendBle(data: ByteArray) {
        val ch = writeCharacteristic ?: return
        val g = gatt ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                ch.value = data
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE write failed: ${e.message}")
        }
    }

    private suspend fun sendRfcomm(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM write failed: ${e.message}")
            }
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        bleConnected = false
        bleAccum.clear()
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

    private fun closeAll() {
        closeGatt()
        closeSocket()
        connectionMode = ConnectionMode.NONE
    }

    fun destroy() {
        autoReconnect = false
        connectJob?.cancel()
        closeAll()
        scope.cancel()
    }

    private fun ByteArray.lastIndexOf(byte: Byte): Int {
        for (i in lastIndex downTo 0) {
            if (this[i] == byte) return i
        }
        return -1
    }
}
