package com.budcontrol.sony.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sony proprietary Bluetooth message framing.
 *
 * Wire format:
 *   [0x3E START]
 *   -- escaped region --
 *   [MessageType  1B]   ACK=0x01, COMMAND_1=0x0C, COMMAND_2=0x0E
 *   [SeqNum       1B]
 *   [PayloadLen   4B big-endian]   number of payload bytes that follow
 *   [Payload      NB]
 *   [Checksum     1B]   sum of MessageType..Payload (before escaping)
 *   -- end escaped region --
 *   [0x3C END]
 *
 * Escape encoding (Gadgetbridge-compatible):
 *   0x3C → 0x3D, (0x3C & 0xEF)   → 0x3D 0x2C
 *   0x3D → 0x3D, (0x3D & 0xEF)   → 0x3D 0x2D
 *   0x3E → 0x3D, (0x3E & 0xEF)   → 0x3D 0x2E
 *
 * Unescape: when 0x3D is seen, next byte OR'd with ~0xEF (= 0x10).
 */
object SonyMessage {

    const val START_MARKER: Byte = 0x3E
    const val END_MARKER: Byte = 0x3C
    private const val ESCAPE: Byte = 0x3D
    private const val ESCAPE_MASK: Int = 0xEF  // mask applied to escaped byte

    const val MSG_TYPE_ACK: Byte = 0x01
    const val MSG_TYPE_COMMAND_1: Byte = 0x0C
    const val MSG_TYPE_COMMAND_2: Byte = 0x0E.toByte()

    private var sequenceNumber: Int = 0

    fun currentSeq(): Byte = sequenceNumber.toByte()

    fun nextSeq(): Byte {
        val s = sequenceNumber.toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFF
        return s
    }

    fun resetSeq() {
        sequenceNumber = 0
    }

    fun updateSeqFromAck(ackSeq: Byte) {
        sequenceNumber = (ackSeq.toInt() and 0xFF)
    }

    /**
     * Build a complete framed message: START + escaped(type+seq+len+payload+checksum) + END.
     * Length field = number of payload bytes (matches Gadgetbridge).
     */
    fun buildMessage(messageType: Byte, payload: ByteArray): ByteArray {
        val seq = nextSeq()
        val buf = ByteBuffer.allocate(payload.size + 6)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(messageType)
        buf.put(seq)
        buf.putInt(payload.size)
        buf.put(payload)

        val inner = buf.array()
        return encodeMessage(inner)
    }

    fun buildCommand(payload: ByteArray): ByteArray =
        buildMessage(MSG_TYPE_COMMAND_1, payload)

    fun buildAck(seqNum: Byte): ByteArray {
        val buf = ByteBuffer.allocate(6)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(MSG_TYPE_ACK)
        buf.put((1 - (seqNum.toInt() and 0xFF)).toByte())
        buf.putInt(0)

        return encodeMessage(buf.array())
    }

    /**
     * Wraps raw inner bytes (type+seq+len+payload) with checksum, escaping, and markers.
     */
    private fun encodeMessage(inner: ByteArray): ByteArray {
        val checksum = calcChecksum(inner, 0, inner.size)

        val out = ByteArrayOutputStream(inner.size + 4)
        out.write(START_MARKER.toInt() and 0xFF)
        out.write(escape(inner))
        out.write(escape(byteArrayOf(checksum)))
        out.write(END_MARKER.toInt() and 0xFF)
        return out.toByteArray()
    }

    /**
     * Parse a complete decoded frame (already unescaped, markers stripped).
     * Returns (messageType, seqNum, payload) or null on failure.
     */
    fun parseFrame(raw: ByteArray): ParsedFrame? {
        if (raw.size < 7) return null

        val messageType = raw[0]
        val seqNum = raw[1]

        val payloadLength = ((raw[2].toInt() and 0xFF) shl 24) or
            ((raw[3].toInt() and 0xFF) shl 16) or
            ((raw[4].toInt() and 0xFF) shl 8) or
            (raw[5].toInt() and 0xFF)

        if (payloadLength < 0 || raw.size < 7 + payloadLength) return null

        val expectedChecksum = calcChecksum(raw, 0, raw.size - 1)
        val actualChecksum = raw[raw.size - 1].toInt() and 0xFF

        if ((expectedChecksum.toInt() and 0xFF) != actualChecksum) return null

        val payload = raw.sliceArray(6 until 6 + payloadLength)
        return ParsedFrame(messageType, seqNum, payload)
    }

    /**
     * Extract complete frames from a byte stream (handles escape decoding).
     */
    fun extractFrames(buffer: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i < buffer.size) {
            if (buffer[i] == START_MARKER) {
                val frameData = ByteArrayOutputStream()
                i++
                while (i < buffer.size && buffer[i] != END_MARKER) {
                    if (buffer[i] == ESCAPE) {
                        i++
                        if (i < buffer.size) {
                            frameData.write((buffer[i].toInt() and 0xFF) or (ESCAPE_MASK.inv() and 0xFF))
                        }
                    } else {
                        frameData.write(buffer[i].toInt() and 0xFF)
                    }
                    i++
                }
                if (i < buffer.size && buffer[i] == END_MARKER) {
                    val data = frameData.toByteArray()
                    if (data.size >= 7) {
                        frames.add(data)
                    }
                }
            }
            i++
        }
        return frames
    }

    private fun escape(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        for (b in bytes) {
            when (b) {
                START_MARKER, END_MARKER, ESCAPE -> {
                    out.write(ESCAPE.toInt() and 0xFF)
                    out.write((b.toInt() and ESCAPE_MASK) and 0xFF)
                }
                else -> out.write(b.toInt() and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun calcChecksum(data: ByteArray, start: Int, end: Int): Byte {
        var sum = 0
        for (i in start until end) {
            sum += data[i].toInt() and 0xFF
        }
        return sum.toByte()
    }

    data class ParsedFrame(
        val messageType: Byte,
        val seqNum: Byte,
        val payload: ByteArray
    ) {
        val isAck: Boolean get() = messageType == MSG_TYPE_ACK
        val isCommand1: Boolean get() = messageType == MSG_TYPE_COMMAND_1
        val isCommand2: Boolean get() = messageType == MSG_TYPE_COMMAND_2

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedFrame) return false
            return messageType == other.messageType && seqNum == other.seqNum && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * (31 * messageType.hashCode() + seqNum.hashCode()) + payload.contentHashCode()
    }
}
