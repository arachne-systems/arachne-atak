package dev.arachne.atak

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.atakmap.android.chat.ChatDatabase
import com.atakmap.android.maps.MapView
import org.json.JSONObject

internal typealias WorkspacePublisher = (ByteArray, String, ByteArray, List<ByteArray>, WorkspaceCurrent?, (Result<JSONObject>) -> Unit) -> Boolean

/** One device-local native stream per saved workspace. Native application
 * composition, parsing and chat persistence remain owned by ATAK. */
internal class WorkspaceNativeAdapter(@Volatile private var record: LocalWorkspace, private val publish: WorkspacePublisher,
                                      resources: WorkspaceResources, private val deliveries: NativeDeliveryDeduplicator) : AutoCloseable {
    private val member = checkNotNull(record.memberId).copyOf()
    private val workspace = record.id.copyOf()
    private val selfUid = MapView.getDeviceUid()
    private val workspaceKey = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
    val routing = NativeChatRouting(workspace, member, selfUid, MapView.getMapView().context.getSharedPreferences(
        "arachne-native-chat-$workspaceKey", android.content.Context.MODE_PRIVATE), MapView.getMapView().context.getSharedPreferences(
        "arachne-native-receipts-$workspaceKey", android.content.Context.MODE_PRIVATE), MapView.getMapView().context.getSharedPreferences(
        "arachne-native-identities-$workspaceKey", android.content.Context.MODE_PRIVATE))
    private val main = Handler(Looper.getMainLooper())
    private val sentChats = LinkedHashSet<String>()
    @Volatile private var closed = false
    @Volatile var sharing = false
    @Volatile var publishingTopics: Set<String> = emptySet()
    private val stream = LocalTakStream({ input, reply ->
        NativeCotFrames.read(input) { bytes ->
            if (!closed) try {
                val document = NativeCotProjection.parse(bytes)
                val type = document.documentElement.getAttribute("type")
                if (type == "t-x-c-t") {
                    document.documentElement.setAttribute("type", "t-x-c-t-r")
                    check(reply(NativeCotProjection.encode(document))) { "Local keepalive write failed" }
                } else {
                    val explicit = NativeCotProjection.isReceipt(document.documentElement) || NativeCotProjection.chat(document.documentElement)?.getAttribute("id")?.let { it != "All Chat Rooms" } == true
                    if (!sharing && !explicit) return@read
                    send(document, explicit)
                }
            } catch (error: Exception) {
                Log.w("Arachne", "WORKSPACE_NATIVE_REJECTED slot=${record.slot} reason=${error.message}")
                failed("", error.message ?: "Native publication was rejected.")
            }
        }
    }, { failed("", "Local native connection stopped. Check Arachne Settings → ATAK connection ports.") },
        binding = { LocalTakBinding.forWorkspace(MapView.getMapView().context, workspace, record.name) },
        applicationService = { binding, identity -> LocalTakHttp(MapView.getMapView().context, binding, identity, resources::fetch, resources::nativeApi) {
            failed("", "Package listener unavailable. Check Arachne Settings → ATAK connection ports. Workspace activity is unchanged.")
        } }, packageServer = WorkspaceResources.policy(MapView.getMapView().context, workspace).server)

    private val destination = NativeWorkspaceContact(record, stream::contacts)

    fun resourcePolicyChanged() = stream.packageServer(WorkspaceResources.policy(MapView.getMapView().context, workspace).server)

    fun update(record: LocalWorkspace) {
        require(record.id.contentEquals(workspace) && checkNotNull(record.memberId).contentEquals(member) && record.slot == this.record.slot)
        val renamed = record.name != this.record.name
        this.record = record
        if (renamed) {
            destination.update(record)
            stream.description("Arachne: ${record.name}")
        }
    }

    fun members(values: List<WorkspaceMember>) {
        val people = values.filterNot { it.service }
        routing.members = people
        destination.members(people, routing.observedNativeIdentities())
    }

    private fun send(document: org.w3c.dom.Document, explicit: Boolean, selectedRecipients: List<ByteArray>? = null) {
        check(!closed) { "Workspace is no longer active." }
        val type = document.documentElement.getAttribute("type")
        val projected = NativeCotProjection.outgoing(document, workspace, member, selfUid, routing)
        require(selectedRecipients == null || projected?.topic == "atak/native/v1/pli") { "Expected native self location" }
        val publication = if (selectedRecipients == null) projected else projected?.copy(recipients = selectedRecipients, current = null)
        if (publication == null) {
            check(!explicit) { "Forwarding this imported item is not connected yet." }
            return
        }
        if (publication.topic !in publishingTopics) {
            check(!explicit) { "Sharing is off for this category in ${record.name}." }
            return
        }
        if (publication.current?.isFresh() == false) {
            Log.i("Arachne", "WORKSPACE_NATIVE_CURRENT_SKIPPED slot=${record.slot} reason=stale")
            return
        }
        if (publication.topic == "atak/native/v1/chat") {
            // GeoChat may dispatch the same group message once per contact.
            // Include the audience so a different explicit selection stays distinct.
            val key = type + "|" + document.documentElement.getAttribute("uid") + publication.recipients.joinToString("|") { id ->
                id.joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            synchronized(sentChats) {
                if (!sentChats.add(key)) return
                // ponytail: 1024 recent attempts per active workspace; retained/restart replay remains #11.
                if (sentChats.size > 1024) sentChats.remove(sentChats.first())
            }
        }
        val feedbackType = if (explicit) "" else type
        val accepted = publish(workspace, publication.topic, publication.bytes, publication.recipients, publication.current) { result ->
            result.onSuccess { report ->
                val outcome = runCatching { WorkspaceData.outcome(report) }.getOrElse {
                    failed(feedbackType, "Send unconfirmed. No offline retry.")
                    return@onSuccess
                }
                Log.i("Arachne", "WORKSPACE_NATIVE_PUBLISHED slot=${record.slot} topic=${publication.topic} remote_admitted=${outcome.remoteAccepted} failed=${outcome.failed} uncertain=${outcome.uncertain} queued=${outcome.queued}")
                when {
                    outcome.uncertain -> failed(feedbackType, "Send unconfirmed. No offline retry.")
                    outcome.failed > 0 -> failed(feedbackType, if (outcome.remoteAccepted > 0) "Some recipients unconfirmed. No offline retry." else "Send unconfirmed. No offline retry.")
                    outcome.remoteAccepted == 0 && !outcome.queued -> failed(feedbackType, "Nobody accepted this send. No offline retry.")
                }
            }.onFailure { failed(feedbackType, "Publication failed; check the workspace connection.") }
        }
        if (!accepted) failed(feedbackType, "Publication was not queued; check the workspace connection.")
    }

    private fun failed(type: String, message: String) {
        Log.w("Arachne", "WORKSPACE_NATIVE_SEND_FAILED slot=${record.slot} type=$type")
        if (!closed && !type.startsWith("a-")) main.post {
            if (!closed) Toast.makeText(MapView.getMapView().context, "${record.name}: $message", Toast.LENGTH_LONG).show()
        }
    }

    /** The workspace worker owns calls. Native chat acknowledges only matching
     * database readback; other CoT acknowledges transport handoff, not rendering. */
    fun receive(publication: JSONObject, complete: (Boolean) -> Unit) {
        if (closed) {
            Log.w("Arachne", "WORKSPACE_NATIVE_DELIVERY_PENDING slot=${record.slot} reason=adapter_closed")
            complete(false); return
        }
        require(publication.getJSONArray("workspace").bytes().contentEquals(workspace))
        val author = publication.getJSONArray("member").bytes()
        if (author.contentEquals(member)) { complete(true); return }
        val topic = publication.getString("topic")
        publication.optJSONObject("current")?.let {
            require(topic in CotTopics.nativeCurrent)
            if (it.getLong("expires_at") <= java.time.Instant.now().epochSecond) {
                Log.i("Arachne", "WORKSPACE_NATIVE_CURRENT_SKIPPED slot=${record.slot} reason=stale")
                complete(true)
                return
            }
        }
        val audience = publication.getJSONArray("recipients").let { array -> (0 until array.length()).map { array.getJSONArray(it).bytes() } }
        val document = NativeCotProjection.incoming(publication.getJSONArray("payload").bytes(), workspace, author, topic, routing, audience)
        val root = document.documentElement
        val chat = NativeCotProjection.chat(root)
        val receipt = NativeCotProjection.isReceipt(root)
        val nativeAuthor = when {
            topic == "atak/native/v1/pli" -> root.getAttribute("uid")
            chat != null -> NativeCotProjection.elements(root, "link")
                .singleOrNull { it.getAttribute("relation") == "p-p" }?.getAttribute("uid")
            else -> null
        }
        if (nativeAuthor != null) {
            routing.observeNative(author, nativeAuthor)
            destination.members(routing.members, routing.observedNativeIdentities())
        }
        val id = if (receipt) root.getAttribute("uid") else chat?.getAttribute("messageId")
        val expectedStatus = if (root.getAttribute("type") == "b-t-f-r") "READ" else "DELIVERED"
        val actor = CotIdentity.member(workspace, author)
        val text = NativeCotProjection.elements(root, "remarks").singleOrNull()?.textContent
        val database = if (chat != null || receipt) ChatDatabase.getInstance(MapView.getMapView().context) else null
        val originalRow = if (receipt) database?.getChatMessage(id)?.getLong("id") else null
        fun stored(): Boolean {
            val row = database?.getChatMessage(id) ?: return false
            if (receipt) {
                check(row.getString("senderUid") == selfUid && row.getString("conversationId") == (routing.nativeIdentity(author) ?: actor) &&
                    (originalRow == null || row.getLong("id") == originalRow)) { "Native receipt readback mismatch" }
                return row.getString("status") == "READ" || (expectedStatus == "DELIVERED" && row.getString("status") == "DELIVERED")
            }
            val conversation = chat?.getAttribute("id")?.let { if (it == selfUid) nativeAuthor else it }
            check(row.getString("senderUid") == nativeAuthor && row.getString("conversationId") == conversation && row.getString("message") == text) { "Native chat readback mismatch" }
            return true
        }
        fun inject(done: (Boolean) -> Unit) {
            if ((chat != null || receipt) && stored()) {
                if (chat != null) {
                    val row = checkNotNull(database?.getChatMessage(id))
                    Log.i("Arachne", "WORKSPACE_NATIVE_CHAT_STORED slot=${record.slot} id=$id actor=$actor row=${row.getLong("id")} repeated=true")
                }
                done(true)
                return
            }
            val bytes = NativeCotProjection.encode(document)
            if (chat != null) {
                val callsign = chat.getAttribute("senderCallsign")
                val contact = nativeAuthor ?: actor
                stream.associateContact(contact, callsign.ifBlank { contact })
                destination.callsign(contact, callsign)
            }
            if (!stream.send(bytes)) {
                Log.w("Arachne", "WORKSPACE_NATIVE_DELIVERY_PENDING slot=${record.slot} topic=$topic reason=local_stream_unavailable")
                done(false); return
            }
            if (chat == null && !receipt) {
                val uid = root.getAttribute("uid")
                Log.i("Arachne", "WORKSPACE_NATIVE_HANDOFF slot=${record.slot} topic=$topic uid=$uid")
                if (BuildConfig.DEBUG) main.postDelayed({
                    if (!closed) Log.i("Arachne", "WORKSPACE_NATIVE_MAP_ITEM slot=${record.slot} topic=$topic uid=$uid present=${MapView.getMapView().rootGroup.deepFindUID(uid) != null}")
                }, 750)
                done(true)
            } else {
                val until = android.os.SystemClock.elapsedRealtime() + 5000
                // Native import is asynchronous. Waiting on the workspace worker
                // prevents that same owner from receiving and repairing streams.
                fun readback() {
                    try {
                        val accepted = !closed && stored()
                        if (!accepted && !closed && android.os.SystemClock.elapsedRealtime() < until) {
                            if (!main.postDelayed(::readback, 25)) done(false)
                            return
                        }
                        if (accepted) {
                            val row = checkNotNull(database?.getChatMessage(id))
                            if (receipt) Log.i("Arachne", "WORKSPACE_NATIVE_RECEIPT_STORED slot=${record.slot} id=$id actor=$actor status=${row.getString("status")} row=${row.getLong("id")} original_row=$originalRow")
                            else Log.i("Arachne", "WORKSPACE_NATIVE_CHAT_STORED slot=${record.slot} id=$id actor=$actor row=${row.getLong("id")} repeated=false")
                        } else {
                            Log.w("Arachne", "WORKSPACE_NATIVE_DELIVERY_PENDING slot=${record.slot} topic=$topic id=$id reason=readback_unconfirmed")
                        }
                        done(accepted)
                    } catch (error: Exception) {
                        Log.w("Arachne", "WORKSPACE_NATIVE_READBACK_FAILED slot=${record.slot} topic=$topic id=$id", error)
                        done(false)
                    }
                }
                if (!main.post(::readback)) done(false)
            }
        }
        val delivery = NativeCotProjection.deliveryId(document)
        if (delivery == null) inject(complete) else deliveries.deliver(delivery, complete, ::inject)
    }

    override fun close() {
        closed = true
        destination.close()
        stream.close()
        Thread({ Log.i("Arachne", "WORKSPACE_NATIVE_CLOSED slot=${record.slot} success=${stream.awaitClosed(10000)}") }, "arachne-workspace-native-close").start()
    }

    fun awaitClosed(milliseconds: Long): Boolean = stream.awaitClosed(milliseconds)

    private fun org.json.JSONArray.bytes() = ByteArray(length()) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
}
