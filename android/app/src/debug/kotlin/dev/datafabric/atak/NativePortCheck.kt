package dev.arachne.atak

import android.os.Handler
import android.os.Looper
import com.atakmap.android.http.rest.NetworkOperationManager
import com.atakmap.android.maps.MapView
import com.atakmap.android.missionpackage.http.MissionPackageDownloader
import com.atakmap.android.missionpackage.http.datamodel.FileTransfer
import com.atakmap.android.missionpackage.http.datamodel.MissionPackageQueryResult
import com.atakmap.android.missionpackage.http.rest.FileTransferRequest
import com.atakmap.android.missionpackage.http.rest.GetFileTransferOperation
import com.atakmap.android.missionpackage.http.rest.PostMissionPackageRequest
import com.atakmap.android.missionpackage.http.rest.QueryMissionPackageOperation
import com.atakmap.android.missionpackage.http.rest.QueryMissionPackageRequest
import com.atakmap.comms.SslNetCotPort
import com.atakmap.comms.TAKServerListener
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Emulator-only caller. Real registry, native stream, authenticated HTTP and
 * extractor with disposable packages; does not change ATAK mesh settings. */
internal object NativePortCheck {
    @JvmStatic fun run(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        val context = MapView.getMapView().context
        val portsBefore = LocalTakPorts.choices(context)
        check(portsBefore == LocalTakPorts.Choices()) { "This fixture requires Automatic ports; preserve custom settings." }
        val mesh = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("enableNonStreamingConnections", true)
        val nativePort = SslNetCotPort.getServerApiPort(SslNetCotPort.Type.SECURE)
        val ids = listOf(MissionPackageDownloader.REQUEST_TYPE_QUERY_MISSIONPACKAGE,
            MissionPackageDownloader.REQUEST_TYPE_POST_MISSIONPACKAGE, MissionPackageDownloader.REQUEST_TYPE_FILETRANSFER_GET_FILE)
        val operations = ids.associateWith { NetworkOperationManager.getOperation(it) }
        val nonce = UUID.randomUUID().toString()
        val workspace = java.security.MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray())
        val workspaceHex = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
        val record = LocalWorkspace("Port routing QA", nonce, workspace, memberId = ByteArray(32) { (it + 1).toByte() })
        val binding = LocalTakBinding.forWorkspace(context, workspace, record.name)
        val resource = WorkspaceResources(context, record, { _, _, _, _, _, _ -> true })
        val service = AtomicReference<LocalTakHttp?>()
        val failure = AtomicReference<String?>()
        val directory = File(context.cacheDir, "arachne-port-check-$nonce").also { check(it.mkdir()) }
        fun <T> ui(block: () -> T): T = FutureTask<T>(block).also { Handler(Looper.getMainLooper()).post(it) }.get(15, TimeUnit.SECONDS)
        val occupied = LocalTakPorts.reserve(binding.host, 0)
        val legacy = try { LocalTakPorts.reserve(binding.host, 8443) } catch (_: java.io.IOException) { null }
        var stream: LocalTakStream? = null
        var partial: File? = null
        val result = JSONObject().put("passed", false).put("native_mesh_enabled", mesh)
        try {
            check(runCatching { LocalTakPorts.save(context, LocalTakPorts.Choices(occupied.localPort, 0)) }.isFailure)
            check(runCatching { LocalTakPorts.save(context, LocalTakPorts.Choices(0, occupied.localPort)) }.isFailure)
            check(LocalTakPorts.choices(context) == portsBefore)
            for (value in listOf(-1, 1, 1023, 65536)) check(runCatching { LocalTakPorts.Choices(value, 0) }.isFailure)
            check(runCatching { LocalTakPorts.Choices(19001, 19001) }.isFailure)
            result.put("occupied_stream_and_https_rejected_settings_preserved", true).put("invalid_and_duplicate_ports_rejected", true)
            stream = LocalTakStream({ input, reply -> NativeCotFrames.read(input) { bytes ->
                val event = NativeCotProjection.parse(bytes)
                if (event.documentElement.getAttribute("type") == "t-x-c-t") {
                    event.documentElement.setAttribute("type", "t-x-c-t-r"); check(reply(NativeCotProjection.encode(event)))
                }
            } }, { failure.set("Native stream failed") }, binding = { binding }, applicationService = { bound, tls ->
                LocalTakHttp(context, bound, tls, resource::fetch, resource::nativeApi) { failure.set("HTTPS failed") }.also { service.set(it) }
            })
            check((0 until 300).any {
                val server = service.get()
                if (failure.get() != null) error(failure.get()!!)
                if (server != null && TAKServerListener.getInstance()?.findServer(server.connection)?.isConnected == true) true
                else { Thread.sleep(100); false }
            }) { "Arachne was not connected in the native server picker" }
            val http = checkNotNull(service.get())
            check(http.nativePackagesReady && http.port != 8443 && http.connection.split(':')[1] != "8089")
            val key = http.connection
            fun execute(request: com.foxykeep.datadroid.requestmanager.Request) = checkNotNull(NetworkOperationManager.getOperation(request.requestType)).execute(context, request)
            val zip = File(directory, "port-routing.zip")
            val manifest = """<MissionPackageManifest version="2"><Configuration><Parameter name="name" value="Port routing QA"/><Parameter name="uid" value="$nonce"/><Parameter name="onReceiveImport" value="false"/><Parameter name="onReceiveDelete" value="true"/></Configuration><Contents><Content ignore="false" zipEntry="port-routing.txt"/></Contents></MissionPackageManifest>"""
            java.util.zip.ZipOutputStream(zip.outputStream()).use { output ->
                for ((name, bytes) in listOf("MANIFEST/manifest.xml" to manifest.toByteArray(), "port-routing.txt" to "Port isolation QA $nonce".toByteArray())) {
                    output.putNextEntry(java.util.zip.ZipEntry(name)); output.write(bytes); output.closeEntry()
                }
            }
            check(com.atakmap.android.missionpackage.file.MissionPackageExtractorFactory.GetManifest(zip)?.isValid == true)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val post = PostMissionPackageRequest(key, hash, "Port routing QA", "port-routing-qa", zip.absolutePath)
            execute(post.createPostMissionPackageRequest())
            check(resource.descriptors().single().hash == hash)
            val query = QueryMissionPackageRequest(key, 49001)
            val listing = JSONObject(execute(query.createQueryMissionPackageRequest()).getString(QueryMissionPackageOperation.PARAM_JSONLIST)!!)
            val item = MissionPackageQueryResult.fromResultJSON(listing).single()
            check(item.hash == hash)
            val transfer = FileTransfer.fromQuery(context, item, key, directory.absolutePath)
            val request = FileTransferRequest(transfer, 49002, 0)
            val partialFile = File(context.cacheDir, "arachne-native-${binding.host}-$hash.part").also { partial = it }
            partialFile.writeBytes(zip.readBytes().copyOf(zip.length().toInt() / 2))
            val downloaded = execute(request.createFileTransferDownloadRequest())
                .getParcelable(GetFileTransferOperation.PARAM_MISSION_PACKAGE_MANIFEST) as? com.atakmap.android.missionpackage.file.MissionPackageManifest
            check(downloaded?.isValid == true && !partialFile.exists())
            result.put("native_dispatch_upload_search_download", true).put("partial_download_resumed_and_verified", true)
                .put("stream_connection", key).put("https_port", http.port)
                .put("workspace_loopback_8443_held_during_operations", legacy != null)
            // A valid, uncompressed 100 MiB+ package through actual native HTTP
            // dispatch. No full-file byte arrays and no importer invocation.
            val large = File(directory, "large-streaming.zip")
            val block = ByteArray(64 * 1024).also(java.security.SecureRandom()::nextBytes)
            val blocks = 1601
            val crc = java.util.zip.CRC32().apply { repeat(blocks) { update(block) } }
            java.util.zip.ZipOutputStream(large.outputStream()).use { output ->
                output.putNextEntry(java.util.zip.ZipEntry("MANIFEST/manifest.xml"))
                output.write(manifest.toByteArray()); output.closeEntry()
                output.putNextEntry(java.util.zip.ZipEntry("port-routing.txt").apply {
                    method = java.util.zip.ZipEntry.STORED
                    size = blocks.toLong() * block.size; compressedSize = size; this.crc = crc.value
                })
                repeat(blocks) { output.write(block) }; output.closeEntry()
            }
            fun digest(file: File): String {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            val largeHash = digest(large)
            val start = android.os.SystemClock.elapsedRealtime()
            execute(PostMissionPackageRequest(key, largeHash, "Large streaming QA", "large-streaming-qa", large.absolutePath).createPostMissionPackageRequest())
            check(resource.descriptors().any { it.hash == largeHash && it.size == large.length() })
            val downloadedLarge = checkNotNull(resource.nativeApi("GET", "/Marti/sync/content?hash=$largeHash", null, null)?.file)
            try { check(downloadedLarge.length() == large.length() && digest(downloadedLarge) == largeHash) }
            finally { check(downloadedLarge.delete()) }
            result.put("large_streaming_bytes", large.length()).put("large_streaming_sha256", largeHash)
                .put("large_streaming_elapsed_ms", android.os.SystemClock.elapsedRealtime() - start)
                .put("large_native_upload_and_resource_read_verified_no_import", true)
            // This native request is NOT an Arachne destination. The original
            // query operation must still contact an ordinary HTTP TAK endpoint.
            ServerSocket(SslNetCotPort.getServerApiPort(SslNetCotPort.Type.UNSECURE), 4, InetAddress.getByName("127.0.0.1")).use { other ->
                other.soTimeout = 10_000
                val received = FutureTask {
                    other.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val first = input.readLine()
                        while (!input.readLine().isNullOrEmpty()) { }
                        val body = "{\"resultCount\":0,\"results\":[]}"
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$body".toByteArray())
                        first
                    }
                }
                Thread(received, "arachne-ordinary-server-fixture").start()
                val normal = execute(QueryMissionPackageRequest("127.0.0.1:45123:tcp", 49003).createQueryMissionPackageRequest())
                check(JSONObject(normal.getString(QueryMissionPackageOperation.PARAM_JSONLIST)!!).getInt("resultCount") == 0)
                check(received.get(12, TimeUnit.SECONDS).startsWith("GET /Marti/sync/search?"))
            }
            check(TAKServerListener.getInstance().connectedServers.any { it.connectString == key })
            result.put("ordinary_tak_query_delegated_unchanged", true)
            check(SslNetCotPort.getServerApiPort(SslNetCotPort.Type.SECURE) == nativePort)
            check(android.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enableNonStreamingConnections", true) == mesh)
            result.put("atak_ports_and_mesh_preference_unchanged", true)
        } finally {
            stream?.close(); check(stream?.awaitClosed(30_000) != false); ui { Unit }
            occupied.close(); legacy?.close(); resource.close()
            val otherListeners = LocalTakPorts.status().any { it.startsWith("https:") }
            if (!otherListeners) check(ids.all { NetworkOperationManager.getOperation(it) === operations[it] }) { "Native operations were not restored" }
            check(service.get()?.nativePackagesReady != true)
            result.put("dispatcher_restored_after_last_close", !otherListeners)
                .put("other_workspace_listeners_remain_active", otherListeners)
            context.getSharedPreferences("arachne-native-bindings-v1", android.content.Context.MODE_PRIVATE).edit().remove(workspaceHex).commit()
            context.deleteSharedPreferences("arachne-resource-cache-$workspaceHex")
            File(context.noBackupFilesDir, "data-fabric/resources/$workspaceHex").deleteRecursively()
            partial?.delete()
            File(com.atakmap.android.missionpackage.MissionPackageMapComponent.getInstance().fileIO.missionPackageFilesPath, nonce).deleteRecursively()
            directory.deleteRecursively()
        }
        return result.put("passed", true).toString()
    }
}
