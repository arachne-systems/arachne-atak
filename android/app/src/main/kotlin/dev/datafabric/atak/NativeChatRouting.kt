package dev.arachne.atak

import android.content.SharedPreferences
import com.atakmap.android.contact.Contacts
import org.w3c.dom.Element
import org.json.JSONObject

/** Resolve native recipient IDs against this workspace's accepted roster.
 * Group aliases keep ATAK-created conversation IDs stable across replies and restart. */
internal class NativeChatRouting(
    private val workspace: ByteArray,
    private val member: ByteArray,
    private val selfUid: String,
    private val groups: SharedPreferences,
    private val receipts: SharedPreferences,
    private val nativeIdentities: SharedPreferences? = null,
) {
    data class Route(val recipients: List<ByteArray>, val identities: Map<String, String>)
    @Volatile var members: List<WorkspaceMember> = emptyList()
    private val selfActor = CotIdentity.member(workspace, member)
    private val nativeByMember = nativeIdentities?.all?.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }?.toMap()?.toMutableMap() ?: mutableMapOf()

    init { rememberNative(member, selfUid) }

    private fun roster(): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        for (value in members) {
            val identity = value.identity()
            val actor = CotIdentity.member(workspace, identity)
            require(put(actor, identity) == null) { "Duplicate workspace member" }
            synchronized(nativeByMember) { nativeByMember[identity.hex()] }?.let { native ->
                require(put(native, identity)?.contentEquals(identity) != false) { "Duplicate native ATAK identity" }
            }
        }
    }.also { require(it[selfActor]?.contentEquals(member) == true) { "Workspace roster is not ready" } }

    fun observeNative(author: ByteArray, uid: String) {
        require(members.any { it.identity().contentEquals(author) }) { "Unknown native author" }
        rememberNative(author, uid)
    }

    fun nativeIdentity(author: ByteArray): String? = synchronized(nativeByMember) { nativeByMember[author.hex()] }

    fun observedNativeIdentities(): Map<String, String> = synchronized(nativeByMember) { nativeByMember.toMap() }

    private fun rememberNative(author: ByteArray, uid: String) {
        require(uid.isNotBlank() && uid.toByteArray(Charsets.UTF_8).size <= 1024 &&
            !uid.startsWith("dfm-") && !uid.startsWith("dfl-") && !uid.startsWith("dfo-")) { "Invalid native ATAK identity" }
        val key = author.hex()
        synchronized(nativeByMember) {
            require(nativeByMember.none { it.key != key && it.value == uid }) { "Native ATAK identity belongs to another member" }
            if (nativeByMember[key] == uid) return
            nativeIdentities?.let { check(it.edit().putString(key, uid).commit()) { "Could not save native ATAK identity" } }
            nativeByMember[key] = uid
        }
    }

    fun selectedMembers(targets: List<String>): List<ByteArray> {
        val roster = roster()
        val selected = targets.filter { it != selfUid && it != selfActor }
        require(selected.isNotEmpty() && selected.size <= 64 && selected.distinct().size == selected.size) { "Invalid native recipients" }
        val names = members.filter { !it.name.isNullOrBlank() }.groupBy { it.name!! }
            .mapNotNull { (name, matches) -> matches.singleOrNull()?.let { name to it.identity() } }.toMap()
        val resolved = selected.map { roster[it] ?: names[it] }
        require(resolved.all { it != null && !it.contentEquals(member) }) { "Choose members of this workspace." }
        return resolved.map { checkNotNull(it) }.sortedBy { it.hex() }
    }

    fun checkMapAudience(author: ByteArray, recipients: List<ByteArray>) {
        val roster = roster()
        require(CotIdentity.member(workspace, author) in roster) { "Unknown native author" }
        require(recipients.isEmpty() || (recipients.any { it.contentEquals(member) } &&
            recipients.none { it.contentEquals(author) } && recipients.all { CotIdentity.member(workspace, it) in roster })) {
            "Native audience excludes this member or belongs to another workspace"
        }
    }

    private fun destinations(root: Element): List<String> {
        val group = NativeCotProjection.elements(root, "chatgrp").single()
        val attributes = group.attributes
        val values = (0 until attributes.length).map { attributes.item(it) }
            .filter { it.nodeName.matches(Regex("uid[0-9]+")) && it.nodeName != "uid0" }
            .map { it.nodeValue }
        require(values.size <= 64 && values.distinct().size == values.size) { "Invalid native recipients" }
        return values
    }

    @Synchronized
    private fun outgoingGroup(native: String): String {
        if (native == Contacts.USER_GROUPS) return native
        groups.getString(native, null)?.let { return it }
        require(!native.startsWith("dfm-") && !native.startsWith("dfl-") && !native.startsWith("dfo-")) {
            "Unknown workspace conversation"
        }
        val wire = CotIdentity.publishedObject(workspace, member, "native-group/$native")
        save(native, wire)
        return wire
    }

    @Synchronized
    private fun incomingGroup(wire: String, author: ByteArray): String {
        if (wire == Contacts.USER_GROUPS) return wire
        require(wire.matches(Regex("dfo-[0-9a-f]{64}"))) { "Invalid native group identity" }
        val existing = groups.all.filterValues { it == wire }.keys
        require(existing.size <= 1) { "Ambiguous native group alias" }
        existing.singleOrNull()?.let { return it }
        val native = CotIdentity.receivedObject(workspace, author, wire)
        save(native, wire)
        return native
    }

    private fun save(native: String, wire: String) {
        require(groups.all.size < 1024) { "Native conversation limit reached" }
        try {
            check(groups.edit().putString(native, wire).commit()) { "Could not save native conversation" }
        } catch (error: Exception) {
            // SharedPreferences may update its memory cache even if persistence fails.
            groups.edit().remove(native).apply()
            throw error
        }
    }

    fun outgoing(root: Element): Route {
        val chat = checkNotNull(NativeCotProjection.chat(root))
        require(chat.getAttribute("id") != "All Chat Rooms")
        val roster = roster()
        val targets = destinations(root).filter { it != selfUid }
        require(targets.isNotEmpty() && targets.all { it in roster && it != selfActor }) {
            "Native recipient is outside this workspace or unavailable"
        }
        val id = chat.getAttribute("id")
        val identities = roster.mapValues { (_, identity) -> CotIdentity.member(workspace, identity) }.toMutableMap()
        if (id in roster) {
            require(targets == listOf(id)) { "Direct chat audience mismatch" }
            val message = chat.getAttribute("messageId")
            require(message.isNotBlank()) { "Missing native message identity" }
            remember("sent/" + CotIdentity.publishedObject(workspace, member, message), message, id)
        }
        else identities[id] = outgoingGroup(id)
        return Route(targets.map { roster.getValue(it) }.sortedBy { it.hex() }, identities)
    }

    fun incoming(root: Element, author: ByteArray, recipients: List<ByteArray>): Map<String, String> {
        val chat = checkNotNull(NativeCotProjection.chat(root))
        val roster = roster()
        val authorActor = CotIdentity.member(workspace, author)
        require(authorActor in roster && recipients.any { it.contentEquals(member) }) { "Native audience excludes this member" }
        val targetIds = destinations(root).filter { it != authorActor }
        require(targetIds.isNotEmpty() && targetIds.all { it in roster && it != authorActor }) { "Unknown native audience" }
        val expected = targetIds.map { roster.getValue(it).hex() }.sorted()
        require(recipients.map { it.hex() }.sorted() == expected) { "Native and authenticated audiences differ" }
        val id = chat.getAttribute("id")
        val identities = members.associate { value ->
            val identity = value.identity()
            CotIdentity.member(workspace, identity) to if (identity.contentEquals(member)) selfUid
            else nativeIdentity(identity) ?: CotIdentity.member(workspace, identity)
        }.toMutableMap()
        if (id in roster) {
            require(targetIds == listOf(id)) { "Direct chat audience mismatch" }
            val message = chat.getAttribute("messageId")
            require(message.isNotBlank()) { "Missing native message identity" }
            remember("received/" + CotIdentity.receivedObject(workspace, author, message), message,
                nativeIdentity(author) ?: authorActor)
        }
        else identities[id] = incomingGroup(id, author)
        return identities
    }

    @Synchronized
    private fun remember(key: String, message: String, peer: String) {
        receipts.getString(key, null)?.let {
            val existing = JSONObject(it)
            require(existing.getString("message") == message && existing.getString("peer") == peer) { "Conflicting native receipt identity" }
            return
        }
        val edit = receipts.edit()
        // ponytail: retain 4096 recent direct-message bindings per workspace; older receipts fail visibly, #11 owns longer history.
        if (receipts.all.size >= 4096) {
            val oldest = receipts.all.minByOrNull { JSONObject(it.value as String).getLong("time") }!!
            edit.remove(oldest.key)
        }
        val value = JSONObject().put("message", message).put("peer", peer).put("time", System.currentTimeMillis()).toString()
        try { check(edit.putString(key, value).commit()) { "Could not save native receipt identity" } }
        catch (error: Exception) { receipts.edit().remove(key).apply(); throw error }
    }

    private fun receipt(root: Element): Element {
        require(root.getAttribute("type") in setOf("b-t-f-d", "b-t-f-r") && NativeCotProjection.chat(root) == null) { "Invalid native receipt type" }
        val detail = NativeCotProjection.elements(root, "__chatreceipt").single()
        require(root.getAttribute("uid").isNotBlank() && detail.getAttribute("messageId") == root.getAttribute("uid")) { "Native receipt message mismatch" }
        return detail
    }

    fun outgoingReceipt(root: Element): Route {
        val detail = receipt(root)
        val localId = root.getAttribute("uid")
        val saved = JSONObject(requireNotNull(receipts.getString("received/$localId", null)) { "Unknown native receipt; original message mapping is unavailable" })
        val peer = saved.getString("peer")
        val roster = roster()
        val targets = destinations(root)
        require(peer in roster && peer != selfActor && targets == listOf(peer) && detail.getAttribute("id") == peer) {
            "Native receipt recipient mismatch: saved=$peer roster=${roster.keys} targets=$targets detail=${detail.getAttribute("id")}" }
        val recipient = roster.getValue(peer)
        return Route(listOf(recipient), mapOf(peer to CotIdentity.member(workspace, recipient), localId to saved.getString("message")))
    }

    fun incomingReceipt(root: Element, author: ByteArray, audience: List<ByteArray>): Map<String, String> {
        val detail = receipt(root)
        val wireId = root.getAttribute("uid")
        val saved = JSONObject(requireNotNull(receipts.getString("sent/$wireId", null)) { "Unknown native receipt; original message mapping is unavailable" })
        val actor = CotIdentity.member(workspace, author)
        val roster = roster()
        require(actor in roster && roster[saved.getString("peer")]?.contentEquals(author) == true &&
            audience.size == 1 && audience[0].contentEquals(member)) { "Native receipt author or audience mismatch" }
        require(detail.getAttribute("id") == selfActor && destinations(root) == listOf(selfActor)) { "Native receipt destination mismatch" }
        return mapOf(wireId to saved.getString("message"), selfActor to selfUid,
            actor to (nativeIdentity(author) ?: actor))
    }

    private fun ByteArray.hex() = Hex.encode(this)
}
