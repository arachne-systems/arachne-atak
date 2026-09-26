package dev.arachne.atak

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class PttFloorAction(val wireValue: Byte) {
    REQUEST(1), GRANT(2), BUSY(3), RELEASE(4), RELEASED(5);

    companion object {
        fun fromWire(value: Byte) = entries.firstOrNull { it.wireValue == value }
    }
}

internal data class PttFloorMessage(
    val action: PttFloorAction,
    val channel: String = PttFloorWire.DEFAULT_CHANNEL,
    val transmission: String,
    val holderTransmission: String = "",
    val holder: String = "",
    val generation: Long = 0,
    val expiresAtMillis: Long = 0,
)

internal object PttFloorWire {
    const val DEFAULT_CHANNEL = "00000000000000000000000000000000"
    const val LEASE_MILLIS = 40_000L
    private val magic = "APTF".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 38

    fun isControl(bytes: ByteArray) = bytes.size >= magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)

    fun encode(message: PttFloorMessage): ByteArray {
        val bodyBytes = when (message.action) {
            PttFloorAction.REQUEST, PttFloorAction.RELEASE -> 0
            PttFloorAction.GRANT -> 48
            PttFloorAction.BUSY -> 64
            PttFloorAction.RELEASED -> 8
        }
        return ByteBuffer.allocate(HEADER_BYTES + bodyBytes).order(ByteOrder.BIG_ENDIAN).apply {
            put(magic).put(1).put(message.action.wireValue)
            put(message.channel.hex(16)).put(message.transmission.hex(16))
            when (message.action) {
                PttFloorAction.REQUEST, PttFloorAction.RELEASE -> Unit
                PttFloorAction.GRANT -> put(message.holder.hex(32)).putLong(message.generation).putLong(message.expiresAtMillis)
                PttFloorAction.BUSY -> put(message.holderTransmission.hex(16)).put(message.holder.hex(32))
                    .putLong(message.generation).putLong(message.expiresAtMillis)
                PttFloorAction.RELEASED -> putLong(message.generation)
            }
        }.array()
    }

    fun decode(bytes: ByteArray): PttFloorMessage? = runCatching {
        require(bytes.size >= HEADER_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(ByteArray(4).also(buffer::get).contentEquals(magic) && buffer.get() == 1.toByte())
        val action = checkNotNull(PttFloorAction.fromWire(buffer.get()))
        val channel = buffer.hex(16)
        val transmission = buffer.hex(16)
        val expected = HEADER_BYTES + when (action) {
            PttFloorAction.REQUEST, PttFloorAction.RELEASE -> 0
            PttFloorAction.GRANT -> 48
            PttFloorAction.BUSY -> 64
            PttFloorAction.RELEASED -> 8
        }
        require(bytes.size == expected)
        when (action) {
            PttFloorAction.REQUEST, PttFloorAction.RELEASE -> PttFloorMessage(action, channel, transmission)
            PttFloorAction.GRANT -> PttFloorMessage(action, channel, transmission, holder = buffer.hex(32),
                generation = buffer.long, expiresAtMillis = buffer.long)
            PttFloorAction.BUSY -> PttFloorMessage(action, channel, transmission, buffer.hex(16), buffer.hex(32),
                buffer.long, buffer.long)
            PttFloorAction.RELEASED -> PttFloorMessage(action, channel, transmission, generation = buffer.long)
        }
    }.getOrNull()

    private fun String.hex(bytes: Int): ByteArray {
        require(length == bytes * 2 && all { it.digitToIntOrNull(16) != null })
        return ByteArray(bytes) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteBuffer.hex(bytes: Int): String = ByteArray(bytes).also(::get)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class PttFloorLease(
    val channel: String,
    val member: String,
    val transmission: String,
    val generation: Long,
    val expiresAtMillis: Long,
)

internal sealed interface PttFloorDecision {
    data class Granted(val lease: PttFloorLease) : PttFloorDecision
    data class Busy(val lease: PttFloorLease) : PttFloorDecision
    data class Released(val generation: Long) : PttFloorDecision
    data object Ignored : PttFloorDecision
}

/** Serialized by the elected workspace administrator; no reachable coordinator means fail-closed PTT. */
internal class PttFloorAuthority(initialGeneration: Long = 0, initialLeases: Map<String, PttFloorLease> = emptyMap()) {
    var generation: Long = initialGeneration
        private set
    private val leases = initialLeases.toMutableMap()
    val lease: PttFloorLease? get() = lease(PttFloorWire.DEFAULT_CHANNEL)

    @Synchronized
    fun lease(channel: String): PttFloorLease? = leases[channel]

    @Synchronized
    fun leases(): List<PttFloorLease> = leases.values.toList()

    @Synchronized
    fun request(channel: String, member: String, transmission: String, nowMillis: Long): PttFloorDecision {
        val active = leases[channel]?.takeIf { it.expiresAtMillis > nowMillis }
        if (active == null) leases.remove(channel) else leases[channel] = active
        if (active != null) {
            return if (active.channel == channel && active.member == member && active.transmission == transmission) {
                PttFloorDecision.Granted(active)
            } else PttFloorDecision.Busy(active)
        }
        generation = Math.addExact(generation, 1)
        val granted = PttFloorLease(channel, member, transmission, generation, nowMillis + PttFloorWire.LEASE_MILLIS)
        leases[channel] = granted
        return PttFloorDecision.Granted(granted)
    }

    @Synchronized
    fun release(channel: String, member: String, transmission: String): PttFloorDecision {
        val active = leases[channel] ?: return PttFloorDecision.Ignored
        if (active.member != member || active.transmission != transmission) return PttFloorDecision.Ignored
        leases.remove(channel)
        generation = Math.addExact(generation, 1)
        return PttFloorDecision.Released(generation)
    }

    @Synchronized
    fun observeGrant(incoming: PttFloorLease): Boolean {
        if (incoming.generation < generation) return false
        val current = leases[incoming.channel]
        if (incoming.generation == generation && current != null && current != incoming) return false
        generation = incoming.generation
        leases[incoming.channel] = incoming
        return true
    }

    @Synchronized
    fun observeRelease(channel: String, transmission: String, nextGeneration: Long): Boolean {
        if (nextGeneration <= generation) return false
        val current = leases[channel]
        if (current != null && current.transmission != transmission) return false
        generation = nextGeneration
        leases.remove(channel)
        return true
    }
}
