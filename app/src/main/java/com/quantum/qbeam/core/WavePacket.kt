package com.quantum.qbeam.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * QBeam wire format — a "wave packet".
 *
 * Every channel (phonon/audio, photon/optical, NFC) transmits the *same* self-describing
 * frames, so a message can in principle be split across channels and reassembled.
 *
 * Frame layout (big-endian):
 *   magic      2 bytes  = 'Q','B'
 *   version    1 byte   = 1
 *   msgId      2 bytes  unsigned, identifies one logical message
 *   total      2 bytes  total number of frames in the message
 *   index      2 bytes  this frame's index (0-based)
 *   payloadLen 2 bytes  length of payload that follows
 *   payload    N bytes
 *   crc32      4 bytes  CRC32 over everything before this field
 *
 * Frame index 0 is the MANIFEST. Frames 1..total-1 carry the data chunks in order.
 */
object WavePacket {

    const val MAGIC0 = 'Q'.code.toByte()
    const val MAGIC1 = 'B'.code.toByte()
    const val VERSION = 1
    const val HEADER_LEN = 11 // magic2 + ver1 + msgId2 + total2 + index2 + payloadLen2
    const val CRC_LEN = 4

    enum class DataType(val code: Int) {
        TEXT(0), IMAGE(1), FILE(2);
        companion object { fun from(c: Int) = entries.firstOrNull { it.code == c } ?: FILE }
    }

    /** A fully reassembled logical message. */
    data class Message(
        val type: DataType,
        val name: String,
        val mimeType: String,
        val data: ByteArray
    )

    /** Build the manifest payload that travels in frame 0. */
    private fun buildManifest(msg: Message): ByteArray {
        val name = msg.name.toByteArray(Charsets.UTF_8)
        val mime = msg.mimeType.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 2 + name.size + 2 + mime.size + 4)
        buf.put(msg.type.code.toByte())
        buf.putShort(name.size.toShort()); buf.put(name)
        buf.putShort(mime.size.toShort()); buf.put(mime)
        buf.putInt(msg.data.size)
        return buf.array()
    }

    private fun parseManifest(p: ByteArray): Triple<DataType, String, Pair<String, Int>> {
        val buf = ByteBuffer.wrap(p)
        val type = DataType.from(buf.get().toInt() and 0xFF)
        val name = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        val mime = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        val dataLen = buf.int
        return Triple(type, String(name, Charsets.UTF_8), String(mime, Charsets.UTF_8) to dataLen)
    }

    /**
     * Serialize a whole message (manifest + data) into one flat blob. Used by channels that
     * carry the payload as a single object rather than fixed frames (e.g. the fountain-coded
     * optical channel). Inverse of [unpackMessage].
     */
    fun packMessage(msg: Message): ByteArray = buildManifest(msg) + msg.data

    /** Parse a blob produced by [packMessage] back into a [Message]. */
    fun unpackMessage(bytes: ByteArray): Message {
        val buf = ByteBuffer.wrap(bytes)
        val type = DataType.from(buf.get().toInt() and 0xFF)
        val name = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        val mime = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        val dataLen = buf.int
        val data = ByteArray(dataLen).also { buf.get(it) }
        return Message(type, String(name, Charsets.UTF_8), String(mime, Charsets.UTF_8), data)
    }

    /**
     * Encode a message into an ordered list of serialized frames.
     * @param chunkSize bytes of message data per frame (channel chooses this).
     */
    fun encode(msg: Message, msgId: Int, chunkSize: Int): List<ByteArray> {
        val manifest = buildManifest(msg)
        val chunks = ArrayList<ByteArray>()
        var off = 0
        while (off < msg.data.size) {
            val end = minOf(off + chunkSize, msg.data.size)
            chunks.add(msg.data.copyOfRange(off, end))
            off = end
        }
        if (chunks.isEmpty()) chunks.add(ByteArray(0)) // allow empty payload
        val total = 1 + chunks.size
        val frames = ArrayList<ByteArray>(total)
        frames.add(serializeFrame(msgId, total, 0, manifest))
        chunks.forEachIndexed { i, c -> frames.add(serializeFrame(msgId, total, i + 1, c)) }
        return frames
    }

    fun serializeFrame(msgId: Int, total: Int, index: Int, payload: ByteArray): ByteArray {
        val body = ByteBuffer.allocate(HEADER_LEN + payload.size)
        body.put(MAGIC0); body.put(MAGIC1); body.put(VERSION.toByte())
        body.putShort(msgId.toShort())
        body.putShort(total.toShort())
        body.putShort(index.toShort())
        body.putShort(payload.size.toShort())
        body.put(payload)
        val crc = CRC32().apply { update(body.array()) }.value
        val out = ByteBuffer.allocate(body.capacity() + CRC_LEN)
        out.put(body.array())
        out.putInt(crc.toInt())
        return out.array()
    }

    /** Parsed frame; null if the bytes are not a valid, CRC-checked frame. */
    data class Frame(val msgId: Int, val total: Int, val index: Int, val payload: ByteArray)

    fun parseFrame(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_LEN + CRC_LEN) return null
        if (bytes[0] != MAGIC0 || bytes[1] != MAGIC1) return null
        val buf = ByteBuffer.wrap(bytes)
        buf.position(3) // skip magic + version
        val msgId = buf.short.toInt() and 0xFFFF
        val total = buf.short.toInt() and 0xFFFF
        val index = buf.short.toInt() and 0xFFFF
        val payloadLen = buf.short.toInt() and 0xFFFF
        if (bytes.size < HEADER_LEN + payloadLen + CRC_LEN) return null
        val payload = ByteArray(payloadLen).also { buf.get(it) }
        val expectedCrc = buf.int
        val actualCrc = CRC32().apply {
            update(bytes, 0, HEADER_LEN + payloadLen)
        }.value.toInt()
        if (expectedCrc != actualCrc) return null
        return Frame(msgId, total, index, payload)
    }

    /**
     * Collects frames for one or more messages and reassembles them when complete.
     * Thread-safe for a single consumer feeding [offer] from a decode loop.
     */
    class Reassembler {
        private val parts = HashMap<Int, Array<ByteArray?>>() // msgId -> frames
        private val totals = HashMap<Int, Int>()

        /** @return a [Message] when the offered frame completes one, else null. */
        @Synchronized
        fun offer(frame: Frame): Message? {
            val arr = parts.getOrPut(frame.msgId) {
                totals[frame.msgId] = frame.total
                arrayOfNulls(frame.total)
            }
            if (frame.index < arr.size) arr[frame.index] = frame.payload
            if (arr.all { it != null }) {
                val message = assemble(arr.map { it!! })
                parts.remove(frame.msgId); totals.remove(frame.msgId)
                return message
            }
            return null
        }

        /** Progress (received/total) for a message, for UI. */
        @Synchronized
        fun progress(msgId: Int): Pair<Int, Int>? {
            val arr = parts[msgId] ?: return null
            return arr.count { it != null } to arr.size
        }

        private fun assemble(frames: List<ByteArray>): Message {
            val (type, name, mimeAndLen) = parseManifest(frames[0])
            val out = ByteArrayOutputStream()
            for (i in 1 until frames.size) out.write(frames[i])
            return Message(type, name, mimeAndLen.first, out.toByteArray())
        }
    }
}
