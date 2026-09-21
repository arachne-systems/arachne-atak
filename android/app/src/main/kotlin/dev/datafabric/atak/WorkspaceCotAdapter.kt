package dev.arachne.atak

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.android.maps.MapView
import com.atakmap.coremap.cot.event.CotEvent
import org.json.JSONObject

/** Receive-only migration of accepted development-era CoT publications. */
internal class WorkspaceCotAdapter : AutoCloseable {
    private data class Scope(val workspace: ByteArray, val member: ByteArray, val name: String)
    @Volatile private var scope: Scope? = null
    private val main = Handler(Looper.getMainLooper())
    private val imported = mutableSetOf<String>() // Main-thread map cleanup only.

    fun bind(record: LocalWorkspace?) {
        val old = scope
        val member = record?.memberId
        if (record != null && member != null && record.joinPeer == null && old != null &&
            old.workspace.contentEquals(record.id) && old.member.contentEquals(member) && old.name == record.memberName) return
        scope = if (record != null && member != null && record.memberName != null && record.joinPeer == null)
            Scope(record.id.copyOf(), member.copyOf(), record.memberName) else null
        scope?.let { Log.i("Arachne", "SCOPED_COT_BOUND member=${CotIdentity.member(it.workspace, it.member)}") }
        main.post {
            for (uid in imported) MapView.getMapView().rootGroup.deepFindUID(uid)?.let { it.group?.removeItem(it) }
            imported.clear()
        }
    }

    fun receive(publication: JSONObject, completed: (Boolean) -> Unit = {}) {
        val active = scope ?: run { completed(false); return }
        val workspace = publication.getJSONArray("workspace").bytes()
        require(workspace.contentEquals(active.workspace))
        val topic = publication.getString("topic")
        val projected = CotProjection.incoming(publication.getJSONArray("payload").bytes(), workspace,
            publication.getJSONArray("member").bytes(), topic)
        val event = CotEvent.parse(CotXml.decode(projected))
        require(event.isValid)
        main.post {
            if (scope !== active) { completed(false); return@post }
            if (imported.size >= 4096 && event.uid !in imported) {
                Log.w("Arachne", "SCOPED_COT_MAP_LIMIT")
                completed(false)
                return@post
            }
            try {
                CotMapComponent.getInternalDispatcher().dispatch(event, Bundle().apply { putString("from", "fabric") })
            } catch (error: Exception) { completed(false); return@post }
            // Ephemeral CoT accepts dispatch, not durable chat/history storage.
            completed(true)
            imported.add(event.uid)
            Log.i("Arachne", "SCOPED_COT_IMPORTED uid=${event.uid} topic=$topic")
            main.postDelayed({
                if (scope === active) Log.i("Arachne", "SCOPED_COT_MAP_ITEM uid=${event.uid} present=${MapView.getMapView().rootGroup.deepFindUID(event.uid) != null}")
            }, 750)
        }
    }

    override fun close() { bind(null) }
    private fun org.json.JSONArray.bytes() = ByteArray(length()) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
}
