package dev.arachne.atak

import org.json.JSONArray

internal data class WorkspaceMember(val id: String, val name: String?, val administrator: Boolean, val self: Boolean,
    val presence: String = "unknown", val service: Boolean = false,
    val lastContactElapsedMs: Long? = null, val reachableUntilElapsedMs: Long? = null,
    // Membership IDs identify signed workspace records; endpoint IDs identify
    // the Iroh peer used for transport and recovery. Older local fixtures that
    // omit endpoint keep the constructor's id fallback, but runtime rosters
    // always provide the explicit endpoint field.
    val endpoint: String = id) {
    fun identity() = Hex.decode(id)
    fun endpointId() = Hex.decode(endpoint)
}

/** Lowercase hex without String.format. `"%02x".format` builds a Formatter per
 * byte; the roster path formats 32 bytes for each of up to 500 members on
 * every poll, so that cost is paid many thousand times each second. */
internal object Hex {
    private val digits = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 255
            out[2 * i] = digits[value ushr 4]; out[2 * i + 1] = digits[value and 15]
        }
        return String(out)
    }

    /** Same text as `(0 until length).joinToString("") { "%02x".format(values.getInt(it)) }`. */
    fun encode(values: JSONArray, length: Int = values.length()): String {
        val out = StringBuilder(length * 2)
        for (i in 0 until length) {
            val value = values.getInt(i)
            if (value in 0..255) out.append(digits[value ushr 4]).append(digits[value and 15])
            else out.append(Integer.toHexString(value).padStart(2, '0'))
        }
        return out.toString()
    }

    fun decode(value: String): ByteArray {
        require(value.length % 2 == 0) { "Odd hex length" }
        return ByteArray(value.length / 2) { i ->
            ((Character.digit(value[2 * i], 16) shl 4) or Character.digit(value[2 * i + 1], 16)).toByte()
        }
    }
}

/** Pure (Android-free) parsing of a `member_roster` reply.
 *
 * The reply carries every member's signed profile as a JSON integer array,
 * which at 500 members is several hundred KB of text and about 200,000 boxed
 * numbers when parsed. The member list needs none of it, and the profile
 * cache needs it only when it changes. [parse] therefore parses only the
 * `workspace` and `members` values, and reduces `profiles` to a digest of its
 * raw text. [profiles] parses the profile array on the rare poll where the
 * digest changed. */
internal object WorkspaceRoster {
    /** Contact times move by the JNI call latency on every poll even when no
     * new contact happened. Differences at or below this are treated as the
     * same contact, so an unchanged roster keeps its previous member objects
     * and the controller does not publish a new view. The comparison is to
     * the kept value, so the displayed error never accumulates above this. */
    const val CONTACT_JITTER_MS = 1000L

    class Reply(val text: String, val members: List<WorkspaceMember>, val profilesDigest: Long,
                private val profilesStart: Int, private val profilesEnd: Int) {
        fun profiles(): JSONArray = JSONArray(text.substring(profilesStart, profilesEnd))
    }

    fun parse(text: String, workspace: ByteArray, observedAt: Long): Reply {
        val spans = topLevelValues(text)
        val workspaceSpan = checkNotNull(spans["workspace"]) { "Roster has no workspace" }
        val workspaceValue = JSONArray(text.substring(workspaceSpan.first, workspaceSpan.last + 1))
        check(workspaceValue.length() == workspace.size &&
            workspace.indices.all { workspaceValue.getInt(it) == (workspace[it].toInt() and 255) })
        val membersSpan = checkNotNull(spans["members"]) { "Roster has no members" }
        val members = JSONArray(text.substring(membersSpan.first, membersSpan.last + 1))
        val profilesSpan = checkNotNull(spans["profiles"]) { "Roster has no profiles" }
        return Reply(text, members(members, observedAt), digest(text, profilesSpan.first, profilesSpan.last + 1),
            profilesSpan.first, profilesSpan.last + 1)
    }

    fun members(members: JSONArray, observedAt: Long): List<WorkspaceMember> = (0 until members.length()).map {
        val m = members.getJSONObject(it)
        val id = m.getJSONArray("id")
        check(id.length() == 32)
        val endpoint = m.getJSONArray("endpoint")
        check(endpoint.length() == 32)
        val kind = m.getString("kind").also { value -> check(value == "person" || value == "service") }
        val age = m.optLong("last_contact_age_ms", -1).takeIf { it >= 0 }
        val freshFor = m.optLong("presence_fresh_for_ms", -1).takeIf { it >= 0 }
        val presence = m.getString("presence").also { value ->
            check(value == "self" || value == "reachable" || value == "stale" || value == "unknown")
        }
        WorkspaceMember(Hex.encode(id, 32),
            if (m.isNull("display_name")) null else m.getString("display_name"), m.getBoolean("administrator"), m.getBoolean("self"),
            presence, kind == "service", age?.let { observedAt - it }, freshFor?.let { observedAt + it }, Hex.encode(endpoint, 32))
    }.sortedWith(compareBy({ it.name ?: "" }, { it.id }))

    /** Keeps each previous member object whose only difference from the new
     * one is contact-time jitter at or below [toleranceMs]. Membership,
     * role, name, kind and presence changes always take the new object. */
    fun stabilize(previous: List<WorkspaceMember>, next: List<WorkspaceMember>,
                  toleranceMs: Long = CONTACT_JITTER_MS): List<WorkspaceMember> {
        if (previous.isEmpty()) return next
        val byId = HashMap<String, WorkspaceMember>(previous.size * 2)
        for (member in previous) byId[member.id] = member
        return next.map { member ->
            val old = byId[member.id]
            if (old != null && old.name == member.name && old.administrator == member.administrator &&
                old.self == member.self && old.presence == member.presence && old.service == member.service &&
                close(old.lastContactElapsedMs, member.lastContactElapsedMs, toleranceMs) &&
                close(old.reachableUntilElapsedMs, member.reachableUntilElapsedMs, toleranceMs)) old
            else member
        }
    }

    private fun close(a: Long?, b: Long?, tolerance: Long) =
        if (a == null || b == null) a == b else kotlin.math.abs(a - b) <= tolerance

    /** 64-bit FNV-1a over the raw characters of text[start, end), mixed with
     * the length. Used only to decide whether the on-device profile cache
     * needs rewriting; arachne-runtime verifies every profile it receives. */
    fun digest(text: String, start: Int, end: Int): Long {
        var hash = -0x340d631b7bdddcdbL
        for (i in start until end) {
            hash = hash xor text[i].code.toLong()
            hash *= 0x100000001b3L
        }
        return hash xor (end - start).toLong()
    }

    /** Character spans (inclusive) of each value in the top-level JSON object. */
    fun topLevelValues(text: String): Map<String, IntRange> {
        val spans = HashMap<String, IntRange>()
        var i = skipSpace(text, 0)
        check(i < text.length && text[i] == '{') { "Roster is not an object" }
        i = skipSpace(text, i + 1)
        if (i < text.length && text[i] == '}') return spans
        while (true) {
            check(i < text.length && text[i] == '"') { "Expected a key" }
            val keyEnd = stringEnd(text, i)
            val key = JSONArray("[" + text.substring(i, keyEnd + 1) + "]").getString(0)
            i = skipSpace(text, keyEnd + 1)
            check(i < text.length && text[i] == ':') { "Expected ':'" }
            val start = skipSpace(text, i + 1)
            val end = valueEnd(text, start)
            spans[key] = start..end
            i = skipSpace(text, end + 1)
            check(i < text.length) { "Unterminated roster" }
            if (text[i] == '}') return spans
            check(text[i] == ',') { "Expected ','" }
            i = skipSpace(text, i + 1)
        }
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Index of the closing quote of the string that opens at [start]. */
    private fun stringEnd(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        error("Unterminated string")
    }

    /** Index of the last character of the value that starts at [start]. */
    private fun valueEnd(text: String, start: Int): Int {
        check(start < text.length) { "Missing value" }
        return when (text[start]) {
            '"' -> stringEnd(text, start)
            '{', '[' -> {
                var depth = 0
                var i = start
                while (i < text.length) {
                    when (text[i]) {
                        '"' -> i = stringEnd(text, i)
                        '{', '[' -> depth++
                        '}', ']' -> { depth--; if (depth == 0) return i }
                    }
                    i++
                }
                error("Unterminated value")
            }
            else -> {
                var i = start
                while (i + 1 < text.length && text[i + 1] != ',' && text[i + 1] != '}' && !text[i + 1].isWhitespace()) i++
                i
            }
        }
    }

    /** Splits profiles into groups of at most [countLimit] items whose
     * serialized UTF-8 bytes each stay at or under [byteLimit], preserving
     * array order. An empty input still yields one empty group, so a present-
     * but-empty cache still causes exactly one call/page. Each item is
     * serialized once; the old form re-serialized the whole growing group for
     * every item, which is quadratic in the group size. */
    fun chunk(profiles: JSONArray, byteLimit: Int, countLimit: Int = Int.MAX_VALUE): List<JSONArray> {
        val groups = mutableListOf<JSONArray>()
        var current = JSONArray()
        var currentBytes = 2 // "[]"
        for (i in 0 until profiles.length()) {
            val item = profiles.get(i)
            // Serialized exactly as JSONArray.toString() writes an element.
            val itemBytes = JSONArray().put(item).toString().toByteArray(Charsets.UTF_8).size - 2
            val trialBytes = currentBytes + itemBytes + if (current.length() > 0) 1 else 0
            val overCount = current.length() + 1 > countLimit
            val overBytes = trialBytes > byteLimit
            if ((overCount || overBytes) && current.length() > 0) {
                groups.add(current)
                current = JSONArray().put(item)
                currentBytes = 2 + itemBytes
            } else {
                current.put(item)
                currentBytes = trialBytes
            }
        }
        if (current.length() > 0 || groups.isEmpty()) groups.add(current)
        return groups
    }
}
