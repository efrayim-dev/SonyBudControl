package com.budcontrol.sony.protocol

import android.util.Log

/**
 * Parses V2 protocol response payloads from Sony headphones.
 * Based on Gadgetbridge SonyProtocolImplV2 (GPLv3).
 */
object SonyParser {

    private const val TAG = "SonyParser"

    data class NcAsmState(
        val enabled: Boolean,
        val mode: SonyCommands.AncMode,
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

    data class ButtonModeState(
        val left: SonyCommands.ButtonMode,
        val right: SonyCommands.ButtonMode
    )

    sealed class ParsedResponse {
        data class NcAsm(val state: NcAsmState) : ParsedResponse()
        data class Battery(val state: BatteryState) : ParsedResponse()
        data class Equalizer(val state: EqState) : ParsedResponse()
        data class SpeakToChat(val enabled: Boolean) : ParsedResponse()
        data class ButtonModes(val state: ButtonModeState) : ParsedResponse()
        data class InitReply(val protocolVersion: Int, val rawPayload: ByteArray) : ParsedResponse()
        data object Ack : ParsedResponse()
        data class Unknown(val payloadType: Byte, val data: ByteArray) : ParsedResponse()
    }

    fun parse(frame: SonyMessage.ParsedFrame): ParsedResponse? {
        if (frame.isAck) {
            return ParsedResponse.Ack
        }

        val payload = frame.payload
        if (payload.isEmpty()) return null

        return try {
            when (payload[0].toInt() and 0xFF) {
                0x01 -> parseInitReply(payload)

                0x67, 0x69 -> parseNcAsm(payload)

                0x23, 0x25 -> parseBatteryV2(payload)
                0x11, 0x13 -> parseBatteryV1(payload)

                0x57, 0x59 -> parseEq(payload)

                0xF7, 0xF9 -> parseAutoPowerButtonMode(payload)

                else -> {
                    Log.d(TAG, "Unknown payloadType: 0x${"%02X".format(payload[0])} (${payload.size}B)")
                    ParsedResponse.Unknown(payload[0], payload)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse payload: ${payload.toHexString()}", e)
            ParsedResponse.Unknown(payload.getOrElse(0) { 0 }, payload)
        }
    }

    // ── Init Reply ──────────────────────────────────────────────────

    private fun parseInitReply(payload: ByteArray): ParsedResponse.InitReply {
        val version = if (payload.size == 8) 2 else 1
        Log.i(TAG, "Init reply: V$version (${payload.size}B) ${payload.toHexString()}")
        return ParsedResponse.InitReply(version, payload)
    }

    // ── Ambient Sound Control (V2) ──────────────────────────────────

    private fun parseNcAsm(payload: ByteArray): ParsedResponse.NcAsm {
        if (payload.size < 6) {
            Log.w(TAG, "ANC payload too short: ${payload.size}")
            return ParsedResponse.NcAsm(NcAsmState(false, SonyCommands.AncMode.OFF, 0, false))
        }

        val sub = payload[1].toInt() and 0xFF
        val includesWind = sub == 0x17 && payload.size > 7

        val ncEnabled = (payload[3].toInt() and 0xFF) == 0x01

        var mode = SonyCommands.AncMode.OFF
        if (ncEnabled) {
            if (includesWind) {
                val windByte = payload[5].toInt() and 0xFF
                val ambientByte = payload[4].toInt() and 0xFF
                mode = when {
                    windByte == 0x03 || windByte == 0x05 -> SonyCommands.AncMode.WIND_NOISE_REDUCTION
                    windByte == 0x02 && ambientByte == 0x00 -> SonyCommands.AncMode.NOISE_CANCELING
                    windByte == 0x02 && ambientByte == 0x01 -> SonyCommands.AncMode.AMBIENT_SOUND
                    else -> SonyCommands.AncMode.NOISE_CANCELING
                }
            } else {
                val ambientByte = payload[4].toInt() and 0xFF
                mode = when (ambientByte) {
                    0x01 -> SonyCommands.AncMode.AMBIENT_SOUND
                    else -> SonyCommands.AncMode.NOISE_CANCELING
                }
            }
        }

        val focusVoiceIdx = payload.size - 2
        val ambientLevelIdx = payload.size - 1
        val focusOnVoice = (payload[focusVoiceIdx].toInt() and 0xFF) == 0x01
        val ambientLevel = payload[ambientLevelIdx].toInt() and 0xFF

        Log.i(TAG, "ANC: mode=$mode enabled=$ncEnabled ambient=$ambientLevel focus=$focusOnVoice")
        return ParsedResponse.NcAsm(NcAsmState(ncEnabled, mode, ambientLevel, focusOnVoice))
    }

    // ── Battery (V2 format) ─────────────────────────────────────────

    private fun parseBatteryV2(payload: ByteArray): ParsedResponse.Battery {
        if (payload.size < 2) {
            return ParsedResponse.Battery(BatteryState(-1, -1, -1, false, false, false))
        }

        val batteryTypeCode = payload[1].toInt() and 0xFF
        var left = -1; var right = -1; var case_ = -1
        var leftCharging = false; var rightCharging = false; var caseCharging = false

        when (batteryTypeCode) {
            0x09 -> {
                if (payload.size >= 6) {
                    left = payload[2].toInt() and 0xFF
                    leftCharging = (payload[3].toInt() and 0xFF) != 0
                    right = payload[4].toInt() and 0xFF
                    rightCharging = (payload[5].toInt() and 0xFF) != 0
                }
            }
            0x0A -> {
                if (payload.size >= 4) {
                    case_ = payload[2].toInt() and 0xFF
                    caseCharging = (payload[3].toInt() and 0xFF) != 0
                }
            }
            0x01 -> {
                if (payload.size >= 4) {
                    left = payload[2].toInt() and 0xFF
                    leftCharging = (payload[3].toInt() and 0xFF) != 0
                    right = left
                    rightCharging = leftCharging
                }
            }
        }

        Log.i(TAG, "Battery V2: L=$left R=$right C=$case_ (type=0x${"%02X".format(batteryTypeCode)})")
        return ParsedResponse.Battery(BatteryState(left, right, case_, leftCharging, rightCharging, caseCharging))
    }

    private fun parseBatteryV1(payload: ByteArray): ParsedResponse.Battery {
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
                0x03, 0x09, 0x0A -> { case_ = level; caseCharging = charging }
            }
            offset += 3
        }

        Log.i(TAG, "Battery V1: L=$left R=$right C=$case_")
        return ParsedResponse.Battery(BatteryState(left, right, case_, leftCharging, rightCharging, caseCharging))
    }

    // ── Equalizer ───────────────────────────────────────────────────

    private fun parseEq(payload: ByteArray): ParsedResponse.Equalizer {
        if (payload.size < 3) {
            return ParsedResponse.Equalizer(EqState(SonyCommands.EqPreset.OFF, null))
        }

        val presetId = payload[2]
        val preset = SonyCommands.EqPreset.fromId(presetId)

        val bands = if (preset == SonyCommands.EqPreset.CUSTOM && payload.size >= 9) {
            IntArray(5) { (payload[4 + it].toInt() and 0xFF) - 10 }
        } else null

        Log.i(TAG, "EQ: preset=$preset bands=${bands?.toList()}")
        return ParsedResponse.Equalizer(EqState(preset, bands))
    }

    // ── Auto Power / Button Mode / Speak-to-Chat ────────────────────

    private fun parseAutoPowerButtonMode(payload: ByteArray): ParsedResponse? {
        if (payload.size < 3) return null

        return when (payload[1].toInt() and 0xFF) {
            0x03 -> {
                if (payload.size >= 5) {
                    val left = SonyCommands.ButtonMode.fromWire(payload[3])
                    val right = SonyCommands.ButtonMode.fromWire(payload[4])
                    Log.i(TAG, "Button modes: L=$left R=$right")
                    ParsedResponse.ButtonModes(ButtonModeState(left, right))
                } else {
                    ParsedResponse.Unknown(payload[0], payload)
                }
            }
            0x0C -> {
                val disabled = (payload[2].toInt() and 0xFF) == 0x01
                Log.i(TAG, "Speak-to-Chat: ${!disabled}")
                ParsedResponse.SpeakToChat(!disabled)
            }
            else -> {
                Log.d(TAG, "Button mode sub=${"%02X".format(payload[1])}")
                ParsedResponse.Unknown(payload[0], payload)
            }
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }
}
