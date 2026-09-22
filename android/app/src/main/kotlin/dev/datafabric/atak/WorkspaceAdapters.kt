package dev.arachne.atak

import org.json.JSONObject

/** Native streams follow active workspace owners, independently of navigation. */
internal class WorkspaceAdapters(private val resource: WorkspaceResourceCall, private val publish: WorkspacePublisher) : AutoCloseable {
    private class Bound(@Volatile private var record: LocalWorkspace, publish: WorkspacePublisher,
                        deliveries: NativeDeliveryDeduplicator, resource: WorkspaceResourceCall) : AutoCloseable {
        val resources = WorkspaceResources(com.atakmap.android.maps.MapView.getMapView().context, record, publish, resource)
        val native = WorkspaceNativeAdapter(record, publish, resources, deliveries)
        val packages = WorkspaceMissionPackages(record, resources, publish, native.routing::selectedMembers)
        private var legacyCot: WorkspaceCotAdapter? = null
        val feeds = FeedMapAdapter()
        init { com.atakmap.android.data.URIContentManager.getInstance().registerSender(packages.sender) }
        fun update(record: LocalWorkspace) {
            native.update(record)
            packages.update(record)
            this.record = record
            legacyCot?.bind(record)
        }
        fun legacyCotReceive(publication: JSONObject, complete: (Boolean) -> Unit) {
            val receiver = legacyCot ?: WorkspaceCotAdapter().also { it.bind(record); legacyCot = it }
            receiver.receive(publication, complete)
        }
        override fun close() {
            com.atakmap.android.data.URIContentManager.getInstance().unregisterSender(packages.sender)
            packages.close(); resources.close(); native.close(); legacyCot?.close(); feeds.close()
        }
        fun awaitClosed(milliseconds: Long): Boolean = native.awaitClosed(milliseconds)
    }
    private val connected = mutableMapOf<List<Byte>, Bound>()
    private var closing = emptyList<Bound>()
    private val nativeDeliveries = NativeDeliveryDeduplicator()
    private var revision = 0L
    private var closed = false
    private val contactPackages = WorkspaceContactPackages { uids ->
        synchronized(this) { connected.values.toList() }.mapNotNull { bound ->
            if (runCatching { bound.native.routing.selectedMembers(uids) }.getOrNull()?.size == uids.size)
                bound.packages else null
        }
    }

    @Synchronized
    fun bind(state: WorkspaceConnectionView) {
        // Independent owners can finish callbacks out of order. Never resurrect
        // a disconnected scope or restore an older broadcast selection.
        if (closed || state.revision <= revision) return
        revision = state.revision
        val keep = state.connected.map { checkNotNull(it.active).id.toList() }.toSet()
        for (key in connected.keys.toList()) if (key !in keep) connected.remove(key)?.close()
        for (view in state.connected) {
            val record = checkNotNull(view.active)
            val bound = connected.getOrPut(record.id.toList()) { Bound(record, publish, nativeDeliveries, resource) }
            bound.update(record)
            bound.resources.members(view.members)
            bound.native.members(view.members)
            bound.packages.members(view.members)
            bound.native.sharing = record.id.toList() in state.publishing
            bound.native.publishingTopics = view.topics.filter { it.publish }.map { it.topic }.toSet()
            bound.feeds.bind(record, view.feeds)
        }
    }

    fun receive(publication: JSONObject, complete: (Boolean) -> Unit) {
        val id = publication.getJSONArray("workspace")
        val key = (0 until id.length()).map { id.getInt(it).also { n -> require(n in 0..255) }.toByte() }
        val bound = synchronized(this) { connected[key] } ?: run {
            android.util.Log.w("Arachne", "WORKSPACE_ADAPTER_PENDING topic=${publication.optString("topic")} reason=not_bound")
            complete(false); return
        }
        val topic = publication.getString("topic")
        if (topic in WorkspaceResources.topics) bound.resources.receive(publication, complete)
        else if (topic in WorkspaceMissionPackages.topics) bound.packages.receive(publication, complete)
        else if (topic.startsWith("feeds/")) bound.feeds.receive(publication, complete)
        // Retired development builds used a custom workspace chat topic. Drain
        // already accepted objects without recreating that conversation in ATAK.
        else if (topic == "chat/messages/v1") complete(true)
        else if (topic in setOf("atak/pli", "atak/features")) bound.legacyCotReceive(publication, complete)
        else if (topic in CotTopics.nativeDefaults) bound.native.receive(publication, complete)
        else {
            android.util.Log.w("Arachne", "WORKSPACE_ADAPTER_PENDING topic=$topic reason=unsupported_topic")
            complete(false)
        }
    }

    fun offerResource(workspace: ByteArray, file: java.io.File): String =
        checkNotNull(synchronized(this) { connected[workspace.toList()] }) { "Workspace is not active" }.resources.offer(file)
    fun withdrawResource(workspace: ByteArray, hash: String) =
        checkNotNull(synchronized(this) { connected[workspace.toList()] }) { "Workspace is not active" }.resources.withdraw(hash)
    fun resources(workspace: ByteArray): WorkspaceResources? = synchronized(this) { connected[workspace.toList()]?.resources }
    fun resourcePolicyChanged(workspace: ByteArray) {
        val bound = synchronized(this) { connected[workspace.toList()] } ?: return
        bound.native.resourcePolicyChanged()
        bound.resources.policyChanged()
    }
    fun showFeed(workspace: ByteArray, topic: String, completed: (Boolean) -> Unit) {
        val feeds = synchronized(this) { connected[workspace.toList()] }?.feeds
        if (feeds == null) completed(false) else feeds.show(topic, completed)
    }
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closing = connected.values.toList()
        closing.forEach { it.close() }
        contactPackages.close()
        connected.clear()
    }

    fun awaitClosed(milliseconds: Long): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + milliseconds
        return synchronized(this) { closing.toList() }.all {
            it.awaitClosed((end - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }
}
