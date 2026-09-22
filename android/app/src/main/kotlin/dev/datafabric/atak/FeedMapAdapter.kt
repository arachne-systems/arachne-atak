package dev.arachne.atak

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.TextUtils
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.android.maps.MapView
import com.atakmap.coremap.cot.event.CotEvent
import org.json.JSONObject
import java.time.Instant

internal data class FeedMapObservation(
    val event: CotEvent,
    val uid: String,
    val identity: String,
    val observedAt: Long,
    val expiresAt: Long,
    val latitude: Double,
    val longitude: Double,
)

/** Rescope authenticated feed CoT IDs before passing events into ATAK. */
internal fun parseCotFeedObservation(
    payload: ByteArray,
    workspace: ByteArray,
    member: ByteArray,
    topic: String,
    now: Long,
): FeedMapObservation {
    val document = NativeCotProjection.parse(payload)
    val root = document.documentElement
    require(root.getAttribute("type") in setOf("a-f-G-U-C", "a-n-A-C"))
    val identity = root.getAttribute("uid").also {
        require(it.isNotBlank() && it.length <= 128 && it.none { char -> char.isISOControl() })
    }
    val observedAt = Instant.parse(root.getAttribute("time")).toEpochMilli()
    require(Instant.parse(root.getAttribute("start")).toEpochMilli() == observedAt)
    val expiresAt = Instant.parse(root.getAttribute("stale")).toEpochMilli()
    require(observedAt in Math.subtractExact(now, 60_000)..Math.addExact(now, 10_000))
    require(expiresAt > now && expiresAt <= Math.addExact(observedAt, 60_000))

    val point = NativeCotProjection.elements(root, "point").single()
    val latitude = point.getAttribute("lat").toDouble().also { require(it.isFinite() && it in -90.0..90.0) }
    val longitude = point.getAttribute("lon").toDouble().also { require(it.isFinite() && it in -180.0..180.0) }
    val uid = CotIdentity.receivedObject(workspace, member, "$topic/$identity")
    root.setAttribute("uid", uid)
    val event = CotEvent.parse(CotXml.decode(NativeCotProjection.encode(document)))
    require(event.isValid)
    return FeedMapObservation(event, uid, identity, observedAt, expiresAt, latitude, longitude)
}

/** Maps selected CoT and legacy ADS-B feeds. */
internal class FeedMapAdapter : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var workspace: ByteArray? = null
    @Volatile private var selected = emptySet<String>()
    private data class Track(val topic: String, val expires: Long, val latitude: Double, val longitude: Double)
    private val tracks = mutableMapOf<String, Track>() // Main thread only, capped below.
    private val cleanup = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val removed = tracks.filterValues { it.expires <= now || it.topic !in selected }.keys
            for (uid in removed) { MapView.getMapView().rootGroup.deepFindUID(uid)?.let { it.group?.removeItem(it) }; tracks.remove(uid); Log.i("Arachne", "FEED_MAP_REMOVED uid=$uid") }
            main.postDelayed(this, 5000)
        }
    }
    init { main.post(cleanup) }
    fun bind(record: LocalWorkspace?, feeds: List<FeedView>) {
        val next = record?.takeIf { it.joinPeer == null }?.id
        val changed = workspace?.contentEquals(next ?: byteArrayOf()) != true
        workspace = next?.copyOf()
        selected = feeds.filter { it.enabled }.map { it.topic }.toSet()
        if (changed) main.post {
            for (uid in tracks.keys) MapView.getMapView().rootGroup.deepFindUID(uid)?.let { it.group?.removeItem(it) }
            tracks.clear()
        }
    }
    fun show(topic: String, completed: (Boolean) -> Unit) {
        main.post {
            val track = tracks.values.firstOrNull { it.topic == topic && topic in selected && it.expires > System.currentTimeMillis() }
            if (track == null) { completed(false); return@post }
            val map = MapView.getMapView()
            map.mapController.panZoomTo(com.atakmap.coremap.maps.coords.GeoPoint(track.latitude, track.longitude),
                map.mapResolutionAsMapScale(100.0), true)
            completed(true)
        }
    }
    fun receive(publication: JSONObject, completed: (Boolean) -> Unit) {
        try {
            val scope = workspace ?: error("workspace closed")
            fun bytes(key: String) = publication.getJSONArray(key).let { a -> ByteArray(a.length()) { a.getInt(it).toByte() } }
            require(scope.contentEquals(bytes("workspace")))
            val topic = publication.getString("topic")
            val member = bytes("member")
            require(topic.startsWith("feeds/" + member.joinToString("") { "%02x".format(it.toInt() and 255) } + "/"))
            require(topic in selected)
            val now = System.currentTimeMillis()
            val item = when (publication.getString("feed_format")) {
                "cot.event.v1" -> parseCotFeedObservation(bytes("payload"), scope, member, topic, now)
                "adsb.lol.aircraft.v1" -> {
                    val value = WorkspaceFeeds.json(bytes("payload"))
                    val identity = value.getString("hex").also { require(it.matches(Regex("~?[0-9a-f]{6}"))) }
                    val lat = value.getDouble("lat").also { require(it.isFinite() && it in -90.0..90.0) }
                    val lon = value.getDouble("lon").also { require(it.isFinite() && it in -180.0..180.0) }
                    val observed = value.getLong("observed_at")
                    require(observed in Math.subtractExact(now, 60_000)..Math.addExact(now, 10_000))
                    val expires = Math.addExact(observed, 60_000)
                    require(expires > now)
                    val altitude = if (value.has("alt_geom")) value.getDouble("alt_geom") * 0.3048 else 9999999.0
                    require(altitude.isFinite() && (altitude == 9999999.0 || altitude in -1000.0..100000.0))
                    val name = WorkspaceFeeds.label(value.optString("flight", identity).trim().ifEmpty { identity }, 80)
                    val uid = CotIdentity.receivedObject(scope, member, "$topic/$identity")
                    val time = Instant.ofEpochMilli(observed).toString()
                    val stale = Instant.ofEpochMilli(expires).toString()
                    val event = CotEvent.parse("<event version=\"2.0\" uid=\"$uid\" type=\"a-n-A-C\" time=\"$time\" start=\"$time\" stale=\"$stale\" how=\"m-g\"><point lat=\"$lat\" lon=\"$lon\" hae=\"$altitude\" ce=\"9999999\" le=\"9999999\"/><detail><contact callsign=\"${TextUtils.htmlEncode(name)}\"/><remarks>ADSB.lol observation; unverified source report</remarks></detail></event>")
                    require(event.isValid)
                    FeedMapObservation(event, uid, identity, observed, expires, lat, lon)
                }
                else -> error("Unsupported feed format")
            }
            main.post {
                if (workspace?.contentEquals(scope) != true || topic !in selected) { completed(true); return@post }
                if (tracks.size >= 512 && item.uid !in tracks) { Log.w("Arachne", "FEED_MAP_CAPACITY"); completed(true); return@post }
                try {
                    CotMapComponent.getInternalDispatcher().dispatch(item.event, Bundle().apply { putString("from", "fabric") })
                    tracks[item.uid] = Track(topic, item.expiresAt, item.latitude, item.longitude)
                    Log.i("Arachne", "FEED_MAP_IMPORTED uid=${item.uid} observation=${item.identity} observed_at=${item.observedAt}")
                    completed(true)
                } catch (error: Exception) { completed(false) }
            }
        } catch (error: Exception) {
            Log.w("Arachne", "FEED_OBSERVATION_DISCARDED")
            completed(true) // Invalid/stale ephemeral samples must not block chat.
        }
    }
    override fun close() { bind(null, emptyList()); main.removeCallbacks(cleanup) }
}
