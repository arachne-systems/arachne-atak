package dev.arachne.atak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.atakmap.android.maps.MapView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Debug-build remote control for test rigs. Drives the same owner methods the
 * screens call, with no screen involved.
 *
 *     adb shell am broadcast -a dev.arachne.atak.debug.CONTROL -p com.atakmap.app.civ --es op state
 *
 * The reply is the broadcast result data: one JSON object, printed by `am`.
 * Only senders holding android.permission.DUMP (adb shell, not apps) can reach
 * it. The class exists only in the debug source set; release APKs never ship it.
 * `scripts/arachne-ctl.py` is the host client. */
internal object DebugControl {
    const val ACTION = "dev.arachne.atak.debug.CONTROL"
    private const val RESULT_OK = 1
    private const val RESULT_ERROR = 2
    private const val CALL_TIMEOUT_MS = 20_000L
    private const val WORKER_TIMEOUT_MS = 2_000L
    private var thread: HandlerThread? = null
    private var receiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic fun install(plugin: FabricPlugin) {
        check(BuildConfig.DEBUG && receiver == null)
        val worker = HandlerThread("arachne-debug-control").apply { start() }
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION) return
                val op = intent.getStringExtra("op").orEmpty()
                val started = SystemClock.elapsedRealtimeNanos()
                val reply = try {
                    handle(plugin, op) { intent.getStringExtra(it) }.put("ok", true)
                } catch (error: Throwable) {
                    Log.w("Arachne", "DEBUG_CONTROL_FAILED op=$op", error)
                    JSONObject().put("ok", false).put("error", "${error.javaClass.simpleName}: ${error.message}")
                }
                reply.put("op", op).put("took_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
                intent.getStringExtra("id")?.let { reply.put("id", it) }
                resultCode = if (reply.getBoolean("ok")) RESULT_OK else RESULT_ERROR
                resultData = reply.toString()
            }
        }
        val context = MapView.getMapView().context
        val filter = IntentFilter(ACTION)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(next, filter, android.Manifest.permission.DUMP, Handler(worker.looper), Context.RECEIVER_EXPORTED)
        else context.registerReceiver(next, filter, android.Manifest.permission.DUMP, Handler(worker.looper))
        thread = worker; receiver = next
        Log.i("Arachne", "DEBUG_CONTROL_READY action=$ACTION")
        if (BuildConfig.RIG_KEY.length == 64) startRigLink(plugin)
    }

    @Volatile private var rigRunning = false
    private var rigThread: Thread? = null

    /** Dial the test-rig controller by its endpoint key (iroh: direct, else
     * relay) and long-poll it for commands. No adb, IP or port involved. The
     * controller identity is proved by the iroh handshake, so only the key
     * pinned in this debug build can send commands. See crates/arachne-rig. */
    private fun startRigLink(plugin: FabricPlugin) {
        val controller = BuildConfig.RIG_KEY.chunked(2).map { it.toInt(16) }
        rigRunning = true
        rigThread = Thread({
            var node: DebugNative? = null
            val results = ArrayDeque<JSONObject>()
            var backoff = 1_000L
            while (rigRunning) {
                try {
                    val active = node ?: DebugNative(MapView.getMapView().context).also { node = it }
                    // Send at most ~24 KB of results per poll (the control request bound is 32 KiB).
                    val batch = JSONArray()
                    var bytes = 0
                    while (results.isNotEmpty() && bytes + results.first().toString().length <= 24_000) {
                        val next = results.removeFirst(); batch.put(next); bytes += next.toString().length
                    }
                    val poll = JSONObject().put("device", JSONObject()
                        .put("callsign", onMain { MapView.getMapView().deviceCallsign })
                        .put("version", BuildConfig.VERSION_NAME).put("pid", Process.myPid()))
                        .put("results", batch).put("more", results.isNotEmpty())
                    val payload = "ARIG\u0001".toByteArray(Charsets.ISO_8859_1) + poll.toString().toByteArray()
                    val request = JSONObject().put("op", "control_exchange").put("peer", JSONArray(controller))
                        .put("payload", JSONArray(payload.map { it.toInt() and 255 }))
                    val response = JSONObject(String(active.execute(request.toString().toByteArray()), Charsets.UTF_8))
                    val reply = response.getJSONArray("reply")
                    val text = String(ByteArray(reply.length()) { reply.getInt(it).toByte() }, Charsets.UTF_8)
                    val commands = JSONObject(text).optJSONArray("commands") ?: JSONArray()
                    backoff = 1_000L
                    for (index in 0 until commands.length()) {
                        val command = commands.getJSONObject(index)
                        val id = command.getLong("id")
                        val op = command.getString("op")
                        val args = command.optJSONObject("args") ?: JSONObject()
                        val started = SystemClock.elapsedRealtimeNanos()
                        val result = try {
                            handle(plugin, op) { name -> args.optString(name).takeIf { args.has(name) } }.put("ok", true)
                        } catch (error: Throwable) {
                            JSONObject().put("ok", false).put("error", "${error.javaClass.simpleName}: ${error.message}")
                        }
                        result.put("op", op).put("took_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
                        val encoded = result.toString()
                        if (encoded.length <= 24_000) results.addLast(JSONObject().put("id", id).put("reply", result))
                        else {
                            val parts = encoded.chunked(20_000)
                            parts.forEachIndexed { chunk, data ->
                                results.addLast(JSONObject().put("id", id).put("chunk", chunk).put("total", parts.size).put("data", data))
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (!rigRunning) break
                    Log.w("Arachne", "DEBUG_RIG_LINK_RETRY ${error.javaClass.simpleName}: ${error.message?.take(160)}")
                    // A closed or broken node is rebuilt; the controller may simply be down.
                    runCatching { node?.close() }; node = null
                    Thread.sleep(backoff); backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
            runCatching { node?.close() }
        }, "arachne-rig-link").apply { isDaemon = true; start() }
        Log.i("Arachne", "DEBUG_RIG_LINK_STARTED controller=${BuildConfig.RIG_KEY.take(8)}")
    }

    @JvmStatic fun uninstall(plugin: FabricPlugin) {
        rigRunning = false
        receiver?.let { MapView.getMapView().context.unregisterReceiver(it) }
        thread?.quitSafely()
        receiver = null; thread = null
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        main.post(task)
        return task.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun handle(plugin: FabricPlugin, op: String, argument: (String) -> String?): JSONObject {
        fun arg(name: String) = argument(name)
        fun required(name: String) = requireNotNull(arg(name)?.takeIf { it.isNotBlank() }) { "missing --es $name" }
        val connections = { checkNotNull(plugin.debugWorkspaces()) { "Arachne is not started" } }
        fun owners() = connections().debugOwners()
        // The owner an op acts on: the one holding `slot`, else the only open workspace.
        fun target(): Pair<WorkspaceView?, WorkspaceController> {
            val all = owners()
            arg("slot")?.let { slot -> return requireNotNull(all.firstOrNull { it.first?.active?.slot == slot }) { "no open owner for slot $slot" } }
            val open = all.filter { it.first?.active != null }
            return open.singleOrNull() ?: all.singleOrNull() ?: error("${open.size} workspaces open; pass --es slot")
        }
        return when (op) {
            "ping" -> JSONObject().put("version", BuildConfig.VERSION_NAME).put("build_type", BuildConfig.BUILD_TYPE)
                .put("pid", Process.myPid()).put("callsign", onMain { MapView.getMapView().deviceCallsign })
                .put("elapsed_ns", SystemClock.elapsedRealtimeNanos()).put("wall_ms", System.currentTimeMillis())
            "state" -> JSONObject().put("owners", JSONArray(owners().map { (view, owner) ->
                ownerJson(view, owner, arg("endpoint") != "false") }))
                .put("saved", JSONArray(owners().firstNotNullOfOrNull { it.first }?.saved.orEmpty().map(::recordJson)))
            "members" -> {
                val view = checkNotNull(target().first) { "no view yet" }
                JSONObject().put("slot", view.active?.slot).put("members", JSONArray(view.members.map { member ->
                    JSONObject().put("id", member.id).put("name", member.name).put("administrator", member.administrator)
                        .put("self", member.self).put("presence", member.presence).put("service", member.service)
                        .put("last_contact_ms", member.lastContactElapsedMs)
                }))
            }
            "member_summary" -> {
                val view = checkNotNull(target().first) { "no view yet" }
                JSONObject().put("slot", view.active?.slot).put("members", view.members.size)
                    .put("named", view.members.count { !it.name.isNullOrBlank() })
                    .put("reachable", view.members.count { it.presence == "reachable" })
            }
            "create" -> JSONObject().put("accepted", connections().create(required("name"), required("member")))
            "join" -> JSONObject().put("accepted", connections().join(required("link"), required("member")))
            "select" -> {
                val record = requireNotNull(owners().firstNotNullOfOrNull { it.first }?.saved?.firstOrNull { it.slot == required("slot") }) { "no saved slot" }
                JSONObject().put("accepted", onMain { connections().open(record) })
            }
            "rename" -> {
                val (view, owner) = target()
                val record = checkNotNull(view?.active) { "no active workspace" }
                val workspace = arg("workspace")?.let(::hexBytes) ?: checkNotNull(record.id)
                val previous = arg("previous_name") ?: record.name
                JSONObject().put("accepted", owner.rename(workspace, previous, required("name")))
            }
            "manage" -> {
                val (view, owner) = target()
                val record = checkNotNull(view?.active) { "no active workspace" }
                val workspace = arg("workspace")?.let(::hexBytes) ?: checkNotNull(record.id)
                JSONObject().put("accepted", owner.manage(workspace, hexBytes(required("member")), required("action")))
            }
            "pause" -> {
                val (view, _) = target()
                val workspace = arg("workspace")?.let(::hexBytes) ?: checkNotNull(view?.active?.id)
                JSONObject().put("accepted", connections().pause(workspace))
            }
            // Open, multi-device invitation. Poll `invitation` for the link.
            "invite" -> {
                val lifetime = arg("expires_s")?.toLong() ?: 0L
                JSONObject().put("accepted", target().second.invite(if (lifetime == 0L) 0 else System.currentTimeMillis() / 1000 + lifetime))
            }
            "invitation" -> JSONObject().put("link", target().first?.invitation)
            "metrics" -> JSONObject().put("runtime", target().second.debugCall(JSONObject().put("op", "workspace_metrics"), CALL_TIMEOUT_MS))
                .put("host", ArachneTrace.stats())
            // Any runtime request on the owner's serial worker, e.g. {"op":"endpoint_info"}.
            "runtime" -> JSONObject().put("result", target().second.debugCall(JSONObject(required("request")), CALL_TIMEOUT_MS))
            "trace" -> ArachneTrace.snapshot(arg("since")?.toLong() ?: 0, (arg("limit")?.toInt() ?: 1000).coerceIn(1, 4000))
            "trace_stats" -> ArachneTrace.stats()
            "trace_reset" -> JSONObject().put("since", ArachneTrace.reset()).put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            // Recreates Arachne state in this ATAK process; retain the separate
            // debug rig identity so the caller stays connected.
            "reset" -> {
                val done = java.util.concurrent.CompletableFuture<Result<Unit>>()
                main.post { plugin.resetState("debug-rig") { done.complete(it) } }
                done.get(60, TimeUnit.SECONDS).getOrThrow()
                JSONObject().put("restart_required", false)
            }
            else -> error("unknown op '$op'")
        }
    }

    private fun ownerJson(view: WorkspaceView?, owner: WorkspaceController, endpoint: Boolean): JSONObject {
        // The owner's worker can be inside a long call (a join request waits up
        // to 45 s). Report that instead of failing the whole state reply.
        val json = JSONObject().put("debug", runCatching { owner.debugState(WORKER_TIMEOUT_MS) }
            .getOrElse { JSONObject().put("error", "worker busy: ${it.javaClass.simpleName}") })
        if (endpoint) json.put("endpoint", runCatching { owner.debugCall(JSONObject().put("op", "endpoint_info"), WORKER_TIMEOUT_MS) }
            .getOrElse { JSONObject().put("error", "worker busy: ${it.javaClass.simpleName}") })
        if (view == null) return json
        view.active?.let { json.put("active", recordJson(it)) }
        return json.put("message", view.message).put("busy", view.busy).put("attention", view.attention)
            .put("attention_reason", view.attentionReason).put("continuity", view.continuity)
            .put("members", view.members.size).put("administrators", view.members.count { it.administrator })
            .put("self_administrator", view.members.any { it.self && it.administrator })
            .put("invitations", view.invitations.size).put("approvals", view.approvals.size)
            .put("has_invitation_link", view.invitation != null)
            .put("metrics", view.metrics?.let { metrics ->
                JSONObject().put("admission_queue", metrics.admissionQueue).put("admission_in_flight", metrics.admissionInFlight)
                    .put("approval_pending", metrics.approvalPending).put("receive_queue", metrics.receiveQueue)
                    .put("pending_objects", metrics.pendingObjects).put("pending_sends", metrics.pendingSends).put("repair_jobs", metrics.repairJobs)
            })
    }

    private fun hex(bytes: ByteArray?) = bytes?.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun hexBytes(value: String): ByteArray {
        require(value.length == 64 && value.all { it in "0123456789abcdefABCDEF" }) { "expected 32-byte hex" }
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun recordJson(record: LocalWorkspace) = JSONObject().put("slot", record.slot).put("name", record.name)
        .put("workspace", hex(record.id)).put("member", hex(record.memberId)).put("member_name", record.memberName)
        .put("joining", record.joinPeer != null).put("join_address", record.joinAddress).put("pre_join", record.preJoin)
        .put("ended", record.ended)
}
