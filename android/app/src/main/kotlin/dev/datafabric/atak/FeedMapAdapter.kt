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

/** ADSB.lol observation-to-map adapter. The fabric never interprets these fields. */
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
            require(topic in selected && publication.getString("feed_format") == "adsb.lol.aircraft.v1")
            val value = WorkspaceFeeds.json(bytes("payload"))
            val identity = value.getString("hex").also { require(it.matches(Regex("~?[0-9a-f]{6}"))) }
            val lat = value.getDouble("lat").also { require(it.isFinite() && it in -90.0..90.0) }
            val lon = value.getDouble("lon").also { require(it.isFinite() && it in -180.0..180.0) }
            val observed = value.getLong("observed_at")
            val now = System.currentTimeMillis()
            require(observed in (now-60000)..(now+10000))
            val altitude = if (value.has("alt_geom")) value.getDouble("alt_geom") * 0.3048 else 9999999.0
            require(altitude.isFinite() && (altitude == 9999999.0 || altitude in -1000.0..100000.0))
            val name = WorkspaceFeeds.label(value.optString("flight", identity).trim().ifEmpty { identity }, 80)
            val uid = CotIdentity.receivedObject(scope, member, "$topic/$identity")
            val time = Instant.ofEpochMilli(observed).toString()
            val stale = Instant.ofEpochMilli(observed+60000).toString()
            val event = CotEvent.parse("<event version=\"2.0\" uid=\"$uid\" type=\"a-n-A-C\" time=\"$time\" start=\"$time\" stale=\"$stale\" how=\"m-g\"><point lat=\"$lat\" lon=\"$lon\" hae=\"$altitude\" ce=\"9999999\" le=\"9999999\"/><detail><contact callsign=\"${TextUtils.htmlEncode(name)}\"/><remarks>ADSB.lol observation; unverified source report</remarks></detail></event>")
            require(event.isValid)
            main.post {
                if (workspace?.contentEquals(scope) != true || topic !in selected) { completed(true); return@post }
                if (tracks.size >= 512 && uid !in tracks) { Log.w("Arachne", "FEED_MAP_CAPACITY"); completed(true); return@post }
                try {
                    CotMapComponent.getInternalDispatcher().dispatch(event, Bundle().apply { putString("from", "fabric") })
                    tracks[uid] = Track(topic, observed+60000, lat, lon)
                    Log.i("Arachne", "FEED_MAP_IMPORTED uid=$uid observation=$identity observed_at=$observed")
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
