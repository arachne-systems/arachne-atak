package dev.arachne.atak

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.android.maps.MapView
import com.atakmap.comms.CommsLogger
import com.atakmap.comms.CommsMapComponent
import com.atakmap.comms.TAKServer
import com.atakmap.coremap.cot.event.CotEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Development gate for native CoT over the real fabric. No admission authority.
 * Only explicitly provisioned private test files can activate this adapter. */
internal class CotFabricAdapter(private val session: FabricSession) : CommsLogger, AutoCloseable {
    @Volatile private var binding: JSONObject? = null
    @Volatile private var generation = 0
    private val imported = LinkedHashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    fun applyFixture(status: (String) -> Unit) {
        check(BuildConfig.DEBUG) { "Fixture activation requires a debug build" }
        val attempt = ++generation
        binding = null
        val file = File(MapView.getMapView().context.filesDir, "fabric-fixture.json")
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (out.size() <= 16 * 1024) {
                val size = input.read(chunk); if (size < 0) break; out.write(chunk, 0, size)
            }
            out.toByteArray()
        }
        require(bytes.size <= 16 * 1024) { "Fixture exceeds 16 KiB" }
        val config = JSONObject(String(bytes, Charsets.UTF_8))
        val workspace = config.getJSONArray("workspace")
        require(workspace.length() == 32 && (0 until 32).all { workspace.getInt(it) in 0..255 })
        require(config.getLong("revision") > 0)
        fun selected(name: String): JSONArray {
            val values = config.optJSONArray(name) ?: JSONArray(CotTopics.standard.toList())
            require((0 until values.length()).all { values.getString(it) in CotTopics.standard })
            return values
        }
        val publishing = selected("publishTopics")
        val receiving = selected("receiveTopics")
        val operations = config.getJSONArray("requests")
        require(operations.length() in 1..8)
        fun apply(index: Int) {
            if (generation != attempt) return
            if (index == operations.length()) {
                binding = JSONObject().put("workspace", workspace).put("revision", config.getLong("revision"))
                    .put("publishTopics", publishing).put("receiveTopics", receiving)
                check(session.receive(::importPublication)) { "Event consumer not admitted" }
                Log.i("Arachne", "COT_FIXTURE_READY operations=$index")
                status("Development fixture active.\nNative broadcast CoT adapter enabled.\nSecure onboarding is not implemented.")
                return
            }
            val operation = operations.getJSONObject(index)
            require(operation.getString("op") in setOf("add_address_hint", "install_verified_policy", "subscribe", "unsubscribe"))
            check(session.request(operation.toString().toByteArray()) { result ->
                result.onSuccess { response ->
                    try {
                        val text = String(response, Charsets.UTF_8)
                        if (text != "null") check(JSONObject(text).getJSONArray("failed").length() == 0) { text }
                        apply(index + 1)
                    } catch (error: Exception) {
                        Log.e("Arachne", "COT_FIXTURE_FAILED", error)
                        status("Development fixture failed: ${error.message}")
                    }
                }.onFailure { error ->
                    Log.e("Arachne", "COT_FIXTURE_FAILED", error)
                    status("Development fixture failed: ${error.message}")
                }
            }) { "Fixture request not admitted" }
        }
        apply(0)
    }

    /** Runtime-only isolation for the controlled experiment. Restart ATAK to
     * restore configured ports; this does not edit the user's stored config. */
    fun disableNativePorts() {
        check(BuildConfig.DEBUG)
        val comms = CommsMapComponent.getInstance()
        val ports = comms.allPortsBundle
        for (kind in listOf("inputs", "outputs", "streams")) {
            for (value in ports.getParcelableArray(kind) ?: emptyArray()) {
                val address = (value as Bundle).getString(TAKServer.CONNECT_STRING_KEY) ?: error("Missing port address")
                when (kind) {
                    "inputs" -> comms.removeInput(address)
                    "outputs" -> comms.removeOutput(address)
                    else -> comms.removeStreaming(address)
                }
            }
        }
        check(listOf("inputs", "outputs", "streams").all { comms.allPortsBundle.getParcelableArray(it).isNullOrEmpty() })
        Log.i("Arachne", "NATIVE_PORTS_REMOVED inputs=0 outputs=0 streams=0 restore=restart")
    }

    override fun logSend(event: CotEvent, destination: String?) {
        if (destination == "broadcast") publish(event)
    }
    override fun logSend(event: CotEvent, destinations: Array<out String>?) {
        // Directed audience mapping is unfinished. Never widen a private send.
    }
    override fun logReceive(event: CotEvent, endpoint: String?, server: String?) {}
    override fun dispose() {}

    private fun room(event: CotEvent) = event.detail?.getFirstChildByName(0, "__chat")?.getAttribute("id")
    private fun selected(scope: JSONObject, name: String, topic: String): Boolean {
        val values = scope.getJSONArray(name)
        return (0 until values.length()).any { values.getString(it) == topic }
    }

    private fun identity(event: CotEvent) = "${event.uid}|${event.type}|${event.time}"

    private fun publish(event: CotEvent) {
        val scope = binding ?: return
        val topic = CotTopics.outgoing(event.type, event.uid == MapView.getMapView().selfMarker.uid, room(event)) ?: return
        if (!selected(scope, "publishTopics", topic)) return
        synchronized(imported) {
            if (!imported.add(identity(event))) return
            if (imported.size > 512) imported.remove(imported.first())
        }
        val payload = event.toString().toByteArray(Charsets.UTF_8)
        if (payload.size > 16384) { Log.w("Arachne", "COT_SEND_REJECTED oversized"); return }
        val request = JSONObject().put("workspace", scope.getJSONArray("workspace")).put("revision", scope.getLong("revision"))
            .put("op", "publish").put("topic", topic)
            .put("payload", JSONArray(payload.map { it.toInt() and 255 }))
        if (!session.request(request.toString().toByteArray()) { result ->
            result.onSuccess { report -> Log.i("Arachne", "COT_PUBLISHED uid=${event.uid} type=${event.type} topic=$topic report=${String(report)}") }
                .onFailure { error -> Log.e("Arachne", "COT_PUBLISH_FAILED", error) }
        }) Log.w("Arachne", "COT_SEND_REJECTED queue")
    }

    private fun importPublication(bytes: ByteArray) {
        val scope = binding ?: return
        val publication = JSONObject(String(bytes, Charsets.UTF_8))
        require(publication.getJSONArray("workspace").toString() == scope.getJSONArray("workspace").toString())
        require(publication.getLong("revision") == scope.getLong("revision"))
        val topic = publication.getString("topic")
        if (!selected(scope, "receiveTopics", topic)) return
        val values = publication.getJSONArray("payload")
        require(values.length() <= 16384)
        val payload = ByteArray(values.length()) { index ->
            val value = values.getInt(index); require(value in 0..255); value.toByte()
        }
        val xml = CotXml.decode(payload)
        val event = CotEvent.parse(xml)
        require(event.isValid && CotTopics.accepts(topic, event.type, room(event))) { "Unsupported or invalid CoT" }
        synchronized(imported) {
            if (!imported.add(identity(event))) return
            // ponytail: bounded recent-event echo guard for the diagnostic slice;
            // durable publication IDs and workspace-aware entity mapping remain required.
            if (imported.size > 512) imported.remove(imported.first())
        }
        CotMapComponent.getInternalDispatcher().dispatch(event, Bundle().apply { putString("from", "fabric") })
        Log.i("Arachne", "COT_IMPORTED uid=${event.uid} type=${event.type} topic=$topic")
        if (event.type.startsWith("a-")) main.postDelayed({
            if (binding === scope) Log.i("Arachne", "COT_MAP_ITEM uid=${event.uid} present=${MapView.getMapView().rootGroup.deepFindUID(event.uid) != null}")
        }, 500)
    }

    override fun close() { generation++; binding = null; session.receive(null); main.removeCallbacksAndMessages(null) }

}
