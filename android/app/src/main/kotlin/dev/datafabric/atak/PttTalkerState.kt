package dev.arachne.atak

import java.io.ByteArrayInputStream
import java.io.DataInputStream

internal data class PttMapPosition(val uid: String, val latitude: Double, val longitude: Double,
    val observedAt: Long, val expiresAt: Long)
internal data class PttMapTalker(val member: String, val name: String, val channel: String,
    val transmission: String, val position: PttMapPosition)

/** Ephemeral app projection. Callers supply the fabric's authenticated workspace and member.
 * A batch proves recent media reception, not live capture or audible output. One transmit target per member. */
internal class PttTalkerState {
    private data class Activity(val channel: String, val transmission: String, val receivedAt: Long)
    private var workspace: String? = null
    private var members = emptyMap<String, String>()
    private val positions = mutableMapOf<String, PttMapPosition>()
    private val active = mutableMapOf<String, Activity>()
    private val sequences = mutableMapOf<String, Long>()

    fun bind(workspace: String?, members: Map<String, String>) {
        require(workspace == null || workspace.matches(Regex("[0-9a-f]{64}")))
        require(members.keys.all { it.matches(Regex("[0-9a-f]{64}")) })
        if (this.workspace != workspace) {
            positions.clear(); active.clear(); sequences.clear()
        }
        this.workspace = workspace
        this.members = if (workspace == null) emptyMap() else members.toMap()
        positions.keys.retainAll(this.members.keys)
        active.keys.retainAll(this.members.keys)
        sequences.keys.retainAll(this.members.keys)
    }

    fun position(workspace: String, member: String, value: PttMapPosition, now: Long) {
        if (this.workspace != workspace || member !in members ||
            value.uid.isBlank() || value.uid.length > 1024 ||
            !value.latitude.isFinite() || value.latitude !in -90.0..90.0 ||
            !value.longitude.isFinite() || value.longitude !in -180.0..180.0 ||
            value.observedAt <= 0 || value.observedAt > now + 10_000 ||
            value.expiresAt <= now || value.expiresAt <= value.observedAt || now - value.observedAt > 60_000 ||
            value.observedAt < (positions[member]?.observedAt ?: 0) ||
            positions.any { it.key != member && it.value.uid == value.uid }) return
        positions[member] = value.copy(expiresAt = minOf(value.expiresAt, value.observedAt + 60_000))
    }

    fun media(workspace: String, member: String, sequence: Long, payload: ByteArray, now: Long) {
        if (this.workspace != workspace || member !in members || sequence <= (sequences[member] ?: 0) || now < 0) return
        val message = parsePttMapMedia(payload) ?: return
        sequences[member] = sequence
        when (message.kind) {
            2 -> active[member] = Activity(message.channel, message.transmission, now)
            3 -> active[member]?.let {
                if (it.channel == message.channel && it.transmission == message.transmission) active.remove(member)
            }
        }
    }

    fun visible(now: Long, epochMillis: Long): List<PttMapTalker> {
        active.entries.removeAll { now - it.value.receivedAt !in 0 until 1500 }
        positions.entries.removeAll { it.value.expiresAt <= epochMillis }
        return active.mapNotNull { (member, activity) ->
            positions[member]?.let { position ->
                PttMapTalker(member, members.getValue(member), activity.channel, activity.transmission, position)
            }
        }
    }
}

private data class PttMapMedia(val kind: Int, val channel: String, val transmission: String)

/** Current APTM v1 / APTT v1 app envelopes; no Core domain types or audio decoding. */
private fun parsePttMapMedia(payload: ByteArray): PttMapMedia? = runCatching {
    require(payload.size in 43..4117)
    DataInputStream(ByteArrayInputStream(payload)).use { input ->
        fun id() = ByteArray(16).also(input::readFully).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        require(input.readInt() == 0x4150544d && input.readUnsignedByte() == 1)
        val channel = id()
        require(input.readInt() == 0x41505454 && input.readUnsignedByte() == 1)
        val kind = input.readUnsignedByte()
        val transmission = id()
        when (kind) {
            1 -> {
                require(input.readInt() == 48_000 && input.readUnsignedByte() == 1 && input.readInt() == 24_000 &&
                    input.readUnsignedShort() == 960 && input.readUnsignedByte() == 5 && input.readUnsignedShort() in 1..300)
            }
            2 -> {
                val index = input.readUnsignedShort()
                require(index < 300 && input.readLong() == index * 4800L)
                val count = input.readUnsignedByte().also { require(it in 1..5) }
                repeat(count) {
                    val length = input.readUnsignedShort().also { require(it in 1..1275) }
                    require(input.skipBytes(length) == length)
                }
            }
            3 -> { require(input.readUnsignedShort() <= 300); require(input.skipBytes(32) == 32) }
            else -> error("Unknown PTT media kind")
        }
        require(input.available() == 0)
        PttMapMedia(kind, channel, transmission)
    }
}.getOrNull()
