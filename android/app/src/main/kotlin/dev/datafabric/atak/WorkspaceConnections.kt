package dev.arachne.atak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class WorkspaceConnectionView(val selected: WorkspaceView, val connected: List<WorkspaceView>, val revision: Long, val publishing: Set<List<Byte>>, val pauses: Map<String, String>,
    val attention: List<WorkspaceView> = emptyList())

/** Composes independent workspace owners. Selection changes the viewed owner,
 * never publishing preferences, keys, inbox or pending save/adopt transactions. */
internal class WorkspaceConnections(
    private val context: Context,
    private val topics: Set<String>,
    private val received: (JSONObject, (Boolean) -> Unit) -> Unit,
    private val changed: (WorkspaceConnectionView) -> Unit
) : AutoCloseable {
    private class Entry {
        lateinit var owner: WorkspaceController
        var view: WorkspaceView? = null
        var pending: ((WorkspaceController) -> Boolean)? = null
    }
    private val preferences = context.getSharedPreferences("fabric-broadcast-sharing", Context.MODE_PRIVATE)
    private val connectionPreferences = context.getSharedPreferences("fabric-workspace-connections", Context.MODE_PRIVATE)
    private val catalog = WorkspaceCatalog(context)
    companion object {
        private fun sharingKey(workspace: ByteArray) = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun hiddenKey(slot: String) = "hidden-$slot"
        internal fun saveSharing(context: Context, workspace: ByteArray, enabled: Boolean): Boolean =
            context.getSharedPreferences("fabric-broadcast-sharing", Context.MODE_PRIVATE)
                .edit().putBoolean(sharingKey(workspace), enabled).commit()
    }
    private val entries = mutableListOf<Entry>()
    private val savedRecords = linkedMapOf<String, LocalWorkspace>()
    private val closing = mutableMapOf<String, WorkspaceController>()
    private val pauseFailures = mutableSetOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private var inspected: LocalWorkspace? = null
    private var selected: Entry? = null
    private var restoreScheduled = false
    private var closed = false
    private var revision = 0L

    init { synchronized(this) { newEntry() } }

    // Called under our lock. No child method is invoked while holding this lock:
    // child callbacks hold their own monitor and enter update below.
    private fun newEntry(): Entry {
        check(entries.size < 7) { "Connection limit reached" } // One native slot is reserved for nearby invitations.
        val entry = Entry()
        entries.add(entry)
        selected = entry
        entry.owner = WorkspaceController(context, topics, received, onClosed = { onOwnerClosed(entry) }) { update(entry, it) }
        return entry
    }

    private fun onOwnerClosed(entry: Entry) {
        val state = synchronized(this) {
            if (closed) null
            else closing.entries.firstOrNull { it.value === entry.owner }?.let { (slot, owner) ->
                if (owner.shutdownSucceeded) closing.remove(slot) else pauseFailures.add(slot)
                snapshot()
            }
        }
        state?.let(changed)
    }

    private fun snapshot(): WorkspaceConnectionView {
        val view = inspected?.let { record ->
            val failure = entries.mapNotNull { it.view }.firstOrNull { it.active == null && it.attention && it.statusWorkspace?.slot == record.slot }
            val message = when (record.slot) {
                in pauseFailures -> "Pause failed. Restart ATAK before resuming this workspace."
                in closing -> "Pausing ${record.name}… Finishing accepted work."
                else -> if (failure != null) failure.message else if (record.ended) "Your membership has ended. This workspace is no longer sharing." else if (record.joinPeer != null) "Joining… Your saved request will continue automatically when a workspace member is reachable."
                    else "Paused ${record.name}. Membership and sharing settings are retained. Resume to use this workspace."
            }
            WorkspaceView(emptyList(), record, message, false, attention = failure != null, statusWorkspace = record, attentionReason = failure?.attentionReason)
        } ?: selected?.view ?: WorkspaceView(emptyList(), null, "Loading workspaces…", true)
        // Ended records remain on disk so an old invitation cannot silently
        // restore membership. Hiding one only removes it from this device's UI.
        val saved = savedRecords.values.filterNot { connectionPreferences.getBoolean(hiddenKey(it.slot), false) }
        val connected = entries.mapNotNull { it.view }.filter { it.active?.joinPeer == null && it.active?.memberId != null && it.active?.ended == false }
        val publishing = connected.map { checkNotNull(it.active).id }.filter {
            preferences.getBoolean(sharingKey(it), true)
        }.map { it.toList() }.toSet()
        return WorkspaceConnectionView(view.copy(saved = saved,
            busy = view.busy || (inspected == null && selected?.pending != null)), connected, ++revision, publishing,
            saved.filter { record -> entries.none { it.view?.active?.slot == record.slot } }.associate { record ->
                record.slot to when (record.slot) {
                    in pauseFailures -> "Pause failed"
                    in closing -> "Pausing"
                    else -> if (record.ended) "Membership ended"
                        else if (entries.any { it.view?.active == null && it.view?.attention == true && it.view?.statusWorkspace?.slot == record.slot }) "Stopped"
                        else "Paused"
                }
            }, entries.mapNotNull { it.view }.filter { it.attention })
    }

    private fun update(entry: Entry, view: WorkspaceView) {
        val action: ((WorkspaceController) -> Boolean)?
        val state: WorkspaceConnectionView
        var restore = emptyList<LocalWorkspace>()
        synchronized(this) {
            if (closed || entry !in entries) return
            // Import the catalog once. Later owners hold stale copies of other
            // rows, including canceled joins, and may only change their own row.
            val initialCatalog = !restoreScheduled && !view.busy && view.active == null
            val ownedSlots = setOfNotNull(entry.view?.active?.slot, view.active?.slot)
            savedRecords.keys.removeAll { slot -> slot in ownedSlots && view.saved.none { it.slot == slot } }
            for (record in view.saved) {
                if (initialCatalog || record.slot in ownedSlots) savedRecords[record.slot] = record
            }
            view.active?.let { savedRecords[it.slot] = it }
            view.active?.let { record ->
                if (record.ended) connectionPreferences.edit().putBoolean(record.slot, false).apply()
                else if (record.memberId != null || record.preJoin) connectionPreferences.edit().putBoolean(record.slot, true).apply()
            }
            if (initialCatalog) {
                restoreScheduled = true
                restore = view.saved.filter { !it.ended && connectionPreferences.getBoolean(it.slot, true) }.take(6)
            }
            action = if (!view.busy) entry.pending.also { entry.pending = null } else null
            entry.view = view.copy(busy = view.busy || action != null)
            state = snapshot()
        }
        changed(state)
        view.active?.takeIf { it.ended }?.let { record -> main.post { dismiss(record) } }
        if (restore.isNotEmpty()) main.post { restore.forEach(::open) }
        action?.let {
            if (!it(entry.owner)) update(entry, view.copy(message = "Workspace is busy. Try again.", busy = false))
        }
    }

    private fun current(): WorkspaceController? = synchronized(this) {
        if (closed || inspected != null) null else selected?.takeIf { it.pending == null }?.owner
    }

    /** Read local workspace presentation without opening a transport/security owner. */
    fun inspect(record: LocalWorkspace): Boolean {
        val state = synchronized(this) {
            if (closed) return false
            val entry = entries.firstOrNull { it.view?.active?.slot == record.slot }
            if (entry == null) inspected = record else { selected = entry; inspected = null }
            snapshot()
        }
        changed(state)
        return true
    }

    /** Add a connection without closing other workspaces. An existing connection
     * is only selected; opening it again must not restart its security owner. */
    fun open(record: LocalWorkspace): Boolean {
        if (record.ended) return inspect(record)
        val entry: Entry
        val existing: Boolean
        val state: WorkspaceConnectionView
        synchronized(this) {
            if (closed || record.slot in closing || record.slot in pauseFailures) return false
            if (!connectionPreferences.edit().putBoolean(record.slot, true).commit()) return false
            val found = entries.firstOrNull { it.view?.active?.slot == record.slot }
            if (found == null && entries.size >= 7) return false
            existing = found != null
            entry = found ?: entries.firstOrNull { (it.view?.active == null || it.view?.active?.ended == true) && it.pending == null && it.view?.busy == false } ?: newEntry()
            selected = entry
            inspected = null
            state = snapshot()
        }
        changed(state)
        return if (existing) true else launch(entry) { it.open(record) }
    }

    private fun launch(entry: Entry, action: (WorkspaceController) -> Boolean): Boolean {
        val ready = synchronized(this) {
            if (closed || entry.pending != null) return false
            entry.pending = action
            entry.view?.takeUnless { it.busy }
        }
        // Mark the owner busy through update before another restore can reuse it.
        ready?.let { update(entry, it) }
        return true
    }

    private fun fresh(action: (WorkspaceController) -> Boolean): Boolean {
        val entry: Entry
        synchronized(this) {
            if (closed) return false
            entry = entries.firstOrNull { (it.view?.active == null || it.view?.active?.ended == true) && it.pending == null && it.view?.busy == false }
                ?: if (entries.size < 7) newEntry() else return false
            selected = entry
            inspected = null
        }
        return launch(entry, action)
    }

    fun create(name: String, memberName: String, sharing: Boolean = true) = fresh { it.create(name, memberName, sharing) }
    fun join(link: String, memberName: String, sharing: Boolean = true) = fresh { it.join(link, memberName, sharing) }
    fun join(invitation: JSONObject, memberName: String, sharing: Boolean = true) = fresh { it.join(invitation, memberName, sharing) }
    fun rename(workspace: ByteArray, previousName: String, name: String) = current()?.rename(workspace, previousName, name) ?: false
    fun leave(workspace: ByteArray, successor: ByteArray? = null): Boolean {
        val owner = synchronized(this) {
            if (closed) null else entries.singleOrNull {
                it.pending == null && it.view?.active?.id?.contentEquals(workspace) == true
            }?.owner
        }
        return owner?.leave(workspace, successor) ?: false
    }

    fun leaveDevice(record: LocalWorkspace): Boolean {
        var local: WorkspaceConnectionView? = null
        val owner = synchronized(this) {
            if (closed) return false
            val entry = entries.singleOrNull {
                it.view?.active?.slot == record.slot && it.view?.active?.id?.contentEquals(record.id) == true
            }
            if (entry?.pending != null || record.slot in closing) return false
            entry?.owner ?: run {
                val saved = savedRecords[record.slot]?.takeIf { it.id.contentEquals(record.id) && !it.ended } ?: return false
                val ended = saved.copy(ended = true)
                catalog.save(ended)
                savedRecords[record.slot] = ended
                connectionPreferences.edit().putBoolean(record.slot, false).putBoolean(hiddenKey(record.slot), true).apply()
                if (inspected?.slot == record.slot) inspected = null
                local = snapshot()
                null
            }
        }
        if (owner != null) return owner.leaveDevice(record.id)
        Log.i("Arachne", "WORKSPACE_LEFT_DEVICE")
        Log.i("Arachne", "WORKSPACE_DISMISSED slot=${record.slot}")
        changed(checkNotNull(local))
        return true
    }

    fun dismiss(record: LocalWorkspace): Boolean {
        if (!record.ended) return false
        var owner: WorkspaceController? = null
        val state = synchronized(this) {
            if (closed) return false
            val entry = entries.singleOrNull { it.view?.active?.slot == record.slot }
            if (entry?.pending != null || !connectionPreferences.edit().putBoolean(hiddenKey(record.slot), true).commit()) return false
            if (inspected?.slot == record.slot) inspected = null
            entry?.let {
                entries.remove(entry)
                owner = entry.owner
                check(closing.put(record.slot, entry.owner) == null)
                if (selected === entry) selected = entries.firstOrNull { candidate ->
                    candidate.view?.active?.let { !connectionPreferences.getBoolean(hiddenKey(it.slot), false) } != false
                }
                if (entries.isEmpty() || selected == null) newEntry()
            }
            snapshot()
        }
        Log.i("Arachne", "WORKSPACE_DISMISSED slot=${record.slot}")
        changed(state)
        owner?.close()
        return true
    }
    fun invite(expiresAt: Long = 0, personal: Boolean = false, automatic: Boolean = false, requestAccess: Boolean = false) = current()?.invite(expiresAt, personal, automatic, requestAccess) ?: false
    fun approveInvitation(workspace: ByteArray, pending: PendingApproval) = owner(workspace)?.approveInvitation(workspace, pending) ?: false
    fun declineInvitation(workspace: ByteArray, pending: PendingApproval) = owner(workspace)?.declineInvitation(workspace, pending) ?: false
    fun disableInvitation(workspace: ByteArray, key: ByteArray) = owner(workspace)?.disableInvitation(workspace, key) ?: false
    fun cancelJoin(workspace: ByteArray) = owner(workspace)?.cancelJoin(workspace) ?: false
    fun retryJoin() = current()?.retryJoin() ?: false
    fun reconnect(link: String) = current()?.reconnect(link) ?: false
    private fun owner(workspace: ByteArray): WorkspaceController? = synchronized(this) {
        if (closed) null else entries.singleOrNull { it.view?.active?.id?.contentEquals(workspace) == true }?.owner
    }
    fun selectFeed(workspace: ByteArray, topic: String, enabled: Boolean) =
        owner(workspace)?.selectFeed(workspace, topic, enabled) ?: false
    fun selectTopics(workspace: ByteArray, topics: Set<String>, publish: Boolean? = null, receive: Boolean? = null): Boolean {
        return owner(workspace)?.selectTopics(workspace, topics, publish, receive) ?: false
    }
    fun manage(workspace: ByteArray, member: ByteArray, action: String) =
        owner(workspace)?.manage(workspace, member, action) ?: false

    /** The producer supplies its captured workspace. No selected-workspace
     * fallback is permitted for a stale callback or a disconnected owner. */
    fun publish(workspace: ByteArray, topic: String, payload: ByteArray, recipients: List<ByteArray> = emptyList(),
                current: WorkspaceCurrent? = null,
                completed: ((Result<JSONObject>) -> Unit)? = null): Boolean {
        return owner(workspace)?.publish(workspace, topic, payload, recipients, current, completed) ?: false
    }

    /** Explicit adapter preference, independent of navigation and subscriptions. */
    fun resource(workspace: ByteArray, request: JSONObject, completed: (Result<JSONObject>) -> Unit): Boolean =
        owner(workspace)?.resource(workspace, request, completed) ?: false

    /** Explicit adapter preference, independent of navigation and subscriptions. */
    fun shareBroadcasts(workspace: ByteArray, enabled: Boolean): Boolean {
        val state = synchronized(this) {
            if (closed || entries.none { it.view?.active?.id?.contentEquals(workspace) == true }) return false
            if (!saveSharing(context, workspace, enabled)) return false
            snapshot()
        }
        changed(state)
        return true
    }

    fun pause(workspace: ByteArray): Boolean {
        val entry: Entry
        val state: WorkspaceConnectionView
        synchronized(this) {
            if (closed) return false
            entry = entries.singleOrNull { it.view?.active?.id?.contentEquals(workspace) == true } ?: return false
            if (entry.pending != null) return false
            val record = checkNotNull(entry.view?.active)
            if (record.joinPeer != null || record.memberId == null) return false
            if (!connectionPreferences.edit().putBoolean(record.slot, false).commit()) return false
            entries.remove(entry)
            closing[record.slot] = entry.owner
            if (selected === entry && inspected == null) inspected = record
            if (selected === entry) selected = entries.firstOrNull()
            if (entries.isEmpty()) newEntry()
            state = snapshot()
        }
        changed(state)
        entry.owner.close()
        return true
    }

    /** Debug control only: each open owner with its last view. */
    internal fun debugOwners(): List<Pair<WorkspaceView?, WorkspaceController>> = synchronized(this) {
        check(BuildConfig.DEBUG)
        entries.map { it.view to it.owner }
    }

    override fun close() {
        val owners = synchronized(this) {
            if (closed) return
            closed = true
            entries.map { it.owner } + closing.values
        }
        owners.forEach { it.close() }
    }

    fun resetAndClose() {
        val owners = synchronized(this) {
            if (closed) return
            closed = true
            entries.map { it.owner } + closing.values
        }
        owners.forEach { it.resetAndClose() }
    }

    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean {
        val owners = synchronized(this) { entries.map { it.owner } + closing.values }
        val end = System.nanoTime() + unit.toNanos(timeout)
        return owners.all { it.awaitClosed((end - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) && it.shutdownSucceeded }
    }
}
