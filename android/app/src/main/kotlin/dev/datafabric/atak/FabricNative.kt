package dev.arachne.atak

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Blocking entrypoints: call only on the session worker, never the UI thread. */
internal object FabricNative {
    fun load(path: String) { System.load(path) }
    external fun inspectInvitation(request: ByteArray): ByteArray
    external fun create(secret: ByteArray, relayOnly: Boolean, lanLookup: Boolean, localOnly: Boolean): Long
    external fun describe(handle: Long): String
    external fun execute(handle: Long, request: ByteArray): ByteArray
    external fun executeStored(handle: Long, metadata: ByteArray, snapshot: ByteArray): Array<ByteArray>
    external fun enableRecords(handle: Long, path: String, root: ByteArray)
    external fun restoreRecords(handle: Long, path: String, root: ByteArray, workspace: ByteArray): String
    external fun saveCandidate(handle: Long, token: ByteArray)

    /** Blocks until the session may have work. Waiter thread only. */
    external fun waitForWork(handle: Long): Boolean
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}

/** Use Android's package classloader for JNI ownership, independently of ATAK's
 * replaceable plugin classloader. Only bootstrap JVM values cross this seam. */
internal class NativeAccess(context: Context) {
    private val packageContext = context.createPackageContext(
        "dev.arachne.atak", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
    )
    private val type = packageContext.classLoader.loadClass("dev.arachne.atak.FabricNative")
    private val instance = type.getField("INSTANCE").get(null)

    init {
        val info = context.packageManager.getApplicationInfo("dev.arachne.atak", 0)
        call("load", arrayOf(String::class.java), File(info.nativeLibraryDir, "libfabric_android.so").absolutePath)
    }

    private fun call(name: String, types: Array<Class<*>>, vararg args: Any): Any? = try {
        type.getMethod(name, *types).invoke(instance, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    fun inspectInvitation(request: ByteArray): ByteArray = call("inspectInvitation", arrayOf(ByteArray::class.java), request) as ByteArray
    fun create(secret: ByteArray, relayOnly: Boolean, lanLookup: Boolean, localOnly: Boolean = false): Long = call(
        "create",
        arrayOf(ByteArray::class.java, Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
        secret,
        relayOnly,
        lanLookup,
        localOnly,
    ) as Long
    fun describe(handle: Long): String = call("describe", arrayOf(Long::class.javaPrimitiveType!!), handle) as String
    fun execute(handle: Long, request: ByteArray): ByteArray =
        call("execute", arrayOf(Long::class.javaPrimitiveType!!, ByteArray::class.java), handle, request) as ByteArray
    @Suppress("UNCHECKED_CAST")
    fun executeStored(handle: Long, metadata: ByteArray, snapshot: ByteArray): Array<ByteArray> =
        call("executeStored", arrayOf(Long::class.javaPrimitiveType!!, ByteArray::class.java, ByteArray::class.java),
            handle, metadata, snapshot) as Array<ByteArray>
    fun enableRecords(handle: Long, path: String, root: ByteArray) {
        call("enableRecords", arrayOf(Long::class.javaPrimitiveType!!, String::class.java, ByteArray::class.java), handle, path, root)
    }
    fun restoreRecords(handle: Long, path: String, root: ByteArray, workspace: ByteArray): String =
        call("restoreRecords", arrayOf(Long::class.javaPrimitiveType!!, String::class.java, ByteArray::class.java, ByteArray::class.java), handle, path, root, workspace) as String
    fun saveCandidate(handle: Long, token: ByteArray) {
        call("saveCandidate", arrayOf(Long::class.javaPrimitiveType!!, ByteArray::class.java), handle, token)
    }
    fun waitForWork(handle: Long): Boolean =
        call("waitForWork", arrayOf(Long::class.javaPrimitiveType!!), handle) as Boolean
    fun cancel(handle: Long) { call("cancel", arrayOf(Long::class.javaPrimitiveType!!), handle) }
    fun close(handle: Long) { call("close", arrayOf(Long::class.javaPrimitiveType!!), handle) }
}

/** Verify a link without allocating an endpoint, membership or saved join. Call off the UI thread. */
internal fun inspectWorkspaceInvitation(context: Context, request: ByteArray): org.json.JSONObject =
    org.json.JSONObject(String(NativeAccess(context).inspectInvitation(request), Charsets.UTF_8))

/** Resolve compact bootstrap state with a fresh unlinkable endpoint. The returned
 * checkpoint still requires the normal Rust invitation verification before use. */
internal fun hydrateWorkspaceInvitation(context: Context, invitation: org.json.JSONObject): org.json.JSONObject {
    if (invitation.has("checkpoint")) return org.json.JSONObject(invitation.toString())
    val owned = org.json.JSONObject(invitation.toString())
    val bridge = NativeAccess(context)
    val secret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    var handle = 0L
    try {
        handle = bridge.create(secret, false, true)
        val request = org.json.JSONObject().put("op", "fetch_invitation_checkpoint")
            .put("peers", org.json.JSONArray(WorkspaceInvitation.bootstrapPeers(owned).map {
                org.json.JSONArray(it.map { byte -> byte.toInt() and 255 })
            }))
            .put("invitation", owned.getJSONArray("invitation"))
        val fetched = org.json.JSONObject(String(bridge.execute(handle,
            request.toString().toByteArray(Charsets.UTF_8)), Charsets.UTF_8))
        owned.put("workspace", fetched.getJSONArray("workspace"))
            .put("checkpoint", fetched.getJSONArray("checkpoint"))
            .put("peer", fetched.getJSONArray("peer"))
        return owned
    } finally {
        secret.fill(0)
        if (handle != 0L) bridge.close(handle)
    }
}

/** Each plugin start owns one session; old cleanup cannot touch a new handle. */
internal class FabricSession(
    context: Context,
    identitySlot: String,
    private val lanOnly: Boolean = false,
    private val status: (String) -> Unit,
) : AutoCloseable {
    private val worker = ScheduledThreadPoolExecutor(1).apply {
        // Android schedules execute() as zero-delay tasks. Keep admitted work
        // during shutdown; cancel only the periodic poll.
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }
    // Bounds queued + executing requests; the single periodic poll cannot pile up.
    private val requests = Semaphore(64)
    private var closed = false
    private var consumer: ((ByteArray) -> Unit)? = null
    // Confined to worker. close queues behind startup, even if startup is pending.
    @Volatile private var handle = 0L
    @Volatile private var native: NativeAccess? = null
    private var identity: EndpointIdentity? = null
    private var eventPoll: ScheduledFuture<*>? = null
    @Volatile private var workListener: (() -> Unit)? = null

    /** Called on the waiter thread each time the runtime reports work. */
    fun onWork(listener: () -> Unit) { workListener = listener }

    init {
        worker.execute {
            try {
                val bridge = NativeAccess(context)
                native = bridge
                val credential = EndpointIdentity.open(context, identitySlot)
                identity = credential
                val relayOnly = BuildConfig.DEBUG && File(context.noBackupFilesDir, "arachne-relay-only").isFile
                val wanOnly = BuildConfig.DEBUG && File(context.noBackupFilesDir, "arachne-wan-only").isFile
                check(!relayOnly || !wanOnly) { "Conflicting diagnostic network profiles" }
                if (relayOnly) Log.w("Arachne", "NATIVE_RELAY_ONLY_PROFILE")
                if (wanOnly) Log.w("Arachne", "NATIVE_WAN_ONLY_PROFILE")
                try {
                    handle = if (lanOnly) bridge.create(credential.secret, false, false, true)
                    else bridge.create(credential.secret, relayOnly, !relayOnly && !wanOnly)
                }
                finally { credential.secret.fill(0) }
                val waited = handle
                Thread({
                    try {
                        while (bridge.waitForWork(waited)) {
                            ArachneTrace.mark("work_signal")
                            workListener?.invoke()
                        }
                    } catch (_: IllegalStateException) {
                        // Closed handle: the runtime threw instead of returning false.
                    }
                    Log.i("Arachne", "NATIVE_WORK_WAITER_STOPPED handle=$waited")
                }, "arachne-work-$waited").apply { isDaemon = true }.start()
            } catch (error: Exception) {
                Log.e("Arachne", "NATIVE_NODE_FAILED", error)
                status("Fabric engine could not start. Saved identity was not reset.\nCheck device storage and restart ATAK; recovery may be required.")
            } catch (error: LinkageError) {
                Log.e("Arachne", "NATIVE_NODE_LOAD_FAILED", error)
                status("Fabric engine unavailable. Restart ATAK to load the native engine.\n\nNo workspace connected.")
            } finally {
                if (handle == 0L) {
                    identity?.close()
                    identity = null
                }
            }
        }
        // Exercise the same asynchronous request path used by the adapter. No
        // policy is installed and no event consumer drains messages by default.
        check(request("{\"op\":\"poll\"}".toByteArray(Charsets.UTF_8)) { result ->
            result.onSuccess { response ->
                check(response.contentEquals("null".toByteArray(Charsets.UTF_8)))
                Log.i("Arachne", "NATIVE_API_READY handle=$handle")
                Log.i("Arachne", "NATIVE_NODE_STARTED handle=$handle ${checkNotNull(native).describe(handle)}")
                status("Fabric engine running.\nNo workspace connected.\n\nShared workspace connections are not available yet.")
            }.onFailure { error -> Log.e("Arachne", "NATIVE_API_FAILED", error) }
        })
        eventPoll = worker.scheduleWithFixedDelay({
            // The consumer is confined to this worker. Without one, leave events
            // in the Rust bounded queue instead of silently throwing them away.
            val receive = consumer ?: return@scheduleWithFixedDelay
            if (handle == 0L) return@scheduleWithFixedDelay
            try {
                repeat(32) {
                    val event = checkNotNull(native).execute(handle, POLL)
                    if (event.contentEquals(EMPTY)) return@scheduleWithFixedDelay
                    receive(event)
                }
            } catch (error: Exception) {
                Log.e("Arachne", "NATIVE_EVENT_FAILED", error)
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
    }

    /** Callback runs on the session worker. False means not admitted (closed,
     * oversized or full); true requires inspecting the eventual Result. Copy
     * before enqueueing so the caller cannot mutate an admitted request. */
    @Synchronized
    fun request(bytes: ByteArray, complete: (Result<ByteArray>) -> Unit): Boolean {
        if (closed || bytes.size > 128 * 1024 || !requests.tryAcquire()) return false
        val owned = bytes.copyOf()
        val queued = System.nanoTime()
        worker.execute {
            try {
                val started = System.nanoTime()
                val result = runCatching {
                    check(handle != 0L) { "Fabric engine is not running" }
                    checkNotNull(native).execute(handle, owned)
                }
                if (BuildConfig.DEBUG) ArachneTrace.timed(traceOp(owned), started - queued, System.nanoTime() - started)
                try { complete(result) }
                catch (error: Exception) { Log.e("Arachne", "NATIVE_CALLBACK_FAILED", error) }
            } finally { requests.release() }
        }
        return true
    }

    /** Operation name only, for debug timing. Request contents are never kept. */
    private fun traceOp(request: ByteArray): String =
        runCatching { org.json.JSONObject(String(request, Charsets.UTF_8)).optString("op").take(64) }.getOrNull()?.ifBlank { null } ?: "unknown"

    /** Binary storage transfer, owned by the same bounded session worker. */
    @Synchronized
    fun requestStored(metadata: ByteArray, snapshot: ByteArray, complete: (Result<Array<ByteArray>>) -> Unit): Boolean {
        if (closed || metadata.size > 128 * 1024 || snapshot.size > MAX_STORED_SNAPSHOT || !requests.tryAcquire()) return false
        val ownedMetadata = metadata.copyOf()
        val ownedSnapshot = snapshot.copyOf()
        val queued = System.nanoTime()
        worker.execute {
            try {
                val started = System.nanoTime()
                val result = runCatching {
                    check(handle != 0L) { "Fabric engine is not running" }
                    executeStored(ownedMetadata, ownedSnapshot)
                }
                if (BuildConfig.DEBUG) ArachneTrace.timed(traceOp(ownedMetadata), started - queued, System.nanoTime() - started)
                try { complete(result) }
                catch (error: Exception) { Log.e("Arachne", "NATIVE_CALLBACK_FAILED", error) }
            } finally { requests.release() }
        }
        return true
    }

    /** Confined to the existing worker and credential lease. Root bytes never
     * enter JSON and are cleared on both sides of JNI after the storage call. */
    private fun executeStored(metadata: ByteArray, snapshot: ByteArray): Array<ByteArray> {
        val request = org.json.JSONObject(String(metadata, Charsets.UTF_8))
        val bridge = checkNotNull(native)
        val operation = request.optString("op")
        // Standard Android tracing records operation cost only while a profiler
        // is attached; never include request contents or storage credentials.
        android.os.Trace.beginSection("Arachne/${operation.take(96)}")
        try {
        if (operation == "save_candidate") {
            bridge.saveCandidate(handle, snapshot)
            return arrayOf("{\"durable\":true}".toByteArray(Charsets.UTF_8), ByteArray(0))
        }
        if (operation != "enable_record_storage" && operation != "restore_record_storage") {
            return bridge.executeStored(handle, metadata, snapshot)
        }
        check(snapshot.isEmpty())
        val root = checkNotNull(identity).storageSecret()
        try {
            val path = request.getString("path")
            val response = if (operation == "enable_record_storage") {
                bridge.enableRecords(handle, path, root)
                "{\"durable\":true}"
            } else {
                val ids = request.getJSONArray("workspace")
                val workspace = ByteArray(ids.length()) { ids.getInt(it).also { value -> require(value in 0..255) }.toByte() }
                bridge.restoreRecords(handle, path, root, workspace)
            }
            return arrayOf(response.toByteArray(Charsets.UTF_8), ByteArray(0))
        } finally { root.fill(0) }
        } finally { android.os.Trace.endSection() }
    }

    /** Install the adapter consumer on the same worker as JNI calls. Returns
     * false if closed/full. Poll removes live events; callback failure is logged,
     * not retried, and does not imply durable application delivery. */
    @Synchronized
    fun receive(consume: ((ByteArray) -> Unit)?): Boolean {
        if (closed || !requests.tryAcquire()) return false
        worker.execute {
            try { consumer = consume }
            finally { requests.release() }
        }
        return true
    }

    internal fun resetAndClose() = closeInternal(reset = true)

    @Synchronized
    override fun close() = closeInternal(reset = false)

    @Synchronized
    private fun closeInternal(reset: Boolean) {
        if (closed) return
        closed = true
        eventPoll?.cancel(false)
        // Reset must pre-empt a held native exchange. Ordinary close drains
        // admitted work before stopping the session.
        if (reset) {
            val active = handle
            native?.let { bridge -> if (active != 0L) runCatching { bridge.cancel(active) } }
        }
        worker.execute {
            try {
                if (handle != 0L) {
                    if (reset) {
                        runCatching { checkNotNull(native).execute(handle, RESET) }
                            .onSuccess { Log.i("Arachne", "NATIVE_WORKSPACE_RESET") }
                            .onFailure { Log.w("Arachne", "NATIVE_WORKSPACE_RESET_FAILED", it) }
                    }
                    checkNotNull(native).close(handle)
                    Log.i("Arachne", "NATIVE_NODE_STOPPED handle=$handle")
                }
            } catch (error: Exception) {
                Log.e("Arachne", "NATIVE_NODE_STOP_FAILED", error)
            } finally {
                handle = 0L
                identity?.close()
                identity = null
                native = null
                consumer = null
            }
        }
        worker.shutdown()
    }

    /** Off-main-thread handoff: next session must wait for credential lock release. */
    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        return worker.awaitTermination(timeout, unit)
    }

    private companion object {
        // DFWB header/tag + two lengths + bounded DFWS record + attachment.
        const val MAX_STORED_SNAPSHOT = 49 + 16 + 8 + (49 + 128 * 1024 + 16) + 528 * 1024
        val POLL = "{\"op\":\"poll\"}".toByteArray(Charsets.UTF_8)
        val RESET = "{\"op\":\"reset_workspace\"}".toByteArray(Charsets.UTF_8)
        val EMPTY = "null".toByteArray(Charsets.UTF_8)
    }
}
