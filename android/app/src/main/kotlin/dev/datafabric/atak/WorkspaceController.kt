package dev.arachne.atak

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class LocalWorkspace(val legacyName: String, val slot: String, val id: ByteArray, val memberName: String? = null, val joinPeer: ByteArray? = null, val joinAddress: String? = null, val memberId: ByteArray? = null, val joinRoutes: List<WorkspaceRoute> = emptyList(), val joinPeers: List<ByteArray> = emptyList(), val joinPinned: Boolean = false, val sharedName: String? = null, val ended: Boolean = false, val preJoin: Boolean = false) {
    val name: String get() = sharedName ?: legacyName
}

internal fun checkedWorkspaceName(value: String): String {
    require(value.codePoints().noneMatch { Character.isISOControl(it) || it in 0xd800..0xdfff || it == 0x061c || it in 0x200e..0x200f || it in 0x202a..0x202e || it in 0x2066..0x2069 }) { "Use a name without control or direction characters." }
    val name = value.trim()
    require(name.codePointCount(0, name.length) in 1..80 && name.toByteArray(Charsets.UTF_8).size <= 320) { "Use a workspace name of 1 to 80 characters." }
    return name
}

internal fun checkedMemberName(value: String): String {
    require(value.codePoints().noneMatch { Character.isISOControl(it) || it in 0xd800..0xdfff || it == 0x061c || it in 0x200e..0x200f || it in 0x202a..0x202e || it in 0x2066..0x2069 })
    val name = value.trim()
    require(name.codePointCount(0, name.length) in 1..80 && name.toByteArray(Charsets.UTF_8).size <= 256)
    return name
}

/** Local workspace catalog access is independent of a running transport owner. */
internal class WorkspaceCatalog(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "data-fabric/catalog")
    companion object {
        // AtomicFile recovery reads can delete another owner's in-flight write.
        // ponytail: serialize catalog access in this process; use directory locks if contention is measured.
        private val lock = Any()
    }

    fun read(): List<LocalWorkspace> = synchronized(lock) {
        if (!directory.exists()) return@synchronized emptyList()
        check(directory.isDirectory)
        val files = checkNotNull(directory.listFiles { _, name -> name.endsWith(".json") || name.endsWith(".json.bak") })
            .map { File(directory, it.name.removeSuffix(".bak")) }.distinctBy { it.name }.sortedBy { it.name }
        // ponytail: load local metadata linearly; page the catalog if measured UI latency requires it.
        files.mapNotNull { file ->
            val bytes = AtomicFile(file).openRead().use { input ->
                val buffer = ByteArray(4097)
                var size = 0
                while (size < buffer.size) { val n = input.read(buffer, size, buffer.size-size); if (n < 0) break; size += n }
                check(size in 1..4096)
                buffer.copyOf(size)
            }
            val data = JSONObject(String(bytes, Charsets.UTF_8))
            val version = data.getInt("version")
            // The old Cancel writer mixed terminal v6 with pending fields.
            val legacyCancel = version == 6 && data.optBoolean("ended") && data.has("join_peer")
            val pending = version in setOf(2, 3, 5, 7, 8) || legacyCancel
            check(when (version) { 1 -> data.length() == 4; 2 -> data.length() == 6; 3 -> data.length() == 8; 4 -> data.length() == 5; 5 -> data.length() == 9; 6 -> data.length() == if (legacyCancel) 9 + if (data.has("join_address")) 1 else 0 else 6; 7 -> data.length() == 9; 8 -> data.length() == 11; 9 -> data.length() == 6 && data.getBoolean("join_canceled"); else -> false })
            check(version < 4 || data.has("shared_name"))
            val name = data.getString("name")
            val slot = data.getString("slot")
            val id = data.getJSONArray("workspace").bytes()
            check(name.isNotBlank() && name.codePointCount(0, name.length) <= 80 && name.toByteArray(Charsets.UTF_8).size <= 320 && id.size == 32)
            check(slot.matches(Regex("workspace-[a-f0-9]{32}")) && file.name == "$slot.json")
            val peer = if (pending) data.getJSONArray("join_peer").bytes().also { check(it.size == 32) } else null
            val address = if (pending && data.has("join_address")) data.getString("join_address").also { check(it.length in 3..80) } else null
            val routes = if (version in setOf(3, 5, 7, 8) || legacyCancel) WorkspaceInvitation.routes(data.getJSONArray("join_routes")) else emptyList()
            val peers = if (version in setOf(7, 8)) data.getJSONArray("join_peers").let { array ->
                check(array.length() <= 2)
                (0 until array.length()).map { array.getJSONArray(it).bytes().also { value -> check(value.size == 32 && value.any { byte -> byte.toInt() != 0 }) } }
            } else emptyList()
            check((listOfNotNull(peer) + routes.map { it.peer } + peers).map { it.toList() }.distinct().size ==
                listOfNotNull(peer).size + routes.size + peers.size)
            val record = LocalWorkspace(name, slot, id, memberName = if (version == 8) checkedMemberName(data.getString("member_name")) else null,
                joinPeer = peer, joinAddress = address,
                joinRoutes = routes, joinPeers = peers,
                joinPinned = if (version in setOf(3, 5, 7, 8) || legacyCancel) data.getBoolean("join_pinned") else pending,
                sharedName = if (version >= 4 && !data.isNull("shared_name")) checkedWorkspaceName(data.getString("shared_name")) else null,
                ended = version == 6 && data.getBoolean("ended"), preJoin = version == 8 && data.getBoolean("prejoin"))
            if (version == 9 || legacyCancel) {
                WorkspaceStore(context, pendingJoin = true).retire(id)
                WorkspaceStore(context).discardPendingJoin(id)
                PendingInvitationStore(context, slot).delete()
                delete(record)
                check(!file.exists() && !File(file.path + ".bak").exists())
                null
            } else record
        }
    }

    fun save(record: LocalWorkspace) = synchronized(lock) {
        require(!record.ended || record.joinPeer == null) { "Cancel pending joins before ending membership" }
        check(directory.isDirectory || directory.mkdirs())
        val target = AtomicFile(File(directory, "${record.slot}.json"))
        check(record.joinAddress == null || record.joinPeers.isEmpty())
        val version = if (record.ended) 6 else if (record.preJoin) 8 else if (record.joinPeer == null) 4 else if (record.joinAddress == null) 7 else 5
        val data = JSONObject().put("version", version).put("name", record.legacyName)
            .put("shared_name", record.sharedName ?: JSONObject.NULL)
            .put("slot", record.slot).put("workspace", record.id.json())
        if (record.ended) data.put("ended", true)
        if (record.joinPeer != null) {
            data.put("join_peer", record.joinPeer.json())
            record.joinAddress?.let { data.put("join_address", it) }
            data.put("join_routes", JSONArray(record.joinRoutes.map { it.json() }))
                .put("join_pinned", record.joinPinned)
            if (version in setOf(7, 8)) data.put("join_peers", JSONArray(record.joinPeers.map { it.json() }))
            if (version == 8) data.put("prejoin", true).put("member_name", checkedMemberName(checkNotNull(record.memberName)))
        }
        write(target, data)
        val saved = read().single { it.slot == record.slot && it.id.contentEquals(record.id) }
        check(saved.ended == record.ended && saved.legacyName == record.legacyName && saved.sharedName == record.sharedName
            && saved.joinPeer?.toList() == record.joinPeer?.toList() && saved.joinAddress == record.joinAddress
            && saved.joinPinned == record.joinPinned && saved.joinRoutes.map { it.json().toString() } == record.joinRoutes.map { it.json().toString() }
            && saved.joinPeers.map { it.toList() } == record.joinPeers.map { it.toList() } && saved.preJoin == record.preJoin
            && (!record.preJoin || saved.memberName == record.memberName))
    }

    /** Caller closes the pending session before making cleanup durable. */
    fun cancelJoin(record: LocalWorkspace) = synchronized(lock) {
        require(record.joinPeer != null && record.slot.matches(Regex("workspace-[a-f0-9]{32}")))
        check(directory.isDirectory || directory.mkdirs())
        write(AtomicFile(File(directory, "${record.slot}.json")), JSONObject()
            .put("version", 9).put("name", record.legacyName).put("shared_name", record.sharedName ?: JSONObject.NULL)
            .put("slot", record.slot).put("workspace", record.id.json()).put("join_canceled", true))
        check(read().none { it.slot == record.slot })
    }

    private fun write(target: AtomicFile, data: JSONObject) {
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096)
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }

    fun delete(record: LocalWorkspace) = synchronized(lock) {
        require(record.slot.matches(Regex("workspace-[a-f0-9]{32}")))
        AtomicFile(File(directory, "${record.slot}.json")).delete()
    }

    private fun ByteArray.json() = JSONArray(map { it.toInt() and 255 })
    private fun JSONArray.bytes() = ByteArray(length()) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
}

internal data class WorkspaceInvite(val number: Int, val key: ByteArray, val expiresAt: Long, val enabled: Boolean, val personal: Boolean, val automatic: Boolean, val approved: Boolean, val requestAccess: Boolean = false)

internal data class PendingApproval(val attemptId: ByteArray, val name: String, val endpoint: ByteArray, val request: ByteArray)
internal data class WorkspaceView(val saved: List<LocalWorkspace>, val active: LocalWorkspace?, val message: String, val busy: Boolean, val invitation: String? = null, val feeds: List<FeedView> = emptyList(), val members: List<WorkspaceMember> = emptyList(), val topics: List<WorkspaceTopic> = emptyList(), val invitations: List<WorkspaceInvite> = emptyList(), val legacyInvitations: Boolean = false, val approvals: List<PendingApproval> = emptyList(), val continuity: String? = null, val continuityChangedAt: Long? = null, val invitationKey: ByteArray? = null,
    val metrics: WorkspaceMetrics? = null, val metricsError: String? = null, val attention: Boolean = false, val statusWorkspace: LocalWorkspace? = null,
    val attentionReason: String? = null, val activity: WorkspaceActivityView = WorkspaceActivityView("empty", null))

/** ATAK lifecycle coordinator. Rust owns crypto; this owner commits ciphertext
 * before publishing catalog metadata or reporting a created workspace. */
internal class WorkspaceController(
    private val context: Context,
    dataTopics: Set<String> = CotTopics.standard,
    private val received: ((JSONObject, (Boolean) -> Unit) -> Unit)? = null,
    private val onClosed: () -> Unit = {},
    private val changed: (WorkspaceView) -> Unit
) : AutoCloseable {
    private val dataTopics = dataTopics.toSet()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val catalog = WorkspaceCatalog(context)
    private val store = WorkspaceStore(context)
    private var records = emptyList<LocalWorkspace>()
    private var active: LocalWorkspace? = null
    @Volatile private var session: FabricSession? = null
    private val controlDrain = ControlDrain(32, { pollWorkspaceControl().also { if (!it) ArachneTrace.mark("drain_idle") } }) {
        try { worker.execute(it) } catch (_: RejectedExecutionException) { /* close won the race */ }
    }
    private var data: WorkspaceData? = null
    private var dataEpoch: Long? = null
    private var members: WorkspaceMembers? = null
    private var feeds: WorkspaceFeeds? = null
    private var topicChoices: WorkspaceTopics? = null
    private var lastFeedViews = emptyList<FeedView>()
    @Volatile var shutdownSucceeded = false
        private set
    private var closed = false
    private var busy = false
    private val joinSignalQueued = AtomicBoolean(false)
    private var joinRetryDeadline: ScheduledFuture<*>? = null
    private var pendingPublications = 0 // Guarded by this; includes running work.
    private var metrics: WorkspaceMetrics? = null
    private var metricsError: String? = null
    private var activity = WorkspaceActivityView("empty", null)
    private var nextMetrics = 0L
    private var lastMessage = "Loading workspaces…"
    private var lastAttention = false
    private var lastFailure: String? = null
    private var statusWorkspace: LocalWorkspace? = null
    private var invitation: String? = null
    private var invitationKey: ByteArray? = null
    private val approvals = linkedMapOf<String, PendingApproval>()
    private var nextApprovalSync = 0L
    private var nextJoinAttempt = 0L
    // Debug timeline only: the last control state the worker handled.
    private var lastControlState = "none"
    private fun onWorkSignal() {
        val joining = synchronized(this) { !closed && active?.joinPeer != null }
        if (!joining) {
            controlDrain.signal()
            return
        }
        if (!joinSignalQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                joinSignalQueued.set(false)
                val preJoin = synchronized(this) { !closed && active?.preJoin == true }
                if (preJoin) pollPreJoin() else pollPendingJoin(force = true)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            joinSignalQueued.set(false)
        }
    }
    private val joinRetry = IdempotentRetryCounter()
    private var invitations = emptyList<WorkspaceInvite>()
    private var legacyInvitations = false
    private var membershipWarning: String? = null
    private var continuityStatus: String? = null
    private var continuityChangedAt: Long? = null
    @Volatile private var announcePresence = true
    private var nextPresenceView = 0L
    companion object {
        /** True while a page that shows member contact times is visible.
         * The roster reply is several hundred KB at 500 members; poll it at
         * 1 s only while someone is looking, else every 5 s. Presence itself
         * refreshes every 30 s and stays fresh for 70 s in arachne-runtime. */
        @Volatile var presenceWatched = false
        private const val PRESENCE_VIEW_MS = 1000L
        private const val PRESENCE_VIEW_IDLE_MS = 5000L
    }
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    private val networkChangeRequest = "{\"op\":\"network_change\"}".toByteArray(Charsets.UTF_8)
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            announcePresence = true
            val current = synchronized(this@WorkspaceController) { if (closed) null else session }
            if (current != null && !current.request(networkChangeRequest) { result ->
                result.onSuccess { Log.i("Arachne", "NATIVE_NETWORK_CHANGE_NOTIFIED") }
                    .onFailure { Log.w("Arachne", "NATIVE_NETWORK_CHANGE_FAILED", it) }
                onWorkSignal()
            }) Log.w("Arachne", "NATIVE_NETWORK_CHANGE_SKIPPED")
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback)
        submit("Loading saved workspaces…") { records = readCatalog(); "Choose a workspace or create one." }
        worker.scheduleWithFixedDelay({
            synchronized(this) { if (closed || busy) return@scheduleWithFixedDelay }
            try {
                if (active?.joinPeer != null) return@scheduleWithFixedDelay
                // Data recovery can wait on an offline member. Service admission
                // first so that a join reply is not hidden behind that wait.
                pollMetrics()
                pollPresence()
                data?.tick()
                synchronized(this) { if (closed || busy) return@scheduleWithFixedDelay }
                refreshFeeds()
            }
            catch (error: Exception) {
                closeSession()
                Log.e("Arachne", "WORKSPACE_DATA_SAVE_FAILED", error)
                emit("Shared data stopped. Open the saved workspace to recover.", false, true)
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun emit(message: String, working: Boolean, attention: Boolean = false) {
        lastMessage = message; lastAttention = attention
        if (attention) lastFailure = message
        active?.let { statusWorkspace = it }
        val messages = listOfNotNull(lastFailure, message, membershipWarning, continuityStatus).distinct().joinToString("\n")
        synchronized(this) { if (!closed) changed(WorkspaceView(records.toList(), active, messages, working, invitation, feeds?.views().orEmpty(), members?.views.orEmpty(), topicChoices?.views().orEmpty(), invitations, legacyInvitations, approvals.values.toList(), continuityStatus, continuityChangedAt, invitationKey?.copyOf(),
            metrics, metricsError, lastFailure != null || membershipWarning != null, statusWorkspace, lastFailure ?: membershipWarning, activity)) }
    }

    private fun pollMetrics() {
        val owner = session ?: return
        val record = active?.takeIf { it.joinPeer == null } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextMetrics) return
        nextMetrics = now + 2000
        try {
            val value = call(owner, JSONObject().put("op", "workspace_metrics"))
            check(value.getJSONArray("workspace").bytes().contentEquals(record.id))
            activity = WorkspaceActivityView.read(value, activity)
            metrics = WorkspaceMetrics.read(value, metrics, now, synchronized(this) { pendingPublications })
            Log.i("Arachne", "WORKSPACE_METRICS admission_queue=${metrics?.admissionQueue} admission_queue_bytes=${metrics?.admissionQueueBytes} admission_in_flight=${metrics?.admissionInFlight} approval_pending=${metrics?.approvalPending}")
            metricsError = null
        } catch (error: Exception) {
            metrics = null
            metricsError = "Measurements could not be read. Retrying automatically."
            Log.w("Arachne", "WORKSPACE_METRICS_UNAVAILABLE", error)
        }
        emit(lastMessage, synchronized(this) { busy }, lastAttention)
    }

    @Synchronized
    private fun submit(message: String, action: () -> String): Boolean {
        if (closed || busy) return false
        busy = true
        worker.execute {
            lastFailure = null
            emit(message, true)
            var failed = false
            val result = try { action() } catch (error: Exception) {
                failed = true
                Log.e("Arachne", "WORKSPACE_OPERATION_FAILED", error)
                "Workspace operation failed. Saved state was not reset."
            }
            synchronized(this) { busy = false }
            ArachneTrace.mark("busy_end", if (failed) "failed" else "ok")
            // Control that arrived during the operation was refused (busy) and
            // raised no new signal; resume it from the native signal/deadline.
            if (synchronized(this) { active?.joinPeer == null }) onWorkSignal()
            emit(result, false, failed)
        }
        return true
    }

    fun create(name: String, memberName: String, sharing: Boolean = true): Boolean = submit("Creating workspace…") {
        val title = checkedWorkspaceName(name)
        records = readCatalog()
        closeSession()
        val slot = "workspace-" + UUID.randomUUID().toString().replace("-", "")
        val next = FabricSession(context, slot) {}
        try {
            val created = call(next, JSONObject().put("op", "create_workspace").put("display_name", memberName).put("workspace_name", title))
            val id = created.getJSONArray("workspace").bytes()
            val sealed = call(next, JSONObject().put("op", "seal_workspace"))
            check(sealed.getJSONArray("workspace").bytes().contentEquals(id))
            store.save(id, sealed.get("snapshot") as ByteArray)
            store.enableRecords(id) { call(next, it) }
            val record = LocalWorkspace(title, slot, id, created.getJSONObject("member").getString("display_name"),
                memberId = created.getJSONObject("member").getJSONArray("id").bytes(), sharedName = checkedWorkspaceName(created.getString("workspace_name")))
            // Persist consent before exposing a saved membership or sending admission.
            check(WorkspaceConnections.saveSharing(context, id, sharing)) { "Could not save sharing choice" }
            saveRecord(record)
            records = readCatalog()
            session = next
            next.onWork { onWorkSignal() }
            controlDrain.signal() // drain anything that arrived before the listener was set
            active = record
            configureData(created)
            Log.i("Arachne", "LOCAL_WORKSPACE_CREATED slot=$slot")
            "Saved locally. You are the first member.\nCreate an invitation to add someone. Workspace ready. Sharing waits for reachable subscribers."
        } catch (error: Exception) {
            if (session === next) closeSession()
            else { next.close(); check(next.awaitClosed(15, TimeUnit.SECONDS)) }
            throw error
        }
    }

    fun open(record: LocalWorkspace): Boolean = submit("Opening workspace…") {
        statusWorkspace = record
        records = readCatalog()
        val saved = records.single { it.slot == record.slot && it.id.contentEquals(record.id) }
        if (saved.preJoin) {
            return@submit resumePreJoin(saved)
        }
        closeSession()
        val next = FabricSession(context, saved.slot) {}
        try {
            val restored = if (store.usesRecords(saved.id)) {
                store.restoreRecords(saved.id) { call(next, it) }
            } else {
                val legacyComplete = store.exists(saved.id)
                check(legacyComplete || saved.joinPeer != null) { "Saved membership is missing" }
                val source = if (legacyComplete) store else WorkspaceStore(context, pendingJoin = true)
                call(next, JSONObject().put("op", if (legacyComplete) "restore_workspace" else "restore_pending_join")
                    .put("workspace", saved.id.json()).put("snapshot", source.load(saved.id))).also {
                    // Legacy removal is already terminal and consumes its native session.
                    if (it.optString("state") != "removed") store.enableRecords(saved.id) { command -> call(next, command) }
                }
            }
            val complete = restored.optString("state") != "pending"
            check(complete || saved.joinPeer != null) { "Pending join route is missing" }
            check(restored.getJSONArray("workspace").bytes().contentEquals(saved.id))
            // Terminal records contain membership identity, not a new name decision.
            val terminal = restored.optString("state") == "removed"
            var opened = saved.copy(memberName = restored.optJSONObject("member")?.getString("display_name"),
                memberId = restored.optJSONObject("member")?.getJSONArray("id")?.bytes(),
                sharedName = if (terminal) saved.sharedName else sharedName(restored))
            if (complete && saved.joinPeer != null) {
                opened = opened.copy(joinPeer = null, joinAddress = null, joinRoutes = emptyList(), joinPeers = emptyList(), joinPinned = false)
                saveRecord(opened)
                WorkspaceStore(context, pendingJoin = true).retire(saved.id)
                records = readCatalog()
            }
            if (opened.sharedName != saved.sharedName) { saveRecord(opened); records = readCatalog() }
            session = next
            next.onWork { onWorkSignal() }
            controlDrain.signal() // drain anything that arrived before the listener was set
            active = opened
            if (restored.optString("state") == "removed") {
                endMembership()
                return@submit "Your membership has ended. This workspace is no longer sharing."
            }
            if (!complete) {
                return@submit finishJoin()
            }
            configureData(restored)
            Log.i("Arachne", "LOCAL_WORKSPACE_OPENED slot=${saved.slot}")
            "Saved workspace opened."
        } catch (error: Exception) {
            if (session === next) closeSession()
            else { next.close(); check(next.awaitClosed(15, TimeUnit.SECONDS)) }
            throw error
        }
    }

    fun join(link: String, memberName: String, sharing: Boolean = true): Boolean =
        join(WorkspaceInvitation.decode(link.trim()), memberName, sharing)

    fun join(input: JSONObject, memberName: String, sharing: Boolean = true): Boolean {
        val owned = JSONObject(input.toString())
        val expectedWorkspace = owned.optJSONArray("workspace")?.bytes()
        return submit("Saving join attempt…") {
            records = readCatalog()
            closeSession()
            val slot = "workspace-" + UUID.randomUUID().toString().replace("-", "")
            if (!owned.has("checkpoint")) {
                val id = WorkspaceInvitation.workspaceHint(owned)
                check(records.none { it.id.contentEquals(id) }) { "Workspace already saved" }
                val peers = WorkspaceInvitation.bootstrapPeers(owned)
                return@submit beginCompactJoin(slot, owned, checkedMemberName(memberName), sharing, peers, id)
            }
            beginJoin(slot, owned, memberName, sharing, expectedWorkspace)
        }
    }

    private fun beginJoin(slot: String, invitation: JSONObject, memberName: String, sharing: Boolean,
        expectedWorkspace: ByteArray? = null): String {
        val next = FabricSession(context, slot) {}
        try {
            val verified = call(next, JSONObject().put("op", "inspect_invitation")
                .put("invitation", invitation.getJSONArray("invitation"))
                .put("checkpoint", invitation.getJSONArray("checkpoint")))
            val id = verified.getJSONArray("workspace").bytes()
            expectedWorkspace?.let { require(it.contentEquals(id)) { "Invitation workspace mismatch" } }
            check(records.none { it.id.contentEquals(id) && !(it.slot == slot && it.preJoin) }) { "Workspace already saved" }
            val primary = invitation.getJSONArray("peer").bytes()
            val routes = invitation.optJSONArray("routes")?.let(WorkspaceInvitation::routes).orEmpty()
            val peers = WorkspaceInvitation.bootstrapPeers(invitation)
            // These are Iroh address hints only. Rust retains the authenticated
            // endpoint identities and owns selection/retry/recovery.
            (listOfNotNull(invitation.optString("address").takeIf { it.isNotBlank() }?.let { WorkspaceRoute(primary, it) }) + routes)
                .forEach { route -> callOptional(next, JSONObject().put("op", "add_address_hint").put("peer", route.peer.json()).put("address", route.address)) }
            val pending = call(next, JSONObject().put("op", "begin_join")
                .put("invitation", invitation.getJSONArray("invitation"))
                .put("checkpoint", invitation.getJSONArray("checkpoint")).put("display_name", memberName)
                .put("peers", JSONArray(peers.map { it.json() })))
            check(pending.getJSONArray("workspace").bytes().contentEquals(id))
            val sealed = call(next, JSONObject().put("op", "seal_pending_join"))
            WorkspaceStore(context, pendingJoin = true).save(id, sealed.get("snapshot") as ByteArray)
            store.enableRecords(id) { call(next, it) }
            val record = LocalWorkspace("Workspace " + id.take(3).joinToString("") { "%02x".format(it.toInt() and 255) }, slot, id, pending.getJSONObject("member").getString("display_name"),
                joinPeer = primary, memberId = pending.getJSONObject("member").getJSONArray("id").bytes(),
                sharedName = sharedName(pending))
            // Catalog is durable before any request can be sent. Recovery retains
            // the same pending identity and request after an unknown outcome.
            // Persist consent before exposing a saved membership or sending admission.
            check(WorkspaceConnections.saveSharing(context, id, sharing)) { "Could not save sharing choice" }
            saveRecord(record)
            records = readCatalog()
            session = next
            next.onWork { onWorkSignal() }
            controlDrain.signal() // drain anything that arrived before the listener was set
            active = record
        } catch (error: Exception) {
            if (session === next) closeSession()
            else { next.close(); check(next.awaitClosed(15, TimeUnit.SECONDS)) }
            throw error
        }
        return finishJoin()
    }

    private fun resumePreJoin(saved: LocalWorkspace): String {
        check(saved.preJoin)
        closeSession()
        val next = FabricSession(context, saved.slot) {}
        return try {
            val restored = store.restoreRecords(saved.id) { call(next, it) }
            check(restored.getJSONArray("workspace").bytes().contentEquals(saved.id))
            session = next
            next.onWork { onWorkSignal() }
            controlDrain.signal()
            if (restored.optString("state") != "pending") {
                val complete = saved.copy(
                    joinPeer = null,
                    joinAddress = null,
                    joinRoutes = emptyList(),
                    joinPeers = emptyList(),
                    joinPinned = false,
                    preJoin = false,
                    memberName = restored.optJSONObject("member")?.getString("display_name") ?: saved.memberName,
                    sharedName = sharedName(restored),
                )
                saveRecord(complete)
                WorkspaceStore(context, pendingJoin = true).retire(saved.id)
                records = readCatalog()
                active = complete
                configureData(restored)
                "Saved workspace opened."
            } else {
                active = saved
                finishJoin()
            }
        } catch (error: Exception) {
            next.close(); check(next.awaitClosed(15, TimeUnit.SECONDS))
            if (session === next) session = null
            active = saved
            records = readCatalog()
            Log.w("Arachne", "WORKSPACE_PENDING_JOIN_RESTORE_FAILED", error)
            "Saved join could not be restored. The encrypted pending record was kept for recovery."
        }
    }

    private fun beginCompactJoin(slot: String, invitation: JSONObject, memberName: String, sharing: Boolean,
        peers: List<ByteArray>, id: ByteArray): String {
        val next = FabricSession(context, slot) {}
        try {
            val pending = call(next, JSONObject().put("op", "begin_join")
                .put("invitation", invitation.getJSONArray("invitation"))
                .put("display_name", memberName)
                .put("peers", JSONArray(peers.map { it.json() })))
            check(pending.getJSONArray("workspace").bytes().contentEquals(id))
            val sealed = call(next, JSONObject().put("op", "seal_pending_join"))
            WorkspaceStore(context, pendingJoin = true).save(id, sealed.get("snapshot") as ByteArray)
            store.enableRecords(id) { call(next, it) }
            val record = LocalWorkspace("Pending workspace", slot, id, memberName,
                joinPeer = peers.first(), memberId = pending.getJSONObject("member").getJSONArray("id").bytes(),
                joinPeers = peers.drop(1), preJoin = true)
            check(WorkspaceConnections.saveSharing(context, id, sharing)) { "Could not save sharing choice" }
            saveRecord(record)
            records = readCatalog()
            session = next
            next.onWork { onWorkSignal() }
            controlDrain.signal()
            active = record
        } catch (error: Exception) {
            if (session === next) closeSession()
            else { next.close(); check(next.awaitClosed(15, TimeUnit.SECONDS)) }
            throw error
        }
        return finishJoin()
    }

    private fun pollPreJoin() {
        val saved = synchronized(this) { active?.takeIf { it.preJoin } } ?: return
        emit(if (session == null) resumePreJoin(saved) else finishJoin(), false)
    }

    private fun hydrateInvitation(target: FabricSession, input: JSONObject): JSONObject {
        if (input.has("checkpoint")) return input
        val fetched = call(target, JSONObject().put("op", "fetch_invitation_checkpoint")
            .put("peers", JSONArray(WorkspaceInvitation.bootstrapPeers(input).map { it.json() }))
            .put("invitation", input.getJSONArray("invitation")))
        return input.put("workspace", fetched.getJSONArray("workspace"))
            .put("checkpoint", fetched.getJSONArray("checkpoint"))
            .put("peer", fetched.getJSONArray("peer"))
    }

    fun retryJoin(): Boolean = submit("Looking for a workspace member… Your join attempt is saved.") { finishJoin() }

    fun cancelJoin(workspace: ByteArray): Boolean {
        val expected = workspace.copyOf()
        return submit("Canceling join request…") {
            val record = checkNotNull(active)
            check(record.id.contentEquals(expected) && record.joinPeer != null) { "Join attempt changed" }
            cancelJoinAttempt(record, "Join request canceled on this device.")
        }
    }

    private fun cancelJoinAttempt(record: LocalWorkspace, result: String): String {
        closeSession()
        catalog.cancelJoin(record)
        runCatching { WorkspaceStore(context, pendingJoin = true).retire(record.id) }
        runCatching { WorkspaceStore(context).discardPendingJoin(record.id) }
        PendingInvitationStore(context, record.slot).delete()
        records = readCatalog()
        check(records.none { it.slot == record.slot })
        return result
    }

    /** One deadline is a fallback for a missing native signal, not a polling loop. */
    private fun scheduleJoinDeadline(delayMs: Long) {
        joinRetryDeadline?.cancel(false)
        if (closed || active?.joinPeer == null) {
            joinRetryDeadline = null
            return
        }
        joinRetryDeadline = worker.schedule({ onWorkSignal() }, delayMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }

    private fun armPendingJoinDeadline() {
        val record = active?.takeIf { it.joinPeer != null } ?: return
        val attempt = joinRetry.attemptFor(record.id.toList())
        joinRetry.commit(record.id.toList(), attempt)
        val delay = JoinRetryBackoff.intervalMs(attempt)
        nextJoinAttempt = android.os.SystemClock.elapsedRealtime() + delay
        scheduleJoinDeadline(delay)
    }

    private fun pollPendingJoin(force: Boolean = false) {
        synchronized(this) {
            if (closed || busy || session == null) return
            active?.takeIf { it.joinPeer != null } ?: return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now < nextJoinAttempt) return
        joinRetryDeadline?.cancel(false)
        joinRetryDeadline = null
        emit(finishJoin(), false)
    }

    private fun finishJoin(): String {
        val record = checkNotNull(active)
        val owner = checkNotNull(session)
        try {
            val reply = call(owner, JSONObject().put("op", "drive_join"))
            val state = reply.getString("state")
            ArachneTrace.mark("join", state)
            val result = when (state) {
                "workspace_joined" -> {
                    val complete = record.copy(joinPeer = null, joinAddress = null, joinRoutes = emptyList(), joinPeers = emptyList(), joinPinned = false, preJoin = false,
                        memberName = reply.optJSONObject("member")?.getString("display_name") ?: record.memberName, sharedName = sharedName(reply))
                    saveRecord(complete); WorkspaceStore(context, pendingJoin = true).retire(record.id)
                    records = readCatalog(); active = complete
                    configureData(reply)
                    controlDrain.signal()
                    Log.i("Arachne", "WORKSPACE_JOIN_SAVED")
                    "Joined and saved. Workspace ready. Sharing waits for reachable subscribers."
                }
                "admission_waiting" -> {
                    "Waiting for the member handling your join. Your request is saved."
                }
                "admission_pending" -> "Joining… Waiting for the selected member."
                "admission_queued" -> "Join request queued. Your saved join will finish automatically."
                "admission_unavailable" -> when (reply.optString("reason")) {
                    "invitation_disabled" -> cancelJoinAttempt(record, "This invitation was declined or disabled. Contact an administrator for a new invitation.")
                    "invitation_expired" -> cancelJoinAttempt(record, "This invitation has expired. Contact an administrator for a new invitation.")
                    "automatic_approval_required" -> "An administrator is authorizing this personal invitation. Your saved join will finish automatically."
                    "approval_required" -> "Awaiting administrator approval. Your request was sent automatically; you can leave this screen."
                    else -> "A workspace member responded but could not finish your join. Arachne will keep trying; ask an administrator to check the invitation if it does not finish."
                }
                else -> "Joining… No workspace member is reachable yet. Your saved attempt will retry automatically."
            }
            // The native driver owns the in-flight exchange and raises the
            // work signal when it completes; only terminal/no-route outcomes
            // need the existing one-shot recovery fallback.
            if (active?.joinPeer != null && state != "admission_pending") armPendingJoinDeadline()
            else {
                joinRetryDeadline?.cancel(false)
                joinRetryDeadline = null
                joinRetry.reset()
            }
            return result
        } catch (error: Exception) {
            closeSession()
            Log.w("Arachne", "WORKSPACE_JOIN_INCOMPLETE ${error.javaClass.simpleName}: ${error.message}", error)
            return "Join is incomplete. Open the saved workspace to recover, then retry when the peer is reachable."
        }
    }

    fun reconnect(link: String): Boolean = submit("Reconnecting workspace…") {
        val record = checkNotNull(active)
        check(record.joinPeer == null && data != null)
        val owner = checkNotNull(session)
        val hint = hydrateInvitation(owner, WorkspaceInvitation.decode(link.trim()))
        val verified = call(owner, JSONObject().put("op", "inspect_invitation")
            .put("invitation", hint.getJSONArray("invitation")).put("checkpoint", hint.getJSONArray("checkpoint")))
        require(verified.getJSONArray("workspace").bytes().contentEquals(record.id)) { "Link belongs to another workspace" }
        // The link supplies a route, not authority. Node still addresses and
        // authorizes only endpoints in the verified membership projection.
        if (hint.has("address")) callOptional(owner, JSONObject().put("op", "add_address_hint")
            .put("peer", hint.getJSONArray("peer")).put("address", hint.getString("address")))
        data?.discoverCutoff(hint.getJSONArray("peer"))
        "Workspace connection updated. Waiting for subscription announcements."
    }

    fun invite(expiresAt: Long = 0, personal: Boolean = false, automatic: Boolean = false, requestAccess: Boolean = false): Boolean = submit("Creating invitation…") {
        invitation = null; invitationKey = null
        val value = invitationChange(JSONObject().put("op", "stage_invitation").put("expires_at", expiresAt)
            .put("personal", personal).put("automatic", automatic).put("request_access", requestAccess))
        val issued = value.getJSONObject("issued_invitation")
        invitation = WorkspaceInvitation.encode(issued)
        invitationKey = issued.getJSONArray("invitation_key").bytes().also { require(it.size == 32) }
        if (requestAccess) "Request access invitation ready. Review each person's request independently in Members."
        else if (automatic) "Personal invitation ready. The first person will be approved automatically when an administrator is reachable."
        else if (personal) "Personal invitation ready. Send it to one person; review their request in Members."
        else "Open invitation ready. Everyone with this link can join until it expires or you disable it."
    }

    fun approveInvitation(workspace: ByteArray, pending: PendingApproval): Boolean {
        val request = pending.request.copyOf()
        val expected = workspace.copyOf()
        return submit("Approving join request…") {
            check(active?.id?.contentEquals(expected) == true) { "Workspace changed" }
            invitationChange(JSONObject().put("op", "stage_invitation_approval").put("attempt_id", pending.attemptId.json()).put("request", request.json()))
            controlDrain.signal()
            approvals.remove(pending.attemptId.hex())
            "Access approved. The joining device will finish automatically while a member is connected."
        }
    }

    fun declineInvitation(workspace: ByteArray, pending: PendingApproval): Boolean {
        val request = pending.request.copyOf()
        val expected = workspace.copyOf()
        return submit("Declining join request…") {
            check(active?.id?.contentEquals(expected) == true) { "Workspace changed" }
            invitationChange(JSONObject().put("op", "stage_invitation_decline").put("attempt_id", pending.attemptId.json()).put("request", request.json()))
            approvals.remove(pending.attemptId.hex())
            "Request declined. The joining device will be told that its invitation is no longer valid."
        }
    }

    fun disableInvitation(workspace: ByteArray, key: ByteArray): Boolean {
        require(key.size == 32)
        val expected = workspace.copyOf(); val target = key.copyOf()
        return submit("Disabling invitation…") {
            check(active?.id?.contentEquals(expected) == true) { "Workspace changed" }
            invitationChange(JSONObject().put("op", "stage_management").put("action", JSONObject().put("kind", "disable_invitation").put("member", target.json())))
            invitation = null; invitationKey = null
            "Invitation disabled. Connected members will receive the change. People already in the workspace stay members."
        }
    }

    private fun invitationChange(command: JSONObject): JSONObject {
        val owner = checkNotNull(session)
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (data?.drainPending() == false) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Received data is still being saved. Try again." }
            Thread.sleep(25)
        }
        val staged = call(owner, command)
        try {
            return store.commitAdmission(staged) { call(owner, it) }.also { configureData(it) }
        } catch (error: Exception) { closeSession(); throw error }
    }

    fun manage(workspace: ByteArray, member: ByteArray, action: String): Boolean {
        require(member.size == 32 && action in setOf("promote", "demote", "remove"))
        val target = member.copyOf()
        val expected = workspace.copyOf()
        return submit("Updating membership…") {
            check(active?.id?.contentEquals(expected) == true) { "Workspace changed" }
            val owner = checkNotNull(session)
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (data?.drainPending() == false) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Received data is still awaiting application acknowledgement" }
                Thread.sleep(25)
            }
            val staged = call(owner, JSONObject().put("op", "stage_management")
                .put("action", JSONObject().put("kind", action).put("member", target.json())))
            try {
                val updated = store.commitAdmission(staged) { call(owner, it) }
                invitation = null; invitationKey = null
                configureData(updated)
                "Membership change saved. Reachable members will synchronize."
            } catch (error: Exception) {
                closeSession()
                throw error
            }
        }
    }

    fun leave(workspace: ByteArray, successor: ByteArray? = null): Boolean {
        require(successor == null || successor.size == 32)
        val scope = workspace.copyOf()
        val successorId = successor?.copyOf()
        return submit("Leaving workspace…") {
            check(active?.id?.contentEquals(scope) == true && active?.ended == false) { "Workspace changed" }
            val owner = checkNotNull(session)
            val staged = try {
                val deadline = android.os.SystemClock.elapsedRealtime() + 15000
                while (data?.drainPending() == false) {
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Received data is still being saved. Try again." }
                    Thread.sleep(25)
                }
                val rosterValue = call(owner, JSONObject().put("op", "member_roster"))
                val roster = rosterValue.getJSONArray("members")
                val peers = (0 until roster.length()).map { roster.getJSONObject(it) }.filter { !it.getBoolean("self") }
                val request = if (peers.isEmpty()) JSONObject().put("op", "stage_solo_leave") else {
                    val self = (0 until roster.length()).map { roster.getJSONObject(it) }.single { it.getBoolean("self") }
                    val needsHandover = self.getBoolean("administrator") && peers.none { it.getBoolean("administrator") }
                    val successorPeer = successorId?.let { id -> peers.singleOrNull { it.getJSONArray("id").bytes().contentEquals(id) } }
                    if (needsHandover) {
                        check(successorPeer != null && successorPeer.optString("presence") == "reachable") { "Choose a reachable member to become administrator before leaving." }
                        val promotion = call(owner, JSONObject().put("op", "stage_management").put("action",
                            JSONObject().put("kind", "promote").put("member", checkNotNull(successorId).json())))
                        val offered = call(owner, JSONObject().put("op", "offer_staged_membership_update")
                            .put("peer", successorPeer.getJSONArray("endpoint")))
                        check(offered.getString("state") == "membership_offer_pending") { "The successor could not receive the administrator handoff." }
                        // Native control may wait 30 s for the successor; leave
                        // margin for its durable drive/adopt before retrying.
                        val handoffDeadline = android.os.SystemClock.elapsedRealtime() + 45000
                        while (callOptional(owner, JSONObject().put("op", "poll_membership_offer")) == null) {
                            check(android.os.SystemClock.elapsedRealtime() < handoffDeadline) { "The successor did not confirm the administrator handoff. Try again." }
                            Thread.sleep(25)
                        }
                        val promoted = try { store.commitAdmission(promotion) { call(owner, it) }
                            .also { configureData(it) }
                        } catch (error: Exception) {
                            closeSession()
                            throw error
                        }
                        check(promoted.optLong("epoch") == rosterValue.getLong("epoch") + 1) { "The administrator handoff was not committed locally." }
                    }
                    val peer = successorPeer ?: peers.firstOrNull { it.optString("presence") == "reachable" } ?: peers.first()
                    JSONObject().put("op", "leave_via_peer").put("peer", peer.getJSONArray("endpoint"))
                }
                call(owner, request)
            } catch (error: Exception) {
                runCatching { call(owner, JSONObject().put("op", "discard_workspace_candidate")) }
                    .onFailure { error.addSuppressed(it) }
                lastFailure = error.message ?: "The administrator handoff did not complete."
                Log.w("Arachne", "WORKSPACE_LEAVE_RETRY", error)
                return@submit "Workspace remains active. Retry Leave when another member is reachable."
            }
            try {
                val ended = store.commitAdmission(staged) { call(owner, it) }
                check(ended.getString("state") == "removed")
                endMembership()
                Log.i("Arachne", "WORKSPACE_LEFT")
                "You left the workspace. Sharing has stopped."
            } catch (error: Exception) {
                closeSession()
                throw error
            }
        }
    }

    fun leaveDevice(workspace: ByteArray): Boolean {
        val scope = workspace.copyOf()
        return submit("Leaving workspace on this device…") {
            check(active?.id?.contentEquals(scope) == true && active?.ended == false) { "Workspace changed" }
            val ended = checkNotNull(active).copy(ended = true)
            saveRecord(ended)
            records = readCatalog()
            closeSession()
            active = ended
            Log.i("Arachne", "WORKSPACE_LEFT_DEVICE")
            "Workspace access ended on this device. Other members may still show you offline."
        }
    }

    private fun endMembership() {
        val ended = checkNotNull(active).copy(ended = true)
        // Rust has already committed a terminal record. Catalog metadata only
        // presents that result; it never authorizes a membership or rejoin.
        saveRecord(ended)
        records = readCatalog()
        closeSession()
        active = ended
    }

    fun rename(workspace: ByteArray, previousName: String, name: String): Boolean {
        val scope = workspace.copyOf()
        return submit("Saving workspace name…") {
            val title = checkedWorkspaceName(name)
            check(active?.id?.contentEquals(scope) == true && active?.name == previousName) { "Workspace changed. Review its current name and try again." }
            val owner = checkNotNull(session)
            val staged = call(owner, JSONObject().put("op", "stage_workspace_name").put("workspace_name", title))
            try {
                val updated = store.commitAdmission(staged) { call(owner, it) }
                configureData(updated)
                invitation = null; invitationKey = null
                "Workspace name saved."
            } catch (error: Exception) {
                closeSession()
                throw error
            }
        }
    }

    private fun sharedName(metadata: JSONObject): String? =
        if (metadata.isNull("workspace_name")) null else checkedWorkspaceName(metadata.getString("workspace_name"))

    /** One serialized control poller for admission and authorized recovery.
     * A valid invitation carries authority; recovery does not alter membership. */
    private fun pollWorkspaceControl(): Boolean {
        synchronized(this) {
            if (closed || session == null) return false
            // A drain step refused here ends the drain; record why for race analysis.
            if (busy || active?.joinPeer != null) { ArachneTrace.mark("control_refused", if (busy) "busy" else "joining"); return false }
        }
        // The serial worker already excludes concurrent control mutation.
        // Reserve busy for admitted user operations: marking a background poll
        // busy silently rejects taps on controls that the UI still enables.
        var message: String? = null
        try {
            val owner = checkNotNull(session)
            syncPendingApprovals(owner)
            val staged = try { callOptional(owner, JSONObject().put("op", "drive_workspace")) }
            catch (error: Exception) {
                if (BuildConfig.DEBUG) Log.w("Arachne", "WORKSPACE_ADMISSION_REJECTED ${error.message}")
                else Log.w("Arachne", "WORKSPACE_ADMISSION_REJECTED")
                return true
            } ?: return false
            lastControlState = staged.getString("state")
            ArachneTrace.mark("control", lastControlState)
            if (BuildConfig.DEBUG) Log.i("Arachne", "WORKSPACE_CONTROL slot=${active?.slot} state=$lastControlState membership_state=${staged.optString("membership_state", "")}")
            if (BuildConfig.DEBUG && (lastControlState.startsWith("workspace_name_") || staged.optString("membership_state").startsWith("workspace_name_")))
                Log.i("Arachne", "WORKSPACE_NAME_RESULT slot=${active?.slot} $staged")
            if (staged.getString("state") == "admission_queued") {
                Log.i("Arachne", "WORKSPACE_ADMISSION_QUEUED")
                return true
            }
            if (staged.getString("state") == "presence_replied") {
                if (staged.optBoolean("accepted") && refreshMembers()) message = "Workspace presence updated."
                return true
            }
            if (staged.getString("state") in setOf("recovery_replied", "direct_recovery_replied", "current_view_replied", "membership_replied",
                    "admission_replied", "invitation_checkpoint_replied")) {
                if (staged.getString("state") == "membership_replied") {
                    if (refreshMembers()) message = "Workspace members updated."
                }
                Log.i("Arachne", "WORKSPACE_RECOVERY_REPLIED")
                return true
            }
            if (staged.getString("state") == "workspace_committed") {
                configureData(staged)
                message = when {
                    staged.has("results_delivered") -> "Membership saved; ${staged.optInt("results_delivered")} joining devices were answered."
                    staged.optBoolean("reply_queued") -> "Membership saved; response queued. The joining device must confirm completion."
                    else -> "Workspace membership saved."
                }
                if (refreshMembers()) message = "Workspace members updated."
                return true
            }
            if (staged.getString("state") == "workspace_name_committed") {
                configureData(staged)
                message = "Workspace name synchronized."
                return true
            }
            if (staged.getString("state") == "membership_replied") {
                if (refreshMembers()) message = "Workspace members updated."
                return true
            }
            if (staged.getString("state") == "workspace_name_conflict") {
                membershipWarning = "Workspace name conflict; this device kept its local name."
                return true
            }
            if (staged.getString("state") == "workspace_name_unavailable") {
                membershipWarning = "Workspace name update is unavailable; retry when a current member is reachable."
                return true
            }
            if (staged.getString("state") == "workspace_reply_ready") {
                message = if (staged.optBoolean("reply_queued")) "Saved join result sent to the joining device."
                    else "Saved join result is retained; the joining device can ask again."
                return true
            }
            if (staged.getString("state") == "approval_requested") {
                val selfIsAdmin = members?.views?.any { it.self && it.administrator } == true
                if (selfIsAdmin) {
                    val attemptId = staged.getJSONArray("attempt_id").bytes()
                    val endpoint = staged.getJSONArray("endpoint").bytes()
                    val request = staged.getJSONArray("request").bytes()
                    val name = staged.optString("display_name").takeIf { it.isNotBlank() && it != "null" }
                        ?.let(::checkedMemberName) ?: "New member"
                    check(attemptId.size == 32 && endpoint.size == 32 && request.size in 331..16714)
                    val pending = PendingApproval(attemptId, name, endpoint, request)
                    if (staged.optBoolean("automatic")) {
                        acknowledgeApproval(owner, attemptId)
                        invitationChange(JSONObject().put("op", "stage_invitation_approval").put("attempt_id", attemptId.json()).put("request", request.json()))
                        controlDrain.signal()
                        message = "$name was approved automatically. Their device will finish joining."
                    } else {
                        approvals[attemptId.hex()] = pending
                        if (acknowledgeApproval(owner, attemptId)) message = "$name wants to join. Review the request in Members."
                        else approvals.remove(attemptId.hex())
                    }
                }
                return true
            }
            error("Unexpected Rust workspace lifecycle state: ${staged.getString("state")}")
        } finally {
            synchronized(this) { message?.let { emit(it, busy, session == null) } }
        }
        return true
    }

    private fun syncPendingApprovals(owner: FabricSession) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextApprovalSync || members?.views?.none { it.self && it.administrator } == true) return
        nextApprovalSync = now + 1000
        var after: ByteArray? = null
        repeat(32) {
            val request = JSONObject().put("op", "list_admission_approvals").put("limit", 64)
            if (after == null) request.put("after", JSONObject.NULL) else request.put("after", after!!.json())
            val page = runCatching { call(owner, request) }.getOrElse {
                Log.w("Arachne", "WORKSPACE_APPROVAL_PAGE_FAILED", it)
                return
            }
            val rows = page.getJSONArray("approvals")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                if (row.optBoolean("automatic")) continue
                val attemptId = row.getJSONArray("attempt_id").bytes()
                val endpoint = row.getJSONArray("endpoint").bytes()
                val requestBytes = row.getJSONArray("request").bytes()
                check(attemptId.size == 32 && endpoint.size == 32 && requestBytes.size in 331..16714)
                if (approvals.putIfAbsent(attemptId.hex(), PendingApproval(attemptId, row.optString("display_name").takeIf { it.isNotBlank() && it != "null" }?.let(::checkedMemberName) ?: "New member", endpoint, requestBytes)) == null) {
                    if (!acknowledgeApproval(owner, attemptId)) approvals.remove(attemptId.hex())
                }
            }
            if (page.optBoolean("complete")) return
            after = page.getJSONArray("next_after").bytes()
        }
    }

    private fun acknowledgeApproval(owner: FabricSession, attemptId: ByteArray): Boolean =
        runCatching { call(owner, JSONObject().put("op", "acknowledge_admission_approval").put("attempt_id", attemptId.json())) }.onFailure {
            Log.w("Arachne", "WORKSPACE_APPROVAL_ACK_FAILED", it)
        }.isSuccess

    private fun configureData(metadata: JSONObject) {
        val record = checkNotNull(active)
        check(metadata.getJSONArray("workspace").bytes().contentEquals(record.id))
        val acceptedName = sharedName(metadata)
        val epoch = metadata.getLong("epoch")
        if (record.sharedName != acceptedName) {
            val updated = record.copy(sharedName = acceptedName)
            saveRecord(updated)
            records = readCatalog()
            active = updated
        }
        if (metadata.optLong("workspace_name_missing_history", 0) > 0)
            membershipWarning = "Workspace name updated. Some earlier name changes are unavailable on this device."
        announcePresence = true
        val owner = checkNotNull(session)
        if (members == null) members = WorkspaceMembers(context, metadata.getJSONArray("workspace").bytes())
        members!!.refreshText { callText(owner, it) }
        val controls = call(owner, JSONObject().put("op", "invitation_controls"))
        legacyInvitations = controls.getBoolean("legacy_enabled")
        val links = controls.getJSONArray("invitations")
        invitations = (0 until links.length()).map { index ->
            val link = links.getJSONObject(index)
            WorkspaceInvite(link.getInt("number"), link.getJSONArray("key").bytes(), link.getLong("expires_at"), link.getBoolean("enabled"), link.getBoolean("personal"), link.optBoolean("automatic"), link.getBoolean("approved"), link.optBoolean("request_access"))
        }
        // A name update keeps the same crypto epoch and delivery owner, including
        // pending acknowledgements, recovery progress and subscription timers.
        if (data != null && dataEpoch == metadata.getLong("epoch")) return
        val catalog = feeds ?: WorkspaceFeeds(context, metadata.getJSONArray("workspace").bytes()).also { feeds = it }
        val choices = topicChoices ?: WorkspaceTopics(context, metadata.getJSONArray("workspace").bytes(), dataTopics).also { topicChoices = it }
        val consumer: (JSONObject, (Boolean) -> Unit) -> Unit = { value, complete ->
            if (closed || session !== owner) complete(false)
            else if (value.getString("topic") == WorkspaceFeeds.CATALOG) {
                try { catalog.receive(value) }
                catch (error: Exception) { Log.w("Arachne", "WORKSPACE_FEED_CATALOG_REJECTED") }
                // Malformed ephemeral catalog entries are explicitly discarded, not retried forever.
                complete(true)
            } else if (value.getString("topic").startsWith("feeds/") && value.getString("topic") !in catalog.interests()) {
                Log.i("Arachne", "WORKSPACE_FEED_DISCARDED_UNSUBSCRIBED")
                complete(true)
            } else {
                if (value.getString("topic").startsWith("feeds/"))
                    value.put("feed_format", catalog.views().firstOrNull { it.topic == value.getString("topic") }?.format ?: "unknown")
                received?.invoke(value, complete) ?: complete(false)
            }
        }
        dataEpoch = metadata.getLong("epoch")
        data = WorkspaceData(store, metadata, choices.interests() + WorkspaceFeeds.CATALOG + WorkspaceResources.CATALOG + WorkspaceResources.CLAIMS + catalog.interests(), { callOptional(owner, it) }, consumer) { status ->
            if (continuityStatus != status) {
                continuityStatus = status
                continuityChangedAt = System.currentTimeMillis()
                emit("Workspace ready.", false)
            }
        }
    }

    private fun refreshMembers(): Boolean {
        val owner = session ?: return false
        return members?.refreshText { callText(owner, it) } == true
    }

    private fun pollPresence() {
        val owner = session ?: return
        if (data == null) return
        val announce = announcePresence
        announcePresence = false
        // Rust observes newer heads here and pulls verified ranges without an
        // Android timer or peer walk.
        val presence = call(owner, JSONObject().put("op", "poll_workspace_presence").put("announce", announce))
        if (BuildConfig.DEBUG && ((presence.has("sync_peer") && !presence.isNull("sync_peer")) || presence.optInt("response_errors") > 0))
            Log.i("Arachne", "WORKSPACE_PRESENCE_SYNC slot=${active?.slot} peer_seen=${!presence.isNull("sync_peer")} announce=$announce response_errors=${presence.optInt("response_errors")} response_error=${presence.optString("response_error", "")}")
        val now = android.os.SystemClock.elapsedRealtime()
        // nextPresenceView is the watched deadline; unwatched polls wait longer.
        if (now >= nextPresenceView + if (presenceWatched) 0 else PRESENCE_VIEW_IDLE_MS - PRESENCE_VIEW_MS) {
            nextPresenceView = now + PRESENCE_VIEW_MS
            if (refreshMembers()) emit("Workspace presence updated.", false)
        }
    }

    private fun refreshFeeds() {
        val views = feeds?.views().orEmpty()
        if (views != lastFeedViews) { lastFeedViews = views; emit("Workspace ready.", false) }
    }

    fun selectFeed(workspace: ByteArray, topic: String, enabled: Boolean): Boolean {
        val scope = workspace.copyOf()
        return submit("Updating feed selection…") {
            require(active?.id?.contentEquals(scope) == true) { "Workspace selection changed" }
            try {
                val catalog = checkNotNull(feeds)
                catalog.select(topic, enabled)
                checkNotNull(data).interests(checkNotNull(topicChoices).interests() + WorkspaceFeeds.CATALOG + WorkspaceResources.CATALOG + WorkspaceResources.CLAIMS + catalog.interests())
                if (enabled) "Feed selected. Waiting for updates." else "Feed unsubscribed."
            } catch (error: Exception) {
                // A saved selection must not be presented as live when its
                // subscription could not be applied. Reopen reconciles it.
                closeSession()
                throw error
            }
        }
    }

    fun selectTopics(workspace: ByteArray, topics: Set<String>, publish: Boolean?, receive: Boolean?): Boolean {
        if (topics.isEmpty() || !dataTopics.containsAll(topics) || (publish == null && receive == null)) return false
        val scope = workspace.copyOf()
        val selected = topics.toSet()
        return submit("Updating data sharing…") {
            require(active?.id?.contentEquals(scope) == true) { "Workspace selection changed" }
            try {
                val choices = checkNotNull(topicChoices)
                choices.select(selected, publish, receive)
                checkNotNull(data).interests(choices.interests() + WorkspaceFeeds.CATALOG + WorkspaceResources.CATALOG + WorkspaceResources.CLAIMS + checkNotNull(feeds).interests())
                Log.i("Arachne", "WORKSPACE_TOPICS_CHANGED slot=${active?.slot}")
                "Data sharing updated."
            } catch (error: Exception) {
                // A failed unsubscribe must not leave an owner running with a
                // saved choice that its live routing has not applied.
                closeSession()
                throw error
            }
        }
    }

    /** True admits local work only; false means no callback. Admitted work calls
     * completed once on the serial worker, including unavailable/changed scope.
     * Success carries the runtime admission report, NOT delivery/read evidence.
     * A process exit can interrupt the callback. Capture scope when producing. */
    @Synchronized
    fun publish(workspace: ByteArray, topic: String, payload: ByteArray, recipients: List<ByteArray> = emptyList(),
                current: WorkspaceCurrent? = null,
                completed: ((Result<JSONObject>) -> Unit)? = null): Boolean {
        if (closed || pendingPublications >= 32 || payload.size > 12 * 1024 || !WorkspaceData.validTopic(topic)) return false
        if (recipients.size > 64 || recipients.any { it.size != 32 }) return false
        if (current != null && (recipients.isNotEmpty() || current.selector.size != 32 ||
                current.replacementKey.size != 32 || current.expiresAt <= 0)) return false
        val audience = recipients.map { it.copyOf() }.sortedBy { member ->
            member.joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if (audience.zipWithNext().any { (a, b) -> a.contentEquals(b) }) return false
        val expected = workspace.copyOf()
        val currentMetadata = current?.let {
            WorkspaceCurrent(it.selector.copyOf(), it.replacementKey.copyOf(), it.expiresAt, it.tombstone)
        }
        val bytes = payload.copyOf()
        // Reuse the serial worker: control activity must not discard native sends.
        // At most 384 KiB of payload can await processing per workspace.
        pendingPublications++
        worker.execute {
            val result = try {
                val currentData = data
                if (currentData == null || !currentData.workspace.contentEquals(expected)) {
                    Log.w("Arachne", "WORKSPACE_PUBLICATION_REJECTED scope_unavailable")
                    Result.failure(IllegalStateException("Publication workspace is no longer active"))
                } else if (topicChoices?.canPublish(topic) == false) {
                    Result.failure(IllegalStateException("Publishing is disabled for this topic"))
                } else {
                    val report = currentData.publish(topic, bytes, audience, current = currentMetadata)
                    Log.i("Arachne", "WORKSPACE_PUBLICATION_RESULT topic=$topic report=$report")
                    Result.success(report)
                }
            } catch (error: Exception) {
                // A failed save/adopt can leave a staged transaction. Stop this
                // owner so no later operation continues from ambiguous state.
                try { closeSession(); emit("Shared data stopped. Open the saved workspace to recover.", false, true) }
                catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                Log.e("Arachne", "WORKSPACE_PUBLICATION_FAILED", error)
                Result.failure(error)
            } finally { synchronized(this) { pendingPublications-- } }
            // Application callback errors cannot invalidate committed crypto state.
            try { completed?.invoke(result) }
            catch (error: Exception) { Log.w("Arachne", "WORKSPACE_PUBLICATION_CALLBACK_FAILED", error) }
        }
        return true
    }

    @Synchronized
    fun resource(workspace: ByteArray, request: JSONObject, completed: (Result<JSONObject>) -> Unit): Boolean {
        if (closed) return false
        val expected = workspace.copyOf()
        val operation = JSONObject(request.toString())
        worker.execute {
            val result = runCatching {
                check(data?.workspace?.contentEquals(expected) == true) { "Resource workspace is no longer active" }
                call(checkNotNull(session), JSONObject().put("op", "resource").put("request", operation))
            }
            // File I/O failure cannot invalidate or stop the workspace journal.
            runCatching { completed(result) }
        }
        return true
    }

    private fun call(target: FabricSession, request: JSONObject): JSONObject = checkNotNull(callOptional(target, request))

    private fun callOptional(target: FabricSession, request: JSONObject): JSONObject? {
        val (text, binary) = callRaw(target, request) ?: return null
        return JSONObject(text).also {
            if (binary.isNotEmpty()) it.put("snapshot", binary)
            activity = WorkspaceActivityView.read(it, activity)
        }
    }

    /** Reply text without parsing it, for replies the caller parses in part. */
    private fun callText(target: FabricSession, request: JSONObject): String {
        val (text, binary) = checkNotNull(callRaw(target, request))
        check(binary.isEmpty()) { "Unexpected binary reply" }
        return text
    }

    private fun callRaw(target: FabricSession, request: JSONObject): Pair<String, ByteArray>? {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<Array<ByteArray>>>()
        val binaryField = if (request.optString("op") == "stage_join") "welcome" else "snapshot"
        check(binaryField == "snapshot" || !request.has("snapshot")) { "Ambiguous binary input" }
        val snapshot = when (val value = request.remove(binaryField)) {
            null -> ByteArray(0)
            is ByteArray -> value
            is JSONArray -> value.bytes()
            else -> error("Invalid snapshot value")
        }
        check(target.requestStored(request.toString().toByteArray(Charsets.UTF_8), snapshot) { result.set(it); done.countDown() })
        // request_admission may wait on the owner's held exchange for up to the
        // runtime's 30 s control deadline; wait past it so the native call
        // always returns (result or admission_waiting) before we give up.
        // Abandoning it early leaves it running on the session's serial
        // worker, and every retry then queues behind it.
        val waitSeconds = if (request.optString("op") in setOf("request_admission", "drive_join")) 45L else 20L
        check(done.await(waitSeconds, TimeUnit.SECONDS)) { "Workspace request timed out" }
        val response = checkNotNull(result.get()).getOrThrow()
        check(response.size == 2)
        val text = String(response[0], Charsets.UTF_8)
        return if (text == "null") { check(response[1].isEmpty()); null }
        else text to response[1]
    }

    private fun closeSession() {
        announcePresence = true
        nextPresenceView = 0
        metrics = null; metricsError = null; nextMetrics = 0
        nextApprovalSync = 0
        membershipWarning = null
        continuityStatus = null
        continuityChangedAt = null
        activity = WorkspaceActivityView("empty", null)
        joinRetryDeadline?.cancel(false)
        joinRetryDeadline = null
        data = null
        dataEpoch = null
        feeds = null
        topicChoices = null
        members = null
        lastFeedViews = emptyList()
        session?.let { it.close(); check(it.awaitClosed(15, TimeUnit.SECONDS)) { "Workspace is still closing" } }
        session = null
        active = null
        invitation = null; invitationKey = null
        approvals.clear()
        nextJoinAttempt = 0
        joinRetry.reset()
        invitations = emptyList()
        legacyInvitations = false
    }

    private fun readCatalog(): List<LocalWorkspace> = catalog.read()

    private fun saveRecord(record: LocalWorkspace) = catalog.save(record)

    @Synchronized
    override fun close() = closeInternal(reset = false)

    /** Reset owns the Rust invalidation before this adapter tears down. */
    internal fun resetAndClose() = closeInternal(reset = true)

    @Synchronized
    private fun closeInternal(reset: Boolean) {
        if (closed) return
        closed = true
        connectivity.unregisterNetworkCallback(networkCallback)
        // Reset must break a running JNI exchange before cleanup is queued
        // behind it. Ordinary close is queued on this worker so admitted
        // workspace operations drain before closeSession stops the session.
        if (reset) session?.resetAndClose()
        worker.execute {
            try {
                closeSession(); shutdownSucceeded = true
            } finally {
                onClosed()
            }
        }
        worker.shutdown()
    }

    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean = worker.awaitTermination(timeout, unit)

    /** Debug control only: one runtime request on this owner's serial worker,
     * ordered with every other control step. Null when no session is open. */
    internal fun debugCall(request: JSONObject, timeoutMs: Long): JSONObject? {
        check(BuildConfig.DEBUG)
        return worker.submit<JSONObject?> { session?.let { callOptional(it, JSONObject(request.toString())) } }
            .get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Debug control only: worker-confined state that the view does not show. */
    internal fun debugState(timeoutMs: Long): JSONObject {
        check(BuildConfig.DEBUG)
        return worker.submit<JSONObject> {
            JSONObject().put("busy", synchronized(this) { busy }).put("closed", synchronized(this) { closed })
                .put("session", session != null).put("slot", active?.slot).put("joining", active?.joinPeer != null)
                .put("pre_join", active?.preJoin == true)
                .put("next_join_attempt_in_ms", if (nextJoinAttempt == 0L) 0 else nextJoinAttempt - android.os.SystemClock.elapsedRealtime())
                .put("last_control_state", lastControlState).put("approvals", approvals.size)
                .put("membership_warning", membershipWarning)
                .put("pending_publications", synchronized(this) { pendingPublications })
        }.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun JSONArray.bytes(): ByteArray = ByteArray(length()) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
    private fun ByteArray.json() = JSONArray(map { it.toInt() and 255 })
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
