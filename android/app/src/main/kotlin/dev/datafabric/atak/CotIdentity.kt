package dev.arachne.atak

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Adapter identifiers, never membership credentials. The author argument on
 * receive MUST come from the fabric's authenticated member, not CoT detail. */
internal object CotIdentity {
    fun member(workspace: ByteArray, member: ByteArray): String =
        digest("dfm", workspace, member, ByteArray(0))

    fun publishedObject(workspace: ByteArray, author: ByteArray, nativeUid: String): String =
        digest("dfo", workspace, author, uid(nativeUid))

    /** Device-local fallback for retained v1 payloads that predate the native
     * identity projection carried by current publications. */
    fun receivedObject(workspace: ByteArray, author: ByteArray, wireUid: String): String =
        digest("dfl", workspace, author, uid(wireUid))

    fun receivedPli(workspace: ByteArray, author: ByteArray, wireUid: String): String =
        member(workspace, author).also { require(it == wireUid) { "PLI identity does not match authenticated member" } }

    fun broadcastChat(workspace: ByteArray, author: ByteArray, messageId: String): String =
        "GeoChat.${member(workspace, author)}.All Chat Rooms.${publishedObject(workspace, author, messageId)}"

    private fun uid(value: String): ByteArray = value.toByteArray(Charsets.UTF_8).also {
        require(value.isNotBlank() && it.size <= 1024 && String(it, Charsets.UTF_8) == value) { "Invalid CoT identifier" }
    }

    private fun digest(domain: String, workspace: ByteArray, author: ByteArray, value: ByteArray): String {
        require(workspace.size == 32 && author.size == 32)
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("data-fabric/cot-identity/v1/$domain\u0000".toByteArray(Charsets.US_ASCII))
        hash.update(workspace)
        hash.update(author)
        hash.update(ByteBuffer.allocate(4).putInt(value.size).array())
        hash.update(value)
        return domain + "-" + Hex.encode(hash.digest())
    }
}
