package dev.arachne.atak

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Adapter-only fake. Real QUIC/Bao verification is exercised by resource_stream
 * and the joined-device harness; these small fixtures test message correlation. */
internal class ResourceTransferFixture {
    private data class Grant(val bytes: ByteArray, val member: List<Byte>, val path: String?)
    private val grants = mutableMapOf<List<Byte>, Grant>()
    private val jobs = mutableMapOf<Long, JSONObject>()
    private var next = 0L
    private fun ByteArray.json() = JSONArray(map { it.toInt() and 255 })
    private fun JSONArray.bytes() = ByteArray(length()) { getInt(it).toByte() }

    @Synchronized fun ticket(bytes: ByteArray, member: ByteArray, path: String? = null): JSONObject {
        check(bytes.size <= 64 * 1024) { "Use the real streaming harness for large files" }
        val token = ByteArray(32).also(SecureRandom()::nextBytes)
        grants[token.toList()] = Grant(bytes.copyOf(), member.toList(), path)
        return JSONObject().put("hash", MessageDigest.getInstance("SHA-256").digest(bytes).json())
            .put("size", bytes.size).put("grant", token.json())
    }

    fun calls(member: ByteArray): WorkspaceResourceCall = { _, request, completed ->
        val result = synchronized(this) { runCatching {
            val state = when (request.getString("action")) {
                "prepare" -> {
                    val path = request.getString("path")
                    JSONObject().put("state", "prepared").put("ticket", ticket(File(path).readBytes(), request.getJSONArray("member").bytes(), path))
                }
                "fetch" -> {
                    val offer = request.getJSONObject("ticket")
                    val grant = checkNotNull(grants[offer.getJSONArray("grant").bytes().toList()])
                    check(grant.member == member.toList())
                    check(offer.getLong("size") == grant.bytes.size.toLong())
                    File(request.getString("path")).writeBytes(grant.bytes)
                    JSONObject().put("state", "complete").put("bytes", grant.bytes.size)
                }
                "poll" -> checkNotNull(jobs.remove(request.getLong("id")))
                "cancel" -> { jobs.remove(request.getLong("id")); JSONObject().put("state", "cancelled") }
                "revoke" -> {
                    grants.entries.removeIf { !request.has("path") || it.value.path == request.getString("path") }
                    JSONObject().put("state", "revoked")
                }
                "clear" -> JSONObject().put("state", "cleared")
                else -> error("Unexpected resource operation")
            }
            if (request.getString("action") in setOf("prepare", "fetch", "clear")) {
                next++; jobs[next] = state
                JSONObject().put("state", "started").put("id", next)
            } else state
        } }
        completed(result)
        true
    }
}
