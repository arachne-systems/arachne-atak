package dev.arachne.atak

import android.os.Handler
import android.os.Looper
import com.atakmap.android.http.rest.operation.GetFileOperation
import com.atakmap.android.http.rest.operation.NetworkOperation
import com.atakmap.android.http.rest.request.GetFileRequest
import com.atakmap.android.maps.MapView
import com.atakmap.comms.CommsMapComponent
import com.atakmap.comms.CommsProvider
import com.atakmap.comms.CommsProviderFactory
import com.atakmap.comms.TAKServerListener
import com.atakmap.comms.http.TakHttpClient
import com.atakmap.comms.missionpackage.MPSendListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Native API feasibility only. Harmless local fixtures, no fabric/service claim. */
internal object NativeServiceCheck {
    @JvmStatic fun run(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        val result = JSONObject().put("passed", false)
        val provider = CommsProviderFactory.getProvider()
        val context = MapView.getMapView().context
        val nonce = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "arachne-service-check-$nonce")
        check(directory.mkdir())
        fun <T> ui(block: () -> T): T {
            val task = FutureTask<T>(block)
            Handler(Looper.getMainLooper()).post(task)
            return task.get(10, TimeUnit.SECONDS)
        }
        val comms = CommsMapComponent.getInstance()
        val packageFailure = AtomicReference<String?>()
        val packageUpload = CountDownLatch(1)
        val packageUploadStatus = AtomicReference<MPSendListener.UploadStatus?>()
        val packageBytes = "native package upload $nonce".toByteArray()
        val packageHash = java.security.MessageDigest.getInstance("SHA-256").digest(packageBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val packageBinding = LocalTakBinding.forWorkspace(context, ByteArray(32) { it.toByte() }, "Native package service check")
        val packageRecord = LocalWorkspace("Native package service check", nonce, java.security.MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()),
            memberId = ByteArray(32) { (it + 1).toByte() })
        val packageResources = WorkspaceResources(context, packageRecord, { _, _, _, _, _, _ -> true })
        val connections = java.util.concurrent.atomic.AtomicInteger()
        val packageFrames = LinkedBlockingQueue<String>(8)
        val packageService = LocalTakStream({ input, reply ->
            connections.incrementAndGet()
            NativeCotFrames.read(input) { bytes ->
                val event = NativeCotProjection.parse(bytes)
                if (event.documentElement.getAttribute("uid").startsWith("visibility-check-"))
                    check(packageFrames.offer(event.documentElement.getAttribute("uid")))
                if (event.documentElement.getAttribute("type") == "t-x-c-t") {
                    event.documentElement.setAttribute("type", "t-x-c-t-r")
                    check(reply(NativeCotProjection.encode(event)))
                }
            }
        }, { packageFailure.set("Package stream failed") }, binding = { packageBinding },
            applicationService = { binding, tls -> LocalTakHttp(context, binding, tls, { null },
            api = { method, path, body, type ->
                when {
                    method == "GET" && path == "/Marti/api/missions" ->
                        LocalTakHttp.Response(200, "application/json", "{\"version\":\"3\",\"type\":\"Mission\",\"data\":[]}".toByteArray())
                    else -> packageResources.nativeApi(method, path, body, type)
                }
            },
            failed = { packageFailure.set("Package HTTP failed") }) })
        try {
            val key = "${packageBinding.host}:${packageBinding.port}:ssl"
            check((0 until 600).any {
                check(packageFailure.get() == null) { packageFailure.get()!! }
                if (TAKServerListener.getInstance()?.findServer(key)?.isConnected == true) true
                else { Thread.sleep(100); false }
            }) { "ATAK did not connect to the package server" }
            val base = "https://${packageBinding.host}:${packageBinding.httpPort}"
            val client = TakHttpClient(base, key)
            try {
                // Cross the old five-second disconnect and ATAK's 15-second
                // heartbeat while HTTPS requests run on the separate listener.
                repeat(20) {
                    check(TAKServerListener.getInstance().connectedServers.any { it.connectString == key }) {
                        "Package server disappeared from the native picker"
                    }
                    val response = JSONObject(client.get("$base/Marti/api/missions"))
                    check(response.getString("type") == "Mission")
                    Thread.sleep(1000)
                }
            } finally { client.shutdown() }
            check(ui { comms.allPortsBundle.getParcelableArray("streams").orEmpty().count {
                (it as android.os.Bundle).getString("connectString")?.startsWith("${packageBinding.host}:") == true
            } } == 1) { "Workspace registered more than one CoT stream" }
            for (enabled in listOf(false, true)) {
                val beforeConnections = connections.get()
                packageService.packageServer(enabled)
                check((0 until 200).any {
                    val present = TAKServerListener.getInstance().connectedServers.any { it.connectString == key }
                    if (present == enabled && connections.get() > beforeConnections) true else { Thread.sleep(50); false }
                }) { "Native server visibility did not follow its setting" }
                val now = com.atakmap.coremap.maps.time.CoordinatedTime()
                val uid = "visibility-check-$enabled-$nonce"
                val event = com.atakmap.coremap.cot.event.CotEvent().apply {
                    this.uid = uid; type = "a-f-G"; version = "2.0"; how = "h-g-i-g-o"
                    time = now; start = now; stale = now.addMinutes(1)
                    setPoint(com.atakmap.coremap.cot.event.CotPoint.ZERO)
                }
                ui { comms.sendCoTToServersByMission(key, "visibility-check", event) }
                check(packageFrames.poll(10, TimeUnit.SECONDS) == uid) { "Package setting broke native CoT" }
                check(packageFailure.get() == null)
            }
            val packageFile = File(directory, "native-package.zip").also { it.writeBytes(packageBytes) }
            val listener = object : MPSendListener {
                override fun mpSendRecipients(contactUids: Array<String>) = Unit
                override fun mpAckReceived(contactUid: String?, ackDetail: String?, byteCount: Long) = Unit
                override fun mpSendFailed(contactUid: String?, nackDetail: String?, byteCount: Long) {
                    packageUploadStatus.set(MPSendListener.UploadStatus.FAILED); packageUpload.countDown()
                }
                override fun mpSendInProgress(contactUid: String?) = Unit
                override fun mpUploadProgress(contactUid: String?, status: MPSendListener.UploadStatus, detail: String?, byteCount: Long) {
                    if (status == MPSendListener.UploadStatus.COMPLETE || status == MPSendListener.UploadStatus.FAILED) {
                        packageUploadStatus.set(status); packageUpload.countDown()
                    }
                }
            }
            ui { comms.sendMissionPackage(key, packageFile, packageFile.name, listener) }
            check(packageUpload.await(20, TimeUnit.SECONDS)) { "Native MPIO upload did not finish" }
            check(packageUploadStatus.get() == MPSendListener.UploadStatus.COMPLETE) { "Native MPIO upload failed: ${packageUploadStatus.get()}" }
            check(packageResources.descriptors().single().hash == packageHash) { "Native public upload never reached the real catalog" }
            result.put("production_package_endpoint_connected", true)
                .put("production_package_connected_during_http_and_idle_20s", true)
                .put("production_package_one_stream_separate_https", true)
                .put("production_package_mpio_upload", true)
                .put("package_visibility_toggle_preserves_cot", true)
                .put("native_mpio_publication_uses_real_resource_api", true)
        } finally {
            packageService.close()
            check(packageService.awaitClosed(30_000)) { "Package service did not finish credential cleanup" }
            ui { Unit }
            packageResources.close()
            val workspaceHex = packageRecord.id.joinToString("") { "%02x".format(it.toInt() and 255) }
            File(context.noBackupFilesDir, "data-fabric/resources/$workspaceHex").deleteRecursively()
            context.deleteSharedPreferences("arachne-resource-cache-$workspaceHex")
        }
        fun streams() = ui { comms.allPortsBundle.getParcelableArray("streams")!!.map {
            (it as android.os.Bundle).getString("connectString")!!
        }.toSet() }
        val before = streams()
        try {
            result.put("provider", provider.javaClass.name)
                .put("mission_api_feature", provider.hasFeature(CommsProvider.CommsFeature.MISSION_API))
                .put("mission_hook_declared_by", provider.javaClass.getMethod("sendMissionApiRequest", String::class.java, String::class.java).declaringClass.name)
            // Observe the default hook without registering/replacing the global provider.
            check(provider.javaClass.name == "com.atakmap.comms.DefaultCommsProvider")
            result.put("mission_hook_accepted", provider.sendMissionApiRequest("GET", "{\"path\":\"/Marti/api/missions\"}"))
            val connected = CountDownLatch(1)
            val frames = LinkedBlockingQueue<ByteArray>(4)
            val port = LinkedBlockingQueue<Int>(1)
            val failure = AtomicReference<String?>()
            val uid = "service-check-$nonce"
            val mission = "mission-$nonce"
            val stream = LocalTakStream({ input, reply ->
                connected.countDown()
                NativeCotFrames.read(input) { bytes ->
                    val event = NativeCotProjection.parse(bytes)
                    if (event.documentElement.getAttribute("type") == "t-x-c-t") {
                        event.documentElement.setAttribute("type", "t-x-c-t-r")
                        check(reply(NativeCotProjection.encode(event)))
                    }
                    if (event.documentElement.getAttribute("uid") == uid) check(frames.offer(bytes))
                }
            }, { failure.set("Local native stream failed") }, { value, _, attach ->
                check(port.offer(value)); attach()
            })
            try {
                val key = "127.0.0.1:${checkNotNull(port.poll(15, TimeUnit.SECONDS))}:ssl"
                check(connected.await(20, TimeUnit.SECONDS)) { "No native connection" }
                val now = com.atakmap.coremap.maps.time.CoordinatedTime()
                val event = com.atakmap.coremap.cot.event.CotEvent().apply {
                    this.uid = uid; type = "a-f-G"; version = "2.0"; how = "h-g-i-g-o"
                    time = now; start = now; stale = now.addMinutes(1)
                    setPoint(com.atakmap.coremap.cot.event.CotPoint.ZERO)
                }
                ui { comms.sendCoTToServersByMission(key, mission, event) }
                val received = NativeCotProjection.parse(checkNotNull(frames.poll(10, TimeUnit.SECONDS)) { "Native mission send did not reach the named stream" })
                val destinations = received.getElementsByTagName("dest")
                check((0 until destinations.length).any {
                    (destinations.item(it) as org.w3c.dom.Element).getAttribute("mission") == mission
                }) { "Native mission destination was lost" }
                check(failure.get() == null)
                result.put("native_mission_destination", true)
            } finally {
                stream.close()
                check(stream.awaitClosed(10000)) { "Local native reader did not close" }
                ui { Unit } // Drain the queued stream removal.
            }
            val payload = "Arachne native resource fixture $nonce".toByteArray()
            val requests = LinkedBlockingQueue<String>(4)
            val serverFailure = AtomicReference<String?>()
            ServerSocket(0, 3, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 15000
                val base = "http://127.0.0.1:${server.localPort}"
                val serving = Thread({
                    try {
                        repeat(3) {
                            server.accept().use { socket ->
                                socket.soTimeout = 5000
                                val header = java.io.ByteArrayOutputStream()
                                while (!header.toString("US-ASCII").endsWith("\r\n\r\n")) {
                                    check(header.size() < 8192)
                                    val byte = socket.inputStream.read(); check(byte >= 0); header.write(byte)
                                }
                                val request = header.toString("US-ASCII").substringBefore("\r\n")
                                check(requests.offer(request))
                                val path = request.split(' ')[1]
                                val body = when (path) {
                                    "/Marti/api/missions" -> "{\"version\":\"3\",\"type\":\"Mission\",\"data\":[]}".toByteArray()
                                    "/resource.bin" -> payload
                                    else -> ByteArray(0)
                                }
                                val status = if (body.isNotEmpty()) "200 OK" else "404 Not Found"
                                socket.outputStream.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
                                socket.outputStream.write(body)
                            }
                        }
                    } catch (error: Exception) { if (!server.isClosed) serverFailure.set(error.toString()) }
                }, "arachne-service-fixture").apply { start() }
                try {
                    val client = TakHttpClient(base)
                    try {
                        val response = JSONObject(client.get("$base/Marti/api/missions"))
                        check(response.getString("type") == "Mission" && response.getJSONArray("data").length() == 0)
                        result.put("native_http_mission_response", true)
                    } finally { client.shutdown() }
                    val operation = GetFileOperation()
                    // 5.8 retains the old wrapper API but its operation casts the
                    // contained Parcelable to Request2; 5.6/5.7 have only Request.
                    val requestClass = try {
                        Class.forName("com.atakmap.android.http.rest.request.GetFileRequest2")
                    } catch (_: ClassNotFoundException) { GetFileRequest::class.java }
                    result.put("native_resource_request_class", requestClass.name)
                    fun resourceRequest(path: String, name: String): com.foxykeep.datadroid.requestmanager.Request {
                        val request = GetFileRequest("$base/$path", name, directory.absolutePath, 0).createGetFileRequest()
                        val value = requestClass.getConstructor(String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType)
                            .newInstance("$base/$path", name, directory.absolutePath, 0) as android.os.Parcelable
                        return request.put(GetFileOperation.PARAM_GETFILE, value)
                    }
                    val response = checkNotNull(operation.execute(context, resourceRequest("resource.bin", "resource.bin")))
                    check(response.getInt(NetworkOperation.PARAM_STATUSCODE) == 200)
                    check(File(directory, "resource.bin").readBytes().contentEquals(payload))
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(response.getString(GetFileOperation.PARAM_SHA256) == digest)
                    result.put("native_resource_sha256", digest)
                    try {
                        operation.execute(context, resourceRequest("missing", "missing.bin"))
                        error("Native resource operation accepted HTTP 404")
                    } catch (expected: com.foxykeep.datadroid.exception.ConnectionException) {
                        check(expected.statusCode == 404) { "Expected HTTP 404, got ${expected.statusCode}" }
                        check(!File(directory, "missing.bin").exists())
                        result.put("native_resource_missing_rejected", true)
                    }
                    try {
                        provider.simpleFileTransferInit(false, URI("$base/resource.bin"), null, null, null, null, File(directory, "simple.bin"))
                        error("Simple transfer unexpectedly accepted HTTP; inspect native contract")
                    } catch (expected: com.atakmap.commoncommo.CommoException) {
                        result.put("simple_file_http_rejection", expected.message)
                    }
                    serving.join(5000)
                    check(!serving.isAlive && serverFailure.get() == null) { serverFailure.get() ?: "Fixture worker still running" }
                    check(requests.toList().map { it.split(' ')[1] } == listOf("/Marti/api/missions", "/resource.bin", "/missing"))
                    result.put("native_http_requests", JSONArray(requests.toList()))
                } finally {
                    server.close(); serving.join(10000)
                    check(!serving.isAlive) { "Fixture server did not close" }
                }
            }
            check(CommsProviderFactory.getProvider() === provider && streams() == before)
            result.put("provider_and_streams_preserved", true).put("passed", true)
        } catch (error: Exception) {
            result.put("error", error.toString())
        } finally {
            check(directory.deleteRecursively())
            result.put("fixture_files_removed", !directory.exists())
        }
        return result.toString()
    }
}
