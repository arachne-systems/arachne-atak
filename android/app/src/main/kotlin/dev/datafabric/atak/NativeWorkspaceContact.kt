package dev.arachne.atak

import android.os.Handler
import android.os.Looper
import com.atakmap.android.chat.ChatDatabase
import com.atakmap.android.chat.GeoChatConnector
import com.atakmap.android.contact.Contact
import com.atakmap.android.contact.ContactUtil
import com.atakmap.android.contact.Contacts
import com.atakmap.android.contact.GroupContact
import com.atakmap.android.contact.IndividualContact
import com.atakmap.android.contact.IpConnector
import com.atakmap.android.hierarchy.filters.EmptyListFilter
import com.atakmap.android.maps.MapView
import com.atakmap.comms.NetConnectString

/** Makes accepted workspace members available to ATAK's existing Contacts,
 * Chat and recipient pickers. Arachne adds transport, not another chat UI. */
internal class NativeWorkspaceContact(
    @Volatile private var record: LocalWorkspace,
    private val connectMembers: (Map<String, String>) -> Boolean,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val contacts = mutableMapOf<String, IndividualContact>()
    private val retained = mutableSetOf<String>()
    @Volatile private var closed = false

    fun update(record: LocalWorkspace) {
        this.record = record
    }

    /** Membership creates ordinary ATAK contacts. Location reports may later
     * supply their normal ATAK callsigns and map markers on the same UIDs. */
    fun members(values: List<WorkspaceMember>, nativeIdentities: Map<String, String>) {
        val workspaceName = record.name
        val peers = values.filter { !it.self }.associateBy { member ->
            nativeIdentities[member.id] ?: fallbackUid(member.id)
        }
        synchronized(fallbackUids) { fallbackUids.keys.retainAll(values.mapTo(HashSet()) { it.id }) }
        if (!connectMembers(peers.keys.associateWith { it })) return
        // Roster views arrive on every controller emit and on each native
        // author observation. Post only when a contact would change: the main
        // thread otherwise refreshes every ATAK contact each time.
        val wanted = peers.mapValues { (_, member) -> Applied(member.name ?: "Name not received", member.presence, workspaceName) }
        synchronized(posted) {
            if (posted.value == wanted) return
            posted.value = wanted
        }
        main.post {
            if (closed) return@post
            val store = Contacts.getInstance()
            var membershipChanged = false
            for (uid in contacts.keys.toList()) if (uid !in peers) {
                contacts.remove(uid)
                applied.remove(uid)
                release(store, uid)
                membershipChanged = true
            }
            for ((uid, member) in peers) {
                val state = wanted.getValue(uid)
                if (contacts[uid] != null && applied[uid] == state) continue
                val contact = contacts[uid] ?: acquire(store, uid, member.name ?: "Name not received")?.also { membershipChanged = true }
                if (contact == null) {
                    // Another owner holds this UID. Try again on the next view.
                    synchronized(posted) { posted.value = null }
                    continue
                }
                val previousProfile = contact.extras.getString("arachneMemberName")
                val profile = state.name
                if (contact.name.isBlank() || contact.name == uid || contact.name.startsWith("dfm-") || contact.name == previousProfile)
                    contact.setName(profile)
                val address = NetConnectString.fromString(ContactUtil.TAK_SERVER_CONNECTION_STRING)
                if (!contact.hasConnector(IpConnector.CONNECTOR_TYPE)) contact.addConnector(IpConnector(address))
                if (!contact.hasConnector(GeoChatConnector.CONNECTOR_TYPE)) contact.addConnector(GeoChatConnector(address))
                contact.extras.putString("arachneWorkspaceName", state.workspaceName)
                contact.extras.putString("arachneMemberName", profile)
                contact.setUpdateStatus(when (member.presence) {
                    "reachable" -> Contact.UpdateStatus.CURRENT
                    "stale" -> Contact.UpdateStatus.STALE
                    else -> Contact.UpdateStatus.NA
                })
                contact.refresh("Workspace membership updated")
                contacts[uid] = contact
                applied[uid] = state
            }
            if (!membershipChanged) return@post
            val groups = store.getContactByUuid(Contacts.USER_GROUPS) as? GroupContact
            val database = ChatDatabase.getInstance(MapView.getMapView().context)
            groups?.getAllContacts(true)?.filterIsInstance<GroupContact>()?.forEach { group ->
                val saved = database.getGroupInfo(group.getUID())
                if (saved.size >= 2) {
                    val recipients = saved[1].split(',').filter(String::isNotBlank)
                    if (recipients.any(peers::containsKey)) {
                        group.setContactUIDs(recipients)
                        group.syncRefresh(null, EmptyListFilter())
                    }
                }
            }
        }
    }

    fun callsign(uid: String, name: String) {
        if (name.isBlank()) return
        main.post {
            if (!closed) contacts[uid]?.apply {
                extras.putString("arachneAtakCallsign", name)
                setName(name)
                refresh("ATAK callsign received")
            }
        }
    }

    override fun close() {
        closed = true
        main.post {
            val store = Contacts.getInstance()
            contacts.keys.forEach { release(store, it) }
            contacts.clear()
        }
    }

    private fun acquire(store: Contacts, uid: String, name: String): IndividualContact? {
        val existing = store.getContactByUuid(uid)
        if (existing != null && existing !is IndividualContact) return null
        managed[uid]?.takeIf { existing === it.contact }?.let {
            it.users++
            retained.add(uid)
            return it.contact
        }
        if (existing is IndividualContact) return existing
        val contact = IndividualContact(name, uid).also(store::addContact)
        managed[uid] = Managed(contact, 1)
        retained.add(uid)
        return contact
    }

    private fun release(store: Contacts, uid: String) {
        if (!retained.remove(uid)) return
        val value = managed[uid] ?: return
        if (--value.users == 0) {
            managed.remove(uid)
            if (store.getContactByUuid(uid) === value.contact) store.removeContact(value.contact)
        }
    }

    /** CotIdentity.member is a SHA-256 per member; keep it per roster id. */
    private fun fallbackUid(member: String): String = synchronized(fallbackUids) {
        fallbackUids.getOrPut(member) { CotIdentity.member(record.id, Hex.decode(member)) }
    }

    private val fallbackUids = HashMap<String, String>()
    private class Posted { var value: Map<String, Applied>? = null }
    private val posted = Posted()
    // Main thread only: the state last written to each contact.
    private val applied = HashMap<String, Applied>()
    private data class Applied(val name: String, val presence: String, val workspaceName: String)

    private data class Managed(val contact: IndividualContact, var users: Int)
    private companion object { val managed = mutableMapOf<String, Managed>() }
}
