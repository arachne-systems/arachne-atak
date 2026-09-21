package dev.arachne.atak

import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Separate emulator processes; host transfers only the secret invitation. */
internal object PeerAdmissionCheck {
    private fun JSONArray.bytes() = ByteArray(length()) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
    private fun ByteArray.json() = JSONArray(map { it.toInt() and 255 })
    private fun call(session: FabricSession, request: JSONObject): JSONObject? {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<ByteArray>>()
        check(session.request(request.toString().toByteArray(Charsets.UTF_8)) { result.set(it); done.countDown() })
        check(done.await(10, TimeUnit.SECONDS)) { "Peer request deadline" }
        val text = String(checkNotNull(result.get()).getOrThrow(), Charsets.UTF_8)
        return if (text == "null") null else JSONObject(text)
    }
    private fun op(name: String) = JSONObject().put("op", name)

    private fun configureData(session: FabricSession, workspace: ByteArray) {
        call(session, op("install_member_policy").put("revision", 17).put("topics", JSONArray().put("streams/sample")))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val report = checkNotNull(call(session, op("subscribe").put("workspace", workspace.json())
                .put("revision", 17).put("topic", "streams/sample")))
            if (report.getJSONArray("failed").length() == 0) break
            check(System.nanoTime() < deadline) { "Peer routing or return path not ready" }
            Thread.sleep(50)
        }
    }

    private fun exchange(session: FabricSession, store: WorkspaceStore, role: String) {
        val large = ByteArray(12 * 1024) { (it % 251).toByte() }
        val reply = byteArrayOf(0, -1, 42)
        fun publish(payload: ByteArray, number: Byte) {
            val staged = checkNotNull(call(session, op("stage_network_publication").put("revision", 17)
                .put("topic", "streams/sample").put("id", ByteArray(16) { number }.json()).put("payload", payload.json())))
            check(!staged.has("ciphertext"))
            val result = store.commitPublication(staged) { checkNotNull(call(session, it)) }
            check(result.getJSONObject("admission").getJSONArray("failed").length() == 0)
            check(result.getJSONObject("admission").getJSONArray("admitted").length() == 2)
        }
        if (role == "issuer") publish(large, 1)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var incoming: JSONObject? = null
        while (incoming == null && System.nanoTime() < deadline) {
            incoming = call(session, op("poll_protected"))
            if (incoming == null) Thread.sleep(25)
        }
        val staged = checkNotNull(incoming) { "Protected cross-device publication missing" }
        check(!staged.has("payload"))
        val delivered = store.commitReception(staged) { checkNotNull(call(session, it)) }
        check(delivered.getString("topic") == "streams/sample")
        check(delivered.getJSONArray("payload").bytes().contentEquals(if (role == "joiner") large else reply))
        check(delivered.getJSONArray("id").bytes().contentEquals(ByteArray(16) { if (role == "joiner") 1 else 2 }))
        if (role == "joiner") publish(reply, 2)
    }

    fun run(context: Context, args: Bundle): JSONObject {
        val run = checkNotNull(args.getString("run_id"))
        require(run.matches(Regex("[a-f0-9]{32}")))
        val role = checkNotNull(args.getString("network_role"))
        require(role in listOf("issuer", "joiner"))
        val slot = "peer-$run-$role"
        val directory = File(context.noBackupFilesDir, "peer-tests/$run")
        check(directory.isDirectory || directory.mkdirs())
        val invitationFile = File(directory, "invitation.json")
        val store = WorkspaceStore(context)
        val pendingStore = WorkspaceStore(context, pendingJoin = true)
        var workspace: ByteArray? = null
        var session = FabricSession(context, slot) {}
        try {
            val endpoint: JSONArray
            val member: JSONArray
            if (role == "issuer") {
                val created = checkNotNull(call(session, op("create_workspace").put("display_name", "Coordinator")))
                workspace = created.getJSONArray("workspace").bytes()
                member = created.getJSONObject("member").getJSONArray("id")
                val sealed = checkNotNull(call(session, op("seal_workspace")))
                store.save(workspace, sealed.getJSONArray("snapshot").bytes())
                val invitation = checkNotNull(call(session, op("issue_invitation")))
                endpoint = invitation.getJSONArray("peer")
                val address = checkNotNull(args.getString("address"))
                require(address.matches(Regex("[0-9.]+")))
                invitation.put("address", address + ":" + invitation.getString("address").substringAfterLast(':'))
                val temporary = File(directory, "invitation.tmp")
                temporary.writeText(invitation.toString())
                check(temporary.renameTo(invitationFile))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
                var received: JSONObject? = null
                while (received == null && System.nanoTime() < deadline) {
                    received = call(session, op("poll_admission"))
                    if (received == null) Thread.sleep(25)
                }
                val staged = checkNotNull(received) { "No cross-device admission arrived" }
                check(staged.getString("state") == "awaiting_save")
                check(runCatching { call(session, op("send_admission_reply")) }.isFailure)
                val owner = session
                store.commitAdmission(staged) { checkNotNull(call(owner, it)) }
                // Release the admission transaction before installing routing.
                // Both sides retry subscriptions until routing and the observed
                // return path are ready. The issuer then sends first.
                check(checkNotNull(call(session, op("send_admission_reply"))).getBoolean("queued"))
                configureData(session, workspace)
                exchange(session, store, role)
                // Queued is not a receipt. Keep the endpoint alive until the host observes the joiner finish.
                val release = File(directory, "release")
                val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
                while (!release.exists() && System.nanoTime() < releaseDeadline) Thread.sleep(25)
                check(release.exists()) { "Joiner completion barrier deadline" }
            } else {
                val raw = invitationFile.inputStream().use { input ->
                    val bytes = ByteArray(128 * 1024 + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val n = input.read(bytes, count, bytes.size-count)
                        if (n < 0) break
                        count += n
                    }
                    check(count in 1..128 * 1024)
                    bytes.copyOf(count)
                }
                val invitation = JSONObject(String(raw, Charsets.UTF_8))
                workspace = invitation.getJSONArray("workspace").bytes()
                val pending = checkNotNull(call(session, op("begin_join")
                    .put("invitation", invitation.getJSONArray("invitation"))
                    .put("checkpoint", invitation.getJSONArray("checkpoint")).put("display_name", "Field Member")))
                member = pending.getJSONObject("member").getJSONArray("id")
                endpoint = pending.getJSONArray("endpoint")
                val sealed = checkNotNull(call(session, op("seal_pending_join")))
                pendingStore.save(workspace, sealed.getJSONArray("snapshot").bytes())
                call(session, op("add_address_hint").put("peer", invitation.getJSONArray("peer")).put("address", invitation.getString("address")))
                val request = op("request_admission").put("peer", invitation.getJSONArray("peer"))
                val blocked = runCatching { call(session, request) }.exceptionOrNull()
                check(blocked?.message?.contains("control response") == true) { "Blocked Iroh path did not time out" }
                check(pendingStore.load(workspace).contentEquals(sealed.getJSONArray("snapshot").bytes()))
                File(directory, "blocked").writeText("timeout")
                val allow = File(directory, "allow")
                val allowDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!allow.exists() && System.nanoTime() < allowDeadline) Thread.sleep(25)
                check(allow.exists()) { "Fabric restore barrier deadline" }
                val reply = checkNotNull(call(session, request))
                val staged = checkNotNull(call(session, op("stage_join").put("welcome", reply.getJSONArray("welcome"))
                    .put("commits", JSONArray().put(JSONObject().put("commit", reply.getJSONArray("commit"))
                        .put("authorization", reply.getJSONObject("authorization"))))))
                val owner = session
                store.commitJoin(staged, pendingStore) { checkNotNull(call(owner, it)) }
                check(runCatching { pendingStore.load(workspace) }.isFailure)
                configureData(session, workspace)
                exchange(session, store, role)
            }
            // Recover each device independently from its own protected state.
            session.close(); check(session.awaitClosed(15, TimeUnit.SECONDS))
            session = FabricSession(context, slot) {}
            val restored = checkNotNull(call(session, op("restore_workspace").put("workspace", workspace.json())
                .put("snapshot", store.load(workspace).json())))
            check(restored.getInt("members") == 2 && restored.getInt("epoch") == 1)
            check(restored.getJSONObject("member").getJSONArray("id").toString() == member.toString())
            return JSONObject().put("passed", true).put("role", role).put("run_id", run)
                .put("workspace", workspace.json()).put("endpoint", endpoint).put("member", member)
                .put("epoch", 1).put("members", 2).put("restored", true).put("blocked_first", role == "joiner")
                .put("protected_exchange", true).put("largest_plaintext_bytes", 12 * 1024)
                .put("publisher_return_hint", "learned from authorized subscription")
        } finally {
            session.close(); check(session.awaitClosed(15, TimeUnit.SECONDS))
            workspace?.let { id ->
                val name = id.joinToString("") { "%02x".format(it.toInt() and 255) } + ".bin"
                for (phase in listOf("workspaces", "pending-joins"))
                    for (suffix in listOf("", ".bak", ".new"))
                        File(context.noBackupFilesDir, "data-fabric/$phase/$name$suffix").delete()
            }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("dev.datafabric.$slot") }
            for (suffix in listOf(".bin", ".bin.bak", ".bin.new", ".lock"))
                File(context.noBackupFilesDir, "data-fabric/$slot$suffix").delete()
            directory.deleteRecursively()
        }
    }
}
