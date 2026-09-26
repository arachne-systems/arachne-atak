package dev.arachne.atak

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
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Same-device RPC into the selected ATAK workspace. Only protected publications cross it. */
internal class PttWorkspaceBridge(
    context: Context,
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

    private data class Ticket(val workspace: String, val expiresAt: Long)
    private data class Pending(val workspace: String, val complete: (Boolean) -> Unit)

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
        const val ACTION_ATTACH = "dev.arachne.ptt.PLUGIN_SESSION"
        const val EXTRA_PORT = "dev.arachne.ptt.extra.PLUGIN_PORT"
        const val EXTRA_TICKET = "dev.arachne.ptt.extra.PLUGIN_TICKET"
        const val EXTRA_WORKSPACE = "dev.arachne.ptt.extra.PLUGIN_WORKSPACE"
        private const val PTT_TOPIC = "streams/ptt"
        private const val MAX_MEDIA_BYTES = 12 * 1024
        private const val MAX_FRAME_BYTES = 256 * 1024
        private const val DELIVERY_TIMEOUT_MS = 5_000L
        private const val TICKET_LIFETIME_MS = 10 * 60_000L
        private val random = SecureRandom()
    }

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "arachne-ptt-delivery-timeouts").apply { isDaemon = true }
    }
    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val floorPreferences = context.applicationContext.getSharedPreferences("ptt-floor-authority", Context.MODE_PRIVATE)
    private val talkerMap = if (BuildConfig.DEBUG) PttTalkerMap() else null
    private val localMediaSequence = java.util.concurrent.atomic.AtomicLong()
    private val floorLock = Any()
    private var floorWorkspace = ""
    private var floorCoordinator = ""
    private var floorAuthority = PttFloorAuthority()
    @Volatile private var scope: Scope? = null
    @Volatile private var peer: Peer? = null
    @Volatile private var closed = false

    init {
        Thread(::acceptLoop, "arachne-ptt-workspace-rpc").apply { isDaemon = true; start() }
    }

    fun launchIntent(state: WorkspaceConnectionView): Intent? {
        val selected = scopeFor(state) ?: return null
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tickets.clear()
        tickets[token] = Ticket(selected.workspace, android.os.SystemClock.elapsedRealtime() + TICKET_LIFETIME_MS)
        return Intent().setClassName("dev.arachne.ptt", "dev.arachne.ptt.MainActivity")
            .setAction(ACTION_ATTACH)
            .putExtra(EXTRA_PORT, server.localPort)
            .putExtra(EXTRA_TICKET, token)
            .putExtra(EXTRA_WORKSPACE, selected.workspace)
    }

    fun update(state: WorkspaceConnectionView) {
        val next = scopeFor(state)
        val previous = scope
        if (sameScope(next, previous)) return
        scope = next
        talkerMap?.bind(next?.workspace, next?.members?.associate { it.id to it.name }.orEmpty())
        synchronized(floorLock) {
            if (next?.workspace != floorWorkspace || next?.floorCoordinator.orEmpty() != floorCoordinator)
                restoreFloor(next?.workspace.orEmpty(), next?.floorCoordinator.orEmpty())
        }
        val connectedPeer = peer ?: return
        if (next == null) {
            connectedPeer.send(JSONObject().put("op", "scope").put("ready", false))
        } else {
            connectedPeer.send(next.json("scope"))
        }
        if (previous?.workspace != next?.workspace) {
            for ((id, delivery) in pending.entries) {
                if (delivery.workspace != next?.workspace && pending.remove(id, delivery)) delivery.complete(false)
            }
        }
    }

    fun observePli(workspace: ByteArray, member: ByteArray, uid: String, document: org.w3c.dom.Document) =
        talkerMap?.position(workspace, member, uid, document)

    fun receive(publication: JSONObject, complete: (Boolean) -> Unit) {
        val topic = publication.optString("topic")
        if (topic != PTT_TOPIC) { complete(false); return }
        val active = scope
        val workspace = publication.optJSONArray("workspace")?.bytesHex()
        if (active == null || workspace != active.workspace) {
            // Voice from an unselected workspace is intentionally not replayed into PTT.
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
                    observeFloorMessage(message)
                    sendFloorToLocalApp(author, message)
                    complete(true)
                    return
                }
            }
            return
        }
        talkerMap?.media(active.workspace, author, sequence, payload)
        val connectedPeer = peer
        if (connectedPeer == null) {
            Log.i("Arachne", "PTT_PUBLICATION_DROPPED reason=ptt_not_attached")
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
        talkerMap?.close()
        runCatching { server.close() }
        disconnect(peer)
        timer.shutdownNow()
        tickets.clear()
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { return }
            if (!socket.inetAddress.isLoopbackAddress) { socket.close(); continue }
            Thread({ serve(Peer(socket)) }, "arachne-ptt-rpc-client").apply { isDaemon = true; start() }
        }
    }

    private fun serve(candidate: Peer) {
        try {
            candidate.socket.soTimeout = 10_000
            val hello = candidate.read()
            val ticketText = hello.optString("ticket")
            val ticket = tickets[ticketText]
            val current = scope
            require(hello.optString("op") == "attach" && ticket != null
                && ticket.expiresAt >= android.os.SystemClock.elapsedRealtime()
                && ticket.workspace == hello.optString("workspace")
                && current?.workspace == ticket.workspace
                && tickets.remove(ticketText, ticket)) { "PTT attachment is expired, already used, or workspace changed" }
            synchronized(this) {
                disconnect(peer)
                peer = candidate
            }
            candidate.socket.soTimeout = 0
            if (!candidate.send(checkNotNull(current).json("attached"))) return
            while (!closed && peer === candidate) handle(candidate, candidate.read())
        } catch (error: Exception) {
            if (!closed && error !is EOFException) Log.w("Arachne", "PTT_RPC_DISCONNECTED reason=${error.javaClass.simpleName}")
        } finally {
            if (peer === candidate) disconnect(candidate)
            else candidate.close()
        }
    }

    private fun handle(client: Peer, request: JSONObject) {
        when (request.optString("op")) {
            "ack" -> {
                val id = request.optString("id")
                pending.remove(id)?.let { delivery ->
                    delivery.complete(request.optBoolean("stored") && scope?.workspace == delivery.workspace)
                }
            }
            "publish" -> publish(client, request)
            else -> client.send(JSONObject().put("op", "response").put("id", request.optString("id"))
                .put("accepted", false).put("error", "unsupported PTT operation"))
        }
    }

    private fun publish(client: Peer, request: JSONObject) {
        val active = scope
        val workspace = request.optString("workspace")
        val topic = request.optString("topic")
        val payload = runCatching { Base64.decode(request.getString("payload"), Base64.NO_WRAP) }.getOrNull()
        val id = request.optString("id")
        if (active == null || peer !== client || workspace != active.workspace || topic != PTT_TOPIC
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
            floorAuthority.request(request.channel, speaker, request.transmission, System.currentTimeMillis())
                .also { persistFloor() }
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
                if (speaker.equals(active.member, ignoreCase = true)) sendFloorToLocalApp(active.member, response)
            } else {
                if (response.action == PttFloorAction.GRANT) {
                    synchronized(floorLock) {
                        floorAuthority.release(request.channel, speaker, request.transmission)
                        persistFloor()
                    }
                }
                if (speaker.equals(active.member, ignoreCase = true)) {
                    sendFloorToLocalApp(active.member, PttFloorMessage(
                        PttFloorAction.BUSY, request.channel, request.transmission,
                        generation = floorAuthority.generation,
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
            floorAuthority.release(request.channel, speaker, request.transmission).also {
                if (it !is PttFloorDecision.Ignored) persistFloor()
            }
        }
        localClient?.send(JSONObject().put("op", "response").put("id", localRequestId)
            .put("accepted", true).put("queued", true).put("remote_accepted", 1).put("failed", 0))
        if (decision is PttFloorDecision.Released) {
            broadcastFloor(active, PttFloorMessage(
                PttFloorAction.RELEASED, request.channel, request.transmission, generation = decision.generation,
            )) { accepted ->
                if (accepted && speaker.equals(active.member, ignoreCase = true)) {
                    sendFloorToLocalApp(active.member, PttFloorMessage(
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

    private fun sendFloorToLocalApp(author: String, message: PttFloorMessage) {
        peer?.send(JSONObject().put("op", "floor").put("speaker", author)
            .put("payload", Base64.encodeToString(PttFloorWire.encode(message), Base64.NO_WRAP)))
    }

    private fun observeFloorMessage(message: PttFloorMessage) {
        synchronized(floorLock) {
            when (message.action) {
                PttFloorAction.GRANT -> floorAuthority.observeGrant(PttFloorLease(
                    message.channel, message.holder, message.transmission, message.generation, message.expiresAtMillis,
                ))
                PttFloorAction.BUSY -> if (message.holder.isNotEmpty()) floorAuthority.observeGrant(PttFloorLease(
                    message.channel, message.holder, message.holderTransmission, message.generation, message.expiresAtMillis,
                ))
                PttFloorAction.RELEASED -> floorAuthority.observeRelease(
                    message.channel, message.transmission, message.generation,
                )
                else -> return
            }
            persistFloor()
        }
    }

    private fun restoreFloor(workspace: String, coordinator: String) {
        floorWorkspace = workspace
        floorCoordinator = coordinator
        if (workspace.isEmpty()) {
            floorAuthority = PttFloorAuthority()
            return
        }
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
        floorAuthority = PttFloorAuthority(generation, leases)
    }

    private fun persistFloor() {
        if (floorWorkspace.isEmpty()) return
        val prefix = "$floorWorkspace:$floorCoordinator"
        val leases = floorAuthority.leases().associateBy(PttFloorLease::channel)
        val previousChannels = floorPreferences.getStringSet("$prefix:channels", emptySet()).orEmpty().toSet()
        val editor = floorPreferences.edit()
            .putLong("$prefix:generation", floorAuthority.generation)
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

    private fun scopeFor(state: WorkspaceConnectionView): Scope? {
        val view = state.selected
        val record = view.active ?: return null
        if (view.busy || view.attention || record.ended || record.memberId == null || record.joinPeer != null) return null
        val connected = state.connected.singleOrNull { it.active?.slot == record.slot } ?: return null
        val members = connected.members.map {
            Member(it.id, it.name.orEmpty().ifBlank { "Member ${it.id.take(8)}" }, it.administrator)
        }
        val memberId = Hex.encode(record.memberId)
        if (members.none { it.id == memberId }) return null
        val localName = members.firstOrNull { it.id == memberId }?.name
            ?: return null
        return Scope(Hex.encode(record.id), record.id.copyOf(), record.sharedName ?: record.legacyName,
            memberId, localName, members)
    }

    private fun sameScope(left: Scope?, right: Scope?): Boolean = when {
        left == null || right == null -> left == right
        else -> left.workspace == right.workspace && left.workspaceName == right.workspaceName
            && left.member == right.member && left.memberName == right.memberName
            && left.members == right.members
    }

    private fun disconnect(target: Peer?) {
        if (target == null) return
        synchronized(this) { if (peer === target) peer = null }
        target.close()
        for ((id, delivery) in pending.entries) {
            if (pending.remove(id, delivery)) delivery.complete(false)
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
