package dev.arachne.atak

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Same facade intended for ATAK, backed by four actual JNI/Iroh workspace owners. */
internal object WorkspaceConnectionsCheck {
    fun run(context: Context, exitOnly: Boolean = false): JSONObject {
        check(WorkspaceData.outcome(JSONObject().put("network_error", "deadline exceeded")).uncertain)
        val self = org.json.JSONArray(List(32) { 1 })
        val selfOnly = JSONObject().put("publisher_endpoint", self).put("admission", JSONObject()
            .put("admitted", org.json.JSONArray().put(self)).put("failed", org.json.JSONArray()))
        check(WorkspaceData.outcome(selfOnly).remoteAccepted == 0)
        val queuedOnly = JSONObject(selfOnly.toString()).apply {
            getJSONObject("admission").put("queued", true)
        }
        check(WorkspaceData.outcome(queuedOnly).let {
            it.remoteAccepted == 0 && it.failed == 0 && !it.uncertain && it.queued
        })
        val root = File(context.noBackupFilesDir, "connections-tests/${UUID.randomUUID()}")
        val contexts = List(2) { i -> object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("${root.name}-$i-$name", mode)
            override fun getNoBackupFilesDir() = File(root, "$i").apply { mkdirs() }
        } }
        val views = List(2) { LinkedBlockingQueue<WorkspaceConnectionView>() }
        val received = List(2) { LinkedBlockingQueue<JSONObject>() }
        // The producer does not receive this topic. Publishing and receive
        // interests must stay independent through the complete Kotlin facade.
        val owners = MutableList(2) { i -> WorkspaceConnections(contexts[i], if (i == 0) emptySet() else setOf("streams/sample"),
            { message, complete -> received[i].offer(message); complete(true) }) { views[i].offer(it) } }
        fun wait(i: Int, predicate: (WorkspaceConnectionView) -> Boolean): WorkspaceConnectionView {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
            while (System.nanoTime() < end) {
                val v = views[i].poll(1, TimeUnit.SECONDS) ?: continue
                check(!v.selected.message.startsWith("Workspace operation failed")) { v.selected.message }
                if (!v.selected.busy && predicate(v)) return v
            }
            val debug = owners[i].debugOwners().map { it.second.debugState(1000).toString() }
            val roster = owners[i].debugOwners().map { it.second.debugCall(JSONObject().put("op", "member_roster"), 1000)?.toString() }
            error("Workspace connections deadline: $i debug=$debug roster=$roster")
        }
        fun invitation(): String {
            check(owners[0].invite())
            return checkNotNull(wait(0) {
                it.selected.invitation != null && it.selected.message.contains("invitation ready", ignoreCase = true)
            }.selected.invitation)
        }
        fun exchange(id: ByteArray, text: String, audience: List<ByteArray> = emptyList()) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < end) {
                val outcomes = LinkedBlockingQueue<Result<JSONObject>>()
                check(owners[0].publish(id, "streams/sample", text.toByteArray(), audience) { outcomes.offer(it) })
                val report = checkNotNull(outcomes.poll(20, TimeUnit.SECONDS)).getOrThrow()
                val admission = report.getJSONObject("admission")
                check(admission.has("admitted") && admission.has("failed"))
                val message = received[1].poll(500, TimeUnit.MILLISECONDS) ?: continue
                val payload = message.getJSONArray("payload")
                if (String(ByteArray(payload.length()) { payload.getInt(it).toByte() }) != text) continue
                val outcome = WorkspaceData.outcome(report)
                check(outcome.failed == 0 && !outcome.uncertain && (outcome.remoteAccepted == 1 || outcome.queued))
                check(message.getJSONArray("workspace").toString() == org.json.JSONArray(id.map { it.toInt() and 255 }).toString())
                if (audience.isNotEmpty()) {
                    check(message.getJSONArray("recipients").toString() == org.json.JSONArray(
                        audience.map { member -> org.json.JSONArray(member.map { it.toInt() and 255 }) }).toString())
                    // Directed history now has a sequence within its exact audience.
                    check(message.getLong("sequence") > 0)
                }
                check(received[0].isEmpty()) { "Publish-only owner received application data" }
                return
            }
            error("Publication did not reach selected workspace")
        }
        try {
            for (i in 0..1) wait(i) { it.selected.saved.isEmpty() }
            if (exitOnly) {
                check(owners[0].create("Offline removal", "Solo administrator"))
                val offline = checkNotNull(wait(0) { it.selected.active != null }.selected.active)
                check(owners[0].pause(offline.id))
                wait(0) { it.pauses[offline.slot] == "Paused" }
                check(owners[0].leaveDevice(offline))
                wait(0) { it.selected.saved.none { saved -> saved.slot == offline.slot } }
                check(JSONObject(File(contexts[0].noBackupFilesDir,
                    "data-fabric/catalog/${offline.slot}.json").readText()).getBoolean("ended"))
                check(owners[0].create("First workspace", "Solo administrator"))
                val first = checkNotNull(wait(0) { it.selected.active != null }.selected.active)
                val link = invitation()
                check(owners[1].join(link, "Successor"))
                wait(1) { it.selected.message.startsWith("Joined and saved.") }
                val successor = wait(0) { state -> state.selected.members.any { !it.self && it.presence == "reachable" } }
                    .selected.members.single { !it.self }
                check(owners[0].leave(first.id, successor.identity()))
                val removed = wait(0) { state -> state.connected.isEmpty() && state.selected.saved.none { it.slot == first.slot } }
                check(removed.selected.active == null)
                wait(1) { state -> state.selected.members.singleOrNull()?.let { it.self && it.administrator } == true }
                val record = File(contexts[0].noBackupFilesDir, "data-fabric/catalog/${first.slot}.json")
                check(record.isFile && JSONObject(record.readText()).getBoolean("ended"))
                return JSONObject().put("passed", true).put("leave_scoped_to_workspace", true)
                    .put("successor_handoff", true).put("offline_remove_from_device", true)
                    .put("ended_workspace_auto_removal_preserves_record", true)
            }
            check(owners[0].create("Search Team", "Alex Search", false))
            val a = checkNotNull(wait(0) { it.selected.active != null }.selected.active)
            check(views[0].none { a.id.toList() in it.publishing })
            val linkA = invitation()
            check(owners[1].join(linkA, "Jordan Search", false))
            val joined = wait(1) {
                check(it.publishing.isEmpty()) { "Sharing enabled before reviewed choice" }
                it.selected.message.startsWith("Joined and saved.")
            }
            val peerA = checkNotNull(joined.selected.active)
            check(owners[1].shareBroadcasts(a.id, true))
            wait(0) { it.selected.message.startsWith("Membership saved;") }
            exchange(a.id, "first workspace")
            check(!owners[1].selectTopics(a.id, setOf("unknown/topic"), receive = false))
            check(owners[1].selectTopics(a.id, setOf("streams/sample"), publish = false))
            wait(1) { it.selected.topics.single().let { choice -> !choice.publish && choice.receive } }
            val disabled = LinkedBlockingQueue<Result<JSONObject>>()
            check(owners[1].publish(a.id, "streams/sample", byteArrayOf(44)) { disabled.offer(it) })
            check(checkNotNull(disabled.poll(10, TimeUnit.SECONDS)).isFailure)
            exchange(a.id, "subscriber only still receives")
            check(owners[1].selectTopics(a.id, setOf("streams/sample"), publish = true, receive = false))
            wait(1) { it.selected.topics.single().let { choice -> choice.publish && !choice.receive } }
            val withdrawn = LinkedBlockingQueue<Result<JSONObject>>()
            check(owners[0].publish(a.id, "streams/sample", byteArrayOf(45), listOf(checkNotNull(peerA.memberId))) { withdrawn.offer(it) })
            val excluded = WorkspaceData.outcome(checkNotNull(withdrawn.poll(20, TimeUnit.SECONDS)).getOrThrow())
            check(excluded.remoteAccepted == 0 && excluded.failed == 1 && !excluded.uncertain)
            check(received[1].poll(500, TimeUnit.MILLISECONDS) == null)
            check(owners[1].selectTopics(a.id, setOf("streams/sample"), receive = true))
            wait(1) { it.selected.topics.single().receive }
            exchange(a.id, "resubscription restores receiver")
            // Recipient intent cannot override the producer's empty receive set.
            val reports = LinkedBlockingQueue<Result<JSONObject>>()
            check(owners[1].publish(a.id, "streams/sample", byteArrayOf(88), listOf(checkNotNull(a.memberId))) {
                reports.offer(it)
                error("Deliberate adapter callback failure")
            })
            val denied = checkNotNull(reports.poll(20, TimeUnit.SECONDS)).getOrThrow().getJSONObject("admission")
            check(denied.getJSONArray("admitted").length() == 0)
            val failed = denied.getJSONArray("failed")
            check(failed.length() == 1)
            check(failed.getJSONObject(0).getString("error") == "peer has no announced subscription to this topic")
            check(received[0].isEmpty())
            // The throwing callback must not close the receiving workspace.
            exchange(a.id, "callback failure preserves workspace")
            check(reports.isEmpty()) { "Duplicate completion" }
            check(owners[0].create("Support Team", "Alex Support"))
            val two = wait(0) { state ->
                state.connected.any { it.active?.id?.contentEquals(a.id) == false }
            }
            val b = checkNotNull(two.connected.mapNotNull { it.active }.single { !it.id.contentEquals(a.id) })
            check(!a.id.contentEquals(b.id) && !a.memberId!!.contentEquals(b.memberId!!))
            check(owners[0].open(b))
            wait(0) { it.selected.active?.id?.contentEquals(b.id) == true }
            val linkB = invitation()
            check(owners[1].join(linkB, "Jordan Support"))
            val peerTwo = wait(1) { state ->
                state.connected.any { it.active?.id?.contentEquals(b.id) == true }
            }
            val peerB = checkNotNull(peerTwo.connected.mapNotNull { it.active }.single { it.id.contentEquals(b.id) })
            check(!peerA.memberId!!.contentEquals(peerB.memberId!!))
            check(owners[1].open(peerB))
            wait(1) { it.selected.active?.id?.contentEquals(b.id) == true }
            wait(0) { it.selected.message.startsWith("Membership saved;") }
            // Both connections receive while the UI selects the second workspace.
            exchange(a.id, "first while second selected")
            val recipient = checkNotNull(peerA.memberId)
            check(!owners[0].publish(a.id, "streams/sample", byteArrayOf(1), listOf(ByteArray(31))))
            check(!owners[0].publish(a.id, "streams/sample", byteArrayOf(1), listOf(recipient, recipient)))
            exchange(a.id, "recipient while second selected", listOf(recipient))
            exchange(b.id, "second while first connected")
            // Capture workspace A while B is selected: preference edits cannot
            // accidentally apply to the visible workspace.
            check(owners[1].selectTopics(a.id, setOf("streams/sample"), publish = false))
            wait(1) { state -> state.connected.single { it.active?.id?.contentEquals(a.id) == true }.topics.single().publish == false }
            check(owners[1].open(peerB))
            check(wait(1) { it.selected.active?.id?.contentEquals(b.id) == true }.selected.topics.single().publish)
            check(owners[1].selectTopics(a.id, setOf("streams/sample"), publish = true))
            wait(1) { state -> state.connected.single { it.active?.id?.contentEquals(a.id) == true }.topics.single().publish }
            check(owners[1].open(peerA))
            val selected = wait(1) { it.selected.active?.id?.contentEquals(a.id) == true && it.connected.size == 2 }
            check(selected.selected.active!!.memberId!!.contentEquals(peerA.memberId))
            check(selected.publishing == setOf(a.id.toList(), b.id.toList()))
            check(owners[1].shareBroadcasts(a.id, false))
            wait(1) { it.publishing == setOf(b.id.toList()) }
            check(owners[1].open(peerB))
            val viewedB = wait(1) { it.selected.active?.id?.contentEquals(b.id) == true }
            check(viewedB.publishing == setOf(b.id.toList()))
            check(owners[1].open(peerA))
            val viewedA = wait(1) { it.selected.active?.id?.contentEquals(a.id) == true }
            check(viewedA.publishing == setOf(b.id.toList()))
            exchange(b.id, "second while first selected")
            // Hold accepted work so Pausing must remain distinct from Paused.
            val entry = WorkspaceConnections::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(owners[1])
            val controller = entry.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(entry) as WorkspaceController
            val worker = WorkspaceController::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(controller) as java.util.concurrent.ScheduledExecutorService
            val entered = java.util.concurrent.CountDownLatch(1)
            val drain = java.util.concurrent.CountDownLatch(1)
            worker.execute { entered.countDown(); check(drain.await(25, TimeUnit.SECONDS)) }
            check(entered.await(5, TimeUnit.SECONDS))
            val drainedPublication = LinkedBlockingQueue<Result<JSONObject>>()
            try {
                check(owners[1].publish(a.id, "streams/sample", byteArrayOf(77)) { drainedPublication.offer(it) })
                check(owners[1].pause(a.id))
                wait(1) { it.connected.size == 1 && it.pauses[peerA.slot] == "Pausing" }
                check(owners[1].inspect(peerA))
                val pausedPage = wait(1) { it.selected.message.startsWith("Pausing ") }
                check(pausedPage.connected.size == 1 && pausedPage.publishing == setOf(b.id.toList()))
                check(!owners[1].open(peerA))
                check(!owners[1].publish(a.id, "streams/sample", byteArrayOf(99)))
                check(drainedPublication.isEmpty()) { "Publication completed before its worker was released" }
                check(owners[1].inspect(peerB))
                wait(1) { it.selected.active?.id?.contentEquals(b.id) == true }
                exchange(b.id, "second continues while first drains")
            } finally { drain.countDown() }
            wait(1) { it.pauses[peerA.slot] == "Paused" }
            check(controller.shutdownSucceeded)
            check(checkNotNull(drainedPublication.poll(5, TimeUnit.SECONDS)).isSuccess)
            check(drainedPublication.isEmpty()) { "Duplicate drained-publication completion" }
            check(owners[1].inspect(peerA))
            val paused = wait(1) { it.selected.message.startsWith("Paused ") }
            check(paused.connected.size == 1 && paused.selected.saved.any { it.slot == peerA.slot })
            check(!owners[1].invite())
            check(!owners[1].publish(a.id, "streams/sample", byteArrayOf(99)))
            check(!owners[0].publish(ByteArray(32), "streams/sample", byteArrayOf(99)))
            exchange(b.id, "second survives first disconnect")
            check(owners[1].open(peerA))
            val reopened = wait(1) { it.connected.size == 2 && it.selected.active?.id?.contentEquals(a.id) == true }
            check(reopened.publishing == setOf(b.id.toList()))
            check(owners[1].shareBroadcasts(a.id, true))
            wait(1) { it.publishing == setOf(a.id.toList(), b.id.toList()) }
            check(owners[1].shareBroadcasts(b.id, false))
            wait(1) { it.publishing == setOf(a.id.toList()) }
            check(owners[1].shareBroadcasts(a.id, false))
            wait(1) { it.publishing.isEmpty() }
            exchange(a.id, "receive with sharing off")
            check(!owners[1].shareBroadcasts(ByteArray(32), true))
            check(owners[1].selectTopics(a.id, setOf("streams/sample"), publish = false, receive = false))
            wait(1) { it.selected.topics.single().let { choice -> !choice.publish && !choice.receive } }
            check(owners[1].pause(a.id))
            wait(1) { it.pauses[peerA.slot] == "Paused" }
            check(owners[1].open(peerA))
            val choicesReopened = wait(1) { it.connected.size == 2 && it.selected.active?.id?.contentEquals(a.id) == true }
            check(choicesReopened.selected.topics.single().let { !it.publish && !it.receive })
            check(choicesReopened.connected.single { it.active?.id?.contentEquals(b.id) == true }.topics.single().let { it.publish && it.receive })
            // The other owner's catalog predates this rename. Pausing the renamed
            // owner must not make that stale catalog authoritative again.
            check(owners[0].open(a))
            val beforeRename = wait(0) { it.connected.size == 2 && it.selected.active?.id?.contentEquals(a.id) == true }
            check(owners[0].rename(a.id, a.name, "Search and Recovery"))
            val renamed = wait(0) { it.selected.active?.sharedName == "Search and Recovery" }
            val renamedA = checkNotNull(renamed.selected.active)
            check(renamedA.id.contentEquals(a.id) && renamedA.slot == a.slot && renamedA.memberId!!.contentEquals(a.memberId))
            check(renamedA.legacyName == a.legacyName && renamedA.memberName == a.memberName)
            check(renamed.publishing == beforeRename.publishing)
            val renamedPeer = wait(1) { it.selected.active?.sharedName == "Search and Recovery" }
            check(renamedPeer.selected.active!!.id.contentEquals(peerA.id))
            check(renamedPeer.selected.topics.single().let { !it.publish && !it.receive })
            check(owners[0].pause(a.id))
            val renamePaused = wait(0) { it.connected.size == 1 && it.pauses[a.slot] == "Paused" }
            val savedA = renamePaused.selected.saved.single { it.slot == a.slot }
            val savedB = renamePaused.selected.saved.single { it.slot == b.slot }
            check(savedA.name == "Search and Recovery" && savedA.id.contentEquals(a.id))
            check(savedA.memberId!!.contentEquals(a.memberId) && savedA.legacyName == a.legacyName)
            check(savedB.name == b.name && savedB.id.contentEquals(b.id) && savedB.memberId!!.contentEquals(b.memberId))
            check(renamePaused.publishing == beforeRename.publishing - a.id.toList())
            exchange(b.id, "second survives first rename and pause")
            check(owners[0].open(a))
            val renameReopened = wait(0) { it.connected.size == 2 && it.selected.active?.sharedName == "Search and Recovery" }
            check(renameReopened.selected.active!!.slot == a.slot)
            check(renameReopened.publishing == beforeRename.publishing)
            // Leaving is scoped to its workspace even while another workspace
            // is selected, and removes its card without a second action.
            check(owners[1].open(peerB))
            wait(1) { it.selected.active?.id?.contentEquals(b.id) == true }
            check(owners[1].leave(a.id))
            val afterLeave = wait(1) { state -> state.connected.size == 1 && state.selected.saved.none { it.slot == peerA.slot } }
            check(afterLeave.selected.active?.id?.contentEquals(b.id) == true)
            check(afterLeave.connected.single().active?.id?.contentEquals(b.id) == true)
            check(File(contexts[1].noBackupFilesDir, "data-fabric/catalog/${peerA.slot}.json").isFile)
            owners[0].close()
            check(owners[0].awaitClosed(30, TimeUnit.SECONDS))
            views[0].clear()
            owners[0] = WorkspaceConnections(contexts[0], emptySet(), { message, complete ->
                received[0].offer(message); complete(true)
            }) { views[0].offer(it) }
            val restored = wait(0) { it.connected.size == 2 }
            check(restored.connected.map { it.active!!.id.toList() }.toSet() == setOf(a.id.toList(), b.id.toList()))
            check(restored.publishing == beforeRename.publishing)
            return JSONObject().put("passed", true).put("scope", "Two concurrent workspaces, four real Iroh owners inside one emulator; not ATAK UI")
                .put("restart_restores_all_enabled_workspaces", true)
                .put("onboarding_sharing_choice", true).put("concurrent_receive", true).put("scope_preserved", true)
                .put("recipient_audience_preserved", true).put("malformed_recipients_rejected", true)
                .put("publish_without_receive_interest", true)
                .put("publication_outcomes", true).put("unsubscribed_target_reported", true).put("callback_failure_isolated", true)
                .put("publication_close_drains", true)
                .put("shared_name_survives_concurrent_pause_and_reopen", true)
                .put("topic_choices_independent", true).put("topic_unsubscribe_resubscribe", true)
                .put("topic_choices_scoped_and_persisted", true).put("publication_outcome_excludes_self", true)
                .put("pause_drains_before_completed", true).put("paused_inspection_does_not_resume", true)
                .put("other_workspace_continues_during_pause", true).put("navigation_preserves_sharing", true).put("sharing_survives_reopen", true)
                .put("multiple_and_zero_publishing_scopes", true).put("selection_preserves_membership", true).put("disconnect_isolated", true)
                .put("leave_scoped_to_workspace", true).put("ended_workspace_auto_removal_preserves_record", true)
        } finally {
            owners.forEach { it.close() }
            owners.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) }
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".lock") }.forEach {
                keys.deleteEntry("dev.datafabric.${it.name.removeSuffix(".lock")}")
            }
            for (i in 0..1) for (name in listOf("fabric-broadcast-sharing", "fabric-feed-interests", "fabric-topic-choices"))
                context.deleteSharedPreferences("${root.name}-$i-$name")
            check(root.deleteRecursively())
        }
    }
}
