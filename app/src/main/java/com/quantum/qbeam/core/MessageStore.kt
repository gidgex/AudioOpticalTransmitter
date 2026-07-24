package com.quantum.qbeam.core

import android.content.Context
import java.io.File

/** Lightweight metadata for a stored received message (for list display). */
data class StoredMessage(
    val id: Long,
    val name: String,
    val type: WavePacket.DataType,
    val mimeType: String,
    val size: Int,
    val timestamp: Long,
)

/**
 * Persists received messages to the app's private storage so they can be browsed and
 * re-opened later. Each message is saved as a single packed blob (manifest + data) via
 * [WavePacket.packMessage]; the filename encodes the receive time.
 */
class MessageStore(context: Context) {

    private val dir = File(context.filesDir, "received").apply { mkdirs() }

    /** Save a freshly decoded message; returns its metadata entry. */
    fun save(msg: WavePacket.Message): StoredMessage {
        val id = System.currentTimeMillis()
        File(dir, "$id.qbm").writeBytes(WavePacket.packMessage(msg))
        return StoredMessage(id, msg.name, msg.type, msg.mimeType, msg.data.size, id)
    }

    /** All stored messages, newest first. */
    fun list(): List<StoredMessage> =
        (dir.listFiles { f -> f.extension == "qbm" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val id = f.nameWithoutExtension.toLong()
                    val msg = WavePacket.unpackMessage(f.readBytes())
                    StoredMessage(id, msg.name, msg.type, msg.mimeType, msg.data.size, id)
                }.getOrNull()
            }
            .sortedByDescending { it.timestamp }

    /** Load the full message for a stored id, or null if missing/corrupt. */
    fun load(id: Long): WavePacket.Message? = runCatching {
        WavePacket.unpackMessage(File(dir, "$id.qbm").readBytes())
    }.getOrNull()

    fun delete(id: Long) { File(dir, "$id.qbm").delete() }
}
