package dev.arachne.atak

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class FeedView(val topic: String, val name: String, val publisher: String,
    val format: String, val source: String, val attribution: String, val coverage: String,
    val enabled: Boolean, val available: Boolean)

/** Workspace catalog metadata and local interests; payload decoding belongs to adapters. */
internal class WorkspaceFeeds(context: Context, workspace: ByteArray) {
    companion object {
        const val CATALOG = "feeds/catalog/v1"
        private val FEED = Regex("feeds/([0-9a-f]{64})/[0-9a-f]{32}")
        fun authority(topic: String): ByteArray? = FEED.matchEntire(topic)?.groupValues?.get(1)
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
        fun currentSelector(topic: String): ByteArray {
            require(FEED.matches(topic))
            return WorkspaceCurrent.selector(topic)
        }
        fun json(bytes: ByteArray): JSONObject {
            require(bytes.size <= 12 * 1024)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val parser = org.json.JSONTokener(text)
            val value = parser.nextValue() as JSONObject
            require(parser.nextClean() == '\u0000')
            return value
        }
        fun label(value: String, limit: Int): String {
            require(value.isNotBlank() && value.length <= limit && value.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' })
            return value
        }
    }
    private val preferences = context.getSharedPreferences("fabric-feed-interests", Context.MODE_PRIVATE)
    private val key = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val selected = preferences.getStringSet(key, emptySet())!!.toMutableSet().also { require(it.size <= 32) }
    private data class Entry(val view: FeedView, val seen: Long, val live: Boolean)
    private val entries = linkedMapOf<String, Entry>()
    fun interests(): Set<String> = selected.toSet()
    fun views(): List<FeedView> = entries.values.map { it.view.copy(enabled = it.view.topic in selected,
        available = it.live && SystemClock.elapsedRealtime() - it.seen < 30000) }
    fun select(topic: String, enabled: Boolean) {
        require(entries.containsKey(topic))
        val next = selected.toMutableSet().apply { if (enabled) add(topic) else remove(topic) }
        require(next.size <= 32)
        check(preferences.edit().putStringSet(key, next).commit())
        selected.clear(); selected.addAll(next)
    }
    fun receive(publication: JSONObject) {
        val encoded = publication.getJSONArray("payload").let { a -> ByteArray(a.length()) { a.getInt(it).toByte() } }
        val entry = runCatching { FabricCatalogEntry.read(encoded) }.getOrNull()
        if (entry?.tombstone == true) {
            entries.entries.removeIf { java.security.MessageDigest.getInstance("SHA-256")
                .digest(it.key.toByteArray(Charsets.UTF_8)).contentEquals(entry.key) }
            return
        }
        val bytes = entry?.payload ?: encoded
        require(bytes.size <= 4096)
        val value = json(bytes)
        require(value.getInt("version") == 1)
        val id = value.getString("id").also { require(it.matches(Regex("[0-9a-f]{32}"))) }
        val member = publication.getJSONArray("member").let { a -> (0 until a.length()).joinToString("") { "%02x".format(a.getInt(it)) } }
        require(member.length == 64)
        val topic = "feeds/$member/$id"
        require(entry == null || entry.key.contentEquals(java.security.MessageDigest.getInstance("SHA-256")
            .digest(topic.toByteArray(Charsets.UTF_8))))
        require(entries.size < 32 || topic in entries)
        val status = value.getString("status").also { require(it in setOf("live", "source_unavailable")) }
        val view = FeedView(topic, label(value.getString("name"),80), label(value.getString("publisher"),80),
            label(value.getString("format"),80), label(value.getString("source"),200),
            label(value.getString("attribution"),200), label(value.getString("coverage"),120), topic in selected, status == "live")
        entries[topic] = Entry(view, SystemClock.elapsedRealtime(), status == "live")
    }
}
