package dev.arachne.atak

import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Device-local listener choices, not workspace or fabric configuration.
 * Zero asks the OS for a free port. Saving affects the next bridge lifetime. */
internal object LocalTakPorts {
    data class Choices(val stream: Int = 0, val https: Int = 0) {
        init {
            require(listOf(stream, https).all { it == 0 || it in 1024..65535 }) { "Use Automatic or a port from 1024 to 65535." }
            require(stream == 0 || https == 0 || stream != https) { "Stream and HTTPS need different ports." }
        }
    }
    private const val PREFERENCES = "arachne-listener-ports-v1"
    private data class Listening(val host: String, val kind: String, val socket: ServerSocket)
    private val listeners = mutableListOf<Listening>()
    private val errors = mutableMapOf<Pair<String, String>, String>()
    private var started: Choices? = null

    @Synchronized fun start(context: Context) { started = choices(context) }
    @Synchronized fun activeChoices(context: Context): Choices = started ?: choices(context).also { started = it }

    fun choices(context: Context): Choices = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).let {
        Choices(it.getInt("stream", 0), it.getInt("https", 0))
    }

    /** Port probes are bound to the exact workspace loopback, never a wildcard. */
    fun reserve(host: String, port: Int): ServerSocket {
        require(LocalTakBinding.validHost(host))
        val address = InetAddress.getByName(host)
        require(address.isLoopbackAddress)
        return ServerSocket(port, 4, address)
    }

    /** Bind, rather than merely inspecting a port list. The returned socket is
     * the reservation and remains open for the actual listener's lifetime. */
    @Synchronized fun bind(host: String, kind: String, port: Int, socket: ServerSocket) {
        require(LocalTakBinding.validHost(host) && kind in setOf("stream", "https"))
        val address = InetAddress.getByName(host)
        require(address.isLoopbackAddress)
        listeners.removeAll { it.socket.isClosed }
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port), 4)
            listeners.add(Listening(host, kind, socket))
            errors.remove(host to kind)
        } catch (error: Exception) {
            socket.close()
            errors[host to kind] = "$kind port $port unavailable on $host"
            throw java.io.IOException("Arachne $kind port $port unavailable; check Settings → ATAK connection ports.", error)
        }
    }

    @Synchronized fun status(): List<String> {
        listeners.removeAll { it.socket.isClosed }
        return listeners.map { "${it.kind}: ${it.host}:${it.socket.localPort}" } + errors.values
    }

    /** This is a preflight for the next start, not a promise that another app
     * cannot claim a port later. Startup always binds and checks again. */
    @Synchronized fun save(context: Context, next: Choices) {
        val reserved = mutableListOf<ServerSocket>()
        try {
            listeners.removeAll { it.socket.isClosed }
            val hosts = context.getSharedPreferences("arachne-native-bindings-v1", Context.MODE_PRIVATE)
                .all.values.map { require(it is String && LocalTakBinding.validHost(it)); it as String }
                .toSet().ifEmpty { setOf("127.64.0.1") }
            for (host in hosts) for ((kind, port) in listOf("stream" to next.stream, "https" to next.https)) {
                if (port == 0) continue
                val own = listeners.singleOrNull { it.host == host && it.socket.localPort == port }
                require(own == null || own.kind == kind) { "Port $port is in use by Arachne ${own?.kind}." }
                if (own != null) continue
                try { reserved.add(reserve(host, port)) }
                catch (error: java.io.IOException) { throw IllegalArgumentException("Port $port is unavailable on $host. Choose another port or Automatic.", error) }
            }
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val before = choices(context)
            if (!preferences.edit().putInt("stream", next.stream).putInt("https", next.https).commit()) {
                // SharedPreferences can update memory even when disk commit fails.
                preferences.edit().putInt("stream", before.stream).putInt("https", before.https).commit()
                error("Port settings could not be saved.")
            }
        } finally { reserved.forEach { runCatching { it.close() } } }
    }
}
