package com.budcontrol.sony.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Low-level Sony RFCOMM message framing.
 *
 * Wire format (before escape encoding):
 *   [START 0x3E]
 *   [DataType  1B]
 *   [SeqNum    1B]
 *   [Length    4B big-endian — counts from DataType through Checksum inclusive]
 *   [Payload   NB]
 *   [Checksum  1B — (sum of DataType..Payload) & 0xFF]
 *   [END   0x3C]
 *
 * Escape encoding (applied to everything between START and END exclusive):
 *   0x3C → 0x3D 0x1C
 *   0x3D → 0x3D 0x1D
 *   0x3E → 0x3D 0x1E
 */
object SonyMessage {

    const val START_MARKER: Byte = 0x3E
    const val END_MARKER: Byte = 0x3C
    private const val ESCAPE: Byte = 0x3D

    const val DATA_TYPE_ACK: Byte = 0x00
    const val DATA_TYPE_COMMAND: Byte = 0x0C
    const val DATA_TYPE_REPLY: Byte = 0x0E.toByte()
    const val DATA_TYPE_NOTIFY: Byte = 0x09

    private var sequenceCounter: Int = 0

    fun nextSeq(): Byte {
        val s = sequenceCounter.toByte()
        sequenceCounter = (sequenceCounter + 1) and 0xFF
        return s
    }

    fun resetSeq() {
        sequenceCounter = 0
    }

    fun buildFrame(dataType: Byte, payload: ByteArray): ByteArray {
        val seq = nextSeq()
        // length = 1 (dataType) + 1 (seq) + 4 (length field) + payload.size + 1 (checksum)
        val totalLen = 1 + 1 + 4 + payload.size + 1
        val inner = ByteArrayOutputStream(totalLen)
        inner.write(dataType.toInt() and 0xFF)
        inner.write(seq.toInt() and 0xFF)
        val lenBytes = ByteBuffer.allocate(4).putInt(totalLen).array()
        inner.write(lenBytes)
        inner.write(payload)

        val checksum = inner.toByteArray().fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFF
        inner.write(checksum)

        val raw = inner.toByteArray()
        val out = ByteArrayOutputStream(raw.size + 10)
        out.write(START_MARKER.toInt() and 0xFF)
        for (b in raw) {
            escape(b, out)
        }
        out.write(END_MARKER.toInt() and 0xFF)
        return out.toByteArray()
    }

    fun buildCommand(payload: ByteArray): ByteArray =
        buildFrame(DATA_TYPE_COMMAND, payload)

    fun buildAck(seqNum: Byte): ByteArray {
        val inner = byteArrayOf(DATA_TYPE_ACK, seqNum, 0, 0, 0, 4)
        val checksum = inner.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFF
        val raw = inner + byteArrayOf(checksum.toByte())

        val out = ByteArrayOutputStream(raw.size + 4)
        out.write(START_MARKER.toInt() and 0xFF)
        for (b in raw) {
            escape(b, out)
        }
        out.write(END_MARKER.toInt() and 0xFF)
        return out.toByteArray()
    }

    /**
     * Parse a complete frame (START and END markers already stripped, escape-decoded).
     * Returns (dataType, seqNum, payload) or null on checksum failure.
     */
    fun parseFrame(raw: ByteArray): ParsedFrame? {
        if (raw.size < 7) return null
        val dataType = raw[0]
        val seqNum = raw[1]
        val length = ByteBuffer.wrap(raw, 2, 4).int

        if (raw.size < length) return null

        val expectedChecksum = raw.drop(0).take(raw.size - 1)
            .fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFF
        val actualChecksum = raw.last().toInt() and 0xFF

        if (expectedChecksum != actualChecksum) return null

        val payload = raw.sliceArray(6 until raw.size - 1)
        return ParsedFrame(dataType, seqNum, payload)
    }

    /**
     * Extract complete frames from a byte stream. Handles escape decoding.
     * Returns list of decoded frame byte arrays (without START/END markers).
     */
    fun extractFrames(buffer: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i < buffer.size) {
            if (buffer[i] == START_MARKER) {
                val frameData = ByteArrayOutputStream()
                i++
                var escaped = false
                while (i < buffer.size && buffer[i] != END_MARKER) {
                    if (escaped) {
                        frameData.write((buffer[i].toInt() or 0x20) and 0xFF)
                        escaped = false
                    } else if (buffer[i] == ESCAPE) {
                        escaped = true
                    } else {
                        frameData.write(buffer[i].toInt() and 0xFF)
                    }
                    i++
                }
                if (i < buffer.size && buffer[i] == END_MARKER) {
                    frames.add(frameData.toByteArray())
                }
            }
            i++
        }
        return frames
    }

    private fun escape(b: Byte, out: ByteArrayOutputStream) {
        when (b) {
            START_MARKER -> {
                out.write(ESCAPE.toInt() and 0xFF)
                out.write(0x1E)
            }
            END_MARKER -> {
                out.write(ESCAPE.toInt() and 0xFF)
                out.write(0x1C)
            }
            ESCAPE -> {
                out.write(ESCAPE.toInt() and 0xFF)
                out.write(0x1D)
            }
            else -> out.write(b.toInt() and 0xFF)
        }
    }

    data class ParsedFrame(
        val dataType: Byte,
        val seqNum: Byte,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedFrame) return false
            return dataType == other.dataType && seqNum == other.seqNum && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * (31 * dataType.hashCode() + seqNum.hashCode()) + payload.contentHashCode()
    }
}
