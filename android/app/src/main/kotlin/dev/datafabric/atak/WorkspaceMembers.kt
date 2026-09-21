package dev.arachne.atak

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Cache signed presentation only. Rust rechecks every profile against the current
 * membership before exposing names; this file never supplies roles or authority.
 *
 * The cache has no cap on member count. Each single file read stays under the
 * 128 KiB bound by paging the profile array across multiple page files rather
 * than by rejecting large rosters. A manifest file records the current
 * generation and page count and is the sole commit point: it is written only
 * after every page of that generation is written and verified, and a reader
 * that finds the manifest naming a page which is not present (a torn write)
 * treats the whole cache as absent rather than assembling a partial roster.
 * New generations use fresh page filenames, so a save in progress never
 * mutates a page file an existing manifest still points to. */
internal class WorkspaceMembers(context: Context, private val workspace: ByteArray) {
    private val directory = File(context.noBackupFilesDir, "data-fabric/member-profiles")
    private val prefix = Hex.encode(workspace)
    // Pre-paging cache file from a build before this fix; read as a fallback, never written again.
    private val legacyFile = AtomicFile(File(directory, "$prefix.json"))
    private val manifestFile = AtomicFile(File(directory, "$prefix.manifest.json"))
    private fun pageFile(generation: Long, page: Int) = AtomicFile(File(directory, "$prefix.g$generation.p$page.json"))
    private var loaded = false
    // Digest of the last saved profile array text, not the text itself: the
    // text is several hundred KB at 500 members.
    private var saved: Long? = null
    var views = emptyList<WorkspaceMember>()
        private set

    fun refresh(call: (JSONObject) -> JSONObject): Boolean = refreshText { call(it).toString() }

    /** [call] returns the raw reply text. Only the final reply's workspace
     * and members values are parsed; its profiles are parsed only when their
     * text changed since the last save. */
    fun refreshText(call: (JSONObject) -> String): Boolean {
        // A saved cache can hold far more entries than arachne-runtime's
        // merge_profiles accepts in one member_roster call (MAX_PROFILES=64 in
        // crates/arachne-runtime/src/membership.rs, which rejects the whole call
        // otherwise) and can serialize larger than FabricNative's 128 KiB
        // requestStored metadata bound. Feed it across the seam in chunks
        // sized to both limits instead of one bulk call; merge_profiles is
        // additive across calls (existing ids are updated, new ids accepted
        // up to its own cap), so this is a client-side wire concern only --
        // no Rust change needed. The final call's response is authoritative.
        val cached = if (!loaded) readCache() else null
        val chunks = cached?.let { WorkspaceRoster.chunk(it, REQUEST_BYTES_LIMIT, REQUEST_PROFILE_LIMIT) } ?: listOf(null)
        // Timestamp before any call so time spent retrieving/merging state cannot extend freshness.
        val observedAt = android.os.SystemClock.elapsedRealtime()
        var lastResponse: String? = null
        for (piece in chunks) {
            val request = JSONObject().put("op", "member_roster")
            piece?.let { request.put("profiles", it) }
            lastResponse = call(request)
        }
        val reply = WorkspaceRoster.parse(checkNotNull(lastResponse), workspace, observedAt)
        if (reply.profilesDigest != saved) {
            writeCache(reply.profiles())
            saved = reply.profilesDigest
        }
        loaded = true
        // Contact times move by the call latency on every poll. Keep the
        // previous objects when nothing else changed, so no new view is sent.
        val next = WorkspaceRoster.stabilize(views, reply.members)
        val changed = views != next
        views = next
        return changed
    }

    /** Reassembled profile array from the paged on-device cache, the legacy
     * single-file cache, or null when neither is present or the paged cache
     * is torn (the manifest names a page that is missing). */
    private fun readCache(): JSONArray? {
        val manifestBytes = try { manifestFile.openRead().use { it.readBytesBounded() } }
            catch (_: java.io.FileNotFoundException) { null }
        if (manifestBytes != null) {
            return try {
                val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                val generation = manifest.getLong("generation")
                val pageCount = manifest.getInt("pages")
                val combined = JSONArray()
                for (index in 0 until pageCount) {
                    val bytes = pageFile(generation, index).openRead().use { it.readBytesBounded() }
                    val chunk = JSONArray(String(bytes, Charsets.UTF_8))
                    for (i in 0 until chunk.length()) combined.put(chunk.get(i))
                }
                combined
            } catch (_: java.io.FileNotFoundException) { null }
            catch (_: org.json.JSONException) { null }
        }
        val legacyBytes = try { legacyFile.openRead().use { it.readBytesBounded() } }
            catch (_: java.io.FileNotFoundException) { return null }
        return JSONArray(String(legacyBytes, Charsets.UTF_8))
    }

    private fun writeCache(profiles: JSONArray) {
        check(directory.isDirectory || directory.mkdirs())
        val previousBytes = try { manifestFile.openRead().use { it.readBytesBounded() } }
            catch (_: java.io.FileNotFoundException) { null }
        val previousGeneration = previousBytes?.let {
            runCatching { JSONObject(String(it, Charsets.UTF_8)).getLong("generation") }.getOrNull()
        }
        // A fresh generation number means this save never overwrites a page
        // file the previous, still-valid manifest points to.
        val generation = (previousGeneration ?: 0L) + 1
        val pages = paginate(profiles)
        pages.forEachIndexed { index, page ->
            val bytes = page.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= PAGE_BYTES_LIMIT)
            val target = pageFile(generation, index)
            val output = target.startWrite()
            try { output.write(bytes); target.finishWrite(output) }
            catch (error: Exception) { target.failWrite(output); throw error }
            check(target.openRead().use { it.readBytesBounded() }.contentEquals(bytes))
        }
        // The manifest write is the single commit point: only after every
        // page above is written and its readback verified does the cache
        // start pointing at this generation.
        val manifestBytes = JSONObject().put("generation", generation).put("pages", pages.size)
            .toString().toByteArray(Charsets.UTF_8)
        val manifestOutput = manifestFile.startWrite()
        try { manifestOutput.write(manifestBytes); manifestFile.finishWrite(manifestOutput) }
        catch (error: Exception) { manifestFile.failWrite(manifestOutput); throw error }
        check(manifestFile.openRead().use { it.readBytesBounded() }.contentEquals(manifestBytes))
        // Cleanup only, after the commit above: drop the now-unreferenced
        // previous generation's page files and the pre-paging legacy file.
        if (previousGeneration != null) {
            var index = 0
            while (true) {
                val leftover = File(directory, "$prefix.g$previousGeneration.p$index.json")
                if (!leftover.exists()) break
                leftover.delete()
                index++
            }
        }
        legacyFile.delete()
    }

    /** Splits profiles into pages whose serialized bytes each stay at or
     * under the 128 KiB single-read bound, preserving array order. */
    private fun paginate(profiles: JSONArray): List<JSONArray> = WorkspaceRoster.chunk(profiles, PAGE_BYTES_LIMIT)

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val bytes = ByteArray(128 * 1024 + 1)
        var length = 0
        while (length < bytes.size) { val n = read(bytes, length, bytes.size - length); if (n < 0) break; length += n }
        check(length < bytes.size) { "Member profile cache exceeds bounds" }
        return bytes.copyOf(length)
    }

    private companion object {
        // Local on-device page-file bound: a single readBytesBounded() call.
        const val PAGE_BYTES_LIMIT = 128 * 1024
        // Mirrors arachne-runtime's merge_profiles MAX_PROFILES
        // (crates/arachne-runtime/src/membership.rs): a member_roster call
        // whose "profiles" array is longer than this is rejected outright, not
        // truncated. This is a wire-chunking size, not a stored-member cap --
        // profiles beyond this per call are sent in a later call, not dropped.
        const val REQUEST_PROFILE_LIMIT = 64
        // Mirrors FabricNative.requestStored's metadata bound, minus headroom
        // for the small "op"/wrapper overhead around the "profiles" array.
        const val REQUEST_BYTES_LIMIT = 128 * 1024 - 1024
    }
}
