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

@SuppressLint("MissingPermission")
class SonyBluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "SonyBT"

        // Known Sony control-channel UUIDs (for both GATT service and RFCOMM SDP)
        private val SONY_UUIDS = listOf(
            UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA"),
            UUID.fromString("ba2e0d46-0568-3e20-cc96-e3f5169b31d2"),
        )
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Standard BT service UUIDs we should NOT try RFCOMM on (they're audio, not data)
        private val SKIP_UUIDS = setOf(
            "0000110b", "0000110a", "0000110c", "0000110d", "0000110e", // A2DP
            "0000111e", "0000111f",                                     // HFP
            "00001108", "00001105",                                     // HSP, OPP
            "00001200",                                                 // PnP
            "00001800", "00001801",                                     // GAP, GATT
        )

        private const val MAX_RETRY_ATTEMPTS = 2
        private const val RECONNECT_DELAY_MS = 6000L
        private const val READ_BUFFER_SIZE = 2048
        private const val BLE_MTU = 512
        private const val SDP_TIMEOUT_MS = 5000L
        private const val BLE_CONNECT_TIMEOUT_MS = 10_000L
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
     * Full connect sequence:
     *  0. SDP discovery — ask the device what services it exposes
     *  1. BLE GATT over LE transport
     *  2. BLE GATT over BR/EDR transport (some Sony devices serve GATT here)
     *  3. BLE GATT over AUTO transport
     *  4. RFCOMM on every discovered SDP UUID (+ known Sony UUIDs)
     *  5. RFCOMM reflection fallback
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

        // ── Phase 0: Discover UUIDs via SDP ────────────────────────
        updateError("Discovering services…")
        val discoveredUuids = discoverUuids(device)
        val diagUuids = discoveredUuids.map { it.toString().take(8) }
        Log.i(TAG, "SDP discovered ${discoveredUuids.size} UUIDs: $diagUuids")

        // ── Phase 1–3: BLE GATT (try LE, BR/EDR, AUTO) ────────────
        val transports = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            listOf(
                BluetoothDevice.TRANSPORT_LE to "LE",
                BluetoothDevice.TRANSPORT_BREDR to "BR/EDR",
                BluetoothDevice.TRANSPORT_AUTO to "AUTO"
            )
        } else {
            listOf(0 to "default")
        }

        for ((transport, label) in transports) {
            updateError("BLE GATT ($label)…")
            Log.i(TAG, "Trying BLE GATT transport=$label")
            val ok = tryBleConnect(device, transport)
            if (ok) {
                Log.i(TAG, "Connected via BLE GATT ($label)")
                return
            }
        }

        // ── Phase 4: RFCOMM on discovered + known UUIDs ────────────
        val rfcommCandidates = buildRfcommUuidList(discoveredUuids)
        Log.i(TAG, "RFCOMM candidates: ${rfcommCandidates.size} UUIDs")

        for (uuid in rfcommCandidates) {
            val shortId = uuid.toString().take(8)
            updateError("RFCOMM $shortId…")
            val sock = tryRfcommConnect(device, uuid, secure = true)
                ?: tryRfcommConnect(device, uuid, secure = false)
            if (sock != null) {
                onRfcommConnected(sock, device)
                return
            }
        }

        // ── Phase 5: Reflection fallback ───────────────────────────
        updateError("RFCOMM direct channel…")
        for (channel in listOf(1, 2, 3, 5, 10)) {
            val sock = tryReflectionConnect(device, channel)
            if (sock != null) {
                onRfcommConnected(sock, device)
                return
            }
        }

        // ── All failed ─────────────────────────────────────────────
        val diagInfo = buildString {
            append("Could not connect to ${device.name}.\n")
            append("SDP found ${discoveredUuids.size} service(s)")
            if (discoveredUuids.isNotEmpty()) {
                append(": ${diagUuids.joinToString()}")
            }
            append(".\n")
            append("Tried 3 BLE transports + ${rfcommCandidates.size} RFCOMM UUIDs + 5 channels.\n")
            append("Try: close Sony app → forget + re-pair earbuds → retry.")
        }
        Log.e(TAG, diagInfo)
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            lastError = diagInfo
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
    //  SDP UUID discovery
    // ══════════════════════════════════════════════════════════════

    private suspend fun discoverUuids(device: BluetoothDevice): List<UUID> {
        // First check cached UUIDs from pairing
        val cached = device.uuids?.map { it.uuid } ?: emptyList()
        if (cached.isNotEmpty()) {
            Log.i(TAG, "Using ${cached.size} cached SDP UUIDs")
            return cached
        }

        // Force a fresh SDP lookup
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
            Log.w(TAG, "SDP discovery failed/timed out: ${e.message}")
            emptyList()
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    private fun buildRfcommUuidList(discovered: List<UUID>): List<UUID> {
        val candidates = mutableListOf<UUID>()

        // Priority 1: known Sony control UUIDs if they show up in SDP
        for (uuid in SONY_UUIDS) {
            if (uuid in discovered) candidates.add(uuid)
        }

        // Priority 2: any discovered UUID that isn't a standard audio/system profile
        for (uuid in discovered) {
            if (uuid in candidates) continue
            val short = uuid.toString().take(8).lowercase()
            if (short in SKIP_UUIDS) continue
            candidates.add(uuid)
        }

        // Priority 3: known Sony UUIDs even if not in SDP (some devices hide them)
        for (uuid in SONY_UUIDS) {
            if (uuid !in candidates) candidates.add(uuid)
        }

        // Priority 4: standard SPP as last resort
        if (SPP_UUID !in candidates) candidates.add(SPP_UUID)

        return candidates
    }

    // ══════════════════════════════════════════════════════════════
    //  BLE GATT
    // ══════════════════════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "BLE state: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "BLE link up, requesting MTU")
                    gatt.requestMtu(BLE_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "BLE link down (status=$status)")
                    if (bleConnected) {
                        bleConnected = false
                        onDisconnected()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "BLE MTU=$mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "BLE service discovery failed: $status")
                return
            }
            logGattServices(gatt)
            findSonyCharacteristics(gatt)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, ch: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleBleData(ch.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleBleData(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS)
                Log.w(TAG, "BLE write status=$status")
        }
    }

    private fun logGattServices(g: BluetoothGatt) {
        Log.i(TAG, "GATT: ${g.services.size} services discovered")
        for (svc in g.services) {
            Log.i(TAG, "  svc ${svc.uuid} (${svc.characteristics.size} chars)")
            for (ch in svc.characteristics) {
                val p = ch.properties
                val flags = buildString {
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                }
                Log.i(TAG, "    ch ${ch.uuid} [$flags]")
            }
        }
    }

    private suspend fun tryBleConnect(device: BluetoothDevice, transport: Int): Boolean {
        closeGatt()
        bleConnected = false
        bleAccum.clear()
        writeCharacteristic = null
        notifyCharacteristic = null

        val result = CompletableDeferred<Boolean>()

        val wrappedCallback = object : BluetoothGattCallback() {
            private var resolved = false

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                gattCallback.onConnectionStateChange(g, status, newState)
                if (newState == BluetoothProfile.STATE_DISCONNECTED && !resolved) {
                    resolved = true
                    result.complete(false)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                gattCallback.onMtuChanged(g, mtu, status)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                gattCallback.onServicesDiscovered(g, status)
                if (!resolved) {
                    resolved = true
                    result.complete(writeCharacteristic != null)
                }
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
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && transport != 0) {
                device.connectGatt(context, false, wrappedCallback, transport)
            } else {
                device.connectGatt(context, false, wrappedCallback)
            }
        }

        val connected = try {
            withTimeout(BLE_CONNECT_TIMEOUT_MS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "BLE timed out (transport=$transport)")
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
        for (svcUuid in SONY_UUIDS) {
            val svc = g.getService(svcUuid) ?: continue
            Log.i(TAG, "Matched known Sony service: $svcUuid")
            findCharsByProperties(svc)
            if (writeCharacteristic != null) break
        }

        // Strategy 2: scan ALL non-generic services for writable + notifiable chars
        if (writeCharacteristic == null) {
            Log.i(TAG, "Scanning all GATT services…")
            for (svc in g.services) {
                val short = svc.uuid.toString().take(8).lowercase()
                if (short in SKIP_UUIDS) continue
                findCharsByProperties(svc)
                if (writeCharacteristic != null) {
                    Log.i(TAG, "Found usable chars in service ${svc.uuid}")
                    break
                }
            }
        }

        // Enable notifications on whatever we found
        val notifyCh = notifyCharacteristic
            ?: writeCharacteristic?.takeIf { ch ->
                val p = ch.properties
                p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            }
        if (notifyCh != null) {
            notifyCharacteristic = notifyCh
            enableNotifications(g, notifyCh)
        }

        Log.i(TAG, "BLE chars: write=${writeCharacteristic?.uuid} notify=${notifyCharacteristic?.uuid}")
    }

    private fun findCharsByProperties(svc: BluetoothGattService) {
        for (ch in svc.characteristics) {
            val p = ch.properties
            val canWrite = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val canNotify = p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

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
            Log.i(TAG, "Notifications enabled on ${ch.uuid}")
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
    //  Classic RFCOMM
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
            Log.i(TAG, "RFCOMM ok: ${if (secure) "sec" else "insec"} $uuid")
            sock
        } catch (e: IOException) {
            Log.w(TAG, "RFCOMM fail: $uuid ${e.message}")
            null
        } catch (e: SecurityException) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryReflectionConnect(device: BluetoothDevice, channel: Int): BluetoothSocket? {
        return try {
            closeSocket()
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val sock = m.invoke(device, channel) as BluetoothSocket
            sock.connect()
            Log.i(TAG, "RFCOMM reflection ch=$channel ok")
            sock
        } catch (e: Exception) {
            Log.w(TAG, "RFCOMM reflection ch=$channel fail: ${e.message}")
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
                Log.w(TAG, "RFCOMM read ended: ${e.message}")
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

    // ── I/O dispatch ───────────────────────────────────────────────

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
                g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
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
