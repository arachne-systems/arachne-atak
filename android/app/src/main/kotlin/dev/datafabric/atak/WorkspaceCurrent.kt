package dev.arachne.atak

import java.security.MessageDigest
import java.time.Instant

/** Opaque application policy for one replaceable, expiring fabric value. */
internal data class WorkspaceCurrent(
    val selector: ByteArray,
    val replacementKey: ByteArray,
    val expiresAt: Long,
    val tombstone: Boolean = false
) {
    fun isFresh(now: Long = Instant.now().epochSecond) = expiresAt > now

    companion object {
        fun selector(topic: String) = MessageDigest.getInstance("SHA-256")
            .digest(topic.toByteArray(Charsets.UTF_8))

        private val nativePliSelector = selector("atak/native/v1/pli")

        fun nativePliSelector() = nativePliSelector.copyOf()

        fun nativePli(member: ByteArray, stale: String): WorkspaceCurrent {
            require(member.size == 32)
            return WorkspaceCurrent(nativePliSelector.copyOf(), member.copyOf(), Instant.parse(stale).epochSecond)
                .also { require(it.expiresAt > 0) }
        }

        fun nativeObject(topic: String, uid: String, stale: String, tombstone: Boolean = false): WorkspaceCurrent? {
            require(topic in CotTopics.nativeCurrent && topic != "atak/native/v1/pli")
            val expires = runCatching { Instant.parse(stale).epochSecond }.getOrNull() ?: return null
            if (expires <= 0 || uid.isBlank()) return null
            val key = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            return WorkspaceCurrent(selector(topic), key, expires, tombstone)
        }
    }
}
