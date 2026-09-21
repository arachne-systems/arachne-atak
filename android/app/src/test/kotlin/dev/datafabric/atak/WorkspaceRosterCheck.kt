package dev.arachne.atak

import org.json.JSONArray
import org.json.JSONObject
import java.lang.management.ManagementFactory

/** Roster refresh memory checks at 500 members. No Android or native
 * dependencies; needs a real org.json on the classpath. Run:
 *
 *   kotlinc android/app/src/main/kotlin/dev/datafabric/atak/WorkspaceRoster.kt \
 *     android/app/src/test/kotlin/dev/datafabric/atak/WorkspaceRosterCheck.kt \
 *     -cp json.jar -include-runtime -d /tmp/roster-check.jar
 *   java -cp /tmp/roster-check.jar:json.jar dev.arachne.atak.WorkspaceRosterCheckKt
 *
 * [Legacy] is the per-poll code this change replaced, kept verbatim so the
 * same checks show the old behavior failing. */
fun main() {
    val workspace = ByteArray(32) { (200 + it).toByte() }
    parsesTheSameMembersAsBefore(workspace)
    jitterOnlyPollIsUnchanged(workspace)
    realChangesStillPropagate(workspace)
    profileDigestTracksProfileText(workspace)
    scannerSkipsStringsAndNesting()
    hexMatchesFormat()
    chunkMatchesBefore()
    pollAllocatesLessThanBefore(workspace)
    chunkAllocatesLessThanBefore()
    println("WorkspaceRosterCheck: 9 checks passed")
}

private const val MEMBERS = 500

/** A reply shaped like arachne-runtime's member_roster (serde_json, sorted keys). */
private fun reply(workspace: ByteArray, count: Int = MEMBERS, ageShiftMs: Long = 0, presenceOf: (Int) -> String = { if (it == 0) "self" else "reachable" },
                  profileSalt: Int = 0): String {
    val text = StringBuilder()
    text.append("{\"epoch\":7,\"members\":[")
    for (i in 0 until count) {
        if (i > 0) text.append(',')
        val id = (0 until 32).joinToString(",") { (when (it) { 0 -> i and 255; 1 -> i shr 8; else -> (i * 7 + it * 13) and 255 }).toString() }
        val endpoint = (0 until 32).joinToString(",") { ((i * 11 + it * 3) and 255).toString() }
        val contact = if (i == 0) "\"last_contact_age_ms\":null,\"presence_fresh_for_ms\":null"
            else "\"last_contact_age_ms\":${20000 + i + ageShiftMs},\"presence_fresh_for_ms\":${50000 - i - ageShiftMs}"
        text.append("{\"administrator\":${i == 0},\"display_name\":\"Member $i\",\"endpoint\":[$endpoint],\"id\":[$id],")
            .append("\"kind\":\"person\",$contact,\"presence\":\"${presenceOf(i)}\",\"self\":${i == 0}}")
    }
    text.append("],\"profiles\":[")
    for (i in 0 until count) {
        if (i > 0) text.append(',')
        // Signed profiles are up to 390 bytes; use 300.
        text.append('[').append((0 until 300).joinToString(",") { ((i + it + profileSalt) and 255).toString() }).append(']')
    }
    text.append("],\"workspace\":[").append(workspace.joinToString(",") { (it.toInt() and 255).toString() })
        .append("],\"workspace_name\":\"Test {\\\"x\\\"}\"}")
    return text.toString()
}

/** The replaced code: WorkspaceMembers.refresh before this change, minus I/O. */
private class Legacy(private val workspace: ByteArray) {
    var saved: String? = null
    var views = emptyList<WorkspaceMember>()
    var writes = 0

    fun refresh(text: String, observedAt: Long): Boolean {
        val value = JSONObject(text)
        check(value.getJSONArray("workspace").toString() == JSONArray(workspace.map { it.toInt() and 255 }).toString())
        val members = value.getJSONArray("members")
        val next = (0 until members.length()).map {
            val m = members.getJSONObject(it)
            val id = m.getJSONArray("id")
            check(id.length() == 32)
            val kind = m.getString("kind").also { value -> check(value in setOf("person", "service")) }
            val age = m.optLong("last_contact_age_ms", -1).takeIf { it >= 0 }
            val freshFor = m.optLong("presence_fresh_for_ms", -1).takeIf { it >= 0 }
            WorkspaceMember((0 until 32).joinToString("") { i -> "%02x".format(id.getInt(i)) },
                if (m.isNull("display_name")) null else m.getString("display_name"), m.getBoolean("administrator"), m.getBoolean("self"),
                m.getString("presence").also { check(it in setOf("self", "reachable", "stale", "unknown")) }, kind == "service",
                age?.let { observedAt - it }, freshFor?.let { observedAt + it })
        }.sortedWith(compareBy({ it.name ?: "" }, { it.id }))
        val profiles = value.getJSONArray("profiles").toString()
        if (profiles != saved) {
            value.getJSONArray("profiles"); writes++
            saved = profiles
        }
        val changed = views != next
        views = next
        return changed
    }

    companion object {
        fun chunk(profiles: JSONArray, byteLimit: Int, countLimit: Int = Int.MAX_VALUE): List<JSONArray> {
            val groups = mutableListOf<JSONArray>()
            var current = JSONArray()
            for (i in 0 until profiles.length()) {
                val item = profiles.get(i)
                val trial = JSONArray()
                for (j in 0 until current.length()) trial.put(current.get(j))
                trial.put(item)
                val overCount = trial.length() > countLimit
                val overBytes = trial.toString().toByteArray(Charsets.UTF_8).size > byteLimit
                if ((overCount || overBytes) && current.length() > 0) {
                    groups.add(current)
                    current = JSONArray().put(item)
                } else current = trial
            }
            if (current.length() > 0 || groups.isEmpty()) groups.add(current)
            return groups
        }
    }
}

/** The new per-poll code path of WorkspaceMembers.refreshText, minus I/O. */
private class Current(private val workspace: ByteArray) {
    var saved: Long? = null
    var views = emptyList<WorkspaceMember>()
    var writes = 0

    fun refresh(text: String, observedAt: Long): Boolean {
        val reply = WorkspaceRoster.parse(text, workspace, observedAt)
        if (reply.profilesDigest != saved) {
            reply.profiles(); writes++
            saved = reply.profilesDigest
        }
        val next = WorkspaceRoster.stabilize(views, reply.members)
        val changed = views != next
        views = next
        return changed
    }
}

private fun parsesTheSameMembersAsBefore(workspace: ByteArray) {
    val text = reply(workspace)
    val legacy = Legacy(workspace).apply { refresh(text, 50_000) }
    val current = Current(workspace).apply { refresh(text, 50_000) }
    check(legacy.views.size == MEMBERS && legacy.views == current.views) { "parsed members differ from the replaced parser" }
    check(current.views.all { WorkspaceRoster.parse(text, workspace, 50_000).members.contains(it) })
    check(runCatching { WorkspaceRoster.parse(text, ByteArray(32), 0) }.isFailure) { "workspace mismatch must be rejected" }
}

/** Same underlying roster, observed 3 ms later relative to the call. */
private fun jitterOnlyPollIsUnchanged(workspace: ByteArray) {
    val first = reply(workspace)
    val second = reply(workspace, ageShiftMs = 3)
    val legacy = Legacy(workspace)
    legacy.refresh(first, 50_000)
    val legacyChanged = legacy.refresh(second, 50_000)
    val current = Current(workspace)
    current.refresh(first, 50_000)
    val before = current.views
    val currentChanged = current.refresh(second, 50_000)
    println("jitter-only poll: legacy changed=$legacyChanged, current changed=$currentChanged")
    check(legacyChanged) { "expected the replaced code to report a change on jitter (the bug)" }
    check(!currentChanged) { "a jitter-only poll must not publish a new view" }
    check(current.views.indices.all { current.views[it] === before[it] }) { "unchanged members must keep their objects" }
}

private fun realChangesStillPropagate(workspace: ByteArray) {
    val current = Current(workspace)
    current.refresh(reply(workspace), 50_000)
    check(current.refresh(reply(workspace, ageShiftMs = -5_000), 50_000)) { "a new contact 5 s later must publish" }
    check(current.refresh(reply(workspace, ageShiftMs = -5_000, presenceOf = { if (it == 0) "self" else if (it == 9) "stale" else "reachable" }), 50_000)) {
        "a presence change must publish"
    }
    check(current.views.single { it.name == "Member 9" }.presence == "stale")
    check(current.refresh(reply(workspace, count = MEMBERS - 1, ageShiftMs = -5_000), 50_000)) { "a removal must publish" }
    check(current.views.size == MEMBERS - 1)
    // Staggered real contacts (about 1/30 of peers each second at a 30 s
    // presence refresh) still publish: stabilize suppresses jitter, not
    // real contacts. Only the members that changed get new objects.
    val staggered = Current(workspace)
    staggered.refresh(reply(workspace), 50_000)
    val kept = staggered.views
    val some = reply(workspace).replace(Regex("\"last_contact_age_ms\":(\\d+)")) { match ->
        val age = match.groupValues[1].toLong()
        "\"last_contact_age_ms\":" + if (age % 30 == 0L) age - 20_000 else age
    }
    check(staggered.refresh(some, 50_000)) { "real contacts on part of the roster must publish" }
    val renewed = staggered.views.indices.count { staggered.views[it] !== kept[it] }
    println("staggered poll: $renewed of ${staggered.views.size} member objects renewed")
    check(renewed in 1..MEMBERS / 20) { "only changed members may get new objects, renewed=$renewed" }
    // Accumulated drift: ten polls each 900 ms later stay within tolerance of
    // the kept value only while the total stays within it.
    val drift = Current(workspace)
    drift.refresh(reply(workspace), 50_000)
    var published = 0
    for (step in 1..10) if (drift.refresh(reply(workspace, ageShiftMs = -900L * step), 50_000)) published++
    check(published >= 4) { "drift must not be hidden indefinitely, published=$published" }
    val shown = drift.views.first { !it.self }.lastContactElapsedMs!!
    val actual = WorkspaceRoster.parse(reply(workspace, ageShiftMs = -9000), workspace, 50_000).members.first { !it.self }.lastContactElapsedMs!!
    check(kotlin.math.abs(shown - actual) <= WorkspaceRoster.CONTACT_JITTER_MS) { "shown contact time is off by ${actual - shown} ms" }
}

private fun profileDigestTracksProfileText(workspace: ByteArray) {
    val current = Current(workspace)
    current.refresh(reply(workspace), 50_000)
    current.refresh(reply(workspace, ageShiftMs = 3), 50_000)
    check(current.writes == 1) { "unchanged profiles must not be saved again, writes=${current.writes}" }
    current.refresh(reply(workspace, profileSalt = 1), 50_000)
    check(current.writes == 2) { "changed profiles must be saved" }
    val text = reply(workspace)
    val parsed = WorkspaceRoster.parse(text, workspace, 0).profiles()
    check(parsed.toString() == JSONObject(text).getJSONArray("profiles").toString()) { "profile array differs from full parse" }
}

private fun scannerSkipsStringsAndNesting() {
    val text = """ { "a" : "x}\"]," , "profiles" : [[1,2],{"k":"]"}] ,"b":{"c":[1]},"n":-12.5e3,"t":true, "z":null } """
    val spans = WorkspaceRoster.topLevelValues(text)
    fun value(key: String) = spans.getValue(key).let { text.substring(it.first, it.last + 1) }
    check(value("a") == "\"x}\\\"],\"") { "string value was ${value("a")}" }
    check(value("profiles") == """[[1,2],{"k":"]"}]""") { "profiles value was ${value("profiles")}" }
    check(value("b") == """{"c":[1]}""" && value("n") == "-12.5e3" && value("t") == "true" && value("z") == "null")
    check(runCatching { WorkspaceRoster.topLevelValues("""{"a":[1,2}""") }.isFailure) { "unterminated input must fail" }
}

private fun hexMatchesFormat() {
    val all = ByteArray(256) { it.toByte() }
    check(Hex.encode(all) == all.joinToString("") { "%02x".format(it.toInt() and 255) })
    check(Hex.decode(Hex.encode(all)).contentEquals(all))
    val ints = JSONArray((0 until 300).map { it })
    check(Hex.encode(ints) == (0 until 300).joinToString("") { "%02x".format(it) }) { "out-of-range ints must format like %02x" }
}

private fun profiles(count: Int, size: Int) = JSONArray().apply {
    repeat(count) { i -> put(JSONArray((0 until size).map { (i + it) and 255 })) }
}

private fun chunkMatchesBefore() {
    for ((count, size) in listOf(0 to 0, 1 to 10, 40 to 24, 500 to 24, 500 to 390, 130 to 1200)) {
        val input = profiles(count, size)
        for ((bytes, limit) in listOf(128 * 1024 - 1024 to 64, 128 * 1024 to Int.MAX_VALUE, 5000 to 3)) {
            val old = Legacy.chunk(input, bytes, limit).map { it.toString() }
            val new = WorkspaceRoster.chunk(input, bytes, limit).map { it.toString() }
            check(old == new) { "chunk($count x $size, $bytes, $limit) differs: ${old.size} vs ${new.size} groups" }
        }
    }
    // Object elements, as in the FUT-27 cache fixture (WorkspaceMembersCheck.profile).
    val objects = JSONArray().apply {
        repeat(500) { put(JSONObject().put("id", it).put("bytes", "signed-profile-blob-$it-padding-\"quoted\"/slash-é")) }
    }
    for ((bytes, limit) in listOf(128 * 1024 - 1024 to 64, 4096 to Int.MAX_VALUE, 700 to 3)) {
        val old = Legacy.chunk(objects, bytes, limit).map { it.toString() }
        val new = WorkspaceRoster.chunk(objects, bytes, limit).map { it.toString() }
        check(old == new) { "object chunk($bytes, $limit) differs: ${old.size} vs ${new.size} groups" }
    }
    val big = JSONArray().put(JSONArray((0 until 50_000).map { 1 }))
    check(WorkspaceRoster.chunk(big, 100).size == 1) { "an oversized single item still forms its own group" }
}

private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
private fun allocated(block: () -> Unit): Long {
    val start = threads.getThreadAllocatedBytes(Thread.currentThread().id)
    block()
    return threads.getThreadAllocatedBytes(Thread.currentThread().id) - start
}

private fun pollAllocatesLessThanBefore(workspace: ByteArray) {
    // Alternate two jitter-only replies, as a steady 1 Hz poll sees.
    val texts = listOf(reply(workspace), reply(workspace, ageShiftMs = 3))
    val legacy = Legacy(workspace)
    val current = Current(workspace)
    repeat(20) { legacy.refresh(texts[it % 2], 50_000); current.refresh(texts[it % 2], 50_000) }
    val polls = 20
    val legacyBytes = allocated { repeat(polls) { legacy.refresh(texts[it % 2], 50_000) } } / polls
    val currentBytes = allocated { repeat(polls) { current.refresh(texts[it % 2], 50_000) } } / polls
    println("reply text: ${texts[0].length} chars; legacy retains saved profile text: ${legacy.saved!!.length} chars; current retains an 8-byte digest")
    println("allocated per 500-member poll: legacy=${legacyBytes / 1024} KiB, current=${currentBytes / 1024} KiB (${100 * currentBytes / legacyBytes}%)")
    check(currentBytes * 4 < legacyBytes) { "poll must allocate under a quarter of the replaced code: $currentBytes vs $legacyBytes" }
}

private fun chunkAllocatesLessThanBefore() {
    val input = profiles(500, 390)
    repeat(2) { Legacy.chunk(input, 128 * 1024); WorkspaceRoster.chunk(input, 128 * 1024) }
    val legacyBytes = allocated { Legacy.chunk(input, 128 * 1024) }
    val currentBytes = allocated { WorkspaceRoster.chunk(input, 128 * 1024) }
    println("cache page split of 500 x 390-byte profiles: legacy=${legacyBytes / 1024 / 1024} MiB, current=${currentBytes / 1024} KiB")
    check(currentBytes * 10 < legacyBytes) { "page split must allocate under a tenth of the replaced code: $currentBytes vs $legacyBytes" }
}
