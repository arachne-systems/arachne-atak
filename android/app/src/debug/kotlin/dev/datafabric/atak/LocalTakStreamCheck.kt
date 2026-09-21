package dev.arachne.atak

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.atakmap.comms.CommsProviderFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** Debug source set only: real Android TLS listener and then real ATAK attachment. */
internal object LocalTakStreamCheck {
    /** Raw legacy ClientHello: Android's TLS provider filters these protocols before IO. */
    fun rejectLegacyProtocols(host: String, port: Int): JSONArray {
        val address = java.net.InetAddress.getByName(host)
        require(address.isLoopbackAddress)
        val rejected = JSONArray()
        for (minor in listOf(1, 2)) {
            // RFC 5246 ClientHello layout: version, random, empty session,
            // RSA/ECDHE_RSA cipher list, null compression. No extensions required.
            val hello = byteArrayOf(3, minor.toByte()) + ByteArray(32) +
                byteArrayOf(0, 0, 4, 0, 0x2f, 0xc0.toByte(), 0x2f, 1, 0)
            val handshake = byteArrayOf(1, 0, 0, hello.size.toByte()) + hello
            Socket(address, port).use { socket ->
                socket.soTimeout = 7000
                socket.outputStream.write(byteArrayOf(22, 3, 1, 0, handshake.size.toByte()) + handshake)
                val alert = ByteArray(7)
                java.io.DataInputStream(socket.inputStream).readFully(alert)
                check(alert[0].toInt() == 21 && alert[3].toInt() == 0 && alert[4].toInt() == 2 &&
                    alert[5].toInt() == 2 && alert[6].toInt() == 70) { "Listener did not send fatal protocol_version for TLS 1.$minor" }
                rejected.put(JSONObject().put("client_hello_version", if (minor == 1) "TLSv1" else "TLSv1.1")
                    .put("server_alert", "fatal protocol_version (70)"))
            }
        }
        return rejected
    }

    /** Real saved TAK settings surrounding a stream lifetime, never global resets. */
    @JvmStatic fun runSettings(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        fun <T> ui(block: () -> T): T {
            val task = java.util.concurrent.FutureTask<T>(block)
            Handler(Looper.getMainLooper()).post(task)
            return task.get(10, TimeUnit.SECONDS)
        }
        fun hash(bytes: ByteArray?) = bytes?.let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it).toList()
        }
        val context = com.atakmap.android.maps.MapView.getMapView().context
        val comms = com.atakmap.comms.CommsMapComponent.getInstance()
        fun streams() = ui { comms.allPortsBundle.getParcelableArray("streams")!!.map {
            (it as android.os.Bundle).getString("connectString")!!
        }.toSet() }
        fun configs() = java.io.File(context.filesDir, "cotservice").walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(context.filesDir).path to hash(it.readBytes()) }
        val type = com.atakmap.net.AtakCertificateDatabaseIFace.TYPE_CLIENT_CERTIFICATE
        val caType = com.atakmap.net.AtakCertificateDatabaseIFace.TYPE_TRUST_STORE_CA
        val host = "arachne-check-${java.util.UUID.randomUUID()}.invalid"
        val certificate = checkNotNull(CommsProviderFactory.getProvider().generateSelfSignedCert(java.util.UUID.randomUUID().toString()))
        val result = JSONObject().put("passed", false)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { reserved ->
            val key = "127.0.0.1:${reserved.localPort}:tcp"
            check(key !in streams())
            check(com.atakmap.net.AtakCertificateDatabase.getCertificateForServerAndPort(type, host, reserved.localPort) == null)
            var peer: Socket? = null
            var reader: Thread? = null
            val events = LinkedBlockingQueue<String>(32)
            val readerStopped = AtomicBoolean()
            val readerFailure = java.util.concurrent.atomic.AtomicReference<String?>()
            try {
                com.atakmap.net.AtakCertificateDatabase.saveCertificateForServerAndPort(type, host, reserved.localPort, certificate)
                ui { comms.cotService.addStreaming(key, android.os.Bundle().apply {
                    putString("description", "Unrelated settings check")
                    putBoolean("enabled", true)
                }) }
                reserved.soTimeout = 10000
                val connected = reserved.accept().also { peer = it }
                reader = Thread({
                    try { NativeCotFrames.read(connected.inputStream) { bytes ->
                        val event = NativeCotProjection.parse(bytes)
                        if (event.documentElement.getAttribute("type") == "t-x-c-t") {
                            event.documentElement.setAttribute("type", "t-x-c-t-r")
                            connected.outputStream.write(NativeCotProjection.encode(event))
                        } else events.offer(event.documentElement.getAttribute("uid"))
                    } } catch (error: Exception) {
                        if (!connected.isClosed) readerFailure.set(error.toString())
                    } finally { readerStopped.set(true) }
                }, "arachne-unrelated-native-check").apply { start() }
                fun probe() {
                    val uid = "settings-check-${java.util.UUID.randomUUID()}"
                    ui {
                        val now = com.atakmap.coremap.maps.time.CoordinatedTime()
                        val event = com.atakmap.coremap.cot.event.CotEvent().apply {
                            this.uid = uid; this.type = "a-f-G"; version = "2.0"; how = "h-g-i-g-o"
                            time = now; start = now; stale = now.addMinutes(1)
                            setPoint(com.atakmap.coremap.cot.event.CotPoint.ZERO)
                        }
                        check(event.isValid)
                        com.atakmap.android.cot.CotMapComponent.getExternalDispatcher().dispatch(event)
                    }
                    val deadline = SystemClock.elapsedRealtime()+10000
                    while (events.poll(1, TimeUnit.SECONDS) != uid)
                        check(SystemClock.elapsedRealtime() < deadline && !readerStopped.get()) { "Unrelated native connection stopped carrying CoT" }
                }
                probe()
                val beforeStreams = streams()
                check(key in beforeStreams)
                val beforeConfigs = configs()
                check(beforeConfigs.isNotEmpty())
                val defaults = listOf(type, caType).associateWith { hash(com.atakmap.net.AtakCertificateDatabase.getCertificate(it)) }
                val authentication = JSONObject(run())
                result.put("authentication", authentication)
                check(authentication.getBoolean("passed")) { "Local stream did not complete" }
                probe() // Same native TCP socket still carries data after Arachne closes.
                check(streams() == beforeStreams) { "Unrelated stream changed or local stream remained" }
                check(configs() == beforeConfigs) { "Persisted TAK connection settings changed" }
                check(com.atakmap.net.AtakCertificateDatabase.getCertificateForServerAndPort(type, host, reserved.localPort).contentEquals(certificate)) { "Unrelated certificate changed" }
                check(defaults.all { (kind, value) -> hash(com.atakmap.net.AtakCertificateDatabase.getCertificate(kind)) == value }) { "Default certificate changed" }
                check(!readerStopped.get() && readerFailure.get() == null) { "Unrelated native reader failed: ${readerFailure.get()}" }
                val preferenceName = "arachne-binding-io-check-${java.util.UUID.randomUUID()}"
                val storage = context.getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
                val commits = AtomicInteger()
                // Fault injection at the Android preference boundary: the first
                // commit mutates the real cache but reports failure to the caller.
                val faulty = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
                    if (method.name != "edit") method.invoke(storage, *(args ?: emptyArray()))
                    else {
                        val editor = storage.edit()
                        java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader,
                            arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, operation, values ->
                            val value = operation.invoke(editor, *(values ?: emptyArray()))
                            when {
                                operation.name == "commit" && commits.incrementAndGet() == 1 -> false
                                value is android.content.SharedPreferences.Editor -> proxy
                                else -> value
                            }
                        }
                    }
                } as android.content.SharedPreferences
                val faultContext = object : android.content.ContextWrapper(context) {
                    override fun getSharedPreferences(name: String, mode: Int) = faulty
                }
                try {
                    val scope = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    val attempt = runCatching { LocalTakBinding.forWorkspace(faultContext, scope, "Storage check") }
                    check(attempt.exceptionOrNull()?.message == "Could not save native binding" && commits.get() == 1)
                    check(storage.all.isEmpty()) { "Failed binding commit remained in memory" }
                    val retry = LocalTakBinding.forWorkspace(faultContext, scope, "Storage check")
                    check(commits.get() == 2 && storage.all.values.single() == retry.host)
                    result.put("failed_binding_commit_rolled_back_before_retry", true)
                } finally {
                    check(storage.edit().clear().commit())
                    check(context.deleteSharedPreferences(preferenceName))
                }
                val workspace = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                val bindingPreferences = context.getSharedPreferences("arachne-native-bindings-v1", android.content.Context.MODE_PRIVATE)
                val beforeBindings = bindingPreferences.all
                val binding = LocalTakBinding.forWorkspace(context, workspace, "Binding conflict check")
                val bindingKey = "${binding.host}:${binding.port}:ssl"
                val bindingRecord = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
                val rejected = CountDownLatch(1)
                var conflicting: LocalTakStream? = null
                var reservedKey = false
                try {
                    check(bindingKey !in streams())
                    ui {
                        reservedKey = true
                        comms.addStreaming(bindingKey, android.os.Bundle().apply {
                            putString("connectString", bindingKey); putString("description", "Existing native binding check")
                            putBoolean("enabled", false); putBoolean("isStream", true)
                            putBoolean("temporary", true); putBoolean("noPersist", true)
                        }, false, com.atakmap.comms.CotServiceRemote.Proto.ssl, binding.host, binding.port,
                            null, null, null, null, null, null)
                    }
                    val reservedStreams = streams()
                    conflicting = LocalTakStream({ _, _ -> error("Conflicting stream received data") },
                        { rejected.countDown() }, binding = { binding })
                    check(rejected.await(15, TimeUnit.SECONDS)) { "Existing native key was overwritten" }
                    check(conflicting.awaitClosed(10000))
                    ui { Unit } // Drain any queued removal before inspecting ownership.
                    check(streams() == reservedStreams) { "Failed attachment removed the existing native stream" }
                    val retained = ui { comms.allPortsBundle.getParcelableArray("streams")!!.map { it as android.os.Bundle }
                        .single { it.getString("connectString") == bindingKey } }
                    check(retained.getString("description") == "Existing native binding check" && !retained.getBoolean("enabled"))
                    probe()
                    result.put("existing_native_binding_preserved_on_conflict", true)
                } finally {
                    conflicting?.close(); conflicting?.awaitClosed(10000)
                    if (reservedKey) ui { comms.removeStreaming(bindingKey) }
                    check(bindingPreferences.getString(bindingRecord, null) == binding.host)
                    check(bindingPreferences.edit().remove(bindingRecord).commit())
                    check(bindingPreferences.all == beforeBindings && streams() == beforeStreams)
                }
                result.put("saved_active_stream_preserved", true).put("same_native_socket_carried_cot_before_after", true).put("connection_files_preserved", true)
                    .put("unrelated_certificate_preserved", true).put("default_certificates_preserved", true)
                    .put("local_stream_removed", true).put("passed", true)
            } catch (error: Exception) {
                result.put("error", error.toString())
            } finally {
                ui { comms.cotService.removeStreaming(key, false) }
                peer?.close()
                reader?.join(10000)
                check(reader?.isAlive != true) { "Unrelated connection check reader did not close" }
                com.atakmap.net.AtakCertificateDatabase.deleteCertificateForServerAndPort(type, host, reserved.localPort)
                check(key !in streams())
                check(com.atakmap.net.AtakCertificateDatabase.getCertificateForServerAndPort(type, host, reserved.localPort) == null)
                result.put("owned_settings_removed", true)
            }
        }
        return result.toString()
    }

    @JvmStatic fun run(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        val result = JSONObject().put("passed", false)
        val ready = LinkedBlockingQueue<Triple<Int, X509Certificate, () -> Unit>>(1)
        val callbacks = AtomicInteger()
        val failed = AtomicBoolean()
        val nativeTypes = LinkedBlockingQueue<String>(16)
        val stream = LocalTakStream({ input, reply ->
            callbacks.incrementAndGet()
            NativeCotFrames.read(input) { bytes ->
                val event = NativeCotProjection.parse(bytes)
                val type = event.documentElement.getAttribute("type")
                nativeTypes.offer(type)
                if (type == "t-x-c-t") {
                    event.documentElement.setAttribute("type", "t-x-c-t-r")
                    check(reply(NativeCotProjection.encode(event)))
                }
            }
        }, { failed.set(true) }, { port, certificate, attach ->
            check(ready.offer(Triple(port, certificate, attach)))
        })
        try {
            val (port, certificate, attach) = checkNotNull(ready.poll(15, TimeUnit.SECONDS)) { "Listener did not become ready" }
            result.put("port", port)
            result.put("legacy_wire_rejections", rejectLegacyProtocols("127.0.0.1", port))
            check(callbacks.get() == 0 && !failed.get())
            val trust = KeyStore.getInstance("PKCS12").apply {
                load(null, null); setCertificateEntry("expected-server", certificate)
            }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            fun client(withUnrelatedIdentity: Boolean, protocol: String): SSLSocket {
                val keys = if (withUnrelatedIdentity) {
                    val password = java.util.UUID.randomUUID().toString()
                    val bytes = checkNotNull(CommsProviderFactory.getProvider().generateSelfSignedCert(password))
                    val store = KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), password.toCharArray()) }
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password.toCharArray()) }.keyManagers
                } else null
                val tls = SSLContext.getInstance("TLS").apply { init(keys, managers.trustManagers, null) }
                return (tls.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).apply {
                    soTimeout = 7000; enabledProtocols = arrayOf(protocol)
                }
            }
            val rejected = JSONArray()
            for (protocol in listOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")) for (unrelated in listOf(false, true)) {
                client(unrelated, protocol).use { peer ->
                    var rejection: SSLException? = null
                    try { peer.startHandshake(); peer.inputStream.read() }
                    catch (error: SSLException) { rejection = error }
                    checkNotNull(rejection) { "Unauthorized client did not receive a TLS rejection" }
                    check(callbacks.get() == 0 && nativeTypes.isEmpty() && !failed.get())
                    check(!stream.send("<event/>".toByteArray())) { "Unauthenticated outgoing data was accepted" }
                    rejected.put(JSONObject().put("protocol", protocol).put("client", if (unrelated) "unrelated_identity" else "missing_identity")
                        .put("exception", rejection.javaClass.simpleName).put("reason", rejection.message))
                }
            }
            result.put("rejected", rejected)
            Socket("127.0.0.1", port).use { peer ->
                peer.soTimeout = 7500
                check(!stream.send("<event/>".toByteArray()))
                val mainAlive = CountDownLatch(1)
                val start = SystemClock.elapsedRealtime()
                Handler(Looper.getMainLooper()).post { mainAlive.countDown() }
                check(mainAlive.await(1, TimeUnit.SECONDS)) { "Native UI thread did not respond" }
                result.put("main_response_ms", SystemClock.elapsedRealtime()-start)
                check(peer.inputStream.read() == -1) { "Unhandshaken client received application bytes" }
                result.put("stalled_client_closed_ms", SystemClock.elapsedRealtime()-start)
                check(callbacks.get() == 0 && nativeTypes.isEmpty() && !failed.get())
            }
            result.put("unauthorized_callbacks", callbacks.get())
            attach()
            val connectedBy = SystemClock.elapsedRealtime() + 15000
            while (callbacks.get() == 0) {
                check(SystemClock.elapsedRealtime() < connectedBy && !failed.get()) { "Native TLS did not attach" }
                Thread.sleep(10)
            }
            // A stationary host without a location fix may not emit self PLI.
            // Exercise an actual native CoT send on this exact stream instead.
            val sent = java.util.concurrent.FutureTask<Unit> {
                val now = com.atakmap.coremap.maps.time.CoordinatedTime()
                val event = com.atakmap.coremap.cot.event.CotEvent().apply {
                    uid = "local-stream-check"; type = "a-f-G"; version = "2.0"; how = "h-g-i-g-o"
                    time = now; start = now; stale = now.addMinutes(1)
                    setPoint(com.atakmap.coremap.cot.event.CotPoint.ZERO)
                }
                com.atakmap.comms.CommsMapComponent.getInstance().sendCoTToServersOnly("127.0.0.1:$port:ssl", event)
            }
            Handler(Looper.getMainLooper()).post(sent)
            sent.get(10, TimeUnit.SECONDS)
            val seen = mutableListOf<String>()
            val deadline = SystemClock.elapsedRealtime()+30000
            while (seen.none { it.startsWith("a-") } || "t-x-c-t" !in seen) {
                check(SystemClock.elapsedRealtime() < deadline && !failed.get()) { "Native ATAK did not connect after rejected clients" }
                nativeTypes.poll(1, TimeUnit.SECONDS)?.let { seen.add(it) }
            }
            check(callbacks.get() == 1)
            result.put("native_types_after_rejections", JSONArray(seen))
            fun <T> ui(block: () -> T): T {
                val task = java.util.concurrent.FutureTask<T>(block)
                Handler(Looper.getMainLooper()).post(task)
                return task.get(10, TimeUnit.SECONDS)
            }
            val key = "127.0.0.1:$port:ssl"
            fun nativePort() = com.atakmap.comms.CommsMapComponent.getInstance().allPortsBundle
                .getParcelableArray("streams")!!.map { it as android.os.Bundle }
                .single { it.getString("connectString") == key }
            val originalPort = ui { nativePort() }
            ui { check(com.atakmap.comms.TAKServerListener.getInstance().findServer(key) == null) {
                "Local CoT binding was offered as a TAK Server"
            } }
            stream.description("Arachne: Renamed field operation")
            ui {
                check(nativePort() === originalPort) { "Rename replaced the native stream" }
                check(originalPort.getString("description") == "Arachne: Renamed field operation")
                val destination = com.atakmap.comms.TAKServerListener.getInstance().findServer(key)
                check(destination == null) { "Rename exposed a local binding as a TAK Server" }
            }
            check(callbacks.get() == 1 && !failed.get()) { "Rename interrupted the authenticated stream" }
            val now = com.atakmap.coremap.maps.time.CoordinatedTime()
            val heartbeat = com.atakmap.coremap.cot.event.CotEvent().apply {
                uid = "rename-check"; type = "t-x-c-t-r"; version = "2.0"; how = "m-g"
                time = now; start = now; stale = now.addMinutes(1)
                setPoint(com.atakmap.coremap.cot.event.CotPoint.ZERO)
            }
            check(stream.send(heartbeat.toString().toByteArray())) {
                "Existing native socket stopped carrying data after rename"
            }
            result.put("rename_preserved_stream_and_destination", true)
            result.put("local_stream_excluded_from_server_destinations", true)
            result.put("native_connections", callbacks.get()).put("passed", true)
        } catch (error: Exception) {
            result.put("error", error.toString())
        } finally {
            stream.close()
            val stopped = stream.awaitClosed(10000)
            result.put("reader_stopped", stopped).put("send_after_close", stream.send("<event/>".toByteArray()))
            if (!stopped || failed.get()) result.put("passed", false)
        }
        return result.toString()
    }
}
