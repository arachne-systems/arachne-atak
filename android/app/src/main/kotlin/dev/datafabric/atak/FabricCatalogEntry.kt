package dev.arachne.atak

import java.nio.ByteBuffer

/** Android codec for arachne-delivery's opaque catalog-entry envelope. */
internal data class FabricCatalogEntry(val key: ByteArray, val payload: ByteArray, val tombstone: Boolean = false) {
    init { require(key.size == 32) }

    fun wire(): ByteArray {
        require(tombstone && payload.isEmpty() || !tombstone && payload.isNotEmpty() && payload.size <= MAX_PAYLOAD)
        return MAGIC + key + byteArrayOf(if (tombstone) 1 else 0) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload
    }

    companion object {
        private val MAGIC = byteArrayOf(68, 70, 67, 69, 1)
        private const val MAX_PAYLOAD = 12 * 1024 - 42

        fun read(bytes: ByteArray): FabricCatalogEntry {
            require(bytes.size >= 42 && bytes.copyOfRange(0, 5).contentEquals(MAGIC))
            val key = bytes.copyOfRange(5, 37)
            val tombstone = when (bytes[37].toInt()) { 0 -> false; 1 -> true; else -> error("Invalid catalog tombstone") }
            val length = ByteBuffer.wrap(bytes, 38, 4).int
            require(length >= 0 && length <= MAX_PAYLOAD && bytes.size == 42 + length)
            val payload = bytes.copyOfRange(42, bytes.size)
            return FabricCatalogEntry(key, payload, tombstone).also { it.wire() }
        }
    }
}
