package dev.arachne.atak

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okio.buffer
import okio.source

internal typealias WorkspaceResourceCall = (ByteArray, JSONObject, (Result<JSONObject>) -> Unit) -> Boolean

/** Immutable workspace resources: catalog/grants over protected pub/sub, bytes
 * over the fabric's verified bulk stream.
 * HTTP and native file selection are adapters around this exchange. A hash
 * names content, not a filesystem path or permission. Directed offers require
 * their recipient-bound grant. An available holder is required. */
internal class WorkspaceResources(private val context: Context, record: LocalWorkspace, private val publish: WorkspacePublisher,
                                  private val resourceCall: WorkspaceResourceCall = { _, _, _ -> false }) : AutoCloseable {
    data class DirectedOffer(val hash: String, val grant: String, val size: Long)
    data class Descriptor(val hash: String, val size: Long, val name: String, val mediaType: String, val author: String,
                          val publishedAt: Long = 0)
    enum class Retention { AUTOMATIC, SELECTED, OFF }
    data class Policy(val server: Boolean = true, val retention: Retention = Retention.AUTOMATIC,
                      val quota: Long = DEFAULT_QUOTA, val serveReplicas: Boolean = true)
    data class CacheEntry(val hash: String, val name: String, val size: Long, val cached: Boolean,
                          val published: Boolean, val selected: Boolean)
    data class Usage(val cached: Long, val published: Long, val free: Long)
    private data class Claim(val hash: String, val size: Long, val holder: String)
    private data class Stored(val hash: String, val size: Long)
    private data class Grant(val hash: String, val recipients: Set<String>)
    private val workspace = record.id.copyOf()
    private val self = hex(checkNotNull(record.memberId))
    private val directory = File(context.noBackupFilesDir, "data-fabric/resources/${hex(workspace)}")
    private val preferences = context.getSharedPreferences("arachne-resource-cache-${hex(workspace)}", Context.MODE_PRIVATE)
    private val owned = preferences.getStringSet("owned", emptySet())?.toMutableSet() ?: mutableSetOf()
    private val replicas = preferences.getStringSet("replicas", emptySet())?.toMutableSet() ?: mutableSetOf()
    @Volatile private var closed = false
    @Volatile private var members = setOf(self)
    @Volatile private var memberViews = emptyList<WorkspaceMember>()
    private data class Pending(val author: String, val hash: String, val grant: String?, val result: CompletableFuture<JSONObject>,
                               @Volatile var seen: Long = SystemClock.elapsedRealtime())
    private val pending = mutableMapOf<String, Pending>()
    private val grants = mutableMapOf<String, Grant>()
    private data class StagedUpload(val file: File, val name: String, val mediaType: String, val expires: Long)
    private val uploads = mutableMapOf<String, StagedUpload>()
    private val catalogLock = Any()
    private val catalog = linkedMapOf<String, Descriptor>()
    private val claims = linkedMapOf<String, Claim>()
    private val catalogFile = android.util.AtomicFile(File(directory, "catalog.json"))
    private val reads = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(32),
        { task -> Thread(task, "arachne-resource-read") })
    private val caching = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "arachne-resource-cache")
    }
    private val cacheRetryAt = mutableMapOf<String, Long>()
    init {
        directory.listFiles()?.filter { DIRECTED_FILE.matches(it.name) || it.name.endsWith(".upload") }?.forEach(File::delete)
        val saved = try { catalogFile.openRead().use { input ->
            val size = input.channel.size()
            check(size in 1..MAX_CATALOG_BYTES) { "Invalid resource catalog size" }
            val bytes = ByteArray(size.toInt())
            java.io.DataInputStream(input).readFully(bytes)
            JSONObject(String(bytes, Charsets.UTF_8))
        } } catch (_: java.io.FileNotFoundException) { null }
        if (saved != null) {
            check(saved.getInt("version") == 1 && saved.getString("workspace") == hex(workspace))
            val descriptors = saved.getJSONArray("descriptors")
            repeat(descriptors.length()) {
                val value = descriptors.getJSONObject(it)
                val item = descriptor(value, value.getString("author"), value.getString("hash"))
                check(catalog.put("${item.hash}:${item.author}", item) == null)
            }
            val holders = saved.getJSONArray("claims")
            repeat(holders.length()) {
                val value = holders.getJSONObject(it)
                val hash = value.getString("hash"); val holder = value.getString("holder"); val size = value.getLong("size")
                check(HASH.matches(hash) && HASH.matches(holder) && size > 0)
                check(claims.put("$hash:$holder", Claim(hash, size, holder)) == null)
            }
        }
        // Cache bytes without invoking any native importer. A separate worker
        // leaves incoming access requests serviceable during simultaneous sync.
        caching.scheduleWithFixedDelay({
            try { cacheNext() }
            catch (error: Exception) { if (!closed) Log.w("Arachne", "RESOURCE_CACHE_RETRY", error) }
        }, 5, 5, TimeUnit.SECONDS)
    }

    /** Materialized resource metadata, independent of retained bytes and native
     * HTTP. Save before acknowledging delivery; the fabric owns replay receipts. */
    private fun updateCatalog(change: () -> Unit) = synchronized(catalogLock) {
        val previous = catalog.toMap(); val previousClaims = claims.toMap()
        try {
            change()
            // ponytail: one bounded snapshot per catalog change; use per-record
            // storage if measured catalog write volume makes this expensive.
            val value = JSONObject().put("version", 1).put("workspace", hex(workspace))
                .put("descriptors", JSONArray(catalog.values.map { item ->
                    JSONObject().put("version", 1).put("hash", item.hash).put("size", item.size)
                        .put("name", item.name).put("media_type", item.mediaType).put("author", item.author)
                        .put("published_at", item.publishedAt)
                })).put("claims", JSONArray(claims.values.map { item ->
                    JSONObject().put("hash", item.hash).put("size", item.size).put("holder", item.holder)
                })).toString().toByteArray(Charsets.UTF_8)
            check(value.size <= MAX_CATALOG_BYTES && (directory.isDirectory || directory.mkdirs()))
            val output = catalogFile.startWrite()
            try { output.write(value); catalogFile.finishWrite(output) }
            catch (error: Exception) { catalogFile.failWrite(output); throw error }
            catalogFile.openRead().use { input ->
                val saved = ByteArray(value.size)
                java.io.DataInputStream(input).readFully(saved)
                check(input.read() == -1 && saved.contentEquals(value)) { "Resource catalog readback failed" }
            }
        } catch (error: Exception) {
            catalog.clear(); catalog.putAll(previous)
            claims.clear(); claims.putAll(previousClaims)
            throw java.io.IOException("Resource catalog was not saved", error)
        }
    }

    fun members(next: List<WorkspaceMember>) {
        memberViews = next.toList()
        val ids = next.map { it.id }.toSet()
        if (ids != members) {
            members = ids
            synchronized(pending) {
                pending.values.forEach { it.result.completeExceptionally(CancellationException("Workspace membership changed")) }
                pending.clear()
            }
        }
    }

    /** Local offer only. Caller has explicit user intent to share this file in
     * this workspace. Store a snapshot; never serve the original mutable path. */
    private fun store(source: File, targetName: (String) -> String): Stored {
        check(!closed && source.isFile)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("offer-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                java.security.DigestInputStream(input, digest).use {
                    ResourceFiles.copy(it, temporary, source.length()) { !closed }
                }
            }
            val hash = hex(digest.digest())
            val target = File(directory, targetName(hash))
            if (!target.exists()) check(temporary.renameTo(target)) { "Could not save shared resource" }
            else check(hash(target) == hash) { "Stored resource is damaged" }
            return Stored(hash, target.length())
        } finally { temporary.delete() }
    }

    @Synchronized fun offer(source: File): String = store(source) { "$it.bin" }.hash

    /** Publish durable resource metadata while retaining the verified local copy. */
    @Synchronized fun offerCatalog(source: File, name: String, mediaType: String): String {
        require(name.trim().isNotEmpty() && name.trim().length <= 320 && mediaType.trim().isNotEmpty() && mediaType.trim().length <= 128)
        require(source.length() > 0)
        val stored = store(source) { "$it.bin" }
        val hash = stored.hash
        owned += hash
        check(preferences.edit().putStringSet("owned", owned.toSet()).commit())
        val publishedAt = System.currentTimeMillis()
        updateCatalog { catalog["$hash:$self"] = Descriptor(hash, stored.size, name.trim(), mediaType.trim(), self, publishedAt) }
        publishClaim(hash, stored.size)
        val descriptor = JSONObject().put("version", 1).put("hash", hash).put("size", stored.size)
            .put("name", name.trim()).put("media_type", mediaType.trim()).put("published_at", publishedAt).toString().toByteArray(Charsets.UTF_8)
        val entry = FabricCatalogEntry(unhex(hash), descriptor).wire()
        check(publish(workspace, CATALOG, entry, emptyList(), WorkspaceCurrent(
            WorkspaceCurrent.selector(CATALOG), unhex(hash), Long.MAX_VALUE
        )) { result -> if (result.isFailure) Log.w("Arachne", "RESOURCE_CATALOG_UNCONFIRMED hash=$hash") })
        return hash
    }

    @Synchronized fun offer(source: File, recipients: List<ByteArray>): DirectedOffer {
        require(recipients.isNotEmpty() && recipients.size <= 64 && recipients.all { it.size == 32 })
        val audience = recipients.map(::hex).toSet()
        require(audience.size == recipients.size && audience.all { it in members && it != self })
        val grant = ByteArray(16).also(java.security.SecureRandom()::nextBytes).let(::hex)
        val stored = store(source) { "$it-$grant.bin" }
        grants[grant] = Grant(stored.hash, audience)
        return DirectedOffer(stored.hash, grant, stored.size)
    }

    @Synchronized private fun sharedFile(requester: String, hash: String, grant: String?): File? {
        require(HASH.matches(hash))
        if (grant == null && requester != self && hash in replicas && hash !in owned && !policy(context, workspace).serveReplicas)
            return null
        if (grant != null && grants[grant]?.let { it.hash == hash && requester in it.recipients } != true)
            return null
        val file = File(directory, if (grant == null) "$hash.bin" else "$hash-$grant.bin")
        return file.takeIf(File::isFile)
    }

    private fun native(request: JSONObject): JSONObject {
        val result = CompletableFuture<JSONObject>()
        check(resourceCall(workspace, request) { reply ->
            reply.fold(result::complete, result::completeExceptionally)
        }) { "Resource workspace is unavailable" }
        return try { result.get(30, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw (error.cause as? Exception ?: error) }
    }

    private fun operation(request: JSONObject, alive: () -> Unit = {}): JSONObject {
        return awaitOperation(native(request).getLong("id"), alive)
    }

    private fun awaitOperation(id: Long, alive: () -> Unit = {}): JSONObject {
        try {
            while (!closed && !Thread.currentThread().isInterrupted) {
                val result = native(JSONObject().put("action", "poll").put("id", id))
                if (result.getString("state") != "running") return result
                ResourceFiles.requireSpace(context.noBackupFilesDir, 0)
                alive()
                Thread.sleep(250)
            }
            throw CancellationException("Resource workspace closed")
        } finally {
            resourceCall(workspace, JSONObject().put("action", "cancel").put("id", id)) { }
        }
    }

    private fun revoke(file: File? = null) {
        resourceCall(workspace, JSONObject().put("action", "revoke").apply {
            if (file != null) put("path", file.absolutePath)
        }) { }
    }

    private fun clearTransfer() {
        resourceCall(workspace, JSONObject().put("action", "clear").put("root", File(directory, "transfers").absolutePath)) { result ->
            val id = result.getOrNull()?.optLong("id") ?: return@resourceCall
            if (!closed) runCatching { reads.execute { runCatching { awaitOperation(id) }.onFailure {
                if (!closed) Log.w("Arachne", "RESOURCE_PARTIAL_CLEAR_FAILED", it)
            } } }
        }
    }

    private fun prepare(requester: String, hash: String, grant: String?, alive: () -> Unit): JSONObject {
        val file = sharedFile(requester, hash, grant) ?: return JSONObject().put("status", 404)
        val result = operation(JSONObject().put("action", "prepare").put("member", unhex(requester).json())
            .put("root", File(directory, "transfers").absolutePath).put("path", file.absolutePath), alive)
        check(sharedFile(requester, hash, grant) == file && !closed && requester in members) { "Resource was withdrawn" }
        return JSONObject().put("status", 200).put("ticket", result.getJSONObject("ticket"))
    }

    @Synchronized fun withdraw(hash: String) {
        require(HASH.matches(hash))
        check(!closed)
        val file = File(directory, "$hash.bin")
        revoke(file)
        check(!file.exists() || file.delete()) { "Could not withdraw resource" }
        if (synchronized(catalogLock) { catalog.containsKey("$hash:$self") }) {
            val key = unhex(hash)
            check(publish(workspace, CATALOG, FabricCatalogEntry(key, ByteArray(0), true).wire(), emptyList(), WorkspaceCurrent(
                WorkspaceCurrent.selector(CATALOG), key, Long.MAX_VALUE, true
            )) { result -> if (result.isFailure) Log.w("Arachne", "RESOURCE_WITHDRAW_UNCONFIRMED hash=$hash") })
            updateCatalog { catalog.remove("$hash:$self") }
        }
        owned.remove(hash); replicas.remove(hash)
        check(preferences.edit().putStringSet("owned", owned.toSet()).putStringSet("replicas", replicas.toSet()).commit())
        withdrawClaim(hash)
    }

    @Synchronized fun withdraw(offer: DirectedOffer) {
        require(HASH.matches(offer.hash) && GRANT.matches(offer.grant))
        check(!closed)
        grants.remove(offer.grant)
        val file = File(directory, "${offer.hash}-${offer.grant}.bin")
        revoke(file)
        check(!file.exists() || file.delete()) { "Could not withdraw resource" }
    }

    /** Accept or explicitly discard every resource message; malformed/late
     * requests must not pin the application's pending-object queue. */
    fun receive(publication: JSONObject, complete: (Boolean) -> Unit) {
        var accepted = true
        try {
            require(!closed && publication.getJSONArray("workspace").bytes(32).contentEquals(workspace))
            val author = hex(publication.getJSONArray("member").bytes(32))
            require(author in members && author != self)
            if (publication.getString("topic") == CATALOG) {
                receiveCatalog(publication, publication.getJSONArray("payload").let { it.bytes(it.length()) })
                return
            }
            if (publication.getString("topic") == CLAIMS) {
                receiveClaim(publication, publication.getJSONArray("payload").let { it.bytes(it.length()) }, author)
                return
            }
            val recipients = publication.getJSONArray("recipients")
            require(recipients.length() == 1 && hex(recipients.getJSONArray(0).bytes(32)) == self)
            val payload = publication.getJSONArray("payload")
            require(payload.length() in 1..12288)
            val value = JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload.bytes(payload.length()))).toString())
            require(value.get("v") == 1)
            val id = value.getString("id"); val resource = value.getString("hash")
            val grant = value.optString("grant").takeIf(String::isNotEmpty)
            require(ID.matches(id) && HASH.matches(resource) && (grant == null || GRANT.matches(grant)))
            when (publication.getString("topic")) {
                REQUEST -> {
                    require(value.keys().asSequence().toSet() == setOf("v", "id", "hash") + if (grant == null) emptySet() else setOf("grant"))
                    reads.execute {
                        if (!closed && author in members) {
                            fun reply(response: JSONObject) {
                                response.put("v", 1).put("id", id).put("hash", resource)
                                if (grant != null) response.put("grant", grant)
                                if (!closed && author in members) publish(workspace, RESPONSE, response.toString().toByteArray(Charsets.UTF_8), listOf(unhex(author)), null) { outcome ->
                                    if (outcome.isFailure) Log.w("Arachne", "RESOURCE_RESPONSE_UNCONFIRMED")
                                }
                            }
                            var nextAlive = SystemClock.elapsedRealtime() + 10_000
                            val response = try { prepare(author, resource, grant) {
                                if (SystemClock.elapsedRealtime() >= nextAlive) {
                                    reply(JSONObject().put("status", 102))
                                    nextAlive = SystemClock.elapsedRealtime() + 10_000
                                }
                            } } catch (error: Exception) {
                                Log.w("Arachne", "RESOURCE_PREPARE_FAILED", error)
                                JSONObject().put("status", 500)
                            }
                            reply(response)
                            Log.i("Arachne", "RESOURCE_REQUEST_HANDLED scope=${hex(workspace)} id=$id status=${response.getInt("status")}")
                        }
                    }
                }
                RESPONSE -> {
                    val status = value.get("status")
                    require(status in setOf(102, 200, 404, 500))
                    require(value.keys().asSequence().toSet() == setOf("v", "id", "hash", "status") +
                        (if (grant == null) emptySet() else setOf("grant")) + (if (status == 200) setOf("ticket") else emptySet()))
                    if (status == 200) {
                        val ticket = value.getJSONObject("ticket")
                        require(ticket.keys().asSequence().toSet() == setOf("hash", "size", "grant"))
                        ticket.getJSONArray("hash").bytes(32); ticket.getJSONArray("grant").bytes(32)
                        number(ticket, "size")
                    }
                    synchronized(pending) {
                        val expected = pending[id]
                        if (expected != null && expected.author == author && expected.hash == resource && expected.grant == grant) {
                            expected.seen = SystemClock.elapsedRealtime()
                            if (status != 102) { pending.remove(id); expected.result.complete(value) }
                            Log.i("Arachne", "RESOURCE_RESPONSE_ACCEPTED scope=${hex(workspace)} id=$id status=$status")
                        } else Log.i("Arachne", "RESOURCE_RESPONSE_DISCARDED unmatched=true")
                    }
                }
                else -> error("Unknown resource topic")
            }
        } catch (error: java.io.IOException) {
            accepted = false
            Log.w("Arachne", "RESOURCE_CATALOG_SAVE_FAILED", error)
        } catch (_: Exception) { Log.w("Arachne", "RESOURCE_PUBLICATION_DISCARDED invalid_or_unavailable=true") }
        finally { complete(accepted) }
    }

    private fun receiveCatalog(publication: JSONObject, encoded: ByteArray) {
        val entry = FabricCatalogEntry.read(encoded)
        val hash = entry.key.joinToString("") { "%02x".format(it.toInt() and 255) }
        val author = hex(publication.getJSONArray("member").bytes(32))
        if (entry.tombstone) {
            updateCatalog { catalog.remove("$hash:$author") }
            return
        }
        val value = JSONObject(String(entry.payload, Charsets.UTF_8))
        val item = descriptor(value, author, hash)
        updateCatalog { catalog["$hash:$author"] = item }
    }

    private fun descriptor(value: JSONObject, author: String, hash: String): Descriptor {
        require(HASH.matches(hash) && HASH.matches(author) && value.getInt("version") == 1 && value.getString("hash") == hash)
        val size = value.getLong("size")
        require(size > 0)
        val name = value.getString("name").trim()
        val mediaType = value.getString("media_type").trim()
        require(name.isNotEmpty() && name.length <= 320 && mediaType.isNotEmpty() && mediaType.length <= 128)
        val publishedAt = if (value.has("published_at")) number(value, "published_at") else 0
        require(publishedAt <= 253402300799999L)
        return Descriptor(hash, size, name, mediaType, author, publishedAt)
    }

    private fun publishClaim(hash: String, size: Long) {
        val payload = JSONObject().put("version", 1).put("hash", hash).put("size", size)
            .toString().toByteArray(Charsets.UTF_8)
        val key = claimKey(hash, self)
        publish(workspace, CLAIMS, FabricCatalogEntry(key, payload).wire(), emptyList(), WorkspaceCurrent(
            WorkspaceCurrent.selector(CLAIMS), key, Long.MAX_VALUE
        )) { result -> if (result.isFailure) Log.w("Arachne", "RESOURCE_CLAIM_UNCONFIRMED hash=$hash") }
        updateCatalog { claims["$hash:$self"] = Claim(hash, size, self) }
    }

    private fun withdrawClaim(hash: String) {
        val key = claimKey(hash, self)
        publish(workspace, CLAIMS, FabricCatalogEntry(key, ByteArray(0), true).wire(), emptyList(), WorkspaceCurrent(
            WorkspaceCurrent.selector(CLAIMS), key, Long.MAX_VALUE, true
        )) { result -> if (result.isFailure) Log.w("Arachne", "RESOURCE_CLAIM_WITHDRAW_UNCONFIRMED hash=$hash") }
        updateCatalog { claims.remove("$hash:$self") }
    }

    private fun receiveClaim(publication: JSONObject, encoded: ByteArray, author: String) {
        val entry = FabricCatalogEntry.read(encoded)
        if (entry.tombstone) {
            updateCatalog {
                claims.entries.removeIf { (key, claim) -> claim.holder == author && entry.key.contentEquals(claimKey(claim.hash, author)) }
            }
            return
        }
        val value = JSONObject(String(entry.payload, Charsets.UTF_8))
        val hash = value.getString("hash")
        val size = value.getLong("size")
        require(value.getInt("version") == 1 && HASH.matches(hash) && size > 0)
        require(entry.key.contentEquals(claimKey(hash, author)))
        updateCatalog { claims["$hash:$author"] = Claim(hash, size, author) }
    }

    // An offer belongs to its authenticated publisher; a hash only identifies
    // bytes. Merge for browsing, never for persistence or withdrawal authority.
    fun descriptors(): List<Descriptor> = synchronized(catalogLock) {
        catalog.values.filter { it.author in members }.sortedBy { it.author }.distinctBy { it.hash }
    }

    private fun reachable(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        return memberViews.filter { it.presence == "reachable" &&
            (it.reachableUntilElapsedMs == null || now < it.reachableUntilElapsedMs) }.map { it.id }.toSet()
    }

    /** Membership authorizes reads; presence only orders candidate holders.
     * Always try our verified copy before making any network request. */
    private fun fetchCatalog(item: Descriptor, connectedOnly: Boolean = false): File? {
        val connected = reachable()
        val holders = listOf(self) + synchronized(catalogLock) {
            claims.values.filter { it.hash == item.hash && it.size == item.size }.map { it.holder } +
                catalog.values.filter { it.hash == item.hash && it.size == item.size }.map { it.author }
        }
        return holders.distinct().filter { it in members && (!connectedOnly || it == self || it in connected) }
            .sortedBy { if (it == self) 0 else if (it in connected) 1 else 2 }
            .asSequence().mapNotNull { holder ->
                runCatching { fetch(path(unhex(holder), item.hash), item.size) }.getOrNull()
            }.firstOrNull()
    }

    private fun cacheNext() {
        if (closed) return
        synchronized(this) { uploads.entries.removeIf { (_, item) ->
            if (SystemClock.elapsedRealtime() < item.expires) false else { item.file.delete(); true }
        } }
        cacheRoom(0, true) // Apply a reduced quota without touching owned packages.
        val policy = policy(context, workspace)
        val holders = synchronized(this) { owned.toSet() + if (policy.serveReplicas) replicas.toSet() else emptySet() }
        val claimed = synchronized(catalogLock) { claims.values.filter { it.holder == self }.map { it.hash }.toSet() }
        for (hash in claimed - holders) withdrawClaim(hash)
        for (hash in holders - claimed) File(directory, "$hash.bin").takeIf { it.isFile }?.let { publishClaim(hash, it.length()) }
        if (policy.retention == Retention.OFF) return
        val selected = preferences.getStringSet("selected", emptySet()).orEmpty()
        val excluded = preferences.getStringSet("excluded", emptySet()).orEmpty()
        val available = reachable()
        if (available.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val entries = descriptors()
        cacheRetryAt.keys.retainAll(entries.map { it.hash }.toSet())
        val item = entries.asReversed().firstOrNull { entry ->
            entry.hash !in excluded && (policy.retention == Retention.AUTOMATIC || entry.hash in selected) &&
                !File(directory, "${entry.hash}.bin").isFile && now >= (cacheRetryAt[entry.hash] ?: 0L) &&
                cacheRoom(entry.size, false) && (entry.author in available || synchronized(catalogLock) {
                    claims.values.any { it.hash == entry.hash && it.size == entry.size && it.holder in available }
                })
        } ?: return
        cacheRetryAt[item.hash] = now + 30_000L
        val source = fetchCatalog(item, connectedOnly = true) ?: return
        try {
            if (!closed && item in descriptors() &&
                retainReplica(source, item.hash, item.size, evict = false))
                Log.i("Arachne", "RESOURCE_REPLICA_CACHED hash=${item.hash} size=${item.size} automatic=true")
        } finally { source.delete() }
    }

    /** TAK-compatible read/search projection over the fabric catalog. */
    fun nativeApi(method: String, requestUri: String, body: java.io.InputStream?, contentType: String?): LocalTakHttp.Response? {
        if (!requestUri.startsWith("/Marti/")) return null
        val uri = android.net.Uri.parse(requestUri)
        val path = uri.path ?: return null
        if ((path.startsWith("/Marti/sync/") || path.startsWith("/Marti/api/sync/")) && !policy(context, workspace).server)
            return LocalTakHttp.Response(503, "text/plain", "Workspace package server is disabled in Arachne settings.".toByteArray())
        return when {
            method == "GET" && path == "/Marti/api/version" ->
                LocalTakHttp.Response(200, "text/plain", "Arachne".toByteArray())
            method == "GET" && path == "/Marti/api/version/config" ->
                LocalTakHttp.Response(200, "application/json", JSONObject().put("version", 2)
                    .put("type", "ServerConfig").put("data", JSONObject().put("version", "Arachne").put("api", "2").put("hostname", "Arachne"))
                    .toString().toByteArray(Charsets.UTF_8))
            method == "GET" && path == "/Marti/api/clientEndPoints" ->
                LocalTakHttp.Response(200, "application/json", JSONObject()
                    .put("type", "com.bbn.marti.remote.ClientEndpoint").put("data", JSONArray())
                    .toString().toByteArray(Charsets.UTF_8))
            method == "GET" && path == "/Marti/sync/search" -> {
                val results = descriptors().filter { uri.getQueryParameter("keywords").isNullOrBlank() || uri.getQueryParameter("keywords")!!.equals("missionpackage", true) }
                    .mapIndexed { index, item ->
                        JSONObject().put("UID", item.hash).put("Name", item.name).put("Hash", item.hash)
                            .put("PrimaryKey", index).put("SubmissionDateTime", if (item.publishedAt == 0L) "Unknown" else
                                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                                }.format(java.util.Date(item.publishedAt)))
                            .put("SubmissionUser", memberViews.find { it.id == item.author }?.name ?: item.author.take(12))
                            .put("CreatorUid", item.author)
                            .put("Keywords", "missionpackage").put("MIMEType", item.mediaType).put("Size", item.size)
                    }
                LocalTakHttp.Response(200, "application/json", JSONObject().put("resultCount", results.size).put("results", JSONArray(results)).toString().toByteArray(Charsets.UTF_8))
            }
            method == "GET" && path == "/Marti/sync/missionquery" -> {
                val hash = uri.getQueryParameter("hash") ?: return null
                require(HASH.matches(hash))
                val item = descriptors().find { it.hash == hash } ?: return LocalTakHttp.Response(404, "text/plain")
                LocalTakHttp.Response(200, "text/plain", "/Marti/sync/content?hash=${item.hash}".toByteArray(Charsets.UTF_8))
            }
            method == "GET" && path == "/Marti/sync/content" -> {
                val hash = uri.getQueryParameter("hash") ?: return null
                require(HASH.matches(hash))
                val item = descriptors().find { it.hash == hash } ?: return LocalTakHttp.Response(404, "text/plain")
                val offset = uri.getQueryParameter("offset")?.let { requireNotNull(it.toLongOrNull()) } ?: 0L
                val length = uri.getQueryParameter("length")?.let { requireNotNull(it.toLongOrNull()).also { n -> require(n > 0) } }
                if (offset !in 0..item.size) return LocalTakHttp.Response(416, "text/plain")
                val source = fetchCatalog(item) ?: return LocalTakHttp.Response(404, "text/plain")
                try { retainReplica(source, item.hash, item.size) }
                catch (error: Exception) { source.delete(); throw error }
                val count = minOf(length ?: item.size, item.size - offset)
                val partial = length != null && count < item.size - offset
                if (offset == 0L && count == item.size) LocalTakHttp.Response(200, item.mediaType, file = source)
                else {
                    val served = File.createTempFile("arachne-resource-range-", ".tmp", context.cacheDir)
                    try {
                        RandomAccessFile(source, "r").use { input -> served.outputStream().use { output ->
                            input.seek(offset)
                            val buffer = ByteArray(8192)
                            var remaining = count
                            while (remaining > 0) {
                                val n = minOf(remaining, buffer.size.toLong()).toInt()
                                input.readFully(buffer, 0, n); output.write(buffer, 0, n); remaining -= n
                            }
                        } }
                        LocalTakHttp.Response(if (partial) 206 else 200, item.mediaType, file = served)
                    } catch (error: Exception) { served.delete(); throw error }
                    finally { source.delete() }
                }
            }
            method == "POST" && path == "/Marti/sync/missionupload" -> {
                val tool = uri.getQueryParameter("tool")
                if (tool != null && tool != "public") return LocalTakHttp.Response(403, "text/plain")
                val temporary = File.createTempFile("arachne-upload-", ".bin", context.cacheDir)
                try {
                    val uploaded = multipart(body ?: return LocalTakHttp.Response(400, "text/plain"), contentType, temporary)
                        ?: return LocalTakHttp.Response(400, "text/plain")
                    uri.getQueryParameter("hash")?.let { require(it == hash(temporary)) { "Upload hash mismatch" } }
                    val hash = synchronized(this) {
                        require(uploads.size < 8) { "Too many unfinished uploads" }
                        val stored = store(temporary) { "$it.upload" }
                        uploads[stored.hash] = StagedUpload(File(directory, "${stored.hash}.upload"), uploaded.name,
                            uploaded.mediaType, SystemClock.elapsedRealtime() + 300_000)
                        if (tool == "public") publishUpload(stored.hash)
                        stored.hash
                    }
                    LocalTakHttp.Response(200, "text/plain", "/Marti/sync/content?hash=$hash".toByteArray(Charsets.UTF_8))
                } finally { temporary.delete() }
            }
            method == "PUT" && Regex("^/Marti/api/sync/metadata/[a-f0-9]{64}/tool$").matches(path) -> {
                val hash = path.substringAfterLast("/metadata/").substringBeforeLast("/tool")
                val input = body ?: return LocalTakHttp.Response(400, "text/plain")
                val bytes = ByteArray(129)
                var count = 0
                while (count < bytes.size) {
                    val n = input.read(bytes, count, bytes.size - count)
                    if (n < 0) break
                    count += n
                }
                require(count < bytes.size) { "Invalid metadata value." }
                val tool = String(bytes, 0, count, Charsets.UTF_8)
                synchronized(this) {
                    if (tool != "public") {
                        uploads.remove(hash)?.file?.delete()
                        // No private-server or recall semantics: never claim a
                        // workspace-wide publication can be made private again.
                        LocalTakHttp.Response(403, "text/plain")
                    } else if (uploads.containsKey(hash)) {
                        publishUpload(hash); LocalTakHttp.Response(200, "text/plain")
                    } else LocalTakHttp.Response(if (descriptors().any { it.hash == hash }) 200 else 404, "text/plain")
                }
            }
            else -> null
        }
    }

    @Synchronized private fun publishUpload(hash: String) {
        val item = checkNotNull(uploads[hash])
        check(offerCatalog(item.file, item.name, item.mediaType) == hash)
        uploads.remove(hash); item.file.delete()
    }

    private data class Upload(val name: String, val mediaType: String)

    private fun multipart(body: java.io.InputStream, contentType: String?, target: File): Upload? {
        val boundary = contentType?.substringAfter("boundary=", "")?.trim()?.trim('"') ?: return null
        require(boundary.length in 1..70 && boundary.all { it.code in 32..126 })
        // The host already supplies OkHttp's bounded streaming multipart parser.
        // Accept the native upload's single file part, not arbitrary form fields.
        return okhttp3.MultipartReader(body.source().buffer(), boundary).use { reader ->
            val part = reader.nextPart() ?: return null
            val disposition = part.headers["Content-Disposition"].orEmpty()
            require(Regex("(?:^|;)\\s*name=\"assetfile\"(?:;|$)").containsMatchIn(disposition))
            val name = Regex("filename=\"([^\"]+)\"").find(disposition)?.groupValues?.get(1)
                ?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.takeIf { it.isNotEmpty() } ?: "package.zip"
            val mediaType = part.headers["Content-Type"] ?: "application/x-zip-compressed"
            require(name.length <= 320 && mediaType.length <= 128)
            part.use { require(ResourceFiles.copy(it.body.inputStream(), target) { !closed } > 0) }
            require(reader.nextPart() == null) { "Expected one package file." }
            Upload(name, mediaType)
        }
    }

    /** Retain verified bytes, never install them. Background fill cannot evict
     * another cached package and cycle forever when the catalog exceeds quota. */
    @Synchronized internal fun retainReplica(source: File, hash: String, size: Long, evict: Boolean = true): Boolean {
        val target = File(directory, "$hash.bin")
        if (target.isFile && target.length() == size && hash(target) == hash) return true
        val policy = policy(context, workspace)
        if (closed || policy.retention == Retention.OFF ||
            (policy.retention == Retention.SELECTED && hash !in preferences.getStringSet("selected", emptySet()).orEmpty()) ||
            hash in preferences.getStringSet("excluded", emptySet()).orEmpty()) return false
        require(descriptors().any { it.hash == hash && it.size == size }) { "Only public catalog resources may become replicas" }
        require(HASH.matches(hash) && size > 0 && source.length() == size && hash(source) == hash)
        check(directory.isDirectory || directory.mkdirs()) { "Resource cache is unavailable" }
        if (!cacheRoom(size, evict)) return false
        val temporary = File.createTempFile("replica-", ".tmp", directory)
        try {
            source.inputStream().use { ResourceFiles.copy(it, temporary, size) { !closed } }
            if (temporary.length() == size && temporary.renameTo(target)) {
                replicas += hash
                check(preferences.edit().putStringSet("replicas", replicas.toSet()).commit())
                if (policy.serveReplicas) publishClaim(hash, size)
                return true
            }
            return false
        } finally { temporary.delete() }
    }

    @Synchronized private fun cacheRoom(size: Long, evict: Boolean): Boolean {
        val quota = preferences.getLong("quota", DEFAULT_QUOTA)
        var used = directory.listFiles()?.filter {
            val hash = it.name.removeSuffix(".bin")
            it.isFile && it.name.endsWith(".bin") && HASH.matches(hash) && hash !in owned
        }?.sumOf { it.length() } ?: 0L
        if (size < 0 || size > quota || size > context.noBackupFilesDir.usableSpace / 4) return false
        while (used > quota - size) {
            if (!evict) return false
            val victim = replicas.asSequence().filter { it !in owned }.map { File(directory, "$it.bin") }.filter { it.isFile }
                .minByOrNull(File::lastModified) ?: return false
            val victimHash = victim.name.removeSuffix(".bin")
            val victimSize = victim.length()
            revoke(victim)
            if (!victim.delete()) return false
            used -= victimSize
            replicas.remove(victimHash)
            check(preferences.edit().putStringSet("replicas", replicas.toSet()).commit())
            withdrawClaim(victimHash)
        }
        return true
    }

    @Synchronized fun cacheEntries(): List<CacheEntry> {
        val selected = preferences.getStringSet("selected", emptySet()).orEmpty()
        val entries = descriptors().associateBy { it.hash }
        return (entries.keys + replicas + owned).map { hash ->
            val item = entries[hash]; val file = File(directory, "$hash.bin")
            CacheEntry(hash, item?.name ?: "Resource ${hash.take(12)}", item?.size ?: file.length(),
                file.isFile, hash in owned, hash in selected)
        }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    fun policyChanged() {
        revoke()
        clearTransfer()
        if (!closed) runCatching { caching.execute { runCatching { cacheNext() }.onFailure {
            if (!closed) Log.w("Arachne", "RESOURCE_POLICY_RETRY", it)
        } } }
    }

    @Synchronized fun select(hash: String, selected: Boolean) {
        require(HASH.matches(hash) && !closed)
        val choices = preferences.getStringSet("selected", emptySet()).orEmpty().toMutableSet()
        val excluded = preferences.getStringSet("excluded", emptySet()).orEmpty().toMutableSet()
        if (selected) { choices += hash; excluded -= hash } else choices -= hash
        check(preferences.edit().putStringSet("selected", choices).putStringSet("excluded", excluded).commit())
        policyChanged()
    }

    /** Only our private replicas are cache. Published resources and ATAK's
     * installed packages are never targets of these device-local operations. */
    fun clearCache(completed: (Result<Unit>) -> Unit) {
        revoke()
        clearTransfer()
        cacheOperation(completed) {
            check(savePolicy(context, workspace, policy(context, workspace).copy(retention = Retention.OFF)))
            synchronized(this) { (replicas - owned).toList().forEach(::removeReplica) }
        }
    }

    fun removeCached(hash: String, completed: (Result<Unit>) -> Unit) = cacheOperation(completed) {
        synchronized(this) {
            require(HASH.matches(hash) && hash !in owned && hash in replicas)
            select(hash, false)
            val excluded = preferences.getStringSet("excluded", emptySet()).orEmpty() + hash
            check(preferences.edit().putStringSet("excluded", excluded).commit())
            removeReplica(hash)
        }
    }

    private fun cacheOperation(completed: (Result<Unit>) -> Unit, operation: () -> Unit) {
        try { caching.execute { completed(runCatching { check(!closed); operation() }) } }
        catch (error: java.util.concurrent.RejectedExecutionException) { completed(Result.failure(error)) }
    }

    @Synchronized private fun removeReplica(hash: String) {
        require(hash !in owned && hash in replicas)
        val file = File(directory, "$hash.bin")
        revoke(file)
        check(!file.exists() || file.delete()) { "Cached file could not be removed" }
        replicas.remove(hash)
        check(preferences.edit().putStringSet("replicas", replicas.toSet()).commit())
        withdrawClaim(hash)
    }

    private fun request(author: String, hash: String, grant: String?, alive: () -> Unit): JSONObject {
        if (closed || author !in members) throw CancellationException("Resource holder unavailable")
        val id = UUID.randomUUID().toString().replace("-", "")
        val result = CompletableFuture<JSONObject>()
        val expected = Pending(author, hash, grant, result)
        synchronized(pending) {
            check(!closed && pending.size < 32) { "Resource request queue unavailable" }
            pending[id] = expected
        }
        try {
            val value = JSONObject().put("v", 1).put("id", id).put("hash", hash)
            if (grant != null) value.put("grant", grant)
            val queued = publish(workspace, REQUEST, value.toString().toByteArray(Charsets.UTF_8), listOf(unhex(author)), null) { outcome ->
                val failed = outcome.isFailure || runCatching { WorkspaceData.outcome(outcome.getOrThrow()).remoteAccepted == 0 }.getOrDefault(true)
                if (failed) result.completeExceptionally(IllegalStateException("Resource request was not confirmed"))
            }
            if (!queued) throw IllegalStateException("Resource request was not queued")
            while (!closed && author in members) {
                alive()
                try { return result.get(1, TimeUnit.SECONDS) }
                catch (error: TimeoutException) {
                    if (SystemClock.elapsedRealtime() - expected.seen >= 60_000) throw error
                }
                catch (error: ExecutionException) { throw (error.cause as? Exception ?: error) }
            }
            throw CancellationException("Resource holder unavailable")
        } finally { synchronized(pending) { if (pending[id] === expected) pending.remove(id) } }
    }

    private fun requestAccess(author: String, hash: String, grant: String?, alive: () -> Unit): JSONObject {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                return request(author, hash, grant, alive).also {
                    check(it.getInt("status") != 500) { "Resource holder failed" }
                }
            } catch (error: Exception) {
                if (error is CancellationException || (error !is TimeoutException && error !is IllegalStateException)) throw error
                failure = error
                if (attempt == 2) throw error
                Log.i("Arachne", "RESOURCE_ACCESS_RETRY attempt=${attempt + 2}")
            }
        }
        throw checkNotNull(failure)
    }

    /** Native HTTP adapter: URI contains only an explicit holder and content
     * hash. Verify the entire download before HTTP 200, so a bad hash cannot
     * appear as a successful native file. The HTTP caller owns/deletes the temp. */
    fun fetch(path: String, expectedSize: Long? = null, alive: () -> Unit = {}): File? {
        val match = PATH.matchEntire(path) ?: return null
        require(expectedSize == null || expectedSize >= 0)
        val author = match.groupValues[1]; val expectedHash = match.groupValues[2]
        val grant = match.groupValues[3].takeIf(String::isNotEmpty)
        if (closed || author !in members) return null
        val temporary = File.createTempFile("arachne-resource-", ".tmp", context.cacheDir)
        var success = false
        try {
            val started = SystemClock.elapsedRealtime()
            if (author == self) {
                val file = sharedFile(self, expectedHash, grant) ?: return null
                check(expectedSize == null || file.length() == expectedSize) { "Resource length changed" }
                file.inputStream().use { ResourceFiles.copy(it, temporary, file.length()) { alive(); !closed } }
            } else {
                val response = requestAccess(author, expectedHash, grant, alive)
                if (response.getInt("status") == 404) return null
                check(response.getInt("status") == 200)
                val ticket = response.getJSONObject("ticket")
                val size = number(ticket, "size")
                check(expectedSize == null || size == expectedSize) { "Resource length differs from its descriptor" }
                // Bao outboards are ~0.4% of payload. This is a disk reservation,
                // not a file-size ceiling; memory is bounded inside the native engine.
                ResourceFiles.requireSpace(context.noBackupFilesDir, Math.addExact(size, size / 128))
                val result = operation(JSONObject().put("action", "fetch").put("member", unhex(author).json())
                    .put("root", File(directory, "transfers").absolutePath).put("path", temporary.absolutePath).put("ticket", ticket), alive)
                check(result.getString("state") == "complete" && temporary.length() == size)
            }
            check(hash(temporary, alive) == expectedHash) { "Resource content hash mismatch" }
            check(!closed && author in members) { "Resource workspace is no longer available" }
            Log.i("Arachne", "RESOURCE_STREAM_COMPLETE hash=$expectedHash size=${temporary.length()} elapsed_ms=${SystemClock.elapsedRealtime() - started} local=${author == self}")
            success = true
            return temporary
        } finally {
            if (!success) temporary.delete()
        }
    }

    override fun close() {
        closed = true
        caching.shutdownNow()
        reads.shutdownNow()
        revoke()
        synchronized(this) { uploads.values.forEach { it.file.delete() }; uploads.clear() }
        synchronized(pending) {
            pending.values.forEach { it.result.completeExceptionally(CancellationException("Workspace closed")) }
            pending.clear()
        }
        synchronized(this) { grants.clear() }
    }

    companion object {
        const val REQUEST = "resources/transfer/v1/requests"
        const val RESPONSE = "resources/transfer/v1/responses"
        const val CATALOG = "resources/catalog/v1"
        const val CLAIMS = "resources/replicas/v1"
        val topics = setOf(REQUEST, RESPONSE, CATALOG, CLAIMS)
        private const val DEFAULT_QUOTA = 256L * 1024 * 1024
        private const val MAX_CATALOG_BYTES = 4L * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        private val GRANT = Regex("[a-f0-9]{32}")
        private val ID = Regex("[a-f0-9]{32}")
        private val DIRECTED_FILE = Regex("[a-f0-9]{64}-[a-f0-9]{32}\\.bin")
        private val PATH = Regex("/Marti/arachne/resources/([a-f0-9]{64})/([a-f0-9]{64})(?:/([a-f0-9]{32}))?")
        fun path(member: ByteArray, hash: String, grant: String? = null): String {
            require(member.size == 32 && HASH.matches(hash) && (grant == null || GRANT.matches(grant)))
            return "/Marti/arachne/resources/${hex(member)}/$hash" + if (grant == null) "" else "/$grant"
        }
        private fun preferences(context: Context, workspace: ByteArray) =
            context.getSharedPreferences("arachne-resource-cache-${hex(workspace)}", Context.MODE_PRIVATE)
        fun policy(context: Context, workspace: ByteArray): Policy {
            val saved = preferences(context, workspace)
            val retention = if (!saved.getBoolean("retain", true)) Retention.OFF else
                runCatching { Retention.valueOf(saved.getString("mode", "AUTOMATIC")!!) }.getOrDefault(Retention.AUTOMATIC)
            return Policy(saved.getBoolean("server", true), retention, saved.getLong("quota", DEFAULT_QUOTA), saved.getBoolean("serve", true))
        }
        fun savePolicy(context: Context, workspace: ByteArray, policy: Policy): Boolean {
            require(policy.quota in 0..1024L * 1024 * 1024)
            return preferences(context, workspace).edit().putBoolean("server", policy.server)
                .putBoolean("retain", policy.retention != Retention.OFF).putString("mode", policy.retention.name)
                .putLong("quota", policy.quota).putBoolean("serve", policy.serveReplicas).commit()
        }
        fun usage(context: Context, workspace: ByteArray): Usage {
            val owned = preferences(context, workspace).getStringSet("owned", emptySet()).orEmpty()
            val files = File(context.noBackupFilesDir, "data-fabric/resources/${hex(workspace)}").listFiles().orEmpty()
                .filter { it.isFile && HASH.matches(it.name.removeSuffix(".bin")) && it.name.endsWith(".bin") }
            return Usage(files.filter { it.name.removeSuffix(".bin") !in owned }.sumOf { it.length() },
                files.filter { it.name.removeSuffix(".bin") in owned }.sumOf { it.length() }, context.noBackupFilesDir.usableSpace)
        }
        fun retentionEnabled(context: Context, workspace: ByteArray) = policy(context, workspace).retention != Retention.OFF
        fun cacheQuotaBytes(context: Context, workspace: ByteArray) = preferences(context, workspace).getLong("quota", DEFAULT_QUOTA)
        fun setRetention(context: Context, workspace: ByteArray, enabled: Boolean): Boolean =
            savePolicy(context, workspace, policy(context, workspace).copy(retention = if (enabled) Retention.AUTOMATIC else Retention.OFF))
        fun setCacheQuotaBytes(context: Context, workspace: ByteArray, bytes: Long): Boolean {
            require(bytes in 0..(1024L * 1024 * 1024))
            return preferences(context, workspace).edit().putLong("quota", bytes).commit()
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun claimKey(hash: String, holder: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest((hash + holder).toByteArray(Charsets.UTF_8))
        private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun ByteArray.json() = JSONArray(map { it.toInt() and 255 })
        private fun JSONArray.bytes(size: Int): ByteArray {
            require(length() == size)
            return ByteArray(size) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
        }
        private fun number(value: JSONObject, name: String): Long {
            val raw = value.get(name)
            require(raw is Int || raw is Long)
            return (raw as Number).toLong().also { require(it >= 0) }
        }
        private fun hash(file: File, alive: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { alive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return hex(digest.digest())
        }
    }
}
