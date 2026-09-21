package dev.arachne.atak

import android.os.Looper
import com.atakmap.comms.CommsProviderFactory
import com.atakmap.comms.SslNetCotPort
import com.atakmap.comms.http.TakHttpClient
import com.atakmap.net.AtakAuthenticationCredentials as Credentials
import com.atakmap.net.AtakAuthenticationDatabase as Passwords
import com.atakmap.net.AtakCertificateDatabase as Certificates
import com.atakmap.net.AtakCertificateDatabaseIFace as CertificateTypes
import org.apache.http.HttpVersion
import org.apache.http.entity.StringEntity
import org.apache.http.impl.DefaultHttpServerConnection
import org.apache.http.message.BasicHttpResponse
import org.apache.http.params.BasicHttpParams
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** Native credential-selection experiment, not an exposed workspace service. */
internal object NativeHttpCheck {
    private val clientType = CertificateTypes.TYPE_CLIENT_CERTIFICATE
    private val caType = CertificateTypes.TYPE_TRUST_STORE_CA
    private val passwordTypes = listOf(Credentials.TYPE_clientPassword, Credentials.TYPE_caPassword)

    private fun recoveryFile() = java.io.File(com.atakmap.android.maps.MapView.getMapView().context.noBackupFilesDir,
        "arachne-http-recovery-check.json")

    @JvmStatic fun prepareRecovery(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        val file = recoveryFile()
        check(!file.exists()) { "Previous recovery check needs cleanup" }
        val endpoint = Endpoint("recovery")
        try {
            check(endpoint.nativeGet() == "recovery")
            val metadata = JSONObject().put("host", endpoint.host).put("port", endpoint.port)
            file.writeText(metadata.toString())
            return metadata.put("passed", true).put("native_request_before_kill", true).toString()
        } catch (error: Exception) { endpoint.close(); throw error }
        // Deliberately leave the real listener alive until the external driver
        // kills ATAK. Recovery must work without Endpoint.close ever executing.
    }

    @JvmStatic fun checkRecovery(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        val file = recoveryFile()
        val metadata = JSONObject(file.readText())
        val host = metadata.getString("host"); val port = metadata.getInt("port")
        val result = JSONObject().put("passed", false)
        try {
            val certs = Certificates.getAdapter()
            check(certs.getCertificateForTypeAndServer(clientType, host, false) == null &&
                certs.getCertificateForTypeAndServer(caType, host, false) == null &&
                certs.getCertificateForTypeAndServerAndPort(clientType, host, port, false) == null &&
                passwordTypes.all { Passwords.getCredentials(it, host) == null }) {
                "Native HTTP credentials survived process death without recovery"
            }
            result.put("passed", true).put("owned_rows_absent_after_process_restart", true)
        } catch (error: Exception) { result.put("error", error.message) }
        finally {
            Certificates.deleteCertificateForServerAndPort(clientType, host, port)
            Certificates.deleteCertificateForServer(clientType, host)
            Certificates.deleteCertificateForServer(caType, host)
            for (type in passwordTypes) Passwords.delete(type, host)
            check(file.delete())
        }
        return result.toString()
    }

    private class Endpoint(val label: String) : AutoCloseable {
        val password = UUID.randomUUID().toString()
        private val random = SecureRandom()
        val host = "127.${64 + random.nextInt(128)}.${random.nextInt(256)}.${1 + random.nextInt(254)}"
        val port: Int
        val connectString: String get() = "$host:$port:ssl"
        val url: String get() = "$base:$port/Marti/api/missions"
        val base = "https://$host"
        val handled = AtomicInteger()
        val rejected = AtomicInteger()
        val tlsRejected = AtomicInteger()
        val failure = AtomicReference<String?>()
        @Volatile private var peer: SSLSocket? = null
        @Volatile private var closed = false
        private val listener: SSLServerSocket
        private val worker: Thread
        private var credentials: NativeHttpCredentials? = null
        val clientKeys: Array<javax.net.ssl.KeyManager>
        val serverCertificate: X509Certificate

        init {
            for (type in listOf(clientType, caType)) {
                check(Certificates.getCertificateForServer(type, host) == null)
            }
            check(passwordTypes.all { Passwords.getCredentials(it, host) == null })
            val provider = CommsProviderFactory.getProvider()
            fun identity() = checkNotNull(provider.generateSelfSignedCert(password))
            fun load(bytes: ByteArray) = KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), password.toCharArray()) }
            fun certificate(keys: KeyStore): X509Certificate {
                val alias = keys.aliases().toList().single { keys.isKeyEntry(it) }
                return (keys.getCertificate(alias) as X509Certificate).also { it.checkValidity() }
            }
            fun trust(cert: X509Certificate) = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("peer", cert) }
            val clientBytes = identity()
            val client = load(clientBytes)
            val clientCertificate = certificate(client)
            clientKeys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(client, password.toCharArray()) }.keyManagers
            val server = load(identity())
            serverCertificate = certificate(server)
            val serverKeys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(server, password.toCharArray()) }
            val clientTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust(clientCertificate)) }
            val tls = SSLContext.getInstance("TLS").apply { init(serverKeys.keyManagers, clientTrust.trustManagers, random) }
            listener = tls.serverSocketFactory.createServerSocket(0, 4, InetAddress.getByName(host)) as SSLServerSocket
            port = listener.localPort
            listener.needClientAuth = true
            listener.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            worker = Thread({
                try {
                    while (!closed) {
                        val socket = listener.accept() as SSLSocket
                        peer = socket
                        socket.use {
                            if (!closed) try {
                                socket.soTimeout = 5000
                                socket.startHandshake()
                                check(socket.session.peerCertificates.first().encoded.contentEquals(clientCertificate.encoded))
                                val connection = DefaultHttpServerConnection()
                                connection.bind(socket, BasicHttpParams().apply {
                                    setIntParameter("http.connection.max-header-count", 32)
                                    setIntParameter("http.connection.max-line-length", 8192)
                                })
                                val request = connection.receiveRequestHeader()
                                check(request.requestLine.method == "GET" && request.requestLine.uri == "/Marti/api/missions")
                                handled.incrementAndGet()
                                val response = BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")
                                response.entity = StringEntity(JSONObject().put("scope", label).toString(), "UTF-8")
                                response.setHeader("Content-Length", response.entity.contentLength.toString())
                                response.setHeader("Content-Type", "application/json")
                                response.setHeader("Connection", "close")
                                connection.sendResponseHeader(response)
                                connection.sendResponseEntity(response)
                                connection.flush()
                            } catch (_: SSLException) { tlsRejected.incrementAndGet(); rejected.incrementAndGet() }
                              catch (_: java.net.SocketTimeoutException) { rejected.incrementAndGet() }
                        }
                        peer = null
                    }
                } catch (error: Exception) { if (!closed) failure.set(error.toString()) }
            }, "arachne-native-https-check-$label")
            try {
                // Exercise the production credential owner. Native host-only
                // reads select the sole exact-port certificate at this host.
                val ca = java.io.ByteArrayOutputStream().use { out -> trust(serverCertificate).store(out, password.toCharArray()); out.toByteArray() }
                credentials = NativeHttpCredentials.open(com.atakmap.android.maps.MapView.getMapView().context,
                    host, port, clientBytes, ca, password)
                worker.start()
            } catch (error: Exception) { close(); throw error }
        }

        fun nativeGet(explicitConnection: Boolean = true): String {
            val client = if (explicitConnection) TakHttpClient.GetHttpClient(base, connectString) else TakHttpClient.GetHttpClient(base)
            return try { JSONObject(client.get(url)).getString("scope") }
            finally { client.shutdown() }
        }

        override fun close() {
            if (closed) return
            closed = true
            listener.close(); peer?.close()
            worker.join(10000)
            check(!worker.isAlive) { "HTTPS fixture did not stop" }
            credentials?.close(); credentials = null
            check(passwordTypes.all { Passwords.getCredentials(it, host) == null })
            check(Certificates.getCertificateForServer(clientType, host) == null)
            check(Certificates.getCertificateForServer(caType, host) == null)
            check(Certificates.getCertificateForServerAndPort(clientType, host, port) == null)
        }
    }

    /** Exercise the production facade, including legacy TLS with the correct key. */
    private fun productionBoundary(): JSONObject {
        val context = com.atakmap.android.maps.MapView.getMapView().context
        val workspace = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val scope = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
        val binding = LocalTakBinding.forWorkspace(context, workspace, "TLS boundary check")
        val identity = LocalTakTls()
        val wrong = LocalTakTls()
        val handled = AtomicInteger()
        val failure = AtomicReference<String?>()
        val server = LocalTakHttp(context, binding, identity, {
            handled.incrementAndGet()
            java.io.File.createTempFile("tls-check-", ".txt", context.cacheDir).apply { writeText("authorized") }
        }, failed = { failure.set("Production HTTPS listener failed") })
        val base = "https://${binding.host}"
        fun url() = LocalTakHttp.url(binding, "/Marti/arachne/resources/check")
        fun nativeGet(): String {
            val client = TakHttpClient.GetHttpClient(base, "${binding.host}:${binding.port}:ssl")
            return try { client.get(url()) } finally { client.shutdown() }
        }
        try {
            val ready = android.os.SystemClock.elapsedRealtime() + 10000
            while (runCatching { nativeGet() }.getOrNull() != "authorized") {
                check(failure.get() == null && android.os.SystemClock.elapsedRealtime() < ready) { "Native production HTTP startup failed: ${failure.get()}" }
                Thread.sleep(50)
            }
            val port = java.net.URI(url()).port
            check(port in 1..65535 && port != SslNetCotPort.getServerApiPort(SslNetCotPort.Type.SECURE))
            val beforeLegacy = handled.get()
            val legacy = LocalTakStreamCheck.rejectLegacyProtocols(binding.host, port)
            check(handled.get() == beforeLegacy && failure.get() == null)
            fun keys(value: LocalTakTls): Array<javax.net.ssl.KeyManager> {
                val store = KeyStore.getInstance("PKCS12").apply { load(value.clientBytes.inputStream(), value.password.toCharArray()) }
                return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, value.password.toCharArray()) }.keyManagers
            }
            val trust = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("server", identity.serverCertificate) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            val rejected = JSONArray()
            for (protocol in listOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")) {
                val identities = if (protocol in listOf("TLSv1", "TLSv1.1")) listOf("correct") else listOf("missing", "wrong_scope")
                for (kind in identities) {
                    val before = handled.get()
                    val tls = SSLContext.getInstance("TLS").apply { init(when (kind) {
                        "correct" -> keys(identity); "wrong_scope" -> keys(wrong); else -> null
                    }, managers.trustManagers, null) }
                    var denied = false
                    (tls.socketFactory.createSocket(binding.host, port) as SSLSocket).use { socket ->
                        socket.soTimeout = 7000; socket.enabledProtocols = arrayOf(protocol)
                        try { socket.startHandshake(); socket.outputStream.write("GET / HTTP/1.0\r\n\r\n".toByteArray()); socket.inputStream.read() }
                        catch (_: SSLException) { denied = true }
                    }
                    check(denied && handled.get() == before && failure.get() == null) { "Production HTTP accepted $protocol/$kind or reached application handling" }
                    rejected.put(JSONObject().put("protocol", protocol).put("identity", kind))
                }
            }
            check(nativeGet() == "authorized" && failure.get() == null)
            return JSONObject().put("passed", true).put("rejected", rejected).put("legacy_wire_rejections", legacy).put("native_before_and_after", true)
                .put("listener_port", port).put("uses_actual_listener_url", true)
        } finally {
            server.close()
            check(runCatching { url() }.isFailure) { "Closed native HTTP URL remained available" }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (Passwords.getCredentials(Credentials.TYPE_clientPassword, binding.host) != null) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Production HTTP credential cleanup timed out" }; Thread.sleep(20)
            }
            check(context.getSharedPreferences("arachne-native-bindings-v1", android.content.Context.MODE_PRIVATE).edit().remove(scope).commit())
        }
    }

    @JvmStatic fun run(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        val result = JSONObject().put("passed", false)
        val provider = CommsProviderFactory.getProvider()
        fun ports() = SslNetCotPort.Type.values().map { SslNetCotPort.getServerApiPort(it) }
        fun defaults() = listOf(clientType, caType).map { Certificates.getCertificate(it)?.toList() } +
            passwordTypes.map { Passwords.getCredentials(it)?.let { c -> listOf(c.username, c.password) } }
        fun sites() = Passwords.getDistinctSitesAndTypes().map { it.type to it.site }.toSet()
        val beforePorts = ports(); val beforeDefaults = defaults(); val beforeSites = sites()
        val endpoints = mutableListOf<Endpoint>()
        try {
            val port = SslNetCotPort.getServerApiPort(SslNetCotPort.Type.SECURE)
            val a = Endpoint("A").also { endpoints.add(it) }
            val b = Endpoint("B").also { endpoints.add(it) }
            val context = com.atakmap.android.maps.MapView.getMapView().context
            check(NativeHttpCredentials.recover(context) == 0)
            check(a.nativeGet() == "A" && b.nativeGet() == "B")
            result.put("active_leases_survive_recovery", true)
            val adapter = Certificates.getAdapter()
            val aClient = checkNotNull(adapter.getCertificateForTypeAndServerAndPort(clientType, a.host, a.port, false))
            val aCa = checkNotNull(adapter.getCertificateForTypeAndServerAndPort(caType, a.host, a.port, false))
            check(runCatching { NativeHttpCredentials.open(context, a.host, a.port, aClient, aCa, a.password) }
                .exceptionOrNull()?.message == "Native HTTP binding already in use")
            check(a.nativeGet() == "A")
            result.put("duplicate_owner_rejected", true)
            val absentPort = if (a.port == 65535) a.port - 1 else a.port + 1
            check(adapter.getCertificateForTypeAndServerAndPort(clientType, a.host, absentPort, false) == null)
            check(Certificates.getCertificateForServerAndPort(clientType, a.host, absentPort).contentEquals(aClient))
            result.put("native_port_lookup_fallback_observed", true)

            val conflict = Endpoint("conflict").also { endpoints.add(it) }
            val foreign = checkNotNull(adapter.getCertificateForTypeAndServerAndPort(clientType, b.host, b.port, false))
            val conflictPort = if (conflict.port == 65535) conflict.port - 1 else conflict.port + 1
            try {
                Certificates.saveCertificateForServerAndPort(clientType, conflict.host, conflictPort, foreign)
                check(runCatching { conflict.close() }.exceptionOrNull()?.message == "Native certificate ownership conflict")
                check(adapter.getCertificateForTypeAndServerAndPort(clientType, conflict.host, conflictPort, false).contentEquals(foreign))
                check(adapter.getCertificateForTypeAndServerAndPort(clientType, conflict.host, conflict.port, false) != null)
                check(passwordTypes.all { Passwords.getCredentials(it, conflict.host) != null })
                check(NativeHttpCredentials.recover(context) == 1)
                check(a.nativeGet() == "A" && b.nativeGet() == "B")
                result.put("other_port_conflict_preserves_all_records_and_other_scopes", true)
            } finally {
                check(adapter.getCertificateForTypeAndServerAndPort(clientType, conflict.host, conflictPort, false).contentEquals(foreign))
                Certificates.deleteCertificateForServerAndPort(clientType, conflict.host, conflictPort)
                check(NativeHttpCredentials.recover(context) == 0)
            }
            check(adapter.getServers(clientType).none { it == conflict.host } &&
                adapter.getServers(caType).none { it == conflict.host } &&
                passwordTypes.all { Passwords.getCredentials(it, conflict.host) == null })
            result.put("cleanup_retries_after_conflict_is_removed", true)

            val partial = Endpoint("partial").also { endpoints.add(it) }
            Certificates.deleteCertificateForServerAndPort(caType, partial.host, partial.port)
            Passwords.delete(Credentials.TYPE_caPassword, partial.host)
            partial.close()
            check(adapter.getServers(clientType).none { it == partial.host } &&
                passwordTypes.all { Passwords.getCredentials(it, partial.host) == null })
            result.put("partial_native_records_cleaned", true)
            check(a.host != b.host)
            result.put("hosts", JSONArray(listOf(a.host, b.host))).put("native_api_port", port)
                .put("listener_ports", JSONArray(listOf(a.port, b.port)))
                .put("native_mesh_enabled", context.getSharedPreferences("com.atakmap.app.civ_preferences", android.content.Context.MODE_PRIVATE).getBoolean("enableNonStreamingConnections", true))
            for (endpoint in listOf(a, b)) {
                check(endpoint.nativeGet() == endpoint.label)
                check(endpoint.nativeGet(false) == endpoint.label)
            }
            result.put("native_explicit_and_host_only_selection", true)
            val mismatches = JSONArray()
            for ((destination, credentials) in listOf(a to b, b to a)) {
                val before = destination.handled.get()
                val beforeTls = destination.tlsRejected.get()
                val client = TakHttpClient.GetHttpClient(destination.base, credentials.connectString)
                var rejected = false
                var clientError = ""
                try { client.get(destination.url) }
                catch (error: java.io.IOException) {
                    clientError = error.javaClass.name
                    rejected = true
                } finally { client.shutdown() }
                val until = android.os.SystemClock.elapsedRealtime() + 1000
                while (destination.tlsRejected.get() == beforeTls && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(10)
                check(rejected && destination.handled.get() == before && destination.tlsRejected.get() > beforeTls) { "Wrong-scope native client was not rejected at the server TLS boundary" }
                mismatches.put(JSONObject().put("destination", destination.label).put("credentials", credentials.label)
                    .put("client_error", clientError).put("server_tls_rejections", destination.tlsRejected.get() - beforeTls))
            }
            result.put("native_cross_scope_rejected", mismatches)
            val negatives = JSONArray()
            for (endpoint in listOf(a, b)) for (protocol in listOf("TLSv1.2", "TLSv1.3")) for (wrongIdentity in listOf(false, true)) {
                val before = endpoint.handled.get()
                val trust = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("server", endpoint.serverCertificate) }
                val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
                val other = if (endpoint === a) b else a
                val tls = SSLContext.getInstance("TLS").apply { init(if (wrongIdentity) other.clientKeys else null, tm.trustManagers, null) }
                var rejected = false
                (tls.socketFactory.createSocket(endpoint.host, endpoint.port) as SSLSocket).use { socket ->
                    socket.soTimeout = 7000; socket.enabledProtocols = arrayOf(protocol)
                    try { socket.startHandshake(); socket.inputStream.read() }
                    catch (_: SSLException) { rejected = true }
                }
                check(rejected && endpoint.handled.get() == before) { "Unauthorized HTTP client was not rejected" }
                negatives.put(JSONObject().put("scope", endpoint.label).put("protocol", protocol).put("client", if (wrongIdentity) "other_scope" else "missing"))
            }
            result.put("rejected_clients", negatives)
            val stalledAddress = java.net.InetAddress.getByName(a.host)
            require(stalledAddress.isLoopbackAddress)
            Socket(stalledAddress, a.port).use { stalled ->
                stalled.soTimeout = 7500
                val start = android.os.SystemClock.elapsedRealtime()
                val heartbeat = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
                check(heartbeat.await(1, java.util.concurrent.TimeUnit.SECONDS)) { "HTTP fixture blocked native UI" }
                result.put("main_response_ms", android.os.SystemClock.elapsedRealtime() - start)
                check(stalled.inputStream.read() == -1)
                result.put("stalled_client_closed_ms", android.os.SystemClock.elapsedRealtime() - start)
            }
            check(a.nativeGet() == "A" && b.nativeGet() == "B")
            val cached = TakHttpClient.GetHttpClient(a.base, a.connectString)
            try {
                check(JSONObject(cached.get(a.url)).getString("scope") == "A")
                a.close()
                var rejected = false
                try { cached.get(a.url) }
                catch (_: java.io.IOException) { rejected = true }
                check(rejected) { "Closed endpoint returned a cached response" }
                result.put("cached_client_rejected_after_close", true)
            } finally { cached.shutdown() }
            check(b.nativeGet() == "B")
            result.put("other_scope_survives_close", true)
            check(endpoints.all { it.failure.get() == null }) { endpoints.map { it.failure.get() }.toString() }
            result.put("production_boundary", productionBoundary())
            result.put("native_requests_by_scope", JSONArray(endpoints.map { it.handled.get() }))
            result.put("passed", true)
        } catch (error: Exception) { result.put("error", error.toString()) }
        finally {
            for (endpoint in endpoints.reversed()) endpoint.close()
            check(ports() == beforePorts && defaults() == beforeDefaults && sites() == beforeSites && CommsProviderFactory.getProvider() === provider)
            result.put("settings_and_provider_preserved", true).put("owned_credentials_removed", true)
        }
        return result.toString()
    }
}
