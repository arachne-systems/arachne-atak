package dev.arachne.atak

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** FUT-27: exercises the real WorkspaceMembers profile cache (real Android
 * filesystem and AtomicFile, no substitute) against a saved roster past the
 * old 64-profile cap, a legacy pre-paging cache file, a full write/read/
 * compare round trip, and a torn multi-page write. Not through WorkspaceController
 * or ATAK UI. */
internal object WorkspaceMembersCheck {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun profile(id: Int): JSONObject =
        JSONObject().put("id", id).put("bytes", "signed-profile-blob-$id-padding-padding-padding")

    // A structurally valid (but unsigned) Vec<u8> wire entry: arachne-runtime's
    // merge_profiles only hard-rejects a batch for its length or a per-entry
    // byte length over arachne_security::MAX_MEMBER_PROFILE (390 bytes); an
    // entry that fails signature verification is otherwise silently skipped,
    // not an error. That lets this case exercise the real per-call 64-profile
    // batch bound at the real seam without needing 500 real signed members.
    // 24 bytes keeps 500 of these, as one legacy (unpaged) fixture file, well
    // under the unrelated 128 KiB single-file/request read bound, so what
    // trips on the pre-chunking build is specifically the >64-per-call cap.
    private fun byteProfile(id: Int): JSONArray {
        val bytes = ByteArray(24) { ((id + it) and 255).toByte() }
        return JSONArray(bytes.map { it.toInt() and 255 })
    }

    private fun rosterReply(workspace: ByteArray, count: Int, profileOf: (Int) -> JSONObject = ::profile): JSONObject {
        val members = JSONArray()
        repeat(count) { i ->
            members.put(JSONObject().put("id", JSONArray((0 until 32).map { i }))
                .put("endpoint", JSONArray((0 until 32).map { i }))
                .put("kind", "person").put("administrator", false).put("self", i == 0)
                .put("display_name", "Member $i").put("presence", "unknown"))
        }
        val profiles = JSONArray()
        repeat(count) { profiles.put(profileOf(it)) }
        return JSONObject().put("workspace", JSONArray(workspace.map { it.toInt() and 255 }))
            .put("members", members).put("profiles", profiles)
    }

    fun run(context: Context): JSONObject {
        val root = File(context.noBackupFilesDir, "members-tests/${UUID.randomUUID()}")
        try {
            val result = JSONObject().put("passed", false)

            // Case 1: a saved roster of 500 profiles must load and open() must
            // succeed -- the reported bug -- through the REAL WorkspaceController,
            // FabricSession/JNI and Rust arachne-runtime seam, not a stubbed call
            // lambda. arachne-runtime's merge_profiles rejects a single
            // member_roster call whose "profiles" array is over its own 64-entry
            // MAX_PROFILES (crates/arachne-runtime/src/membership.rs) or whose
            // serialized request exceeds FabricNative's 128 KiB requestStored
            // metadata bound; this proves WorkspaceMembers chunks outgoing calls
            // to stay under both, not just that the local disk cache round-trips.
            run {
                val caseRoot = File(root, "case1").apply { mkdirs() }
                val caseContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = caseRoot }
                val views = LinkedBlockingQueue<WorkspaceView>()
                fun wait(predicate: (WorkspaceView) -> Boolean): WorkspaceView {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                    while (System.nanoTime() < deadline) {
                        val view = views.poll(1, TimeUnit.SECONDS) ?: continue
                        check(!view.message.startsWith("Workspace operation failed")) { "Real-seam check failed: ${view.message}" }
                        if (!view.busy && predicate(view)) return view
                    }
                    error("Real-seam open() deadline")
                }
                var controller = WorkspaceController(caseContext) { views.offer(it) }
                wait { it.saved.isEmpty() }
                check(controller.create("FUT-27 seam check", "Organizer"))
                val record = checkNotNull(wait { it.active != null }.active)
                controller.close(); check(controller.awaitClosed(20, TimeUnit.SECONDS))

                // Simulate a device that already saved a 500-profile roster in an
                // earlier session, before ever running this fix. create() above
                // already wrote its own (small, single-member) paged cache for
                // this workspace; clear it first so the fixture below -- not
                // that real cache -- is what readCache() finds.
                val directory = File(caseRoot, "data-fabric/member-profiles").apply { mkdirs() }
                val prefix = hex(record.id)
                directory.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
                val profiles = JSONArray(); repeat(500) { profiles.put(byteProfile(it)) }
                File(directory, "$prefix.json").writeText(profiles.toString(), Charsets.UTF_8)

                views.clear()
                controller = WorkspaceController(caseContext) { views.offer(it) }
                wait { it.saved.size == 1 }
                check(controller.open(record))
                val reopened = wait { it.active?.slot == record.slot }
                check(reopened.active != null) { "open() did not become active with a 500-profile saved cache" }
                controller.close(); check(controller.awaitClosed(20, TimeUnit.SECONDS))
            }
            result.put("roster_500_profiles_opens", true)

            // Case 2: a legacy (<=64) cache file still loads, with the same rendered
            // names, and is retired once the new paged cache is written.
            run {
                val caseRoot = File(root, "case2").apply { mkdirs() }
                val caseContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = caseRoot }
                val workspace = ByteArray(32) { 9 }
                val directory = File(caseRoot, "data-fabric/member-profiles").apply { mkdirs() }
                val legacyFile = File(directory, hex(workspace) + ".json")
                val profiles = JSONArray(); repeat(40) { profiles.put(profile(it)) }
                legacyFile.writeText(profiles.toString(), Charsets.UTF_8)
                val expectedNames = (0 until 40).map { "Member $it" }.sorted()
                val members = WorkspaceMembers(caseContext, workspace)
                members.refresh { rosterReply(workspace, 40) }
                val actualNames = members.views.mapNotNull { it.name }.sorted()
                check(actualNames == expectedNames) { "Migration mismatch: $actualNames != $expectedNames" }
                check(!legacyFile.exists()) { "Legacy cache file must be retired after migration" }
                check(File(directory, hex(workspace) + ".manifest.json").exists())
            }
            result.put("legacy_cache_migrates_with_identical_names", true)

            // Case 3: write -> read -> compare for 500 profiles (readback verification).
            run {
                val caseRoot = File(root, "case3").apply { mkdirs() }
                val caseContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = caseRoot }
                val workspace = ByteArray(32) { 11 }
                WorkspaceMembers(caseContext, workspace).refresh { rosterReply(workspace, 500) }
                // refresh() feeds the cache across the seam in chunks (<=64
                // profiles per member_roster call), so accumulate every call's chunk.
                val loaded = JSONArray()
                WorkspaceMembers(caseContext, workspace).refresh { request ->
                    request.optJSONArray("profiles")?.let { piece -> for (i in 0 until piece.length()) loaded.put(piece.get(i)) }
                    rosterReply(workspace, 500)
                }
                check(loaded.length() == 500) { "Expected 500 profiles on readback, got ${loaded.length()}" }
                for (i in 0 until 500) check(loaded.getJSONObject(i).toString() == profile(i).toString())
            }
            result.put("readback_verification_500_profiles", true)

            // Case 4: a torn manifest (missing page) is treated as absent, never a
            // silently partial roster.
            run {
                val caseRoot = File(root, "case4").apply { mkdirs() }
                val caseContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = caseRoot }
                val workspace = ByteArray(32) { 13 }
                val directory = File(caseRoot, "data-fabric/member-profiles")
                fun bigProfile(id: Int) = JSONObject().put("id", id).put("bytes", "x".repeat(4000) + id)
                fun bigRoster(count: Int) = rosterReply(workspace, count, ::bigProfile)
                WorkspaceMembers(caseContext, workspace).refresh { bigRoster(80) }
                val prefix = hex(workspace)
                val manifest = JSONObject(File(directory, "$prefix.manifest.json").readText())
                val generation = manifest.getLong("generation")
                val pageCount = manifest.getInt("pages")
                check(pageCount > 1) { "Fixture did not force multiple pages ($pageCount)" }
                check(File(directory, "$prefix.g$generation.p${pageCount - 1}.json").delete())
                var sawProfiles = true
                WorkspaceMembers(caseContext, workspace).refresh { request ->
                    sawProfiles = request.has("profiles"); bigRoster(80)
                }
                check(!sawProfiles) { "Torn manifest must be treated as an absent cache, not a partial roster" }
            }
            result.put("torn_manifest_treated_as_absent", true)

            return result.put("passed", true)
        } finally { root.deleteRecursively() }
    }
}
