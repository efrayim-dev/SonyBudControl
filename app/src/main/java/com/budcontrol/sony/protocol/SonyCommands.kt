package com.budcontrol.sony.protocol

/**
 * Command builders for the Sony proprietary Bluetooth protocol.
 *
 * These byte sequences are derived from reverse-engineering efforts
 * (SonyHeadphonesClient, OpenSCQ30, and Wireshark captures).
 * Tested primarily against WF-1000XM4/XM5 and WH-1000XM4/XM5.
 */
object SonyCommands {

    // ── Feature identifiers ──────────────────────────────────────────

    private const val FEATURE_NC_ASM: Byte = 0x68
    private const val FEATURE_EQ: Byte = 0x58
    private const val FEATURE_BATTERY: Byte = 0x22

    private const val CMD_GET: Byte = 0x01
    private const val CMD_SET: Byte = 0x02
    private const val CMD_NOTIFY: Byte = 0x0D

    // ── ANC / Ambient Sound ─────────────────────────────────────────

    enum class AncMode(val displayName: String) {
        NOISE_CANCELING("Noise Canceling"),
        AMBIENT_SOUND("Ambient Sound"),
        OFF("Off")
    }

    /**
     * Request current NC/Ambient Sound status.
     */
    fun getNcAsmStatus(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(FEATURE_NC_ASM, CMD_GET)
    )

    /**
     * Set Noise Canceling mode ON (full NC, no ambient, no wind reduction).
     */
    fun setNoiseCanceling(windReduction: Boolean = false): ByteArray {
        val windByte: Byte = if (windReduction) 0x01 else 0x00
        return SonyMessage.buildCommand(
            byteArrayOf(
                FEATURE_NC_ASM, CMD_SET,
                0x11,           // NC/ASM enabled
                0x02,           // Mode = Noise Canceling
                0x00,           // NC adjustment mode (auto)
                windByte,       // Wind noise reduction
                0x01,           // ASM amount (ignored in NC mode)
                0x00            // ASM voice focus off (ignored)
            )
        )
    }

    /**
     * Set Ambient Sound mode with configurable level and voice focus.
     * @param level 0–20 (0 = least ambient, 20 = maximum)
     * @param focusOnVoice highlight human voices in ambient pass-through
     */
    fun setAmbientSound(level: Int = 15, focusOnVoice: Boolean = false): ByteArray {
        val clampedLevel = level.coerceIn(0, 20).toByte()
        val voiceByte: Byte = if (focusOnVoice) 0x01 else 0x00
        return SonyMessage.buildCommand(
            byteArrayOf(
                FEATURE_NC_ASM, CMD_SET,
                0x11,               // NC/ASM enabled
                0x01,               // Mode = Ambient Sound
                0x00,               // NC adjustment (unused)
                0x00,               // Wind reduction off
                clampedLevel,       // Ambient level
                voiceByte           // Focus on voice
            )
        )
    }

    /**
     * Turn ANC and Ambient Sound OFF (normal audio passthrough via driver).
     */
    fun setAncOff(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(
            FEATURE_NC_ASM, CMD_SET,
            0x00,       // NC/ASM disabled
            0x00,       // Mode = Off
            0x00, 0x00, 0x00, 0x00
        )
    )

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
        byteArrayOf(FEATURE_EQ, CMD_GET)
    )

    fun setEqPreset(preset: EqPreset): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(FEATURE_EQ, CMD_SET, preset.id)
    )

    /**
     * Set custom 5-band EQ. Each band is a signed value from -10 to +10.
     * Bands: [400Hz, 1kHz, 2.5kHz, 6.3kHz, 16kHz]
     */
    fun setCustomEq(bands: IntArray): ByteArray {
        require(bands.size == 5) { "Custom EQ requires exactly 5 bands" }
        val bandBytes = bands.map { it.coerceIn(-10, 10).toByte() }.toByteArray()
        return SonyMessage.buildCommand(
            byteArrayOf(FEATURE_EQ, CMD_SET, EqPreset.CUSTOM.id) + bandBytes
        )
    }

    // ── Battery ─────────────────────────────────────────────────────

    fun getBatteryStatus(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(FEATURE_BATTERY, CMD_GET)
    )

    // ── Speak-to-Chat ───────────────────────────────────────────────

    fun setSpeakToChat(enabled: Boolean): ByteArray {
        val flag: Byte = if (enabled) 0x01 else 0x00
        return SonyMessage.buildCommand(
            byteArrayOf(0x6C, CMD_SET, flag)
        )
    }

    fun getSpeakToChatStatus(): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x6C, CMD_GET)
    )

    // ── Auto Power Off ──────────────────────────────────────────────

    enum class AutoPowerOff(val code: Byte, val displayName: String) {
        OFF(0x11, "Off"),
        AFTER_5_MIN(0x00, "5 min"),
        AFTER_30_MIN(0x01, "30 min"),
        AFTER_60_MIN(0x02, "60 min"),
        AFTER_180_MIN(0x03, "3 hours");

        companion object {
            fun fromCode(code: Byte): AutoPowerOff = entries.firstOrNull { it.code == code } ?: OFF
        }
    }

    fun setAutoPowerOff(setting: AutoPowerOff): ByteArray = SonyMessage.buildCommand(
        byteArrayOf(0x28, CMD_SET, setting.code)
    )

    // ── Initialization handshake ────────────────────────────────────

    /**
     * Initial handshake payload sent after RFCOMM connection.
     * Tells the device what protocol capabilities the host supports.
     */
    fun initHandshake(): ByteArray = SonyMessage.buildFrame(
        dataType = 0x01,
        payload = byteArrayOf(0x00, 0x00)
    )
}
