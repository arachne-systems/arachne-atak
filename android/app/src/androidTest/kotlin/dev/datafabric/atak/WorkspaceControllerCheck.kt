package dev.arachne.atak

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real controller and JNI with an isolated filesystem, no catalog/UI substitute. */
internal object WorkspaceControllerCheck {
    fun run(context: Context): JSONObject {
        val root = File(context.noBackupFilesDir, "controller-tests/${UUID.randomUUID()}")
        val views = List(4) { LinkedBlockingQueue<WorkspaceView>() }
        val publications = List(4) { LinkedBlockingQueue<JSONObject>() }
        val contexts = List(4) { index -> object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(root, "$index").apply { mkdirs() }
        } }
        val owners = mutableListOf<WorkspaceController>()
        fun start(index: Int): WorkspaceController {
            views[index].clear()
            return WorkspaceController(contexts[index], setOf("streams/sample"), { publication, complete -> publications[index].offer(publication); complete(true) }) { views[index].offer(it) }.also { owners.add(it) }
        }
        fun wait(index: Int, predicate: (WorkspaceView) -> Boolean): WorkspaceView {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (System.nanoTime() < deadline) {
                val view = views[index].poll(1, TimeUnit.SECONDS) ?: continue
                if (!view.busy && predicate(view)) return view
                check(!view.message.startsWith("Workspace operation failed")) { "Controller operation failed" }
            }
            error("Controller state deadline for $index")
        }
        try {
            // Opening a compact link while every member is offline must retain
            // the encrypted bearer and resume after both controllers restart.
            run {
                val offlineViews = List(2) { LinkedBlockingQueue<WorkspaceView>() }
                val offlineContexts = List(2) { index -> object : ContextWrapper(context) {
                    override fun getNoBackupFilesDir() = File(root, "offline-$index").apply { mkdirs() }
                } }
                fun startOffline(index: Int) = WorkspaceController(offlineContexts[index]) { offlineViews[index].offer(it) }
                    .also { owners.add(it) }
                fun waitOffline(index: Int, predicate: (WorkspaceView) -> Boolean): WorkspaceView {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                    while (System.nanoTime() < deadline) {
                        val view = offlineViews[index].poll(1, TimeUnit.SECONDS) ?: continue
                        check(!view.message.startsWith("Workspace operation failed"))
                        if (!view.busy && predicate(view)) return view
                    }
                    error("Offline invitation deadline for $index")
                }
                var offlineIssuer = startOffline(0)
                waitOffline(0) { it.saved.isEmpty() }
                check(offlineIssuer.create("Offline arrival", "Issuer"))
                waitOffline(0) { it.active != null }
                check(offlineIssuer.invite())
                val offlineLink = checkNotNull(waitOffline(0) { it.invitation != null }.invitation)
                offlineIssuer.close(); check(offlineIssuer.awaitClosed(20, TimeUnit.SECONDS))
                var offlineJoiner = startOffline(1)
                waitOffline(1) { it.saved.isEmpty() }
                check(offlineJoiner.join(offlineLink, "Waiting member", false))
                val waiting = waitOffline(1) { it.message.startsWith("Joining…") }
                check(waiting.active!!.preJoin && waiting.saved.single().preJoin)
                val workspace = waiting.active.id
                val pending = WorkspaceStore(offlineContexts[1], pendingJoin = true)
                check(pending.exists(workspace))
                check(WorkspaceStore(offlineContexts[1]).usesRecords(workspace))
                val pendingFile = File(offlineContexts[1].noBackupFilesDir,
                    "data-fabric/pending-joins/${workspace.joinToString("") { "%02x".format(it.toInt() and 255) }}.bin")
                check(pendingFile.isFile && !pendingFile.readBytes().toString(Charsets.ISO_8859_1).contains(offlineLink))
                check(!File(offlineContexts[1].noBackupFilesDir,
                    "data-fabric/pending-invitations/${waiting.active.slot}.bin").exists())
                offlineJoiner.close(); check(offlineJoiner.awaitClosed(20, TimeUnit.SECONDS))
                offlineJoiner = startOffline(1)
                val restoredAttempt = waitOffline(1) { it.saved.singleOrNull()?.preJoin == true }.saved.single()
                check(offlineJoiner.open(restoredAttempt))
                waitOffline(1) { it.message.startsWith("Joining…") }
                offlineIssuer = startOffline(0)
                val issuerRecord = waitOffline(0) { it.saved.size == 1 }.saved.single()
                check(offlineIssuer.open(issuerRecord))
                waitOffline(0) { it.message.startsWith("Saved workspace opened.") }
                val completed = waitOffline(1) { it.message.startsWith("Joined and saved.") }
                check(!completed.active!!.preJoin && completed.active.memberName == "Waiting member" && completed.active.memberId != null)
                check(!pending.exists(workspace))
                check(WorkspaceStore(offlineContexts[1]).usesRecords(workspace))
                offlineJoiner.close(); check(offlineJoiner.awaitClosed(20, TimeUnit.SECONDS))
                offlineIssuer.close(); check(offlineIssuer.awaitClosed(20, TimeUnit.SECONDS))
            }
            val issuer = start(0)
            wait(0) { it.saved.isEmpty() }
            check(issuer.create("Search and Rescue", "Alex Morgan"))
            val created = wait(0) { it.active != null }
            // Hold the existing serial worker while a real UI operation marks it
            // busy. Publication admission must queue, not silently drop chat.
            val executor = WorkspaceController::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(issuer) as java.util.concurrent.ScheduledExecutorService
            val publicationWorkerHeld = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            executor.execute { publicationWorkerHeld.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            check(publicationWorkerHeld.await(10, TimeUnit.SECONDS))
            try {
                check(issuer.invite())
                repeat(32) { check(issuer.publish(created.active!!.id, "streams/sample", byteArrayOf(it.toByte()))) { "Busy controller discarded publication" } }
                check(!issuer.publish(created.active!!.id, "streams/sample", byteArrayOf(33))) { "Publication queue must be bounded" }
            } finally { release.countDown() }
            val link = checkNotNull(wait(0) { it.message.contains("invitation ready", ignoreCase = true) && it.invitation != null }.invitation)
            val invitation = WorkspaceInvitation.decode(link)
            check(WorkspaceInvitation.decode(link.replaceFirst("arachne://", "datafabric://")).toString() == invitation.toString())
            check(link.length == 535 && invitation.getJSONArray("bootstrap_peers").length() == 1)
            check(!invitation.has("workspace") && !invitation.has("checkpoint") && !invitation.has("address"))
            listOf("", "datafabric://join#broken", link + "x", "x".repeat(65537)).forEach {
                check(runCatching { WorkspaceInvitation.decode(it) }.isFailure)
            }
            var joiner = start(1)
            wait(1) { it.saved.isEmpty() }
            val concurrentViews = LinkedBlockingQueue<WorkspaceView>()
            val concurrentContext = object : ContextWrapper(context) {
                override fun getNoBackupFilesDir() = File(root, "concurrent-joiner").apply { mkdirs() }
            }
            val concurrentJoiner = WorkspaceController(concurrentContext) { concurrentViews.offer(it) }
                .also { owners.add(it) }
            fun waitConcurrent(predicate: (WorkspaceView) -> Boolean): WorkspaceView {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (System.nanoTime() < deadline) {
                    val view = concurrentViews.poll(1, TimeUnit.SECONDS) ?: continue
                    check(!view.message.startsWith("Workspace operation failed")) { "Concurrent join failed" }
                    if (!view.busy && predicate(view)) return view
                }
                error("Concurrent join deadline")
            }
            val thirdViews = LinkedBlockingQueue<WorkspaceView>()
            val thirdContext = object : ContextWrapper(context) {
                override fun getNoBackupFilesDir() = File(root, "third-concurrent-joiner").apply { mkdirs() }
            }
            val thirdJoiner = WorkspaceController(thirdContext) { thirdViews.offer(it) }
                .also { owners.add(it) }
            fun waitThird(predicate: (WorkspaceView) -> Boolean): WorkspaceView {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (System.nanoTime() < deadline) {
                    val view = thirdViews.poll(1, TimeUnit.SECONDS) ?: continue
                    check(!view.message.startsWith("Workspace operation failed")) { "Third concurrent join failed" }
                    if (!view.busy && predicate(view)) return view
                }
                error("Third concurrent join deadline")
            }
            waitConcurrent { it.saved.isEmpty() }
            waitThird { it.saved.isEmpty() }
            val concurrentStarted = System.nanoTime()
            check(joiner.join(link, "Jordan Lee"))
            check(concurrentJoiner.join(link, "Taylor Reed"))
            check(thirdJoiner.join(link, "Morgan Reed"))
            val joined = wait(1) { it.message.startsWith("Joined and saved.") }
            val concurrentJoined = waitConcurrent { it.message.startsWith("Joined and saved.") }
            val thirdJoined = waitThird { it.message.startsWith("Joined and saved.") }
            check(System.nanoTime() - concurrentStarted < TimeUnit.SECONDS.toNanos(20))
            check(joined.active!!.id.contentEquals(created.active!!.id))
            check(concurrentJoined.active!!.id.contentEquals(created.active!!.id))
            check(thirdJoined.active!!.id.contentEquals(created.active!!.id))
            check(joined.active.slot != created.active.slot && joined.active.memberName == "Jordan Lee")
            check(concurrentJoined.active.slot != created.active.slot && concurrentJoined.active.memberName == "Taylor Reed")
            check(thirdJoined.active.slot != created.active.slot && thirdJoined.active.memberName == "Morgan Reed")
            check(joined.active.joinPeer == null)
            check(joined.active.memberId?.size == 32)
            check(!checkNotNull(joined.active.memberId).contentEquals(checkNotNull(created.active.memberId)))
            repeat(3) { wait(0) { it.message.startsWith("Membership saved;") } }
            // Automatic coordinator subscription repair and protected save/adopt:
            // no injected routing maps, no direct JNI data operations in this test.
            fun exchange(sender: WorkspaceController, recipient: Int, bytes: ByteArray) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var delivered: JSONObject? = null
                while (delivered == null && System.nanoTime() < deadline) {
                    if (sender.publish(created.active.id, "streams/sample", bytes)) {
                        // Routing may not be ready yet. Retry only after the
                        // previous local operation has finished, never pile up work.
                        val senderWorker = WorkspaceController::class.java.getDeclaredField("worker").apply { isAccessible = true }
                            .get(sender) as java.util.concurrent.ScheduledExecutorService
                        senderWorker.submit {}.get(20, TimeUnit.SECONDS)
                    }
                    delivered = publications[recipient].poll(750, TimeUnit.MILLISECONDS)
                }
                val result = checkNotNull(delivered) { "Controller publication did not arrive" }
                check(result.getString("topic") == "streams/sample")
                check(result.getJSONArray("workspace").toString() == org.json.JSONArray(created.active.id.map { it.toInt() and 255 }).toString())
                check(result.getJSONArray("payload").toString() == org.json.JSONArray(bytes.map { it.toInt() and 255 }).toString())
            }
            exchange(issuer, 1, byteArrayOf(0, -1, 42))
            exchange(joiner, 0, byteArrayOf(7, 0, -1))
            concurrentJoiner.close(); check(concurrentJoiner.awaitClosed(20, TimeUnit.SECONDS))
            thirdJoiner.close(); check(thirdJoiner.awaitClosed(20, TimeUnit.SECONDS))
            // Wrong workspace data must not be re-scoped into the active owner.
            val rejected = LinkedBlockingQueue<Result<JSONObject>>()
            check(issuer.publish(ByteArray(32), "streams/sample", byteArrayOf(99)) { rejected.offer(it) })
            check(checkNotNull(rejected.poll(20, TimeUnit.SECONDS)).isFailure)
            val scopeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!issuer.invite()) {
                check(System.nanoTime() < scopeDeadline)
                Thread.sleep(25)
            }
            wait(0) { it.message.contains("invitation ready", ignoreCase = true) && it.invitation != null }
            Thread.sleep(500)
            val afterWrongScope = mutableListOf<JSONObject>()
            publications[1].drainTo(afterWrongScope)
            check(afterWrongScope.none { it.getJSONArray("payload").toString() == "[99]" })
            check(rejected.isEmpty()) { "Duplicate scope-failure completion" }
            joiner.close(); check(joiner.awaitClosed(20, TimeUnit.SECONDS))
            // Simulate interruption after joined snapshot save but before catalog
            // promotion. Recovery must select and authenticate completed state.
            val catalog = File(contexts[1].noBackupFilesDir, "data-fabric/catalog/${joined.active.slot}.json")
            val metadata = JSONObject(catalog.readText()).put("version", 2)
                .put("join_peer", invitation.getJSONArray("peer")).put("join_address", "192.0.2.1:4242")
            metadata.remove("shared_name") // Exercise the actual legacy v2 schema.
            catalog.writeText(metadata.toString())
            joiner = start(1)
            val saved = wait(1) { it.saved.size == 1 }.saved.single()
            check(joiner.open(saved))
            val recovered = wait(1) { it.message.startsWith("Saved workspace opened.") }
            check(recovered.active!!.memberName == "Jordan Lee")
            check(checkNotNull(recovered.active.memberId).contentEquals(checkNotNull(joined.active.memberId)))
            check(recovered.active.id.contentEquals(created.active.id))
            check(recovered.saved.single().joinPeer == null)
            // A reachable route is not assumed: persist a pending request before
            // a refused port, then recover that pending identity after shutdown.
            check(issuer.invite())
            val latest = checkNotNull(wait(0) { it.message.contains("invitation ready", ignoreCase = true) && it.invitation != null }.invitation)
            publications[0].clear()
            check(joiner.reconnect(latest))
            wait(1) { it.message.startsWith("Workspace connection updated.") }
            exchange(joiner, 0, byteArrayOf(55, 66))
            // A bad port alone is no longer an outage: LAN lookup can find the
            // real issuer. Use a valid test-vector endpoint with no running node.
            val absentPeer = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
                .chunked(2).map { it.toInt(16) }
            val unavailable = hydrateWorkspaceInvitation(contexts[2], WorkspaceInvitation.decode(latest))
                .put("peer", org.json.JSONArray(absentPeer))
                .put("bootstrap_peers", org.json.JSONArray().put(org.json.JSONArray(absentPeer)))
            var pendingOwner = start(2)
            wait(2) { it.saved.isEmpty() }
            check(pendingOwner.join(unavailable, "Casey Reed"))
            val failed = wait(2) { it.message.startsWith("Joining… No workspace member") }
            check(failed.saved.single().joinPeer != null && failed.active?.joinPeer != null)
            val pendingId = failed.saved.single().id
            val pendingStore = WorkspaceStore(contexts[2], pendingJoin = true)
            val before = pendingStore.load(pendingId)
            pendingOwner.close(); check(pendingOwner.awaitClosed(20, TimeUnit.SECONDS))
            pendingOwner = start(2)
            val pendingRecord = wait(2) { it.saved.size == 1 }.saved.single()
            check(pendingOwner.open(pendingRecord))
            val pendingRestored = wait(2) { it.message.startsWith("Joining… No workspace member") }
            check(pendingRestored.active!!.memberName == "Casey Reed")
            check(before.contentEquals(pendingStore.load(pendingId)))
            check(!pendingRestored.active.joinPinned)
            check(issuer.invite())
            val routedLink = checkNotNull(wait(0) { it.message.contains("invitation ready", ignoreCase = true) && it.invitation != null && it.invitation != latest }.invitation)
            val peers = WorkspaceInvitation.decode(routedLink).getJSONArray("bootstrap_peers")
            check(routedLink.length == link.length) { "Concurrent admission changed compact link size: ${routedLink.length} vs ${link.length}" }
            check(peers.length() == 3) { "Concurrent admission was not retained in bootstrap peers: ${peers.length()}" }
            wait(1) { it.message.startsWith("Membership synchronized.") }
            // Simulate a crash after the durable pre-send pin. Even if a later
            // retry cannot connect, it must not switch to the available helper.
            pendingOwner.close(); check(pendingOwner.awaitClosed(20, TimeUnit.SECONDS))
            val pendingFile = File(contexts[2].noBackupFilesDir, "data-fabric/catalog/${pendingRecord.slot}.json")
            val pinned = JSONObject(pendingFile.readText()).put("join_pinned", true)
                .put("join_peers", JSONArray((0 until minOf(2, peers.length())).map { peers.getJSONArray(it) }))
            pendingFile.writeText(pinned.toString())
            pendingOwner = start(2)
            val pinnedRecord = wait(2) { it.saved.size == 1 }.saved.single()
            check(pinnedRecord.joinPinned && pinnedRecord.joinPeers.size == 2)
            check(pendingOwner.open(pinnedRecord))
            wait(2) { it.message.contains("handling your join is offline") }
            check(pendingOwner.retryJoin())
            val stillPending = wait(2) { it.message.contains("handling your join is offline") }
            check(stillPending.saved.single().joinPinned)
            check(!WorkspaceStore(contexts[2]).exists(pendingId))
            check(WorkspaceStore(contexts[2]).usesRecords(pendingId))
            issuer.close(); check(issuer.awaitClosed(20, TimeUnit.SECONDS))
            val late = start(3)
            wait(3) { it.saved.isEmpty() }
            check(late.join(routedLink, "Late arrival"))
            val offlineJoined = wait(3) { it.message.startsWith("Joined and saved.") }
            check(offlineJoined.active!!.joinPeer == null)
            check(offlineJoined.active.id.contentEquals(created.active.id))
            wait(1) { it.message.startsWith("Membership saved;") }
            exchange(joiner, 3, byteArrayOf(88, 89))
            // Separate workspace preserves the ordinary-helper/all-admins-offline
            // scenario above while exercising real Android management persistence.
            val manager = start(0)
            wait(0) { it.saved.isNotEmpty() }
            check(manager.create("Training Team", "Team coordinator"))
            val managed = wait(0) { it.active?.name == "Training Team" }
            check(manager.invite())
            val managementLink = checkNotNull(wait(0) { it.message.contains("invitation ready", ignoreCase = true) && it.invitation != null }.invitation)
            check(late.join(managementLink, "Second coordinator"))
            val member = wait(3) { it.message.startsWith("Joined and saved.") && it.active?.id?.contentEquals(managed.active!!.id) == true }
            check(member.active!!.sharedName == "Training Team")
            wait(0) { it.message.startsWith("Membership saved;") }
            val named = wait(0) { it.members.any { m -> m.name == "Second coordinator" } }
            check(named.members.single { it.self }.name == "Team coordinator")
            check(named.members.single { !it.self }.id == member.active!!.memberId!!.joinToString("") { "%02x".format(it.toInt() and 255) })
            check(manager.manage(managed.active!!.id, checkNotNull(member.active!!.memberId), "promote"))
            wait(0) { it.message.startsWith("Membership change saved.") }
            val promoted = wait(3) { it.message.startsWith("Membership synchronized.") }
            check(promoted.members.count { it.administrator } == 2)
            check(late.invite())
            wait(3) { it.message.contains("invitation ready", ignoreCase = true) && it.invitation != null } // Receiving peer actually gained authority.
            wait(0) { it.message.startsWith("Membership synchronized.") }
            check(manager.manage(managed.active!!.id, checkNotNull(member.active.memberId), "remove"))
            wait(0) { it.message.startsWith("Membership change saved.") }
            val removed = wait(3) { it.message.startsWith("Your membership has ended.") }
            check(removed.active?.ended == true && removed.invitation == null && removed.feeds.isEmpty())
            check(removed.saved.single { it.id.contentEquals(managed.active.id) }.sharedName == "Training Team")
            val removalStore = WorkspaceStore(contexts[3])
            check(removalStore.usesRecords(managed.active!!.id))
            check(!removalStore.exists(managed.active.id))
            late.close(); check(late.awaitClosed(20, TimeUnit.SECONDS))
            val reopened = start(3)
            val savedRemoval = wait(3) { it.saved.size == 2 }.saved.single { it.id.contentEquals(managed.active.id) }
            check(savedRemoval.sharedName == "Training Team")
            check(reopened.open(savedRemoval))
            val stillRemoved = wait(3) { it.message.startsWith("Your membership has ended.") }
            check(stillRemoved.active?.ended == true && stillRemoved.invitation == null)
            val terminalRecord = stillRemoved.saved.single { it.id.contentEquals(managed.active.id) }
            check(terminalRecord.sharedName == "Training Team" && terminalRecord.name == "Training Team")
            check(terminalRecord.slot == savedRemoval.slot && terminalRecord.legacyName == savedRemoval.legacyName)
            // The creator retains a legacy snapshot. Losing its authoritative DB
            // must fail closed instead of resurrecting that stale membership.
            manager.close(); check(manager.awaitClosed(20, TimeUnit.SECONDS))
            val id = created.active.id
            val hex = id.joinToString("") { "%02x".format(it.toInt() and 255) }
            val directory = File(contexts[0].noBackupFilesDir, "data-fabric/workspaces")
            val database = File(directory, "$hex.db")
            val held = File(directory, "$hex.held")
            val marker = File(directory, "$hex.backend")
            check(WorkspaceStore(contexts[0]).exists(id))
            check(database.renameTo(held))
            val recoveryOwner = start(0)
            val recoveryRecord = wait(0) { it.saved.size == 2 }.saved.single { it.id.contentEquals(id) }
            check(recoveryOwner.open(recoveryRecord))
            check(wait(0) { it.message.startsWith("Workspace operation failed") }.active == null)
            check(!database.exists() && held.renameTo(database))
            marker.writeText("unknown-backend\n")
            check(recoveryOwner.open(recoveryRecord))
            check(wait(0) { it.message.startsWith("Workspace operation failed") }.active == null)
            check(marker.delete())
            check(recoveryOwner.open(recoveryRecord))
            wait(0) { it.message.startsWith("Saved workspace opened.") }
            check(marker.readText() == "records-v1\n")
            // Persist opposing valid demotions with both controller pollers closed.
            // Reopen real controllers to discover the disagreement over Iroh.
            check(recoveryOwner.create("Partition exercise", "Coordinator A"))
            val branchA = wait(0) { it.active?.name == "Partition exercise" }.active!!
            check(recoveryOwner.invite())
            val branchLink = checkNotNull(wait(0) { it.invitation != null && it.message.contains("invitation ready", ignoreCase = true) }.invitation)
            check(joiner.join(branchLink, "Coordinator B"))
            val branchB = wait(1) { it.message.startsWith("Joined and saved.") }.active!!
            wait(0) { it.message.startsWith("Membership saved;") }
            check(recoveryOwner.manage(branchA.id, checkNotNull(branchB.memberId), "promote"))
            wait(0) { it.message.startsWith("Membership change saved.") }
            wait(1) { it.members.count { m -> m.administrator } == 2 }
            recoveryOwner.close(); check(recoveryOwner.awaitClosed(20, TimeUnit.SECONDS))
            joiner.close(); check(joiner.awaitClosed(20, TimeUnit.SECONDS))
            for ((index, record, other) in listOf(Triple(0, branchA, branchB), Triple(1, branchB, branchA))) {
                val native = FabricSession(contexts[index], record.slot) {}
                fun nativeCall(request: JSONObject): JSONObject {
                    val binary = request.remove("snapshot") as? ByteArray ?: ByteArray(0)
                    val response = LinkedBlockingQueue<Result<Array<ByteArray>>>()
                    check(native.requestStored(request.toString().toByteArray(Charsets.UTF_8), binary) { response.offer(it) })
                    val parts = checkNotNull(response.poll(20, TimeUnit.SECONDS)).getOrThrow()
                    return JSONObject(String(parts[0], Charsets.UTF_8)).also {
                        if (parts[1].isNotEmpty()) it.put("snapshot", parts[1])
                    }
                }
                try {
                    val nativeStore = WorkspaceStore(contexts[index])
                    nativeStore.restoreRecords(record.id, ::nativeCall)
                    val staged = nativeCall(JSONObject().put("op", "stage_management").put("action",
                        JSONObject().put("kind", "demote").put("member", org.json.JSONArray(checkNotNull(other.memberId).map { it.toInt() and 255 }))))
                    nativeStore.commitAdmission(staged, ::nativeCall)
                } finally { native.close(); check(native.awaitClosed(20, TimeUnit.SECONDS)) }
            }
            val conflictOwners = listOf(start(0), start(1))
            for ((index, record) in listOf(branchA, branchB).withIndex()) {
                val savedBranch = wait(index) { it.saved.any { r -> r.id.contentEquals(record.id) } }.saved.single { it.id.contentEquals(record.id) }
                check(conflictOwners[index].open(savedBranch))
                wait(index) { it.message.startsWith("Saved workspace opened.") }
            }
            check(conflictOwners[0].invite())
            val conflictedLink = checkNotNull(wait(0) { it.invitation != null && it.message.contains("invitation ready", ignoreCase = true) }.invitation)
            check(conflictOwners[1].reconnect(conflictedLink))
            for (index in 0..1) {
                val warning = wait(index) { it.message.contains("Membership differs from a connected peer.") }
                check(warning.active != null)
                check(warning.members.single { it.self }.administrator)
                check(warning.members.none { !it.self && it.administrator })
            }
            // An unrelated successful UI action must not erase the warning.
            check(conflictOwners[1].invite())
            wait(1) { it.message.contains("invitation ready", ignoreCase = true) && it.message.contains("Membership differs from a connected peer.") }
            return JSONObject().put("passed", true).put("scope", "Real workspace controllers and Iroh endpoints inside one emulator; no ATAK UI")
                .put("persisted_branch_disagreement_visible", true).put("disagreement_survives_status_refresh", true)
                .put("native_storage", true).put("missing_db_no_legacy_fallback", true)
                .put("unknown_backend_rejected", true).put("missing_marker_repaired", true)
                .put("authenticated_member_names", true).put("management_promotion_received", true).put("removal_saved_and_reopened", true)
                .put("terminal_removal_preserves_saved_name", true)
                .put("offline_first_open_retries_after_restart", true).put("admin_offline_helper_join", true).put("pinned_unknown_outcome_survives_restart", true)
                .put("reconnect_after_open", true).put("busy_publications_queued_and_bounded", true).put("protected_controller_exchange", true).put("wrong_workspace_not_forwarded", true).put("join_saved", true).put("joiner_reopened", true).put("invalid_links_rejected", 4).put("pending_restart", true).put("interrupted_catalog_promotion_recovered", true)
        } finally {
            owners.forEach { it.close(); check(it.awaitClosed(20, TimeUnit.SECONDS)) }
            // Only aliases named by these isolated identity lock files belong to this run.
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".lock") }.forEach {
                keys.deleteEntry("dev.datafabric.${it.name.removeSuffix(".lock")}")
            }
            check(root.deleteRecursively())
        }
    }
}
