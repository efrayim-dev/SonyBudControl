package com.budcontrol.sony.protocol

/**
 * Sony V2 protocol command builders.
 *
 * Derived from Gadgetbridge's SonyProtocolImplV2 (GPLv3, José Rebelo)
 * and Bluetooth HCI captures of the WF-1000XM5.
 *
 * Message types (first byte in payload, confusingly called "PayloadType" in GB):
 *   AMBIENT_SOUND_CONTROL_GET  = 0x66   (MessageType COMMAND_1)
 *   AMBIENT_SOUND_CONTROL_RET  = 0x67
 *   AMBIENT_SOUND_CONTROL_SET  = 0x68
 *   AMBIENT_SOUND_CONTROL_NOTIFY = 0x69
 *   EQUALIZER_GET              = 0x56
 *   EQUALIZER_RET              = 0x57
 *   EQUALIZER_SET              = 0x58
 *   BATTERY_LEVEL_REQUEST (V2) = 0x22
 *   BATTERY_LEVEL_REPLY   (V2) = 0x23
 *   BATTERY_LEVEL_NOTIFY  (V2) = 0x25
 *   AUTOMATIC_POWER_OFF_BUTTON_MODE_GET = 0xF6  (Speak-to-Chat, etc.)
 *   AUTOMATIC_POWER_OFF_BUTTON_MODE_SET = 0xF8
 *
 * V2 uses sub-bytes 0x15 / 0x17 in ANC payloads to indicate whether
 * wind-noise-cancelling data is included.
 */
object SonyCommands {

    // ── ANC / Ambient Sound ─────────────────────────────────────────

    enum class AncMode(val displayName: String) {
        NOISE_CANCELING("Noise Canceling"),
        WIND_NOISE_REDUCTION("Wind Noise Reduction"),
        AMBIENT_SOUND("Ambient Sound"),
        OFF("Off")
    }

    /**
     * Request current NC/Ambient Sound status.
     * Sub-byte 0x17 = include wind-noise info (V2 / XM5).
     */
    fun getNcAsmStatus(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x66, 0x17)
    )

    /**
     * Set Ambient Sound Control (V2 format matching Gadgetbridge).
     *
     * Payload: [0x68, sub, 0x01, ncEnabled, ambientMode, windByte, focusVoice, ambientLevel]
     *   sub = 0x17 (with wind noise data)
     *   0x01 = "committed" (vs 0x00 for dragging slider)
     *   ncEnabled: 0x00=off, 0x01=on
     *   ambientMode: 0x00=noise cancel, 0x01=ambient sound
     *   windByte: 0x02=normal, 0x03=wind noise reduction
     *   focusVoice: 0x00=off, 0x01=on
     *   ambientLevel: 0-20
     */
    fun setNoiseCanceling(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x00, 0x02, 0x00, 0x00)
    )

    fun setWindNoiseReduction(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x00, 0x03, 0x00, 0x00)
    )

    fun setAmbientSound(level: Int = 15, focusOnVoice: Boolean = false): ByteArray {
        val lvl = level.coerceIn(0, 20).toByte()
        val voice: Byte = if (focusOnVoice) 0x01 else 0x00
        return SonyMessage.buildCommand(
            byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x01, 0x02, voice, lvl)
        )
    }

    fun setAncOff(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x68, 0x17, 0x01, 0x00, 0x00, 0x02, 0x00, 0x00)
    )

    fun setAncMode(mode: AncMode, ambientLevel: Int = 15, focusOnVoice: Boolean = false): ByteArray {
        return when (mode) {
            AncMode.NOISE_CANCELING -> setNoiseCanceling()
            AncMode.WIND_NOISE_REDUCTION -> setWindNoiseReduction()
            AncMode.AMBIENT_SOUND -> setAmbientSound(ambientLevel, focusOnVoice)
            AncMode.OFF -> setAncOff()
        }
    }

    // ── Equalizer ───────────────────────────────────────────────────

    enum class EqPreset(val id: Byte, val displayName: String) {
        OFF(0x00, "Off"),
        BRIGHT(0x10, "Bright"),
        EXCITED(0x11, "Excited"),
        MELLOW(0x12, "Mellow"),
        RELAXED(0x13, "Relaxed"),
        VOCAL(0x14, "Vocal"),
        TREBLE_BOOST(0x15, "Treble Boost"),
        BASS_BOOST(0x16, "Bass Boost"),
        SPEECH(0x17, "Speech"),
        CUSTOM(0xA0.toByte(), "Custom");

        companion object {
            fun fromId(id: Byte): EqPreset = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    fun getEqStatus(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x56, 0x00)
    )

    fun setEqPreset(preset: EqPreset): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x58, 0x00, preset.id, 0x00)
    )

    /**
     * Set custom 5-band EQ (V2 format).
     * Values are 0-20, with 10 being neutral. Input is -10..+10, shifted to 0..20.
     */
    fun setCustomEq(bands: IntArray): ByteArray {
        require(bands.size == 5) { "Custom EQ requires exactly 5 bands" }
        val shifted = bands.map { (it.coerceIn(-10, 10) + 10).toByte() }.toByteArray()
        return SonyMessage.buildCommand(
            byteArrayOf(0x58, 0x00, 0xA0.toByte(), 0x06) + shifted
        )
    }

    // ── Battery (V2 payload types) ──────────────────────────────────

    fun getBatteryDual(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x22, 0x09)
    )

    fun getBatteryCase(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x22, 0x0A)
    )

    // ── Speak-to-Chat (V2) ──────────────────────────────────────────

    fun setSpeakToChat(enabled: Boolean): ByteArray {
        val flag: Byte = if (enabled) 0x00 else 0x01
        return SonyMessage.buildCommand(
            byteArrayOf(0xF8.toByte(), 0x0C, flag, 0x01)
        )
    }

    fun getSpeakToChatStatus(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0xF6.toByte(), 0x0C)
    )

    // ── Button Mode (V2, per-ear touch function assignment) ─────────

    enum class ButtonMode(val wire: Byte, val displayName: String) {
        OFF(0xFF.toByte(), "Off"),
        NOISE_CONTROL(0x35, "Noise Control"),
        AMBIENT_SOUND_CONTROL(0x00, "Ambient Sound Control"),
        PLAYBACK_CONTROL(0x20, "Playback Control");

        companion object {
            fun fromWire(b: Byte): ButtonMode =
                entries.firstOrNull { it.wire == b } ?: OFF
        }
    }

    fun getButtonModes(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0xF6.toByte(), 0x03)
    )

    fun setButtonModes(left: ButtonMode, right: ButtonMode): ByteArray =
        SonyMessage.buildCommand(
            byteArrayOf(0xF8.toByte(), 0x03, 0x02, left.wire, right.wire)
        )

    // ── Initialization ──────────────────────────────────────────────

    fun initRequest(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x00, 0x00)
    )

    // ── Firmware version ────────────────────────────────────────────

    fun getFirmwareVersion(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x04)
    )
}
