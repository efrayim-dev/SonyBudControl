package com.budcontrol.sony.protocol

import android.util.Log

/**
 * Parses response payloads from the Sony headphones into typed state objects.
 */
object SonyParser {

    private const val TAG = "SonyParser"

    data class NcAsmState(
        val enabled: Boolean,
        val mode: SonyCommands.AncMode,
        val windReduction: Boolean,
        val ambientLevel: Int,
        val focusOnVoice: Boolean
    )

    data class BatteryState(
        val left: Int,      // 0–100 or -1 if unavailable
        val right: Int,
        val case: Int,
        val leftCharging: Boolean,
        val rightCharging: Boolean,
        val caseCharging: Boolean
    )

    data class EqState(
        val preset: SonyCommands.EqPreset,
        val customBands: IntArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EqState) return false
            return preset == other.preset && customBands.contentEquals(other.customBands)
        }
        override fun hashCode(): Int = 31 * preset.hashCode() + (customBands?.contentHashCode() ?: 0)
    }

    sealed class ParsedResponse {
        data class NcAsm(val state: NcAsmState) : ParsedResponse()
        data class Battery(val state: BatteryState) : ParsedResponse()
        data class Equalizer(val state: EqState) : ParsedResponse()
        data class SpeakToChat(val enabled: Boolean) : ParsedResponse()
        data object Ack : ParsedResponse()
        data class Unknown(val feature: Byte, val data: ByteArray) : ParsedResponse()
    }

    fun parse(frame: SonyMessage.ParsedFrame): ParsedResponse? {
        if (frame.dataType == SonyMessage.DATA_TYPE_ACK) {
            return ParsedResponse.Ack
        }

        val payload = frame.payload
        if (payload.isEmpty()) return null

        return try {
            when (payload[0]) {
                0x68.toByte() -> parseNcAsm(payload)
                0x22.toByte() -> parseBattery(payload)
                0x58.toByte() -> parseEq(payload)
                0x6C.toByte() -> parseSpeakToChat(payload)
                else -> ParsedResponse.Unknown(payload[0], payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse payload: ${payload.toHexString()}", e)
            ParsedResponse.Unknown(payload.getOrElse(0) { 0 }, payload)
        }
    }

    private fun parseNcAsm(payload: ByteArray): ParsedResponse.NcAsm {
        val enabled = payload.getOrElse(2) { 0 }.toInt() != 0
        val modeRaw = payload.getOrElse(3) { 0 }.toInt()
        val windReduction = payload.getOrElse(5) { 0 }.toInt() != 0
        val ambientLevel = payload.getOrElse(6) { 0 }.toInt() and 0xFF
        val focusOnVoice = payload.getOrElse(7) { 0 }.toInt() != 0

        val mode = when {
            !enabled -> SonyCommands.AncMode.OFF
            modeRaw == 0x02 -> SonyCommands.AncMode.NOISE_CANCELING
            modeRaw == 0x01 -> SonyCommands.AncMode.AMBIENT_SOUND
            else -> SonyCommands.AncMode.OFF
        }

        return ParsedResponse.NcAsm(
            NcAsmState(enabled, mode, windReduction, ambientLevel, focusOnVoice)
        )
    }

    private fun parseBattery(payload: ByteArray): ParsedResponse.Battery {
        // Battery payload format varies by model. Common TWS format:
        // [0x22] [cmd] [count] [type1 level1 charging1] [type2 level2 charging2] ...
        // type: 0x01=left, 0x02=right, 0x03=case
        var left = -1; var right = -1; var case_ = -1
        var leftCharging = false; var rightCharging = false; var caseCharging = false

        val count = payload.getOrElse(2) { 0 }.toInt() and 0xFF
        var offset = 3
        repeat(count) {
            if (offset + 2 >= payload.size) return@repeat
            val type = payload[offset].toInt() and 0xFF
            val level = payload[offset + 1].toInt() and 0xFF
            val charging = payload[offset + 2].toInt() != 0
            when (type) {
                0x01 -> { left = level; leftCharging = charging }
                0x02 -> { right = level; rightCharging = charging }
                0x03 -> { case_ = level; caseCharging = charging }
            }
            offset += 3
        }

        return ParsedResponse.Battery(
            BatteryState(left, right, case_, leftCharging, rightCharging, caseCharging)
        )
    }

    private fun parseEq(payload: ByteArray): ParsedResponse.Equalizer {
        val presetId = payload.getOrElse(2) { 0 }
        val preset = SonyCommands.EqPreset.fromId(presetId)
        val bands = if (preset == SonyCommands.EqPreset.CUSTOM && payload.size >= 8) {
            IntArray(5) { payload[3 + it].toInt() }
        } else null

        return ParsedResponse.Equalizer(EqState(preset, bands))
    }

    private fun parseSpeakToChat(payload: ByteArray): ParsedResponse.SpeakToChat {
        val enabled = payload.getOrElse(2) { 0 }.toInt() != 0
        return ParsedResponse.SpeakToChat(enabled)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }
}
