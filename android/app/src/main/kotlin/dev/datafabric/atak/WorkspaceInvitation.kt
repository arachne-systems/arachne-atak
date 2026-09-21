package dev.arachne.atak

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal data class WorkspaceRoute(val peer: ByteArray, val address: String) {
    fun json() = JSONObject().put("peer", JSONArray(peer.map { it.toInt() and 255 })).put("address", address)
}

/** Transport envelope only. Rust validates invitation authority and checkpoint.
 * No endpoint in this link grants membership. Never log the link. */
internal object WorkspaceInvitation {
    private const val PREFIX = "arachne://join#"
    private const val LEGACY_PREFIX = "datafabric://join#"
    private const val COMPACT_VERSION: Byte = 3
    private const val INVITATION_SIZE = 293
    private const val PEER_SIZE = 32
    private const val BOOTSTRAP_PEERS = 3
    private const val COMPACT_SIZE = 1 + INVITATION_SIZE + PEER_SIZE * BOOTSTRAP_PEERS
    private const val COMPACT_LINK_SIZE = 535
    private const val MAX_LINK_SIZE = 65536
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private val legacySizes = mapOf("workspace" to 32..32, "peer" to 32..32,
        "invitation" to INVITATION_SIZE..INVITATION_SIZE, "checkpoint" to 1..32768)

    private fun bytes(array: JSONArray, bounds: IntRange): ByteArray {
        require(array.length() in bounds)
        return ByteArray(array.length()) { array.getInt(it).also { value -> require(value in 0..255) }.toByte() }
    }

    fun routes(array: JSONArray): List<WorkspaceRoute> {
        require(array.length() <= 7)
        val routes = (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            require(value.length() == 2)
            WorkspaceRoute(bytes(value.getJSONArray("peer"), PEER_SIZE..PEER_SIZE), address(value.getString("address")))
        }
        require(routes.map { it.peer.toList() }.distinct().size == routes.size)
        return routes
    }

    private fun address(value: String): String {
        val parts = value.split(':')
        require(parts.size == 2 && parts[1].toInt() in 1..65535)
        val octets = parts[0].split('.')
        require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
        require(octets[0].toInt() in 1..223 && octets[0].toInt() != 127)
        return value
    }

    fun bootstrapPeers(value: JSONObject): List<ByteArray> {
        val peers = mutableListOf<ByteArray>()
        fun add(peer: ByteArray) {
            require(peer.size == PEER_SIZE && peer.any { it.toInt() != 0 })
            if (peers.none { it.contentEquals(peer) }) peers.add(peer)
        }
        value.optJSONArray("peer")?.let { add(bytes(it, PEER_SIZE..PEER_SIZE)) }
        value.optJSONArray("bootstrap_peers")?.let { array ->
            require(array.length() <= BOOTSTRAP_PEERS)
            for (index in 0 until array.length()) add(bytes(array.getJSONArray(index), PEER_SIZE..PEER_SIZE))
        }
        value.optJSONArray("routes")?.let { array -> routes(array).forEach { add(it.peer) } }
        require(peers.isNotEmpty())
        return peers.take(BOOTSTRAP_PEERS)
    }

    /** Untrusted storage key only. Rust authenticates this workspace ID after
     * an authorized member supplies the signed checkpoint. */
    fun workspaceHint(value: JSONObject): ByteArray =
        bytes(value.getJSONArray("invitation"), INVITATION_SIZE..INVITATION_SIZE).copyOfRange(5, 37)

    fun encode(value: JSONObject): String {
        val invitation = bytes(value.getJSONArray("invitation"), INVITATION_SIZE..INVITATION_SIZE)
        val peers = bootstrapPeers(value)
        val raw = ByteArray(COMPACT_SIZE)
        raw[0] = COMPACT_VERSION
        invitation.copyInto(raw, 1)
        peers.forEachIndexed { index, peer -> peer.copyInto(raw, 1 + INVITATION_SIZE + index * PEER_SIZE) }
        return (PREFIX + Base64.encodeToString(raw, FLAGS)).also { require(it.length == COMPACT_LINK_SIZE) }
    }

    fun decode(link: String): JSONObject {
        val prefix = when {
            link.startsWith(PREFIX) -> PREFIX
            link.startsWith(LEGACY_PREFIX) -> LEGACY_PREFIX
            else -> throw IllegalArgumentException("invalid invitation scheme")
        }
        require(link.length in (prefix.length + 1)..MAX_LINK_SIZE)
        val encoded = link.substring(prefix.length)
        require(encoded.matches(Regex("[A-Za-z0-9_-]+")))
        val raw = Base64.decode(encoded, FLAGS)
        require(Base64.encodeToString(raw, FLAGS) == encoded)
        return if (raw.size == COMPACT_SIZE && raw[0] == COMPACT_VERSION) compact(raw) else legacy(raw)
    }

    private fun compact(raw: ByteArray): JSONObject {
        val invitation = raw.copyOfRange(1, 1 + INVITATION_SIZE)
        val peers = JSONArray()
        var padding = false
        repeat(BOOTSTRAP_PEERS) { index ->
            val start = 1 + INVITATION_SIZE + index * PEER_SIZE
            val peer = raw.copyOfRange(start, start + PEER_SIZE)
            if (peer.all { it.toInt() == 0 }) padding = true
            else {
                require(!padding)
                val json = JSONArray(peer.map { it.toInt() and 255 })
                require((0 until peers.length()).none { peers.getJSONArray(it).toString() == json.toString() })
                peers.put(json)
            }
        }
        require(peers.length() > 0)
        return JSONObject()
            .put("invitation", JSONArray(invitation.map { it.toInt() and 255 }))
            .put("peer", peers.getJSONArray(0))
            .put("bootstrap_peers", peers)
    }

    private fun legacy(raw: ByteArray): JSONObject {
        val parser = org.json.JSONTokener(String(raw, Charsets.UTF_8))
        val value = parser.nextValue() as JSONObject
        require(parser.nextClean() == '\u0000')
        val version = value.getInt("version")
        require((version == 1 && value.length() == 6) || (version == 2 && value.length() == 7))
        val result = JSONObject().put("address", address(value.getString("address")))
        for ((key, bounds) in legacySizes) {
            val decoded = Base64.decode(value.getString(key), FLAGS)
            require(decoded.size in bounds)
            result.put(key, JSONArray(decoded.map { it.toInt() and 255 }))
        }
        val decodedRoutes = JSONArray()
        if (version == 2) {
            val encodedRoutes = value.getJSONArray("routes")
            require(encodedRoutes.length() <= 7)
            for (index in 0 until encodedRoutes.length()) {
                val route = encodedRoutes.getJSONObject(index)
                require(route.length() == 2)
                val peer = Base64.decode(route.getString("peer"), FLAGS)
                decodedRoutes.put(JSONObject().put("peer", JSONArray(peer.map { it.toInt() and 255 }))
                    .put("address", route.getString("address")))
            }
        }
        routes(decodedRoutes)
        result.put("routes", decodedRoutes)
        result.put("bootstrap_peers", JSONArray(bootstrapPeers(result).map { peer ->
            JSONArray(peer.map { it.toInt() and 255 })
        }))
        return result
    }
}
