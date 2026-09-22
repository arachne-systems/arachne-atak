package dev.arachne.atak

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises saved joins through the real controller, JNI, and Android storage. */
internal object JoinCancellationCheck {
    fun run(context: Context): JSONObject {
        val root = File(context.noBackupFilesDir, "cancel-tests/${UUID.randomUUID()}")
        val contexts = List(3) { index -> object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(root, "$index").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("${root.name}-$index-$name", mode)
        } }
        val views = List(3) { LinkedBlockingQueue<WorkspaceView>() }
        val owners = mutableListOf<WorkspaceController>()
        val connections = mutableListOf<WorkspaceConnections>()
        fun start(index: Int): WorkspaceController {
            views[index].clear()
            return WorkspaceController(contexts[index]) { views[index].offer(it) }.also { owners.add(it) }
        }
        fun connect(index: Int): WorkspaceConnections {
            views[index].clear()
            return WorkspaceConnections(contexts[index], emptySet(), { _, complete -> complete(true) }) {
                views[index].offer(it.selected)
            }.also { connections.add(it) }
        }
        fun wait(index: Int, predicate: (WorkspaceView) -> Boolean): WorkspaceView {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (System.nanoTime() < deadline) {
                val view = views[index].poll(1, TimeUnit.SECONDS) ?: continue
                check(!view.message.startsWith("Workspace operation failed")) { "Cancel flow failed: ${view.message}" }
                if (!view.busy && predicate(view)) return view
            }
            error("Cancel flow deadline for $index")
        }
        fun stop(owner: WorkspaceController) { owner.close(); check(owner.awaitClosed(20, TimeUnit.SECONDS)) }
        fun stop(owner: WorkspaceConnections) { owner.close(); check(owner.awaitClosed(20, TimeUnit.SECONDS)) }
        fun absent(record: LocalWorkspace) {
            check(WorkspaceCatalog(contexts[1]).read().none { it.slot == record.slot })
            check(!WorkspaceStore(contexts[1], pendingJoin = true).exists(record.id))
            check(!WorkspaceStore(contexts[1]).exists(record.id) && !WorkspaceStore(contexts[1]).usesRecords(record.id))
            check(!File(contexts[1].noBackupFilesDir, "data-fabric/pending-invitations/${record.slot}.bin").exists())
        }
        try {
            val issuer = start(0)
            wait(0) { it.saved.isEmpty() }
            check(issuer.create("Cancel regression", "Organizer"))
            wait(0) { it.active != null }
            check(issuer.invite(personal = true))
            val link = checkNotNull(wait(0) { it.invitation != null }.invitation)
            var joiner = connect(1)
            wait(1) { it.saved.isEmpty() }
            check(joiner.join(link, "Waiting member", false))
            val waiting = checkNotNull(wait(1) { it.message.startsWith("Awaiting administrator approval") }.active)
            check(waiting.joinPeer != null)
            // This independent owner reads the pending row into its own catalog.
            // Its later updates must not resurrect a canceled join in the facade.
            check(joiner.create("Keep this workspace", "Existing member", false))
            val keep = checkNotNull(wait(1) { it.active != null && it.active.slot != waiting.slot }.active)
            val keepFile = File(contexts[1].noBackupFilesDir, "data-fabric/catalog/${keep.slot}.json")
            val keepBytes = keepFile.readBytes()
            check(joiner.open(waiting))
            wait(1) { it.active?.slot == waiting.slot }
            check(joiner.cancelJoin(waiting.id))
            val canceled = wait(1) { it.message.startsWith("Join request canceled") }
            check(canceled.active == null && canceled.saved.none { it.slot == waiting.slot }) {
                "Canceled join remains in the workspace connection view"
            }
            repeat(3) {
                check(wait(1) { true }.saved.none { it.slot == waiting.slot }) { "Stale owner resurrected canceled join" }
            }
            absent(waiting)
            check(keepFile.readBytes().contentEquals(keepBytes))
            stop(joiner)
            joiner = connect(1)
            wait(1) { it.saved.size == 1 && it.active?.slot == keep.slot }
            check(joiner.join(link, "Fresh request", false))
            val fresh = checkNotNull(wait(1) { it.message.startsWith("Awaiting administrator approval") }.active)
            check(fresh.slot != waiting.slot)
            check(joiner.cancelJoin(fresh.id))
            wait(1) { it.message.startsWith("Join request canceled") }
            absent(fresh)
            var previousLink = link
            for (expires in listOf(false, true)) {
                check(issuer.invite(expiresAt = if (expires) System.currentTimeMillis() / 1000 + 15 else 0,
                    personal = true, requestAccess = true))
                val offered = wait(0) { it.invitation != null && it.invitation != previousLink }
                previousLink = checkNotNull(offered.invitation)
                check(joiner.join(previousLink, "Withdrawn request", false))
                val pending = checkNotNull(wait(1) { it.message.startsWith("Awaiting administrator approval") }.active)
                if (!expires) {
                    check(issuer.disableInvitation(checkNotNull(offered.active).id, checkNotNull(offered.invitationKey)))
                    wait(0) { it.message.startsWith("Invitation disabled") }
                }
                val removed = wait(1) { it.message.startsWith(if (expires) "This invitation has expired" else "This invitation was declined or disabled") }
                check(removed.active == null && removed.saved.none { it.slot == pending.slot })
                absent(pending)
            }
            // Compact invitation before checkpoint retrieval must also cancel.
            stop(issuer)
            check(joiner.join(link, "Offline request", false))
            val offline = checkNotNull(wait(1) { it.message.startsWith("Joining…") }.active)
            check(offline.preJoin)
            check(joiner.cancelJoin(offline.id))
            wait(1) { it.message.startsWith("Join request canceled") }
            absent(offline)
            stop(joiner)
            // Crash after durable cancellation, before cleanup: reopening finishes it.
            val directory = keepFile.parentFile!!
            for (legacy in listOf(false, true)) {
                val slot = "workspace-" + UUID.randomUUID().toString().replace("-", "")
                val marker = JSONObject().put("version", if (legacy) 6 else 9)
                    .put("name", waiting.legacyName).put("shared_name", waiting.sharedName)
                    .put("slot", slot).put("workspace", org.json.JSONArray(waiting.id.map { it.toInt() and 255 }))
                if (legacy) marker.put("ended", true)
                    .put("join_peer", org.json.JSONArray(waiting.joinPeer!!.map { it.toInt() and 255 }))
                    .put("join_routes", org.json.JSONArray()).put("join_pinned", false)
                else marker.put("join_canceled", true)
                File(directory, "$slot.json").writeText(marker.toString())
                check(WorkspaceCatalog(contexts[1]).read().single().slot == keep.slot)
                check(!File(directory, "$slot.json").exists())
            }
            check(keepFile.readBytes().contentEquals(keepBytes))
            val administrator = start(0)
            val adminRecord = wait(0) { it.saved.size == 1 }.saved.single()
            check(administrator.open(adminRecord))
            wait(0) { it.members.any { member -> member.self && member.administrator } }
            check(administrator.invite(personal = true, requestAccess = true))
            val shared = checkNotNull(wait(0) { it.invitation != null }.invitation)
            val first = start(1)
            wait(1) { it.saved.size == 1 }
            val second = start(2)
            wait(2) { it.saved.isEmpty() }
            check(first.join(shared, "First requester"))
            check(second.join(shared, "Second requester"))
            wait(1) { it.message.startsWith("Awaiting administrator approval") }
            wait(2) { it.message.startsWith("Awaiting administrator approval") }
            val requests = wait(0) { it.approvals.size == 2 }.approvals
            for (request in requests) {
                check(administrator.approveInvitation(adminRecord.id, request))
                wait(0) { it.message.startsWith("Access approved") }
            }
            wait(1) { it.message.startsWith("Joined and saved") }
            wait(2) { it.message.startsWith("Joined and saved") }
            check(WorkspaceCatalog(contexts[1]).read().size == 2)
            return JSONObject().put("passed", true).put("approval_cancel_restart_rejoin", true)
                .put("connection_view_removes_canceled_join", true).put("stale_owner_cannot_restore_canceled_join", true)
                .put("disabled_invitation_cleans_pending_join", true).put("expired_invitation_cleans_pending_join", true)
                .put("offline_invitation_cancel", true).put("interrupted_cleanup", true)
                .put("legacy_broken_cancel_recovered", true).put("unrelated_workspace_preserved", true)
                .put("two_requesters_join_one_advertisement", true)
        } finally {
            connections.forEach { it.close() }
            connections.forEach { check(it.awaitClosed(20, TimeUnit.SECONDS)) }
            owners.forEach { it.close() }
            owners.forEach { check(it.awaitClosed(20, TimeUnit.SECONDS)) }
            root.deleteRecursively()
        }
    }
}
