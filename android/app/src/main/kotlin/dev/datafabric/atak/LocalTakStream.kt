package dev.arachne.atak

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.atakmap.comms.CommsMapComponent
import com.atakmap.comms.CotServiceRemote
import com.atakmap.comms.TAKServerListener
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Device-local native byte stream. Workspace routing and CoT framing belong to
 * the caller. The local identity can also serve an optional application listener;
 * that listener owns any native HTTP credential database entries.
 * The authenticated input callback runs on the reader thread, once per
 * connection. Returning ends that connection; close wakes blocked socket IO. */
internal class LocalTakStream(
    private val received: (InputStream, (ByteArray) -> Boolean) -> Unit,
    private val failed: () -> Unit,
    // Separate listener readiness from native attachment so authentication can
    // be exercised before ATAK occupies this single-client local connection.
    private val attachNative: (Int, X509Certificate, () -> Unit) -> Unit = { _, _, attach -> attach() },
    private val binding: (() -> LocalTakBinding)? = null,
    private val applicationService: ((LocalTakBinding, LocalTakTls) -> LocalTakHttp)? = null,
    packageServer: Boolean = applicationService != null
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val writing = Any()
    private var closed = false
    private var listener: SSLServerSocket? = null
    private var socket: SSLSocket? = null
    private var authenticated = false
    private var streamKey: String? = null
    private var registered = false
    private var packageServer = packageServer
    private var register: (() -> Unit)? = null
    private var description: String? = null
    private val memberContacts = mutableMapOf<String, String>()
    private val associateMethod by lazy {
        try { CommsMapComponent::class.java.getMethod("createKnownEndpointForContact",
            String::class.java, String::class.java, String::class.java) } catch (_: NoSuchMethodException) { null }
    }
    private var service: AutoCloseable? = null
    private val worker = Thread({ run() }, "arachne-native-stream").apply { start() }

    private fun run() {
        var application: LocalTakHttp? = null
        try {
            val local = binding?.invoke()
            val host = local?.host ?: "127.0.0.1"
            val identity = LocalTakTls()
            val password = identity.password
            val clientBytes = identity.clientBytes
            val caBytes = identity.caBytes
            val serverCert = identity.serverCertificate
            val clientCert = identity.clientCertificate
            val tls = identity.context
            val bound = tls.serverSocketFactory.createServerSocket() as SSLServerSocket
            try {
                // Resume may reuse a listening address while old TCP sessions
                // finish closing. This does not permit two live listeners.
                if (local != null) LocalTakPorts.bind(host, "stream", local.port, bound)
                else bound.bind(InetSocketAddress(InetAddress.getByName(host), 0), 4)
            } catch (error: Exception) { bound.close(); throw error }
            bound.needClientAuth = true
            bound.enabledProtocols = bound.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            val key = "$host:${bound.localPort}:ssl"
            synchronized(lock) {
                if (closed) { bound.close(); return }
                listener = bound
                streamKey = key
            }
            if (local != null && applicationService != null) {
                val started = applicationService.invoke(local.listeningOn(bound.localPort), identity)
                application = started
                synchronized(lock) { if (closed) started.close() else service = started }
                try { started.awaitReady() }
                catch (error: Exception) {
                    // Package availability must not disable workspace CoT.
                    android.util.Log.w("Arachne", "NATIVE_PACKAGES_UNAVAILABLE_COT_CONTINUES", error)
                    started.close()
                }
            }
            attachNative(bound.localPort, serverCert) { main.post {
                synchronized(lock) {
                    if (!closed) try {
                        val comms = CommsMapComponent.getInstance()
                        check(comms.allPortsBundle.getParcelableArray("streams").orEmpty().none {
                            (it as Bundle).getString("connectString") == key
                        }) { "Local native binding is already registered" }
                        // Reserve ownership before a call that could partially
                        // register and then fail. Never remove a pre-existing key.
                        registered = true
                        register = { comms.addStreaming(key, Bundle().apply {
                            putString("connectString", key)
                            putString("description", description ?: local?.description ?: "Arachne local transport")
                            putBoolean("isStream", application?.nativePackagesReady == true && packageServer); putBoolean("enabled", true)
                            putBoolean("temporary", true); putBoolean("noPersist", true)
                        }, false, CotServiceRemote.Proto.ssl, host, bound.localPort,
                            clientBytes, caBytes, password, password, null, null) }
                        checkNotNull(register).invoke()
                    } catch (_: Exception) { failed(); close() }
                }
            } }
            while (synchronized(lock) { !closed }) {
                val peer = bound.accept() as SSLSocket
                synchronized(lock) {
                    if (closed) { peer.close(); return }
                    socket = peer
                }
                try {
                    peer.soTimeout = 5000
                    peer.startHandshake()
                    check(peer.session.peerCertificates.first().encoded.contentEquals(clientCert.encoded))
                    synchronized(lock) {
                        if (closed) return
                        authenticated = true
                        memberContacts.forEach { (uid, name) -> associateContact(uid, name) }
                    }
                    peer.soTimeout = 0
                    received(peer.inputStream, ::send)
                } catch (_: javax.net.ssl.SSLException) {
                    // An unrelated local client must not terminate the listener.
                } catch (_: java.net.SocketTimeoutException) {
                    // Bound a client that connects without completing TLS.
                } catch (_: java.net.SocketException) {
                    // Native reconnect can reset an established local socket.
                } finally {
                    synchronized(lock) { if (socket === peer) { socket = null; authenticated = false } }
                    peer.close()
                }
            }
        } catch (error: Exception) {
            if (synchronized(lock) { !closed }) {
                android.util.Log.w("Arachne", "LOCAL_NATIVE_STREAM_FAILURE", error)
                failed()
            }
        } finally { close(); application?.awaitClosed() }
    }

    /** Native listeners only process isStream when a port is registered. Use
     * the public remove/add lifecycle, not synthetic connected flags. This
     * reconnects only ATAK's local bridge; the fabric owner and HTTPS stay up. */
    fun packageServer(enabled: Boolean) {
        main.post {
            synchronized(lock) {
                if (closed || packageServer == enabled) return@post
                packageServer = enabled
                if (registered) try {
                    CommsMapComponent.getInstance().removeStreaming(checkNotNull(streamKey))
                    checkNotNull(register).invoke()
                } catch (_: Exception) { failed(); close() }
            }
        }
    }

    /** Refresh native picker labels without reconnecting or replacing the owned stream. */
    fun description(value: String) {
        synchronized(lock) {
            if (closed) return
            description = value
        }
        main.post {
            synchronized(lock) {
                if (closed || !registered) return@post
                val key = checkNotNull(streamKey)
                // ATAK exposes the live port Bundle; its server picker keeps a
                // separate TAKServer record. Only presentation changes on both.
                CommsMapComponent.getInstance().allPortsBundle.getParcelableArray("streams").orEmpty()
                    .map { it as Bundle }.singleOrNull { it.getString("connectString") == key }
                    ?.putString("description", description)
                TAKServerListener.getInstance()?.findServer(key)?.data?.putString("description", description)
            }
        }
    }

    /** Caller is off the UI thread. A successful write is local socket delivery,
     * not native application acceptance or a remote fabric delivery receipt. */
    fun send(bytes: ByteArray): Boolean {
        require(bytes.size in 1..16384)
        val peer = synchronized(lock) { if (closed || !authenticated) null else socket } ?: return false
        return try { synchronized(writing) { peer.outputStream.write(bytes); peer.outputStream.flush() }; true }
        catch (_: java.io.IOException) { false }
    }

    /** Give native replies a return stream even when the authenticated sender
     * does not share PLI. ATAK 5.6 lacks this public API and retains PLI discovery. */
    fun associateContact(uid: String, callsign: String) {
        val method = associateMethod ?: return
        synchronized(lock) {
            check(!closed && registered && authenticated) { "Native contact stream is unavailable" }
            method.invoke(CommsMapComponent.getInstance(), uid, callsign, checkNotNull(streamKey))
        }
    }

    /** Keep accepted members ready for native sends, including TLS reconnect.
     * 5.6 has no public association API and keeps its existing PLI discovery. */
    fun contacts(next: Map<String, String>): Boolean = synchronized(lock) {
        if (closed || associateMethod == null) return false
        for ((uid, name) in next) {
            if (memberContacts[uid] == name) continue
            if (registered && authenticated) associateContact(uid, name)
            memberContacts[uid] = name
        }
        memberContacts.keys.retainAll(next.keys)
        true
    }

    override fun close() {
        val key = synchronized(lock) {
            if (closed) return
            closed = true
            authenticated = false
            memberContacts.clear()
            runCatching { socket?.close() }; socket = null
            runCatching { listener?.close() }; listener = null
            runCatching { service?.close() }; service = null
            register = null
            streamKey.takeIf { registered }.also { streamKey = null; registered = false }
        }
        if (key != null) main.post { CommsMapComponent.getInstance().removeStreaming(key) }
    }

    fun awaitClosed(milliseconds: Long): Boolean {
        check(Thread.currentThread() !== worker)
        worker.join(milliseconds)
        return !worker.isAlive
    }
}
