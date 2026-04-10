package com.budcontrol.sony.bluetooth

import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.protocol.SonyParser

data class DeviceState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val lastError: String? = null,
    val connectAttempt: Int = 0,
    val connectMethod: String? = null,
    val protocolResponses: Int = 0,
    val lastRawHex: String? = null,

    val ancMode: SonyCommands.AncMode = SonyCommands.AncMode.OFF,
    val ancEnabled: Boolean = false,
    val ambientLevel: Int = 0,
    val focusOnVoice: Boolean = false,
    val windReduction: Boolean = false,

    val batteryLeft: Int = -1,
    val batteryRight: Int = -1,
    val batteryCase: Int = -1,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,

    val eqPreset: SonyCommands.EqPreset = SonyCommands.EqPreset.OFF,
    val customEqBands: IntArray? = null,

    val speakToChat: Boolean = false
) {
    val batteryAvg: Int
        get() {
            val levels = listOf(batteryLeft, batteryRight).filter { it >= 0 }
            return if (levels.isEmpty()) -1 else levels.average().toInt()
        }

    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED

    fun withNcAsm(state: SonyParser.NcAsmState) = copy(
        ancMode = state.mode,
        ancEnabled = state.enabled,
        ambientLevel = state.ambientLevel,
        focusOnVoice = state.focusOnVoice,
        windReduction = state.windReduction
    )

    fun withBattery(state: SonyParser.BatteryState) = copy(
        batteryLeft = state.left,
        batteryRight = state.right,
        batteryCase = state.case,
        leftCharging = state.leftCharging,
        rightCharging = state.rightCharging,
        caseCharging = state.caseCharging
    )

    fun withEq(state: SonyParser.EqState) = copy(
        eqPreset = state.preset,
        customEqBands = state.customBands
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceState) return false
        return connectionStatus == other.connectionStatus &&
            deviceName == other.deviceName &&
            lastError == other.lastError &&
            connectAttempt == other.connectAttempt &&
            ancMode == other.ancMode &&
            ancEnabled == other.ancEnabled &&
            ambientLevel == other.ambientLevel &&
            focusOnVoice == other.focusOnVoice &&
            windReduction == other.windReduction &&
            batteryLeft == other.batteryLeft &&
            batteryRight == other.batteryRight &&
            batteryCase == other.batteryCase &&
            eqPreset == other.eqPreset &&
            customEqBands.contentEquals(other.customEqBands) &&
            speakToChat == other.speakToChat
    }

    override fun hashCode(): Int = arrayOf(
        connectionStatus, deviceName, lastError, connectAttempt, ancMode,
        ancEnabled, ambientLevel, batteryLeft, batteryRight, batteryCase,
        eqPreset, speakToChat
    ).contentHashCode()
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}
