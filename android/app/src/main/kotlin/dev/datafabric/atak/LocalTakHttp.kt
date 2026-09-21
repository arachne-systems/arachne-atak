package dev.arachne.atak

import android.content.Context
import org.apache.http.HttpVersion
import org.apache.http.entity.FileEntity
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.impl.DefaultHttpServerConnection
import org.apache.http.message.BasicHttpResponse
import org.apache.http.params.BasicHttpParams
import org.apache.http.HttpEntityEnclosingRequest
import java.io.File
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Local authenticated GET facade. Fetch runs on this HTTP worker, never on
 * the workspace receive worker. A returned file is an owned temporary copy;
 * this facade deletes it after sending. Null means missing resource. */
internal class LocalTakHttp(
    private val context: Context, private val binding: LocalTakBinding,
    private val identity: LocalTakTls, private val fetch: (String) -> File?,
    private val api: ((String, String, java.io.InputStream?, String?) -> Response?)? = null,
    private val failed: () -> Unit
) : AutoCloseable {
    internal data class Response(val status: Int, val contentType: String, val body: ByteArray? = null, val file: File? = null)
    private val lock = Any()
    private var closed = false
    private var listener: SSLServerSocket? = null
    private var peer: SSLSocket? = null
    private val ready = CompletableFuture<Unit>()
    val connection = "${binding.host}:${binding.port}:ssl"
    val host get() = binding.host
    val port get() = synchronized(lock) { checkNotNull(listener).localPort }
    @Volatile var nativePackagesReady = false
        private set
    private val worker = Thread({ run() }, "arachne-native-http").apply { start() }

    private fun run() {
        var credentials: NativeHttpCredentials? = null
        var routing: AutoCloseable? = null
        try {
            val server = identity.context.serverSocketFactory.createServerSocket() as SSLServerSocket
            try {
                LocalTakPorts.bind(binding.host, "https", binding.httpPort, server)
                server.needClientAuth = true
                server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            } catch (error: Exception) { server.close(); throw error }
            synchronized(lock) {
                if (closed) { server.close(); return }
                listener = server
                check(listeners.putIfAbsent(binding.host, this) == null) { "Native HTTP binding already registered" }
            }
            credentials = NativeHttpCredentials.open(context, binding.host, server.localPort,
                identity.clientBytes, identity.caBytes, identity.password)
            try {
                routing = NativePackageRouting.open(this)
                nativePackagesReady = true
            } catch (error: LinkageError) {
                android.util.Log.w("Arachne", "NATIVE_PACKAGE_ROUTING_UNSUPPORTED", error)
            } catch (error: Exception) {
                android.util.Log.w("Arachne", "NATIVE_PACKAGE_ROUTING_UNAVAILABLE", error)
            }
            synchronized(lock) {
                if (closed) return
                ready.complete(Unit)
            }
            android.util.Log.i("Arachne", "NATIVE_HTTP_READY host=${binding.host} port=${server.localPort}")
            // ponytail: one bounded request at a time per local workspace;
            // use a bounded pool if measured native download concurrency needs it.
            while (synchronized(lock) { !closed }) {
                val socket = server.accept() as SSLSocket
                synchronized(lock) { if (closed) { socket.close(); return }; peer = socket }
                try {
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    check(socket.session.peerCertificates.first().encoded.contentEquals(identity.clientCertificate.encoded))
                    serveHttp(socket, server.localPort)
                } catch (_: java.io.IOException) {
                    // A rejected/stalled local client or native cancellation
                    // must not terminate the listener or the CoT connection.
                } finally {
                    synchronized(lock) { if (peer === socket) peer = null }
                    socket.close()
                }
            }
        } catch (error: Exception) {
            ready.completeExceptionally(error)
            if (synchronized(lock) { !closed }) { android.util.Log.w("Arachne", "NATIVE_HTTP_FAILED", error); failed() }
        } finally {
            close()
            try { routing?.close() }
            finally {
                try { credentials?.close() }
                catch (error: Exception) { android.util.Log.w("Arachne", "NATIVE_HTTP_CREDENTIAL_CLEANUP_FAILED", error) }
            }
        }
    }

    /** The stream owner advertises a TAK Server only after HTTPS is usable. */
    fun awaitReady() { ready.get() }

    fun url(path: String): String = url(binding, path)

    fun awaitClosed() {
        check(Thread.currentThread() !== worker)
        worker.join()
    }

    private fun serveHttp(socket: Socket, localPort: Int) {
        val connection = DefaultHttpServerConnection()
        connection.bind(socket, BasicHttpParams().apply {
            setIntParameter("http.connection.max-header-count", 32)
            setIntParameter("http.connection.max-line-length", 8192)
        })
        val request = connection.receiveRequestHeader()
        if (request is HttpEntityEnclosingRequest) connection.receiveRequestEntity(request)
        var file: File? = null
        var body: ByteArray? = null
        var contentType = "application/octet-stream"
        val result = try {
            val requestBody = if (request is HttpEntityEnclosingRequest) request.entity?.content else null
            api?.invoke(request.requestLine.method, request.requestLine.uri, requestBody,
                request.getFirstHeader("Content-Type")?.value)
                ?: if (request.requestLine.method != "GET") Response(405, contentType)
                else if (request.containsHeader("Transfer-Encoding") || request.getHeaders("Content-Length").any { it.value != "0" }) Response(400, contentType)
                else { file = fetch(request.requestLine.uri); Response(if (file == null) 404 else 200, contentType, file = file) }
        } catch (_: TimeoutException) { Response(504, contentType) }
        catch (_: java.util.concurrent.CancellationException) { Response(503, contentType) }
        catch (_: IllegalArgumentException) { Response(400, contentType) }
        catch (error: Exception) { android.util.Log.w("Arachne", "NATIVE_RESOURCE_FETCH_FAILED", error); Response(502, contentType) }
        body = result.body; file = result.file; contentType = result.contentType
        if (body != null && contentType == "text/plain" && String(body!!, Charsets.UTF_8).startsWith("/Marti/"))
            body = "https://${binding.host}:$localPort${String(body!!, Charsets.UTF_8)}".toByteArray(Charsets.UTF_8)
        try {
            val response = BasicHttpResponse(HttpVersion.HTTP_1_1, result.status, if (result.status in 200..299) "OK" else "Unavailable")
            response.setHeader("Connection", "close")
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Content-Type", contentType)
            response.setHeader("Content-Length", (body?.size?.toLong() ?: file?.length() ?: 0).toString())
            if (file != null) response.entity = FileEntity(file, contentType)
            else if (body != null) response.entity = ByteArrayEntity(body)
            connection.sendResponseHeader(response)
            if (file != null || body != null) connection.sendResponseEntity(response)
            connection.flush()
        } finally { file?.delete() }
    }

    override fun close() = synchronized(lock) {
        closed = true
        nativePackagesReady = false
        ready.completeExceptionally(java.util.concurrent.CancellationException("Native file service closed"))
        listeners.remove(binding.host, this)
        runCatching { listener?.close() }; listener = null
        runCatching { peer?.close() }; peer = null
    }

    companion object {
        private val listeners = ConcurrentHashMap<String, LocalTakHttp>()

        /** Local URLs last only for this listener lifetime; resolve again after
         * pause/restart. Never publish these device-local addresses to peers. */
        fun url(binding: LocalTakBinding, path: String): String {
            require(path.startsWith('/'))
            val service = checkNotNull(listeners[binding.host]) { "Native file service is not ready" }
            return synchronized(service.lock) {
                check(!service.closed)
                URI("https", null, binding.host, checkNotNull(service.listener).localPort,
                    path, null, null).toASCIIString()
            }
        }
    }
}
