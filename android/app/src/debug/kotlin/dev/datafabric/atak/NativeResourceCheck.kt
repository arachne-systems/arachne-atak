package dev.arachne.atak

import android.os.Looper
import com.atakmap.android.http.rest.operation.GetFileOperation
import com.atakmap.android.http.rest.operation.NetworkOperation
import com.atakmap.android.http.rest.request.GetFileRequest
import com.atakmap.android.maps.MapView
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Real saved workspace adapters and native GetFileOperation. File selection
 * uses a debug-owned random fixture; no membership, packet or database injection. */
internal object NativeResourceCheck {
    private fun catalogPrivacy(context: android.content.Context) {
        fun identity() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun array(bytes: ByteArray) = org.json.JSONArray(bytes.map { it.toInt() and 255 })
        val self = identity(); val peer = identity()
        val record = LocalWorkspace("Catalog privacy check", UUID.randomUUID().toString(), identity(), memberId = self)
        val storage = File(context.noBackupFilesDir, "data-fabric/resources/${hex(record.id)}")
        var publications = 0
        val publisher: WorkspacePublisher = { _, _, _, _, _, _ -> publications++; true }
        var resource = WorkspaceResources(context, record, publisher)
        val roster = listOf(self, peer).map { WorkspaceMember(hex(it), null, false, it.contentEquals(self)) }
        resource.members(roster)
        val source = File.createTempFile("privacy-check-", ".zip", context.cacheDir)
        try {
            source.writeText("Only an explicit public upload belongs in the workspace catalog")
            val hash = hex(MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
            val directed = resource.offer(source, listOf(peer))
            check(resource.descriptors().isEmpty() && publications == 0)
            check(resource.fetch(WorkspaceResources.path(self, hash)) == null) { "Directed bytes escaped their grant" }
            resource.withdraw(directed)
            val multipart = ("--privacy\r\nContent-Disposition: form-data; name=\"assetfile\"; filename=\"private.zip\"\r\n" +
                "Content-Type: application/zip\r\n\r\n${source.readText()}\r\n--privacy--\r\n").toByteArray()
            fun upload(query: String = "") = resource.nativeApi("POST", "/Marti/sync/missionupload$query", multipart.inputStream(), "multipart/form-data; boundary=privacy")
            fun tool(value: String) = resource.nativeApi("PUT", "/Marti/api/sync/metadata/$hash/tool", value.byteInputStream(), "text/plain")
            check(upload()?.status == 200)
            check(resource.descriptors().isEmpty() && publications == 0) { "Unmarked native upload was made public" }
            check(resource.fetch(WorkspaceResources.path(self, hash)) == null) { "Staged upload was readable without publication" }
            check(tool("private")?.status == 403 && resource.descriptors().isEmpty())
            check(upload("?tool=private")?.status == 403 && publications == 0)
            check(upload()?.status == 200 && tool("public")?.status == 200)
            check(resource.descriptors().single().author == hex(self))
            check(tool("private")?.status == 403) { "API falsely promised recall of public bytes" }
            for ((query, expected, status) in listOf(
                Triple("offset=3", source.readBytes().drop(3).toByteArray(), 200),
                Triple("offset=3&length=5", source.readBytes().drop(3).take(5).toByteArray(), 206),
                Triple("offset=${source.length()}", ByteArray(0), 200)
            )) {
                val response = checkNotNull(resource.nativeApi("GET", "/Marti/sync/content?hash=$hash&$query", null, null))
                try { check(response.status == status && checkNotNull(response.file).readBytes().contentEquals(expected)) }
                finally { response.file?.delete() }
            }
            check(resource.nativeApi("GET", "/Marti/sync/content?hash=$hash&offset=${source.length() + 1}", null, null)?.status == 416)
            check(runCatching { resource.nativeApi("GET", "/Marti/sync/content?hash=$hash&offset=bad", null, null) }.isFailure)
            val descriptor = JSONObject().put("version", 1).put("hash", hash).put("size", source.length())
                .put("name", "peer-name.zip").put("media_type", "application/zip")
            fun remote(tombstone: Boolean) = JSONObject().put("workspace", array(record.id)).put("member", array(peer))
                .put("topic", WorkspaceResources.CATALOG).put("payload", array(FabricCatalogEntry(
                    MessageDigest.getInstance("SHA-256").digest(source.readBytes()),
                    if (tombstone) ByteArray(0) else descriptor.toString().toByteArray(), tombstone).wire()))
            resource.receive(remote(false)) { check(it) }
            resource.close()
            resource = WorkspaceResources(context, record, publisher).also { it.members(roster) }
            check(resource.descriptors().size == 1) { "Native catalog duplicated identical bytes" }
            resource.receive(remote(true)) { check(it) }
            check(resource.descriptors().single().author == hex(self)) { "Peer tombstone removed our offer" }
            resource.receive(remote(false)) { check(it) }
            resource.withdraw(hash)
            check(resource.descriptors().single().author == hex(peer)) { "Withdrawal removed another publisher's offer" }
            resource.members(roster.filter { it.self })
            check(resource.descriptors().isEmpty()) { "Removed member still advertised resources" }
        } finally {
            resource.close(); source.delete(); check(storage.deleteRecursively())
            context.deleteSharedPreferences("arachne-resource-cache-${hex(record.id)}")
        }
    }

    private fun cachePolicy(context: android.content.Context, automatic: Boolean) {
        fun identity() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun array(bytes: ByteArray) = org.json.JSONArray(bytes.map { it.toInt() and 255 })
        val self = identity(); val holder = identity()
        val record = LocalWorkspace("Cache policy check", UUID.randomUUID().toString(), identity(), memberId = self)
        val storage = File(context.noBackupFilesDir, "data-fabric/resources/${hex(record.id)}")
        val requests = java.util.concurrent.ArrayBlockingQueue<JSONObject>(8)
        val replies = java.util.concurrent.ArrayBlockingQueue<JSONObject>(8)
        val nativeDirectory = File(android.os.Environment.getExternalStorageDirectory(), "atak/tools/datapackage")
        val nativeBefore = nativeDirectory.list()?.toSet()
        check(WorkspaceResources.retentionEnabled(context, record.id))
        check(WorkspaceResources.cacheQuotaBytes(context, record.id) == 256L * 1024 * 1024)
        check(WorkspaceResources.setCacheQuotaBytes(context, record.id, 0))
        val transfer = ResourceTransferFixture()
        val resource = WorkspaceResources(context, record, { _, topic, bytes, _, _, _ ->
            if (topic == WorkspaceResources.REQUEST) check(requests.offer(JSONObject(String(bytes, Charsets.UTF_8))))
            if (topic == WorkspaceResources.RESPONSE) check(replies.offer(JSONObject(String(bytes, Charsets.UTF_8))))
            true
        }, transfer.calls(self))
        val content = "Verified cached bytes, not an installed package".toByteArray()
        val hash = hex(MessageDigest.getInstance("SHA-256").digest(content))
        val target = File(storage, "$hash.bin")
        fun awaitFile(present: Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 8000
            while (target.isFile != present) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Cache policy did not settle" }
                android.os.SystemClock.sleep(50)
            }
        }
        try {
            resource.members(listOf(WorkspaceMember(hex(self), null, false, true),
                WorkspaceMember(hex(holder), null, false, false, "reachable")))
            val descriptor = JSONObject().put("version", 1).put("hash", hash).put("size", content.size)
                .put("name", "cached-only.zip").put("media_type", "application/zip")
            resource.receive(JSONObject().put("workspace", array(record.id)).put("member", array(holder))
                .put("topic", WorkspaceResources.CATALOG).put("payload", array(FabricCatalogEntry(
                    MessageDigest.getInstance("SHA-256").digest(content), descriptor.toString().toByteArray()).wire()))) { check(it) }
            check(requests.poll(6, java.util.concurrent.TimeUnit.SECONDS) == null) { "Auto-cache exceeded quota" }
            check(WorkspaceResources.setRetention(context, record.id, false))
            check(WorkspaceResources.setCacheQuotaBytes(context, record.id, 1024))
            check(requests.poll(6, java.util.concurrent.TimeUnit.SECONDS) == null) { "Disabled retention still fetched bytes" }
            val selected = WorkspaceResources.Policy(retention = WorkspaceResources.Retention.SELECTED, quota = 1024, serveReplicas = false)
            check(WorkspaceResources.savePolicy(context, record.id, selected))
            check(WorkspaceResources.policy(context, record.id) == selected)
            check(requests.poll(6, java.util.concurrent.TimeUnit.SECONDS) == null) { "Selected mode fetched an unselected resource" }
            if (automatic) check(WorkspaceResources.savePolicy(context, record.id, selected.copy(retention = WorkspaceResources.Retention.AUTOMATIC)))
            else resource.select(hash, true)
            val request = checkNotNull(requests.poll(8, java.util.concurrent.TimeUnit.SECONDS)) { "Enabled retention did not auto-cache" }
            check(request.getString("hash") == hash)
            val response = JSONObject(request.toString()).put("status", 200).put("ticket", transfer.ticket(content, self))
            resource.receive(JSONObject().put("workspace", array(record.id)).put("member", array(holder))
                .put("topic", WorkspaceResources.RESPONSE).put("recipients", org.json.JSONArray().put(array(self)))
                .put("payload", array(response.toString().toByteArray()))) { check(it) }
            awaitFile(true)
            check(target.readBytes().contentEquals(content))
            check(resource.cacheEntries().single().let { it.cached && it.selected == !automatic && !it.published })
            check(WorkspaceResources.usage(context, record.id).cached == content.size.toLong())
            fun remoteRead(expected: Int) {
                val query = JSONObject().put("v", 1).put("id", "a".repeat(32)).put("hash", hash)
                resource.receive(JSONObject().put("workspace", array(record.id)).put("member", array(holder))
                    .put("topic", WorkspaceResources.REQUEST).put("recipients", org.json.JSONArray().put(array(self)))
                    .put("payload", array(query.toString().toByteArray()))) { check(it) }
                check(checkNotNull(replies.poll(3, java.util.concurrent.TimeUnit.SECONDS)).getInt("status") == expected)
            }
            remoteRead(404)
            check(WorkspaceResources.savePolicy(context, record.id, selected.copy(serveReplicas = true)))
            remoteRead(200)
            check(nativeDirectory.list()?.toSet() == nativeBefore) { "Caching changed native installed packages" }
            // Same accepted members, but no reachable peer. Local bytes win
            // over the original author and require no resource publication.
            resource.members(listOf(WorkspaceMember(hex(self), null, false, true),
                WorkspaceMember(hex(holder), null, false, false)))
            val started = android.os.SystemClock.elapsedRealtime()
            val served = checkNotNull(resource.nativeApi("GET", "/Marti/sync/content?hash=$hash", null, null))
            try {
                check(served.status == 200 && checkNotNull(served.file).readBytes().contentEquals(content))
                check(android.os.SystemClock.elapsedRealtime() - started < 2000 && requests.isEmpty()) { "Cached read attempted network access" }
            } finally { served.file?.delete() }
            check(WorkspaceResources.savePolicy(context, record.id, selected.copy(server = false)))
            check(resource.nativeApi("GET", "/Marti/sync/search", null, null)?.status == 503)
            checkNotNull(resource.fetch(WorkspaceResources.path(self, hash))).let { it.delete() }
            check(WorkspaceResources.savePolicy(context, record.id, selected))
            val own = File.createTempFile("owned-cache-check-", ".bin", context.cacheDir)
            try {
                own.writeText("A user-owned offer must survive cache eviction")
                val owned = resource.offerCatalog(own, "owned.bin", "application/octet-stream")
                val protected = java.util.concurrent.CompletableFuture<Result<Unit>>()
                resource.removeCached(owned) { protected.complete(it) }
                check(protected.get(3, java.util.concurrent.TimeUnit.SECONDS).isFailure)
                val cleared = java.util.concurrent.CompletableFuture<Result<Unit>>()
                resource.clearCache { cleared.complete(it) }
                check(cleared.get(3, java.util.concurrent.TimeUnit.SECONDS).isSuccess)
                check(WorkspaceResources.policy(context, record.id).retention == WorkspaceResources.Retention.OFF)
                check(!target.exists() && resource.descriptors().any { it.hash == hash })
                check(nativeDirectory.list()?.toSet() == nativeBefore)
                check(WorkspaceResources.setCacheQuotaBytes(context, record.id, 0))
                awaitFile(false)
                check(File(storage, "$owned.bin").isFile)
            } finally { own.delete() }
        } finally {
            resource.close()
            check(storage.deleteRecursively())
            context.deleteSharedPreferences("arachne-resource-cache-${hex(record.id)}")
        }
    }

    /** In-process adapter boundary check. Publications below are synthetic;
     * actual joined-peer/native HTTP evidence comes from run("download", ...). */
    @JvmStatic fun protocol(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        val context = MapView.getMapView().context
        catalogPrivacy(context)
        fun identity() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun array(bytes: ByteArray) = org.json.JSONArray(bytes.map { it.toInt() and 255 })
        val self = identity(); val holder = identity(); val other = identity()
        val record = LocalWorkspace("Resource contract check", UUID.randomUUID().toString(), identity(), memberId = self)
        val requests = java.util.concurrent.ArrayBlockingQueue<JSONObject>(8)
        val publisher: WorkspacePublisher = { workspace, topic, bytes, recipients, current, _ ->
            check(workspace.contentEquals(record.id))
            if (topic == WorkspaceResources.REQUEST) {
                check(current == null && recipients.size == 1 && recipients.single().contentEquals(holder))
                check(requests.offer(JSONObject(String(bytes, Charsets.UTF_8))))
            } else check(topic in setOf(WorkspaceResources.CATALOG, WorkspaceResources.CLAIMS) && current != null && recipients.isEmpty())
            true
        }
        val transfer = ResourceTransferFixture()
        var resource = WorkspaceResources(context, record, publisher, transfer.calls(self))
        val roster = listOf(self, holder, other).map { WorkspaceMember(hex(it), null, false, it.contentEquals(self)) }
        resource.members(roster)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val content = "Resource correlation check".toByteArray(Charsets.UTF_8)
        val hash = hex(MessageDigest.getInstance("SHA-256").digest(content))
        val temporaryBefore = context.cacheDir.list()?.filter { it.startsWith("arachne-resource-") }?.toSet()
        fun fetch() = executor.submit<File?> { resource.fetch(WorkspaceResources.path(holder, hash)) }
        fun request() = checkNotNull(requests.poll(3, java.util.concurrent.TimeUnit.SECONDS))
        fun response(request: JSONObject, bytes: ByteArray = content): JSONObject {
            val payload = JSONObject(request.toString()).put("status", 200).put("ticket", transfer.ticket(bytes, self))
            return JSONObject().put("workspace", array(record.id)).put("member", array(holder))
                .put("topic", WorkspaceResources.RESPONSE).put("recipients", org.json.JSONArray().put(array(self)))
                .put("payload", array(payload.toString().toByteArray(Charsets.UTF_8)))
        }
        fun receive(value: JSONObject) {
            var completions = 0
            resource.receive(value) { accepted -> check(accepted); completions++ }
            check(completions == 1) { "Resource message pinned the receive worker" }
        }
        fun failure(future: java.util.concurrent.Future<File?>, expected: Class<out Throwable>) {
            try { future.get(3, java.util.concurrent.TimeUnit.SECONDS)?.delete(); error("Invalid resource was returned") }
            catch (error: java.util.concurrent.ExecutionException) { check(expected.isInstance(error.cause)) { error.toString() } }
        }
        try {
            val source = File.createTempFile("resource-hash-", ".bin", context.cacheDir)
            try {
                source.writeBytes(byteArrayOf())
                val emptyHash = resource.offer(source)
                check(emptyHash == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                check(resource.offer(source) == emptyHash) // Rehash the stored empty snapshot.
                checkNotNull(resource.fetch(WorkspaceResources.path(self, emptyHash))).let { copy ->
                    try { check(copy.length() == 0L) } finally { check(copy.delete()) }
                }
                resource.withdraw(emptyHash)
                source.writeBytes(content)
                check(resource.offer(source) == hash)
                source.writeText("changed content")
                val changedHash = resource.offer(source)
                check(changedHash != hash)
                resource.withdraw(changedHash)
                resource.withdraw(hash)
            } finally { check(source.delete()) }
            val catalogSource = File.createTempFile("resource-catalog-", ".zip", context.cacheDir)
            try {
                catalogSource.writeBytes(content)
                check(resource.offerCatalog(catalogSource, "catalog-check.zip", "application/zip") == hash)
                check(resource.nativeApi("GET", "/Marti/api/version", null, null)?.status == 200)
                check(resource.nativeApi("GET", "/Marti/api/version/config", null, null)?.status == 200)
                check(resource.nativeApi("GET", "/Marti/api/clientEndPoints", null, null)?.status == 200)
                val search = checkNotNull(resource.nativeApi("GET", "/Marti/sync/search?keywords=missionpackage", null, null))
                check(search.status == 200 && JSONObject(String(checkNotNull(search.body), Charsets.UTF_8)).getInt("resultCount") == 1)
                val query = checkNotNull(resource.nativeApi("GET", "/Marti/sync/missionquery?hash=$hash", null, null))
                check(query.status == 200 && String(checkNotNull(query.body), Charsets.UTF_8) == "/Marti/sync/content?hash=$hash")
                val contentResponse = checkNotNull(resource.nativeApi("GET", "/Marti/sync/content?hash=$hash", null, null))
                check(contentResponse.status == 200 && checkNotNull(contentResponse.file).readBytes().contentEquals(content))
                checkNotNull(contentResponse.file).delete()
                check(resource.nativeApi("PUT", "/Marti/api/sync/metadata/$hash/tool", "public".byteInputStream(), "text/plain")?.status == 200)
                val remoteHash = "1".repeat(64)
                val descriptor = JSONObject().put("version", 1).put("hash", remoteHash).put("size", 17)
                    .put("name", "remote-package.zip").put("media_type", "application/zip")
                val remote = JSONObject().put("workspace", array(record.id)).put("member", array(holder))
                    .put("topic", WorkspaceResources.CATALOG).put("payload", array(FabricCatalogEntry(
                        ByteArray(32) { 0x11 }, descriptor.toString().toByteArray(Charsets.UTF_8)).wire()))
                val storage = File(context.noBackupFilesDir, "data-fabric/resources/${hex(record.id)}")
                check(storage.setWritable(false, false))
                try {
                    var acknowledged: Boolean? = null
                    resource.receive(remote) { acknowledged = it }
                    check(acknowledged == false && resource.descriptors().none { it.hash == remoteHash }) {
                        "Failed catalog save was acknowledged or exposed"
                    }
                } finally { check(storage.setWritable(true, true)) }
                resource.receive(remote) { check(it) }
                val expected = resource.descriptors().toSet()
                check(expected.size == 2)
                resource.close()
                resource = WorkspaceResources(context, record, publisher, transfer.calls(self)).also { it.members(roster) }
                check(resource.descriptors().toSet() == expected) { "Catalog was lost on adapter restart" }
                val restored = checkNotNull(resource.nativeApi("GET", "/Marti/sync/search?keywords=missionpackage", null, null))
                check(JSONObject(String(checkNotNull(restored.body), Charsets.UTF_8)).getInt("resultCount") == 2)
                val tombstone = JSONObject(remote.toString()).put("payload", array(FabricCatalogEntry(
                    ByteArray(32) { 0x11 }, ByteArray(0), true).wire()))
                resource.receive(tombstone) { check(it) }
                resource.close()
                resource = WorkspaceResources(context, record, publisher, transfer.calls(self)).also { it.members(roster) }
                check(resource.descriptors().single().hash == hash) { "Catalog tombstone was lost on restart" }
            } finally { check(catalogSource.delete()) }
            val first = fetch(); val firstRequest = request()
            val rejected = org.json.JSONArray()
            for ((name, change) in listOf<Pair<String, (JSONObject) -> Unit>>(
                "wrong workspace" to { it.put("workspace", array(identity())) },
                "wrong recipient" to { it.put("recipients", org.json.JSONArray().put(array(other))) },
                "other member" to { it.put("member", array(other)) },
                "outsider" to { it.put("member", array(identity())) }
            )) {
                receive(response(firstRequest).also(change)); check(!first.isDone); rejected.put(name)
            }
            for ((name, change) in listOf<Pair<String, (JSONObject) -> Unit>>(
                "wrong request" to { it.put("id", "0".repeat(32)) },
                "wrong hash" to { it.put("hash", "0".repeat(64)) },
                "unexpected offset" to { it.put("offset", -1) },
                "wrong version" to { it.put("v", 2) },
                "unexpected data" to { it.put("data", "!") },
                "negative size" to { it.getJSONObject("ticket").put("size", -1) },
                "invalid grant" to { it.getJSONObject("ticket").put("grant", org.json.JSONArray().put(999)) }
            )) {
                val value = response(firstRequest)
                val bytes = value.getJSONArray("payload")
                val payload = JSONObject(String(ByteArray(bytes.length()) { bytes.getInt(it).toByte() }, Charsets.UTF_8)).also(change)
                receive(value.put("payload", array(payload.toString().toByteArray(Charsets.UTF_8))))
                check(!first.isDone); rejected.put(name)
            }
            val valid = response(firstRequest)
            receive(valid)
            checkNotNull(first.get(3, java.util.concurrent.TimeUnit.SECONDS)).let { file ->
                try { check(file.readBytes().contentEquals(content)) } finally { check(file.delete()) }
            }
            receive(valid) // Duplicate after completion is harmless.
            val second = fetch(); val secondRequest = request()
            check(firstRequest.getString("id") != secondRequest.getString("id"))
            receive(valid); check(!second.isDone) // Old response cannot complete a new call.
            receive(response(secondRequest, ByteArray(content.size)))
            failure(second, IllegalStateException::class.java)
            val removed = fetch(); request()
            resource.members(roster.filter { it.id != hex(holder) })
            failure(removed, java.util.concurrent.CancellationException::class.java)
            resource.members(roster)
            val closed = fetch(); request(); resource.close()
            failure(closed, java.util.concurrent.CancellationException::class.java)
            check(context.cacheDir.list()?.filter { it.startsWith("arachne-resource-") }?.toSet() == temporaryBefore)
            cachePolicy(context, automatic = true)
            cachePolicy(context, automatic = false)
            return JSONObject().put("passed", true).put("synthetic_adapter_boundary", true)
                .put("empty_and_changed_content_hashes", true).put("rejected", rejected).put("duplicate_and_stale", true).put("hash_mismatch", true)
                .put("membership_change", true).put("close_cancellation", true).put("temporary_cleanup", true)
                .put("catalog_restart_and_tombstone", true).put("catalog_failed_save_not_acknowledged", true)
                .put("publisher_scoped_catalog_and_private_upload", true)
                .put("automatic_cache_without_install", true).put("local_cache_without_peers", true)
                .put("quota_retention_and_owned_protection", true)
                .put("selected_retention_and_independent_serving", true)
                .put("clear_cache_preserves_catalog_published_and_installed", true)
                .put("tak_offset_length_and_invalid_range", true).toString()
        } finally { resource.close(); executor.shutdownNow(); check(File(context.noBackupFilesDir, "data-fabric/resources/${hex(record.id)}").deleteRecursively()) }
    }

    @JvmStatic fun run(action: String, record: LocalWorkspace, adapters: WorkspaceAdapters, reference: String): String {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper())
        val context = MapView.getMapView().context
        val member = checkNotNull(record.memberId)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val result = JSONObject().put("scope", hex(record.id)).put("member", hex(member))
        val binding = LocalTakBinding.forWorkspace(context, record.id, record.name)
        result.put("local_host", binding.host)
        if (action == "binding") return result.put("passed", true).toString()
        val directory = File(context.cacheDir, "arachne-resource-check-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            if (action == "offer") {
                val source = File(directory, "source.bin")
                source.writeBytes(ByteArray(20519).also { SecureRandom().nextBytes(it) })
                val hash = adapters.offerResource(record.id, source)
                check(hash == hex(MessageDigest.getInstance("SHA-256").digest(source.readBytes())))
                return result.put("passed", true).put("hash", hash).put("size", source.length())
                    .put("reference", "${hex(member)}/$hash").toString()
            }
            val parts = reference.split('/')
            require(parts.size == 2 && parts.all { it.matches(Regex("[a-f0-9]{64}")) })
            val hash = parts[1]
            if (action == "withdraw") {
                require(parts[0] == hex(member))
                adapters.withdrawResource(record.id, hash)
                return result.put("passed", true).put("withdrawn", hash).toString()
            }
            require(action == "download")
            val holder = parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val url = LocalTakHttp.url(binding, WorkspaceResources.path(holder, hash))
            val requestClass = try { Class.forName("com.atakmap.android.http.rest.request.GetFileRequest2") }
                catch (_: ClassNotFoundException) { GetFileRequest::class.java }
            val request = GetFileRequest(url, "received.bin", directory.absolutePath, 0).createGetFileRequest()
            val nativeRequest = requestClass.getConstructor(String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType)
                .newInstance(url, "received.bin", directory.absolutePath, 0) as android.os.Parcelable
            request.put(GetFileOperation.PARAM_GETFILE, nativeRequest)
            result.put("holder", parts[0]).put("requested_hash", hash).put("native_request_class", requestClass.name)
            val target = File(directory, "received.bin")
            try {
                val response = checkNotNull(GetFileOperation().execute(context, request))
                check(response.getInt(NetworkOperation.PARAM_STATUSCODE) == 200)
                val actual = hex(MessageDigest.getInstance("SHA-256").digest(target.readBytes()))
                check(actual == hash && response.getString(GetFileOperation.PARAM_SHA256) == hash)
                result.put("status", 200).put("native_sha256", response.getString(GetFileOperation.PARAM_SHA256))
                    .put("size", target.length()).put("file_sha256", actual)
            } catch (error: com.foxykeep.datadroid.exception.ConnectionException) {
                check(!target.exists()) { "Failed native request left a destination file" }
                result.put("status", error.statusCode).put("native_error", error.javaClass.name).put("destination_absent", true)
            }
            return result.put("passed", true).toString()
        } finally { check(directory.deleteRecursively()) }
    }
}
