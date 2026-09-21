package dev.arachne.atak

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

internal object WorkspaceResourcesCheck {
    private fun ByteArray.json() = JSONArray(map { it.toInt() and 255 })
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun run(context: Context): JSONObject {
        val workspace = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sourceId = ByteArray(32) { 2 }
        val receiverId = ByteArray(32) { 3 }
        val otherId = ByteArray(32) { 4 }
        var attempts = 0
        val transfer = ResourceTransferFixture()
        var failFirst = true
        lateinit var source: WorkspaceResources
        lateinit var receiver: WorkspaceResources
        lateinit var other: WorkspaceResources
        fun packet(member: ByteArray, topic: String, payload: ByteArray, recipients: List<ByteArray>) =
            JSONObject().put("workspace", workspace.json()).put("member", member.json())
                .put("topic", topic).put("payload", payload.json())
                .put("recipients", JSONArray(recipients.map { it.json() }))
        fun report(member: ByteArray, recipients: List<ByteArray>) = JSONObject()
            .put("publisher_endpoint", member.json()).put("admission", JSONObject()
                .put("admitted", JSONArray(recipients.map { it.json() }))
                .put("failed", JSONArray()).put("queued", false))
        val fromSource: WorkspacePublisher = { _, topic, payload, recipients, current, completed ->
            if (topic == WorkspaceResources.CLAIMS || topic == WorkspaceResources.CATALOG) {
                check(current != null && recipients.isEmpty())
                completed(Result.success(report(sourceId, recipients)))
                true
            } else {
            check(current == null)
            val target = when {
                recipients.single().contentEquals(receiverId) -> receiver
                recipients.single().contentEquals(otherId) -> other
                else -> error("Unknown resource recipient")
            }
            target.receive(packet(sourceId, topic, payload, recipients)) { check(it) }
            completed(Result.success(report(sourceId, recipients)))
            true
            }
        }
        fun fromPeer(peer: ByteArray, retry: Boolean): WorkspacePublisher = { _, topic, payload, recipients, current, completed ->
            check(current == null)
            if (retry) attempts++
            if (retry && failFirst) {
                failFirst = false; completed(Result.failure(IllegalStateException("simulated lost access request")))
            } else {
                source.receive(packet(peer, topic, payload, recipients)) { check(it) }
                completed(Result.success(report(peer, recipients)))
            }
            true
        }
        source = WorkspaceResources(context, LocalWorkspace("Resource test", "source", workspace,
            memberId = sourceId), fromSource, transfer.calls(sourceId))
        receiver = WorkspaceResources(context, LocalWorkspace("Resource test", "receiver", workspace,
            memberId = receiverId), fromPeer(receiverId, true), transfer.calls(receiverId))
        other = WorkspaceResources(context, LocalWorkspace("Resource test", "other", workspace,
            memberId = otherId), fromPeer(otherId, false), transfer.calls(otherId))
        val members = listOf(WorkspaceMember(hex(sourceId), "Source", false, true),
            WorkspaceMember(hex(receiverId), "Receiver", false, false), WorkspaceMember(hex(otherId), "Other", false, false))
        source.members(members)
        receiver.members(members)
        other.members(members)
        val input = File.createTempFile("resource-check-", ".bin", context.cacheDir)
        var output: File? = null
        var hash: String? = null
        try {
            val expected = ByteArray(9000) { (it % 251).toByte() }
            input.writeBytes(expected)
            val offered = source.offer(input)
            hash = offered
            output = checkNotNull(receiver.fetch(WorkspaceResources.path(sourceId, offered)))
            check(output.readBytes().contentEquals(expected))
            check(attempts == 2) { "Expected one lost grant request and one retry, no byte chunks" }
            input.writeBytes(expected + byteArrayOf(1))
            val directed = source.offer(input, listOf(receiverId))
            checkNotNull(receiver.fetch(WorkspaceResources.path(sourceId, directed.hash, directed.grant))).delete()
            check(other.fetch(WorkspaceResources.path(sourceId, directed.hash, directed.grant)) == null)
            check(other.fetch(WorkspaceResources.path(sourceId, directed.hash)) == null)
            source.withdraw(directed)
            return JSONObject().put("passed", true).put("access_requests", attempts).put("directed_grant", true)
        } finally {
            output?.delete()
            hash?.let { source.withdraw(it) }
            input.delete()
            other.close()
            receiver.close()
            source.close()
        }
    }
}
