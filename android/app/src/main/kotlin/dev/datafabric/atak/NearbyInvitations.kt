package dev.arachne.atak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal data class NearbyEndpoint(val id: ByteArray, val name: String? = null) {
    val label = name ?: "Unnamed Arachne device · " + id.take(4).joinToString("") { "%02x".format(it.toInt() and 255) }
}
internal enum class NearbyJoinMode(val wire: String, val label: String) {
    REQUEST_ACCESS("request_access", "Request access"),
    OPEN_JOINING("open_joining", "Open joining");

    companion object { fun fromWire(value: String) = entries.single { it.wire == value } }
}
internal data class NearbyWorkspace(val peer: ByteArray, val mode: NearbyJoinMode, val link: String, val name: String?) {
    val label = name ?: "Workspace name unavailable"
    val code = WorkspaceInvitation.workspaceHint(WorkspaceInvitation.decode(link))
        .take(4).joinToString("") { "%02x".format(it.toInt() and 255) }
}
internal data class NearbyWorkspaceResults(val workspaces: List<NearbyWorkspace>, val limited: Boolean)

/** Local route discovery only. The invitation remains the sole authority to join. */
internal class NearbyInvitations(
    context: Context,
    private val deviceName: String,
    private val received: (String) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val session = FabricSession(context, "nearby-invitations-v1", lanOnly = true) {}
    @Volatile private var closed = false
    private var nextDiscoveryAt = 0L
    private var identityPublished = false
    // A local nearby-invitation poll is not admission traffic, but the same
    // storm risk applies at scale: back off instead of resending every
    // 500 ms forever (FUT-30).
    private var pollRetryAttempt = 0
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            val admitted = session.request(POLL) { result ->
                result.onSuccess { response ->
                    if (!response.contentEquals(NULL)) runCatching {
                        val value = JSONObject(String(response, Charsets.UTF_8))
                        if (value.optString("state") == "nearby_invitation_received") {
                            val link = bytes(value.getJSONArray("invitation")).toString(Charsets.UTF_8)
                            WorkspaceInvitation.decode(link)
                            main.post { if (!closed) received(link) }
                        }
                    }.onFailure { Log.w("Arachne", "NEARBY_INVITATION_REJECTED", it) }
                }
                if (result.isSuccess && !identityPublished) {
                    val request = JSONObject().put("op", "set_nearby_identity").put("name", checkedMemberName(deviceName))
                    session.request(request.toString().toByteArray(Charsets.UTF_8)) { set ->
                        if (set.isSuccess) identityPublished = true
                    }
                }
                val attempt = if (result.isSuccess) 0 else pollRetryAttempt++
                if (result.isSuccess) pollRetryAttempt = 0
                if (!closed) main.postDelayed(this, JoinRetryBackoff.intervalMs(attempt))
            }
            if (!admitted) main.postDelayed(this, JoinRetryBackoff.intervalMs(pollRetryAttempt++))
        }
    }

    init { main.post(poll) }

    fun discover(complete: (Result<List<NearbyEndpoint>>) -> Unit): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < nextDiscoveryAt) {
            complete(Result.failure(IllegalStateException("Nearby discovery can refresh every 3 seconds.")))
            return true
        }
        nextDiscoveryAt = now + 3_000
        return session.request(DISCOVER) { result ->
            val parsed = result.map { response ->
                val value = JSONObject(String(response, Charsets.UTF_8))
                val array = value.getJSONArray("endpoints")
                val nameArray = value.optJSONArray("names") ?: JSONArray()
                val names = (0 until nameArray.length()).associate {
                    val item = nameArray.getJSONObject(it)
                    bytes(item.getJSONArray("id")).toList() to checkedMemberName(item.getString("name"))
                }
                (0 until array.length()).map {
                    val id = bytes(array.getJSONArray(it)).also { id -> require(id.size == 32) }
                    NearbyEndpoint(id, names[id.toList()])
                }
            }
            main.post { complete(parsed) }
        }
    }

    fun discoverWorkspaces(complete: (Result<NearbyWorkspaceResults>) -> Unit): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < nextDiscoveryAt) {
            complete(Result.failure(IllegalStateException("Nearby discovery can refresh every 3 seconds.")))
            return true
        }
        nextDiscoveryAt = now + 3_000
        return session.request(WORKSPACES) { result ->
            val parsed = result.map { response ->
                val value = JSONObject(String(response, Charsets.UTF_8))
                val array = value.getJSONArray("workspaces")
                NearbyWorkspaceResults((0 until array.length()).mapNotNull { index -> runCatching {
                    val item = array.getJSONObject(index)
                    val link = bytes(item.getJSONArray("invitation")).toString(Charsets.UTF_8)
                    WorkspaceInvitation.decode(link)
                    NearbyWorkspace(bytes(item.getJSONArray("peer")).also { require(it.size == 32) },
                        NearbyJoinMode.fromWire(item.getString("mode")), link,
                        if (item.isNull("workspace_name")) null else checkedWorkspaceName(item.getString("workspace_name")))
                }.getOrNull() }.sortedWith(compareBy({ it.label }, { it.code })), value.optBoolean("limited"))
            }
            main.post { complete(parsed) }
        }
    }

    fun advertise(mode: NearbyJoinMode?, invitation: String?, name: String? = null,
                  workspace: ByteArray? = null, complete: (Result<Unit>) -> Unit): Boolean {
        require((mode == null) == (invitation == null))
        if (invitation != null && runCatching { WorkspaceInvitation.decode(invitation) }.isFailure) return false
        val request = JSONObject().put("op", "set_nearby_workspace")
            .put("mode", mode?.wire ?: JSONObject.NULL)
        if (workspace != null) request.put("workspace", JSONArray(workspace.map { it.toInt() and 255 }))
        if (name != null) request.put("workspace_name", checkedWorkspaceName(name))
        if (invitation != null) request.put("invitation",
            JSONArray(invitation.toByteArray(Charsets.UTF_8).map { it.toInt() and 255 }))
        return session.request(request.toString().toByteArray(Charsets.UTF_8)) { result ->
            val changed = result.map { response ->
                val expected = if (mode == null) "nearby_workspace_private" else "nearby_workspace_advertised"
                check(JSONObject(String(response, Charsets.UTF_8)).optString("state") == expected)
            }
            main.post { complete(changed) }
        }
    }

    fun send(endpoint: NearbyEndpoint, invitation: String, complete: (Result<Unit>) -> Unit): Boolean {
        if (runCatching { WorkspaceInvitation.decode(invitation) }.isFailure) return false
        val request = JSONObject().put("op", "send_nearby_invitation")
            .put("peer", JSONArray(endpoint.id.map { it.toInt() and 255 }))
            .put("invitation", JSONArray(invitation.toByteArray(Charsets.UTF_8).map { it.toInt() and 255 }))
        return session.request(request.toString().toByteArray(Charsets.UTF_8)) { result ->
            val sent = result.map { response ->
                check(JSONObject(String(response, Charsets.UTF_8)).optString("state") == "nearby_invitation_sent")
            }
            main.post { complete(sent) }
        }
    }

    override fun close() {
        closed = true
        main.removeCallbacks(poll)
        session.close()
    }

    private fun bytes(array: JSONArray): ByteArray =
        ByteArray(array.length()) { array.getInt(it).also { value -> require(value in 0..255) }.toByte() }

    private companion object {
        val POLL = "{\"op\":\"poll_admission\"}".toByteArray(Charsets.UTF_8)
        val DISCOVER = "{\"op\":\"nearby_endpoints\"}".toByteArray(Charsets.UTF_8)
        val WORKSPACES = "{\"op\":\"nearby_workspaces\"}".toByteArray(Charsets.UTF_8)
        val NULL = "null".toByteArray(Charsets.UTF_8)
    }
}
