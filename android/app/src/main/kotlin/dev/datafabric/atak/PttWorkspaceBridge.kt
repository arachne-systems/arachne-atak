package dev.arachne.atak

import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Same-device RPC into every linked ATAK workspace, plus the plugin-link-v2
 * discovery broadcasts (PttLink.kt) that let the Arachne PTT app find and
 * attach to them automatically. Only protected publications cross the RPC. */
internal class PttWorkspaceBridge(
    private val context: Context,
    private val publish: (ByteArray, String, ByteArray, (Result<JSONObject>) -> Unit) -> Boolean,
) : AutoCloseable {
    private data class Member(val id: String, val name: String, val administrator: Boolean)
    private data class Scope(
        val workspace: String,
        val workspaceBytes: ByteArray,
        val workspaceName: String,
        val member: String,
        val memberName: String,
        val members: List<Member>,
    ) {
        val floorCoordinator: String = members.filter { it.administrator }.ifEmpty { members }
            .minOfOrNull { it.id.lowercase() }.orEmpty()

        fun json(op: String) = JSONObject()
            .put("op", op)
            .put("ready", true)
            .put("workspace", workspace)
            .put("workspace_name", workspaceName)
            .put("member", member)
            .put("member_name", memberName)
            .put("floor_coordinator", floorCoordinator)
            .put("members", JSONArray(members.map {
                JSONObject().put("id", it.id).put("name", it.name).put("administrator", it.administrator)
            }))
    }

    private data class Pending(val workspace: String, val complete: (Boolean) -> Unit)
    private data class FloorRecord(val coordinator: String, val authority: PttFloorAuthority)

    private class Peer(val socket: Socket) : AutoCloseable {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        @Synchronized fun send(message: JSONObject): Boolean = runCatching {
            val bytes = message.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..MAX_FRAME_BYTES)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }.isSuccess

        fun read(): JSONObject {
            val length = input.readInt()
            require(length in 1..MAX_FRAME_BYTES) { "PTT RPC frame is too large" }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return JSONObject(String(bytes, Charsets.UTF_8))
        }

        override fun close() = runCatching { socket.close() }.let { Unit }
    }

    companion object {
        private const val PTT_TOPIC = "streams/ptt"
        private const val MAX_MEDIA_BYTES = 12 * 1024
        private const val MAX_FRAME_BYTES = 256 * 1024
        private const val DELIVERY_TIMEOUT_MS = 5_000L
    }

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "arachne-ptt-delivery-timeouts").apply { isDaemon = true }
    }
    private val tickets = PttTicketStore()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val floorPreferences = context.applicationContext.getSharedPreferences("ptt-floor-authority", Context.MODE_PRIVATE)
    private val talkerMap = if (BuildConfig.DEBUG) PttTalkerMap() else null
    private val localMediaSequence = java.util.concurrent.atomic.AtomicLong()
    private val floorLock = Any()
    private val floorRecords = mutableMapOf<String, FloorRecord>() // workspace -> state; guarded by floorLock
    @Volatile private var scopes: Map<String, Scope> = emptyMap()
    @Volatile private var previousSelected: String? = null
    private var lastRevision: Long = -1 // written only from the synchronized update()
    @Volatile private var closed = false

    init {
        Thread(::acceptLoop, "arachne-ptt-workspace-rpc").apply { isDaemon = true; start() }
    }

    /** dev.arachne.atak.PTT_REQUEST_OFFERS received from the PTT app. Ignores anything but protocol 1. */
    fun handleRequestOffers(intent: Intent) {
        val protocol = intent.getIntExtra(PttLinkProtocol.EXTRA_PROTOCOL, -1)
        if (protocol != PttLinkProtocol.VERSION) {
            Log.i("Arachne", "PTT_LINK_REQUEST_OFFERS_IGNORED protocol=$protocol")
            return
        }
        scopes.values.forEach(::sendOffer)
    }

    /** Explicit "Push to talk" from the workspace menu: offer this one workspace. True if it is linkable. */
    fun offerWorkspace(workspace: String): Boolean {
        val current = scopes[workspace] ?: return false
        sendOffer(current)
        return true
    }

    @Synchronized
    fun update(state: WorkspaceConnectionView) {
        // WorkspaceController callbacks can arrive out of order across threads; FabricPlugin
        // already guards its own display the same way. Without this, a stale snapshot could
        // disconnect a peer or invalidate a ticket for a workspace that has since reconnected.
        if (state.revision <= lastRevision) return
        lastRevision = state.revision

        val next = scopesFor(state)
        val previous = scopes
        scopes = next

        val selectedWorkspace = state.selected.active?.id?.let(Hex::encode)?.takeIf { it in next }
        val selectionChanged = selectedWorkspace != null && selectedWorkspace != previousSelected
        previousSelected = selectedWorkspace
        val selectedScope = selectedWorkspace?.let(next::get)
        talkerMap?.bind(selectedScope?.workspace, selectedScope?.members?.associate { it.id to it.name }.orEmpty())

        val gone = previous.keys - next.keys
        val newlyConnected = next.keys - previous.keys

        for (workspace in gone) {
            // GONE removes the workspace's "net" on the PTT side, so it must fire only when the
            // user actually left, removed or ended the workspace -- never for a merely transient
            // disconnect (paused, stopped, or a workspace that is still saved but not currently
            // scoped) and never for ATAK/the plugin stopping. PTT notices a transient disconnect
            // on its own, from the loopback link closing. state.selected.saved is the full,
            // ground-truth list of every known (non-hidden) record, each with its own `ended`
            // flag -- that is authoritative, unlike inferring intent from a derived status string.
            val record = state.selected.saved.firstOrNull { Hex.encode(it.id) == workspace }
            if (PttLinkGone.onDrop(stillSaved = record != null, ended = record?.ended == true)) sendGone(workspace)
            disconnectPeer(workspace)
            synchronized(floorLock) { floorRecords.remove(workspace) }
            tickets.invalidate(workspace)
        }

        for ((workspace, current) in next) {
            val previousScope = previous[workspace]
            if (previousScope == null || previousScope.floorCoordinator != current.floorCoordinator) {
                synchronized(floorLock) { restoreFloor(workspace, current.floorCoordinator) }
            }
            if (!sameScope(current, previousScope)) peers[workspace]?.send(current.json("scope"))
        }

        val toOffer = LinkedHashSet<String>().apply {
            addAll(newlyConnected)
            if (selectionChanged) selectedWorkspace?.let(::add)
        }
        for (workspace in toOffer) next[workspace]?.let(::sendOffer)
    }

    fun observePli(workspace: ByteArray, member: ByteArray, uid: String, document: org.w3c.dom.Document) =
        talkerMap?.position(workspace, member, uid, document)

    fun receive(publication: JSONObject, complete: (Boolean) -> Unit) {
        val topic = publication.optString("topic")
        if (topic != PTT_TOPIC) { complete(false); return }
        val workspace = publication.optJSONArray("workspace")?.bytesHex()
        val active = workspace?.let(scopes::get)
        if (active == null) {
            // Voice from a workspace we are not tracking is intentionally not replayed into PTT.
            complete(true)
            return
        }
        val author = publication.optJSONArray("member")?.bytesHex()
        if (author == active.member) { complete(true); return }
        val payload = publication.optJSONArray("payload")?.bytes(MAX_MEDIA_BYTES)
        val sequence = publication.optLong("counter", 0)
        if (author == null || payload == null || payload.isEmpty() || sequence <= 0) {
            complete(false)
            return
        }
        if (PttFloorWire.isControl(payload)) {
            val message = PttFloorWire.decode(payload)
            if (message == null || active.members.none { it.id.equals(author, ignoreCase = true) }) {
                complete(false)
                return
            }
            when (message.action) {
                PttFloorAction.REQUEST -> {
                    if (active.member == active.floorCoordinator) handleFloorRequest(active, author, message)
                    complete(true)
                    return
                }
                PttFloorAction.RELEASE -> {
                    if (active.member == active.floorCoordinator) handleFloorRelease(active, author, message)
                    complete(true)
                    return
                }
                PttFloorAction.GRANT, PttFloorAction.BUSY, PttFloorAction.RELEASED -> {
                    if (!author.equals(active.floorCoordinator, ignoreCase = true)) {
                        complete(false)
                        return
                    }
                    observeFloorMessage(active.workspace, message)
                    sendFloorToLocalApp(active.workspace, author, message)
                    complete(true)
                    return
                }
            }
            return
        }
        talkerMap?.media(active.workspace, author, sequence, payload)
        val connectedPeer = peers[active.workspace]
        if (connectedPeer == null) {
            Log.i("Arachne", "PTT_PUBLICATION_DROPPED reason=ptt_not_attached workspace=${active.workspace}")
            complete(true)
            return
        }
        val id = UUID.randomUUID().toString()
        val delivery = Pending(active.workspace, complete)
        if (pending.size >= 64 || pending.putIfAbsent(id, delivery) != null) {
            complete(false)
            return
        }
        timer.schedule({ pending.remove(id)?.complete?.invoke(false) }, DELIVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!connectedPeer.send(JSONObject()
                .put("op", "publication")
                .put("id", id)
                .put("workspace", active.workspace)
                .put("speaker", author)
                .put("sequence", sequence)
                .put("payload", Base64.encodeToString(payload, Base64.NO_WRAP)))) {
            pending.remove(id)?.complete?.invoke(false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // No GONE here: ATAK/the plugin stopping is not the user leaving, removing or ending a
        // workspace. PTT keeps its nets in place and shows "no link" from the loopback link
        // closing; a fresh OFFER follows on reconnect once the plugin (and this bridge) restart.
        talkerMap?.close()
        runCatching { server.close() }
        peers.keys.toList().forEach(::disconnectPeer)
        timer.shutdownNow()
        tickets.clear()
        synchronized(floorLock) { floorRecords.clear() }
    }

    private fun proof(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(PttLinkProtocol.ACTION_PROOF).setPackage(PttLinkProtocol.ATAK_PACKAGE),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun sendOffer(current: Scope) {
        val now = System.currentTimeMillis()
        val ticket = tickets.issue(current.workspace, now)
        val extras = PttOfferExtras.build(
            workspace = current.workspace, workspaceName = current.workspaceName, memberName = current.memberName,
            port = server.localPort, ticket = ticket, expiresAt = now + PttLinkProtocol.OFFER_TICKET_LIFETIME_MS,
            proof = proof(),
        )
        sendExplicitBroadcast(withExtras(Intent(PttLinkProtocol.ACTION_OFFER).setPackage(PttLinkProtocol.PTT_PACKAGE), extras))
        Log.i("Arachne", "PTT_LINK_OFFER_SENT workspace=${current.workspace}")
    }

    private fun sendGone(workspace: String) {
        val intent = Intent(PttLinkProtocol.ACTION_GONE).setPackage(PttLinkProtocol.PTT_PACKAGE)
            .putExtra(PttLinkProtocol.EXTRA_PROTOCOL, PttLinkProtocol.VERSION)
            .putExtra(PttLinkProtocol.EXTRA_WORKSPACE, workspace)
            .putExtra(PttLinkProtocol.EXTRA_PROOF, proof())
        sendExplicitBroadcast(intent)
        Log.i("Arachne", "PTT_LINK_GONE_SENT workspace=$workspace")
    }

    /** OFFER/GONE both carry the `proof` PendingIntent on every API level. On 34+ the broadcast
     * itself also shares the sender's identity, so the PTT app can check sentFromUid == ATAK's uid
     * without falling back to the proof's creatorUid (still checked on older APIs). */
    private fun sendExplicitBroadcast(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.sendBroadcast(intent, null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        } else {
            context.sendBroadcast(intent)
        }
    }

    private fun withExtras(intent: Intent, extras: Map<String, Any>): Intent {
        for ((key, value) in extras) when (value) {
            is Int -> intent.putExtra(key, value)
            is Long -> intent.putExtra(key, value)
            is String -> intent.putExtra(key, value)
            is PendingIntent -> intent.putExtra(key, value)
            else -> error("Unsupported PTT offer extra \"$key\" of type ${value::class}")
        }
        return intent
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { return }
            if (!socket.inetAddress.isLoopbackAddress) { socket.close(); continue }
            Thread({ serve(Peer(socket)) }, "arachne-ptt-rpc-client").apply { isDaemon = true; start() }
        }
    }

    private fun serve(candidate: Peer) {
        var attached: String? = null
        try {
            candidate.socket.soTimeout = 10_000
            val hello = candidate.read()
            val workspace = hello.optString("workspace")
            val ticketText = hello.optString("ticket")
            val now = System.currentTimeMillis()
            require(hello.optString("op") == "attach" && workspace.isNotEmpty()
                && tickets.consume(ticketText, workspace, now)
                && scopes.containsKey(workspace)) { "PTT attachment is expired, already used, or workspace changed" }
            attached = workspace
            val displaced = peers.put(workspace, candidate)
            if (displaced != null && displaced !== candidate) closePeer(displaced, workspace)
            candidate.socket.soTimeout = 0
            if (!candidate.send(checkNotNull(scopes[workspace]).json("attached"))) return
            while (!closed && peers[workspace] === candidate) handle(workspace, candidate, candidate.read())
        } catch (error: Exception) {
            if (!closed && error !is EOFException) Log.w("Arachne", "PTT_RPC_DISCONNECTED reason=${error.javaClass.simpleName}")
        } finally {
            val workspace = attached
            if (workspace != null && peers.remove(workspace, candidate)) closePeer(candidate, workspace)
            else candidate.close()
        }
    }

    private fun handle(workspace: String, client: Peer, request: JSONObject) {
        when (request.optString("op")) {
            "ack" -> {
                val id = request.optString("id")
                pending.remove(id)?.let { delivery ->
                    delivery.complete(request.optBoolean("stored") && delivery.workspace == workspace && scopes.containsKey(workspace))
                }
            }
            "publish" -> publish(workspace, client, request)
            else -> client.send(JSONObject().put("op", "response").put("id", request.optString("id"))
                .put("accepted", false).put("error", "unsupported PTT operation"))
        }
    }

    private fun publish(workspace: String, client: Peer, request: JSONObject) {
        val active = scopes[workspace]
        val requestWorkspace = request.optString("workspace")
        val topic = request.optString("topic")
        val payload = runCatching { Base64.decode(request.getString("payload"), Base64.NO_WRAP) }.getOrNull()
        val id = request.optString("id")
        if (active == null || peers[workspace] !== client || requestWorkspace != active.workspace || topic != PTT_TOPIC
            || payload == null || payload.isEmpty() || payload.size > MAX_MEDIA_BYTES || id.isBlank()) {
            client.send(JSONObject().put("op", "response").put("id", id).put("accepted", false)
                .put("error", "selected workspace or PTT payload is unavailable"))
            return
        }
        if (active.member.equals(active.floorCoordinator, ignoreCase = true)
            && PttFloorWire.isControl(payload)
        ) {
            val message = PttFloorWire.decode(payload)
            if (message?.action == PttFloorAction.REQUEST) {
                handleFloorRequest(active, active.member, message, client, id)
                return
            }
            if (message?.action == PttFloorAction.RELEASE) {
                handleFloorRelease(active, active.member, message, client, id)
                return
            }
        }
        val accepted = publish(active.workspaceBytes, topic, payload) { result ->
            val response = result.fold(
                onSuccess = { report ->
                val outcome = WorkspaceData.outcome(report)
                    if (outcome.queued && !outcome.uncertain) {
                        talkerMap?.media(active.workspace, active.member, localMediaSequence.incrementAndGet(), payload)
                    }
                    JSONObject().put("op", "response").put("id", id)
                        .put("accepted", outcome.queued && !outcome.uncertain && outcome.failed == 0)
                        .put("queued", outcome.queued).put("remote_accepted", outcome.remoteAccepted)
                        .put("failed", outcome.failed).put("uncertain", outcome.uncertain)
                },
                onFailure = { error -> JSONObject().put("op", "response").put("id", id)
                    .put("accepted", false).put("error", error.message ?: "protected publication failed") },
            )
            client.send(response)
        }
        if (!accepted) client.send(JSONObject().put("op", "response").put("id", id).put("accepted", false)
            .put("error", "workspace publication queue is full"))
    }

    private fun handleFloorRequest(
        active: Scope,
        speaker: String,
        request: PttFloorMessage,
        localClient: Peer? = null,
        localRequestId: String = "",
    ) {
        val decision = synchronized(floorLock) {
            floorAuthorityFor(active.workspace).request(request.channel, speaker, request.transmission, System.currentTimeMillis())
                .also { persistFloor(active.workspace) }
        }
        val response = when (decision) {
            is PttFloorDecision.Granted -> PttFloorMessage(
                PttFloorAction.GRANT, request.channel, request.transmission,
                holder = decision.lease.member, generation = decision.lease.generation,
                expiresAtMillis = decision.lease.expiresAtMillis,
            )
            is PttFloorDecision.Busy -> PttFloorMessage(
                PttFloorAction.BUSY, request.channel, request.transmission,
                holderTransmission = decision.lease.transmission, holder = decision.lease.member,
                generation = decision.lease.generation, expiresAtMillis = decision.lease.expiresAtMillis,
            )
            else -> return
        }
        localClient?.send(JSONObject().put("op", "response").put("id", localRequestId)
            .put("accepted", true).put("queued", true).put("remote_accepted", 1).put("failed", 0))
        broadcastFloor(active, response) { accepted ->
            if (accepted) {
                if (speaker.equals(active.member, ignoreCase = true)) sendFloorToLocalApp(active.workspace, active.member, response)
            } else {
                if (response.action == PttFloorAction.GRANT) {
                    synchronized(floorLock) {
                        floorAuthorityFor(active.workspace).release(request.channel, speaker, request.transmission)
                        persistFloor(active.workspace)
                    }
                }
                if (speaker.equals(active.member, ignoreCase = true)) {
                    sendFloorToLocalApp(active.workspace, active.member, PttFloorMessage(
                        PttFloorAction.BUSY, request.channel, request.transmission,
                        generation = synchronized(floorLock) { floorAuthorityFor(active.workspace).generation },
                    ))
                }
            }
        }
    }

    private fun handleFloorRelease(
        active: Scope,
        speaker: String,
        request: PttFloorMessage,
        localClient: Peer? = null,
        localRequestId: String = "",
    ) {
        val decision = synchronized(floorLock) {
            floorAuthorityFor(active.workspace).release(request.channel, speaker, request.transmission).also {
                if (it !is PttFloorDecision.Ignored) persistFloor(active.workspace)
            }
        }
        localClient?.send(JSONObject().put("op", "response").put("id", localRequestId)
            .put("accepted", true).put("queued", true).put("remote_accepted", 1).put("failed", 0))
        if (decision is PttFloorDecision.Released) {
            broadcastFloor(active, PttFloorMessage(
                PttFloorAction.RELEASED, request.channel, request.transmission, generation = decision.generation,
            )) { accepted ->
                if (accepted && speaker.equals(active.member, ignoreCase = true)) {
                    sendFloorToLocalApp(active.workspace, active.member, PttFloorMessage(
                        PttFloorAction.RELEASED, request.channel, request.transmission,
                        generation = decision.generation,
                    ))
                }
            }
        }
    }

    private fun broadcastFloor(active: Scope, message: PttFloorMessage, result: (Boolean) -> Unit) {
        val payload = PttFloorWire.encode(message)
        val queued = publish(active.workspaceBytes, PTT_TOPIC, payload) { outcome ->
            result(outcome.fold(
                onSuccess = { report ->
                    val delivery = WorkspaceData.outcome(report)
                    delivery.queued && !delivery.uncertain && delivery.failed == 0
                },
                onFailure = { false },
            ))
        }
        if (!queued) result(false)
    }

    private fun sendFloorToLocalApp(workspace: String, author: String, message: PttFloorMessage) {
        peers[workspace]?.send(JSONObject().put("op", "floor").put("speaker", author)
            .put("payload", Base64.encodeToString(PttFloorWire.encode(message), Base64.NO_WRAP)))
    }

    private fun observeFloorMessage(workspace: String, message: PttFloorMessage) {
        synchronized(floorLock) {
            val authority = floorAuthorityFor(workspace)
            when (message.action) {
                PttFloorAction.GRANT -> authority.observeGrant(PttFloorLease(
                    message.channel, message.holder, message.transmission, message.generation, message.expiresAtMillis,
                ))
                PttFloorAction.BUSY -> if (message.holder.isNotEmpty()) authority.observeGrant(PttFloorLease(
                    message.channel, message.holder, message.holderTransmission, message.generation, message.expiresAtMillis,
                ))
                PttFloorAction.RELEASED -> authority.observeRelease(
                    message.channel, message.transmission, message.generation,
                )
                else -> return
            }
            persistFloor(workspace)
        }
    }

    /** Guarded by floorLock. */
    private fun floorAuthorityFor(workspace: String): PttFloorAuthority =
        floorRecords.getOrPut(workspace) { FloorRecord("", PttFloorAuthority()) }.authority

    /** Guarded by floorLock. */
    private fun restoreFloor(workspace: String, coordinator: String) {
        if (workspace.isEmpty()) return
        val prefix = "$workspace:$coordinator"
        val legacyPrefix = "$prefix:${PttFloorWire.DEFAULT_CHANNEL}"
        val generation = maxOf(
            floorPreferences.getLong("$prefix:generation", 0),
            floorPreferences.getLong("$legacyPrefix:generation", 0),
        )
        val channels = floorPreferences.getStringSet("$prefix:channels", emptySet()).orEmpty().toMutableSet()
        if (floorPreferences.getString("$legacyPrefix:holder", "").orEmpty().isNotEmpty()) {
            channels += PttFloorWire.DEFAULT_CHANNEL
        }
        val now = System.currentTimeMillis()
        val leases = channels.mapNotNull { channel ->
            val leasePrefix = "$prefix:lease:$channel"
            val oldPrefix = if (channel == PttFloorWire.DEFAULT_CHANNEL) legacyPrefix else ""
            val holder = floorPreferences.getString("$leasePrefix:holder", null)
                ?: oldPrefix.takeIf(String::isNotEmpty)?.let { floorPreferences.getString("$it:holder", null) }
            val transmission = floorPreferences.getString("$leasePrefix:transmission", null)
                ?: oldPrefix.takeIf(String::isNotEmpty)?.let { floorPreferences.getString("$it:transmission", null) }
            val expiresAt = floorPreferences.getLong("$leasePrefix:expires", 0)
                .takeIf { it > 0 }
                ?: oldPrefix.takeIf(String::isNotEmpty)?.let { floorPreferences.getLong("$it:expires", 0) }
                ?: 0
            if (holder.isNullOrEmpty() || transmission.isNullOrEmpty() || expiresAt <= now) null
            else channel to PttFloorLease(channel, holder, transmission, generation, expiresAt)
        }.toMap()
        floorRecords[workspace] = FloorRecord(coordinator, PttFloorAuthority(generation, leases))
    }

    /** Guarded by floorLock. */
    private fun persistFloor(workspace: String) {
        if (workspace.isEmpty()) return
        val record = floorRecords[workspace] ?: return
        val prefix = "$workspace:${record.coordinator}"
        val leases = record.authority.leases().associateBy(PttFloorLease::channel)
        val previousChannels = floorPreferences.getStringSet("$prefix:channels", emptySet()).orEmpty().toSet()
        val editor = floorPreferences.edit()
            .putLong("$prefix:generation", record.authority.generation)
            .putStringSet("$prefix:channels", leases.keys)
        (previousChannels - leases.keys).forEach { channel ->
            val leasePrefix = "$prefix:lease:$channel"
            editor.remove("$leasePrefix:holder").remove("$leasePrefix:transmission").remove("$leasePrefix:expires")
        }
        leases.forEach { (channel, lease) ->
            val leasePrefix = "$prefix:lease:$channel"
            editor.putString("$leasePrefix:holder", lease.member)
                .putString("$leasePrefix:transmission", lease.transmission)
                .putLong("$leasePrefix:expires", lease.expiresAtMillis)
        }
        val legacyPrefix = "$prefix:${PttFloorWire.DEFAULT_CHANNEL}"
        editor.remove("$legacyPrefix:generation").remove("$legacyPrefix:holder")
            .remove("$legacyPrefix:transmission").remove("$legacyPrefix:expires")
        check(editor.commit()) { "Could not persist the PTT floor lease" }
    }

    /** One Scope per currently connected workspace (not just the UI-selected one), so
     * every linked workspace can be offered, ticketed and served concurrently. Busy/attention
     * flags on the entry are intentionally not gated on here: a transient busy workspace keeps
     * its last-known scope instead of appearing to disconnect and reconnect. */
    private fun scopesFor(state: WorkspaceConnectionView): Map<String, Scope> {
        val result = LinkedHashMap<String, Scope>()
        for (view in state.connected) {
            val record = view.active ?: continue
            val memberId = record.memberId?.let(Hex::encode) ?: continue
            val members = view.members.map {
                Member(it.id, it.name.orEmpty().ifBlank { "Member ${it.id.take(8)}" }, it.administrator)
            }
            val localName = members.firstOrNull { it.id == memberId }?.name ?: continue
            val workspace = Hex.encode(record.id)
            result[workspace] = Scope(workspace, record.id.copyOf(), record.sharedName ?: record.legacyName,
                memberId, localName, members)
        }
        return result
    }

    private fun sameScope(left: Scope?, right: Scope?): Boolean = when {
        left == null || right == null -> left == right
        else -> left.workspace == right.workspace && left.workspaceName == right.workspaceName
            && left.member == right.member && left.memberName == right.memberName
            && left.members == right.members
    }

    private fun disconnectPeer(workspace: String) {
        peers.remove(workspace)?.let { closePeer(it, workspace) }
    }

    private fun closePeer(target: Peer, workspace: String) {
        target.close()
        for ((id, delivery) in pending.entries) {
            if (delivery.workspace == workspace && pending.remove(id, delivery)) delivery.complete(false)
        }
    }

    private fun JSONArray.bytes(maximum: Int): ByteArray? {
        if (length() !in 1..maximum) return null
        val result = ByteArray(length())
        for (index in result.indices) {
            val value = getInt(index)
            if (value !in 0..255) return null
            result[index] = value.toByte()
        }
        return result
    }

    private fun JSONArray.bytesHex(): String? = bytes(32)?.takeIf { it.size == 32 }?.let(Hex::encode)
}
