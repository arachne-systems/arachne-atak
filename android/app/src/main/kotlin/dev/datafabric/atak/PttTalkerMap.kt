package dev.arachne.atak

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.atakmap.android.maps.MapGroup
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Marker
import com.atakmap.android.maps.PointMapItem
import com.atakmap.coremap.maps.coords.GeoPoint
import org.w3c.dom.Document
import java.time.Instant

/** Local map prototype. Authenticated PTT activity decorates an existing, recent CoT PLI.
 * The original map item is never edited, and this overlay is never published as CoT. */
internal class PttTalkerMap : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val state = PttTalkerState()
    private var group: MapGroup? = null
    private val markers = mutableMapOf<String, Marker>()
    @Volatile private var closed = false
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            runCatching(::render).onFailure { Log.w("Arachne", "PTT_MAP_RENDER_FAILED", it) }
            main.postDelayed(this, 250)
        }
    }
    init { main.post(tick) }

    fun bind(workspace: String?, members: Map<String, String>) {
        main.post { if (!closed) { state.bind(workspace, members); render() } }
    }

    /** The native CoT adapter invokes this only after author, topic and native identity checks. */
    fun position(workspace: ByteArray, member: ByteArray, uid: String, document: Document) {
        val value = runCatching {
            val root = document.documentElement
            val point = NativeCotProjection.elements(root, "point").single()
            PttMapPosition(uid, point.getAttribute("lat").toDouble(), point.getAttribute("lon").toDouble(),
                Instant.parse(root.getAttribute("time")).toEpochMilli(),
                Instant.parse(root.getAttribute("stale")).toEpochMilli())
        }.getOrNull() ?: return
        val scope = Hex.encode(workspace)
        val author = Hex.encode(member)
        main.post { if (!closed) { state.position(scope, author, value, System.currentTimeMillis()); render() } }
    }

    fun media(workspace: String, member: String, sequence: Long, payload: ByteArray) {
        val received = SystemClock.elapsedRealtime()
        val owned = payload.copyOf()
        main.post { if (!closed) { state.media(workspace, member, sequence, owned, received); render() } }
    }

    private fun render() {
        val map = MapView.getMapView() ?: return
        val visible = state.visible(SystemClock.elapsedRealtime(), System.currentTimeMillis()).filter {
            // A scoped PLI must have reached ATAK. Never make a coordinate for a contact-only member.
            map.rootGroup.deepFindUID(it.position.uid) is PointMapItem
        }.associateBy { it.member }
        for (member in markers.keys.toList()) if (member !in visible) markers.remove(member)?.let { it.group?.removeItem(it) }
        for ((member, talker) in visible) {
            val marker = markers.getOrPut(member) {
                Marker("arachne-ptt-activity-$member").apply {
                    setMetaBoolean("nevercot", true)
                    setMetaBoolean("addToObjList", false)
                    setMetaBoolean("movable", false)
                    setMetaBoolean("removable", false)
                    type = "u-d-arachne-ptt-activity"
                    iconVisibility = Marker.ICON_GONE
                    setAlwaysShowText(true)
                    textColor = 0xffffb300.toInt()
                }.also {
                    val target = group ?: map.rootGroup.addGroup("Arachne PTT activity").also { group = it }
                    target.addItem(it)
                }
            }
            marker.point = GeoPoint(talker.position.latitude, talker.position.longitude)
            marker.title = "PTT · ${talker.name.ifBlank { member.take(12) }}"
            marker.summary = "Recent media · last reported position"
        }
    }

    override fun close() {
        closed = true
        main.removeCallbacks(tick)
        main.post {
            state.bind(null, emptyMap())
            markers.values.forEach { it.group?.removeItem(it) }
            markers.clear()
            group?.let { it.parentGroup?.removeGroup(it) }
            group = null
        }
    }
}
